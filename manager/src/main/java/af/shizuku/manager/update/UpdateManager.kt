package af.shizuku.manager.update
import af.shizuku.manager.R

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import timber.log.Timber
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import io.sentry.Sentry
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.BuildConfig
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages downloading and installing updates.
 *
 * The download itself is a four-source relay: every URL in the list is tried in order,
 * the next one starts only after the previous attempt actually failed (HTTP error,
 * DownloadManager failure, truncated body, or a same-named file of the wrong size).
 * Progress is published through [state] so the in-app card and the status-bar
 * notification show the same numbers.
 *
 * State lives in the companion object: HomeActivity creates its own instance while the
 * About screen gets the Koin singleton, and both must observe one download.
 */
class UpdateManager(private val context: Context) {

    private val appContext: Context = context.applicationContext

    sealed interface State {
        data object Idle : State

        /** HEAD-equivalent phase: the DownloadManager row exists but no bytes have arrived. */
        data class Connecting(
            val versionName: String,
            val source: String,
            val attempt: Int,
            val total: Int
        ) : State

        data class Downloading(
            val versionName: String,
            val source: String,
            val percent: Int,
            val attempt: Int,
            val total: Int
        ) : State

        data class Ready(val versionName: String) : State

        data class Failed(val versionName: String?) : State

        data object Cancelled : State
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val NOTIFICATION_CHANNEL_ID = "update_channel"
        // Keep these away from 1xxx/9xxx: 1001 is the watchdog notification and 9999 is the
        // live-status one, so reusing them would replace those ongoing notifications.
        private const val PROGRESS_NOTIFICATION_ID = 2101
        private const val READY_NOTIFICATION_ID = 2102
        private const val ERROR_NOTIFICATION_ID = 2103
        private const val DOWNLOAD_ID_PREF = "update_download_id"

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state

        @Volatile private var appCtx: Context? = null
        private var job: Job? = null
        private var activeDownloadId = -1L
        private var activeFile: File? = null
        private var backgroundMode = false
        private var currentVersion: String? = null

        /** True while a relay download is running (card should not start a second one). */
        val isDownloading: Boolean
            get() = job?.isActive == true
    }

    // ------------------------------------------------------------------------------------
    // Public API used by the UI layer
    // ------------------------------------------------------------------------------------

    /**
     * Start (or join) the download relay.
     * @param downloadUrls sources in priority order — tried one after another
     * @param versionName version being downloaded, used for the file name and notifications
     * @param expectedSize asset size reported by GitHub; mismatching files are rejected
     */
    fun downloadUpdate(downloadUrls: List<String>, versionName: String, expectedSize: Long = -1) {
        appCtx = appContext
        if (job?.isActive == true) {
            // A relay is already running (e.g. the user reopened the card): do not restart.
            Timber.tag(TAG).d("Download already running, ignoring duplicate start")
            return
        }
        if (downloadUrls.isEmpty()) {
            _state.value = State.Failed(versionName)
            return
        }
        currentVersion = versionName
        backgroundMode = false
        _state.value = State.Connecting(versionName, sourceLabel(downloadUrls.first()), 1, downloadUrls.size)

        job = CoroutineScope(Dispatchers.IO + Job()).launch {
            runRelay(downloadUrls, versionName, expectedSize)
        }
    }

    /** Card button "后台下载": keep going, move progress into the status bar. */
    fun moveToBackground() {
        backgroundMode = true
        when (val s = _state.value) {
            is State.Connecting -> notifyProgress(0, s.versionName)
            is State.Downloading -> notifyProgress(s.percent, s.versionName)
            else -> Unit
        }
    }

    /** Card button "关闭": abort the transfer and delete whatever was written. */
    fun cancelDownload() {
        cancelRelay()
    }

    fun canRequestPackageInstalls(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        appContext.packageManager.canRequestPackageInstalls()
    } else {
        true
    }

    // ------------------------------------------------------------------------------------
    // Relay engine
    // ------------------------------------------------------------------------------------

    private suspend fun runRelay(urls: List<String>, versionName: String, expectedSize: Long) {
        val ctx = appCtx ?: return
        val file = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ShizukuX-v$versionName.apk")
        file.delete()
        cleanupOtherApks(file)
        createNotificationChannel(ctx)

        for ((index, url) in urls.withIndex()) {
            if (!currentCoroutineContext().isActive) return
            val source = sourceLabel(url)
            _state.value = State.Connecting(versionName, source, index + 1, urls.size)

            val id = try {
                enqueue(ctx, url, file)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Enqueue failed for $source")
                -1L
            }
            if (id <= 0) {
                Timber.tag(TAG).w("Source $source rejected before download")
                continue
            }

            activeDownloadId = id
            activeFile = file
            ctx.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)
                .edit().putLong(DOWNLOAD_ID_PREF, id).apply()

            val outcome = monitor(ctx, id, file, versionName, source, index + 1, urls.size, expectedSize)
            activeDownloadId = -1
            if (!currentCoroutineContext().isActive) {
                file.delete()
                return
            }

            when (outcome) {
                Outcome.SUCCESS -> {
                    _state.value = State.Ready(versionName)
                    onDownloadComplete(file, versionName)
                    return
                }
                Outcome.FAILED -> {
                    Timber.tag(TAG).w("Source $source failed, trying next")
                    runCatching { (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) }
                    file.delete()
                }
                Outcome.CANCELLED -> {
                    file.delete()
                    return
                }
            }
        }

        Timber.tag(TAG).e("All ${urls.size} sources failed")
        _state.value = State.Failed(versionName)
        if (backgroundMode) notifyError(ctx)
    }

    private fun enqueue(ctx: Context, url: String, file: File): Long {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(ctx.getString(R.string.update_downloading_title))
            .setDescription(ctx.getString(R.string.update_downloading_description, currentVersion ?: ""))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setMimeType("application/vnd.android.package-archive")

        request.addRequestHeader("User-Agent", "ShizukuX/${currentVersion ?: BuildConfig.VERSION_NAME}")
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    private enum class Outcome { SUCCESS, FAILED, CANCELLED }

    @SuppressLint("Range")
    private suspend fun monitor(
        ctx: Context,
        downloadId: Long,
        file: File,
        versionName: String,
        source: String,
        attempt: Int,
        total: Int,
        expectedSize: Long
    ): Outcome {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var missingRowCount = 0
        var lastNotifiedPercent = -1
        var lastNotifyTimeMs = 0L
        // A dead mirror (unresolvable host, black-holed connection) leaves DownloadManager in
        // STATUS_RUNNING with zero bytes forever, so bytes stalling must also count as a failure.
        var lastBytes = 0L
        var lastProgressTimeMs = System.currentTimeMillis()
        val stallTimeoutMs = 15_000L

        while (currentCoroutineContext().isActive) {
            try {
                val cursor = try {
                    dm.query(DownloadManager.Query().setFilterById(downloadId))
                } catch (e: IllegalArgumentException) {
                    Timber.tag(TAG).w(e, "DownloadManager.query rejected; retrying")
                    null
                }

                if (cursor == null) {
                    delay(500)
                    continue
                }

                cursor.use { c ->
                    if (!c.moveToFirst()) {
                        missingRowCount++
                        if (missingRowCount >= 10) return Outcome.FAILED
                        return@use
                    }
                    missingRowCount = 0

                    val statusIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val progressIdx = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalIdx = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val status = if (statusIdx >= 0) c.getInt(statusIdx) else DownloadManager.STATUS_RUNNING
                    val downloaded = if (progressIdx >= 0) c.getLong(progressIdx) else 0L
                    val bytesTotal = if (totalIdx >= 0) c.getLong(totalIdx) else 0L

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val actual = file.length()
                            val wrongSize = expectedSize > 0 && actual > 0 &&
                                    kotlin.math.abs(actual - expectedSize) > expectedSize * 0.02
                            if (wrongSize) {
                                Timber.tag(TAG).e("Size $actual != expected $expectedSize from $source")
                                return Outcome.FAILED
                            }
                            return Outcome.SUCCESS
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reasonIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIdx >= 0) c.getInt(reasonIdx) else -1
                            Timber.tag(TAG).w("$source failed (reason=$reason)")
                            return Outcome.FAILED
                        }
                        DownloadManager.STATUS_PAUSED -> Unit
                        DownloadManager.STATUS_RUNNING -> {
                            val now = System.currentTimeMillis()
                            if (downloaded != lastBytes) {
                                lastBytes = downloaded
                                lastProgressTimeMs = now
                            } else if (now - lastProgressTimeMs > stallTimeoutMs) {
                                Timber.tag(TAG).w("$source stalled at $downloaded B, trying next")
                                return Outcome.FAILED
                            }
                            if (bytesTotal <= 0) {
                                // Still connecting: no Content-Length yet, keep showing the source.
                                if (_state.value !is State.Connecting ||
                                    (_state.value as State.Connecting).source != source
                                ) {
                                    _state.value = State.Connecting(versionName, source, attempt, total)
                                }
                            } else {
                                val percent = (downloaded * 100 / bytesTotal).toInt()
                                if (_state.value !is State.Downloading) {
                                    _state.value = State.Downloading(
                                        versionName, source, percent, attempt, total
                                    )
                                } else if ((_state.value as State.Downloading).percent != percent ||
                                    (_state.value as State.Downloading).source != source
                                ) {
                                    _state.value = State.Downloading(
                                        versionName, source, percent, attempt, total
                                    )
                                }
                                if (backgroundMode) {
                                    if (percent - lastNotifiedPercent >= 2 || now - lastNotifyTimeMs >= 2000) {
                                        notifyProgress(percent, versionName)
                                        lastNotifiedPercent = percent
                                        lastNotifyTimeMs = now
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error monitoring download")
                Sentry.captureException(e)
            }
            delay(500)
        }
        return Outcome.CANCELLED
    }

    private fun cancelRelay() {
        val ctx = appCtx
        job?.cancel()
        job = null
        if (ctx != null) {
            if (activeDownloadId > 0) {
                runCatching { (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(activeDownloadId) }
            }
            activeFile?.delete()
            NotificationManagerCompat.from(ctx).apply {
                cancel(PROGRESS_NOTIFICATION_ID)
                cancel(READY_NOTIFICATION_ID)
            }
        }
        activeDownloadId = -1
        activeFile = null
        backgroundMode = false
        _state.value = State.Cancelled
    }

    /** Removes leftover APKs from earlier versions so "关闭" really leaves nothing behind. */
    private fun cleanupOtherApks(keep: File) {
        try {
            val dir = appCtx?.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            dir.listFiles { f -> f.name.endsWith(".apk") && f.absolutePath != keep.absolutePath }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Cleanup of old APKs failed")
        }
    }

    // ------------------------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------------------------

    private fun createNotificationChannel(ctx: Context) {
        val channel = NotificationChannelCompat.Builder(
            NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName(ctx.getString(R.string.update_notification_channel))
            .setDescription(ctx.getString(R.string.update_notification_channel_description))
            .build()
        NotificationManagerCompat.from(ctx).createNotificationChannel(channel)
    }

    private fun notifyProgress(percent: Int, versionName: String) {
        val ctx = appCtx ?: return
        createNotificationChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent().setClassName(ctx, "af.shizuku.manager.home.HomeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(ctx.getString(R.string.update_downloading_title))
            .setContentText(ctx.getString(R.string.update_downloading_progress, versionName, percent))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, percent, percent == 0)
            .build()
        NotificationManagerCompat.from(ctx).notify(PROGRESS_NOTIFICATION_ID, notification)
    }

    // ------------------------------------------------------------------------------------
    // Completion / install
    // ------------------------------------------------------------------------------------

    private fun onDownloadComplete(file: File, versionName: String) {
        val ctx = appCtx ?: return
        NotificationManagerCompat.from(ctx).cancel(PROGRESS_NOTIFICATION_ID)
        backgroundMode = false

        val downloadedCode = readApkVersionCode(file)
        val installedCode = BuildConfig.VERSION_CODE
        if (downloadedCode == -1) {
            Timber.tag(TAG).w("Downloaded file is not a readable APK; discarding")
            file.delete()
            _state.value = State.Failed(versionName)
            notifyError(ctx)
            return
        }
        if (downloadedCode > 0 && downloadedCode < installedCode) {
            Timber.tag(TAG).w("versionCode $downloadedCode < installed $installedCode; refusing install")
            showDowngradeWarningNotification(ctx, file, versionName, downloadedCode, installedCode)
            return
        }

        if (ShizukuSettings.isAutoInstallEnabled()) {
            CoroutineScope(Dispatchers.IO).launch {
                val ok = installApk(file)
                if (!ok) withContext(Dispatchers.Main) { showInstallNotification(ctx, file, versionName) }
            }
        } else {
            showInstallNotification(ctx, file, versionName)
        }
    }

    private fun readApkVersionCode(file: File): Int = try {
        val info = appCtx?.packageManager?.getPackageArchiveInfo(file.absolutePath, 0) ?: return -1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    } catch (e: Exception) {
        Timber.tag(TAG).w(e, "Failed to read downloaded APK versionCode")
        -1
    }

    private fun showDowngradeWarningNotification(
        ctx: Context, file: File, versionName: String, downloadedCode: Int, installedCode: Int
    ) {
        try {
            val content = ctx.getString(
                R.string.update_downgrade_warning_content, versionName, downloadedCode, installedCode
            )
            val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(ctx.getString(R.string.update_downgrade_warning_title))
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(READY_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to show downgrade warning")
            notifyError(ctx)
        }
    }

    private fun showInstallNotification(ctx: Context, file: File, versionName: String) {
        try {
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                shareableApkUri(ctx, file)
            } else {
                Uri.fromFile(file)
            }
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingIntent = PendingIntent.getActivity(
                ctx, 0, installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(ctx.getString(R.string.update_ready_title))
                .setContentText(ctx.getString(R.string.update_ready_description, versionName))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(
                    R.drawable.ic_notification_icon,
                    ctx.getString(R.string.update_install_now),
                    pendingIntent
                )
                .build()
            NotificationManagerCompat.from(ctx).notify(READY_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to build install notification")
            Sentry.captureException(e)
            notifyError(ctx)
        }
    }

    private fun notifyError(ctx: Context) {
        createNotificationChannel(ctx)
        val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(ctx.getString(R.string.update_download_failed_title))
            .setContentText(ctx.getString(R.string.update_download_failed_message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(ERROR_NOTIFICATION_ID, notification)
    }

    private fun shareableApkUri(ctx: Context, file: File): Uri {
        val authority = "${ctx.packageName}.fileprovider"
        return try {
            FileProvider.getUriForFile(ctx, authority, file)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w(e, "APK path not FileProvider-shareable; copying to cache")
            val cached = File(ctx.cacheDir, file.name)
            file.copyTo(cached, overwrite = true)
            FileProvider.getUriForFile(ctx, authority, cached)
        }
    }

    /**
     * Install APK directly (for auto-install when enabled).
     * Must be called from a background coroutine — Shell.getShell() blocks until a shell is ready.
     */
    suspend fun installApk(file: File): Boolean {
        try {
            val shizukuAlive = withTimeoutOrNull(5000) {
                withContext(Dispatchers.IO) { rikka.shizuku.Shizuku.pingBinder() }
            } ?: false

            val rootAlive = withTimeoutOrNull(5000) {
                withContext(Dispatchers.IO) { com.topjohnwu.superuser.Shell.getShell().isRoot }
            } ?: false

            if (shizukuAlive || rootAlive) {
                Timber.tag(TAG).d("Attempting silent install via Shizuku/Root...")
                val installSuccess = withContext(Dispatchers.IO) {
                    runCatching {
                        if (rootAlive) {
                            com.topjohnwu.superuser.Shell.cmd("pm install -r -d \"${file.absolutePath}\"").exec().isSuccess
                        } else {
                            val apkPath = file.absolutePath
                            val script = "cp \"$apkPath\" /data/local/tmp/shizukux_update.apk && " +
                                    "chmod 644 /data/local/tmp/shizukux_update.apk && " +
                                    "pm install -r -d /data/local/tmp/shizukux_update.apk; " +
                                    "rm -f /data/local/tmp/shizukux_update.apk"
                            val process = rikka.shizuku.Shizuku.newProcess(
                                arrayOf("sh", "-c", script),
                                null, null
                            )
                            val exitCode = process?.waitFor() ?: -1
                            if (exitCode != 0) {
                                val err = process?.errorStream?.bufferedReader()?.readText().orEmpty()
                                Timber.tag(TAG).w("Shizuku pm install exit=$exitCode err=$err")
                            }
                            exitCode == 0
                        }
                    }.getOrDefault(false)
                }
                if (installSuccess) {
                    Timber.tag(TAG).i("Silent install successful")
                    return true
                } else {
                    Timber.tag(TAG).w("Silent install failed; trying force-update script")
                    if (UpdateInstaller.forceUpdateWithShizuku(appContext, file)) {
                        Timber.tag(TAG).i("Force-update background script initiated")
                        return true
                    }
                }
            }

            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                shareableApkUri(appContext, file)
            } else {
                Uri.fromFile(file)
            }
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(installIntent)
            Timber.tag(TAG).d("Install intent launched for: ${file.absolutePath}")
            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to launch install intent")
            Sentry.captureException(e)
            return false
        }
    }

    /** Human-readable source label used by the card ("Gitee" / "GitHub" / ...). */
    private fun sourceLabel(url: String): String = when {
        url.contains("gitee.com", ignoreCase = true) -> "Gitee"
        url.contains("github.com", ignoreCase = true) -> "GitHub"
        url.contains("fd.shizukux.xyz", ignoreCase = true) -> "Cloudflare"
        url.contains("cos.ap-guangzhou", ignoreCase = true) -> "COS"
        else -> runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
    }
}
