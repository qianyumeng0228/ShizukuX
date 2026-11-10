package af.shizuku.manager.backup

import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.ShizukuSettings
import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.ShizukuXAPI
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val versionName: String,
        val isSystem: Boolean,
        val allowBackup: Boolean,
        val isFrozen: Boolean = false
    )

    /**
     * Progress of a batch backup/restore operation.
     */
    data class BatchProgress(
        val current: Int = 0,
        val total: Int = 0,
        val currentPkg: String = "",
        val succeeded: Int = 0,
        val failed: Int = 0,
        /** True for a batch restore, false for a batch backup — picks the progress wording. */
        val isRestore: Boolean = false,
    )

    sealed class UiState {
        object Loading : UiState()
        data class Loaded(val apps: List<AppEntry>) : UiState()
        data class Error(val msg: String) : UiState()
        object ServiceNotRunning : UiState()
    }

    sealed class BackupEvent {
        data class BackupComplete(val pkg: String, val path: String) : BackupEvent()
        /**
         * A batch finished. [isRestore] separates the two very different operations this one
         * event used to carry: restoreAll() emitted BatchComplete too, so the Activity rendered
         * a restore with the backup wording ("已备份 N 个应用至 restore").
         */
        data class BatchComplete(
            val succeeded: Int,
            val failed: Int,
            val path: String,
            val isRestore: Boolean = false
        ) : BackupEvent()
        data class RestoreComplete(val pkg: String, val success: Boolean) : BackupEvent()
        data class FreezeChanged(val pkg: String, val nowFrozen: Boolean) : BackupEvent()
        data class Failure(val msg: String) : BackupEvent()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state

    private val _events =
        MutableSharedFlow<BackupEvent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<BackupEvent> = _events

    // Packages currently being backed up — drives per-row busy state in the adapter.
    private val _busyPackages = MutableStateFlow<Set<String>>(emptySet())
    val busyPackages: StateFlow<Set<String>> = _busyPackages

    // Batch backup state
    private val _batchRunning = MutableStateFlow(false)
    val batchRunning: StateFlow<Boolean> = _batchRunning

    private val _batchProgress = MutableStateFlow(BatchProgress())
    val batchProgress: StateFlow<BatchProgress> = _batchProgress

    // Master app list (unfiltered); _state holds the filtered view
    @Volatile private var allApps: List<AppEntry> = emptyList()
    private val _query = MutableStateFlow("")

    fun setQuery(q: String) {
        _query.value = q
        applyFilter()
    }

    private fun applyFilter() {
        if (allApps.isEmpty()) return
        val q = _query.value.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            allApps
        } else {
            allApps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
        _state.value = UiState.Loaded(filtered)
    }

    fun loadApps(includeSystem: Boolean = false) {
        _state.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            if (ShizukuStateMachine.get() != ShizukuStateMachine.State.RUNNING) {
                _state.value = UiState.ServiceNotRunning
                return@launch
            }
            try {
                val pm = getApplication<Application>().packageManager
                val bundles = ShizukuXAPI.BackupRestoreExtra.listInstalledPackages(includeSystem)
                val entries = bundles
                    .mapNotNull { b ->
                        val pkg = b.getString("packageName") ?: return@mapNotNull null
                        val label = try {
                            val info = pm.getApplicationInfo(pkg, 0)
                            pm.getApplicationLabel(info).toString()
                        } catch (e: Exception) { pkg }
                        val isFrozen = try {
                            ShizukuXAPI.BackupRestoreExtra.isAppFrozen(pkg)
                        } catch (e: Exception) { false }
                        AppEntry(
                            packageName = pkg,
                            label = label,
                            versionName = b.getString("versionName") ?: "",
                            isSystem = b.getBoolean("isSystem"),
                            allowBackup = b.getBoolean("allowBackup"),
                            isFrozen = isFrozen
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                allApps = entries
                applyFilter()
            } catch (e: Exception) {
                Timber.e(e, "loadApps failed")
                _state.value = UiState.Error(e.message ?: "未知错误")
            }
        }
    }

    fun backupAppData(entry: AppEntry, outputDir: File? = null, safTreeUri: Uri? = null) {
        val pkg = entry.packageName
        if (pkg in _busyPackages.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _busyPackages.value = _busyPackages.value + pkg
            var prepared = false
            try {
                val cr = getApplication<Application>().contentResolver

                // Root path: read /data/data/<pkg> directly. No APK patching, so the app is never
                // uninstalled/reinstalled during a backup (user report: 备份会卸载原有 APP).
                val rootDir = rootBackupDir()
                if (rootDir != null) {
                    ShizukuXAPI.BackupRestoreExtra.forceStop(pkg)
                    if (RootBackup.backup(pkg, rootDir)) {
                        _events.emit(BackupEvent.BackupComplete(pkg, RootBackup.packageDir(rootDir, pkg)))
                    } else {
                        _events.emit(BackupEvent.Failure("无法读取 $pkg 的数据（ROOT 备份失败）"))
                    }
                    return@launch
                }

                ShizukuXAPI.BackupRestoreExtra.forceStop(pkg)

                // prepareTempDebug is best-effort; Shizuku's privileged API can stream data
                // without debug mode on most paths, so a failure here is not fatal.
                prepared = try {
                    ShizukuXAPI.ApkPatcher.prepareTempDebug(pkg)
                } catch (e: Exception) {
                    Timber.w(e, "prepareTempDebug skipped for $pkg, attempting direct backup")
                    false
                }

                var backedUpSomething = false

                // NOTE: wrap each pfd in its own use{} around writeBackupStream (via the
                // streamPfdToBackup helper). writeBackupStream can return false WITHOUT
                // invoking the write block (SAF createDocument/openOutputStream failure),
                // in which case the old inline `pfd.use{}` inside the block never ran and
                // the server pipe's read-side fd leaked — one fd per failed backup,
                // accumulating until the process was reaped.
                val dataPfd = try { ShizukuXAPI.ApkPatcher.streamDataDir(pkg) } catch (e: Exception) {
                    Timber.w(e, "streamDataDir failed for $pkg")
                    null
                }
                if (dataPfd != null && streamPfdToBackup(dataPfd, safTreeUri, outputDir, pkg, "data.tar.gz", cr)) {
                    backedUpSomething = true
                }

                val extPfd = try { ShizukuXAPI.BackupRestoreExtra.backupExternalData(pkg) } catch (e: Exception) {
                    Timber.w(e, "backupExternalData failed for $pkg")
                    null
                }
                if (extPfd != null && streamPfdToBackup(extPfd, safTreeUri, outputDir, pkg, "external.tar.gz", cr)) {
                    backedUpSomething = true
                }

                // OBB data (upstream 737e394c): best-effort, null when the app has no OBB dir.
                val obbPfd = try { ShizukuXAPI.BackupRestoreExtra.backupObbData(pkg) } catch (e: Exception) {
                    Timber.w(e, "backupObbData failed for $pkg")
                    null
                }
                if (obbPfd != null && streamPfdToBackup(obbPfd, safTreeUri, outputDir, pkg, "obb.tar.gz", cr)) {
                    backedUpSomething = true
                }

                if (backedUpSomething) {
                    val outputDesc = if (safTreeUri != null) {
                        safTreeUri.lastPathSegment ?: "备份文件夹"
                    } else {
                        outputDir?.absolutePath ?: "备份文件夹"
                    }
                    _events.emit(BackupEvent.BackupComplete(pkg, outputDesc))
                } else {
                    _events.emit(BackupEvent.Failure("无法读取 $pkg 的数据，应用可能阻止了备份访问"))
                }
            } catch (e: Exception) {
                Timber.e(e, "Backup failed for $pkg")
                _events.emit(BackupEvent.Failure("备份 $pkg 失败：${e.message}"))
            } finally {
                if (prepared) {
                    try { ShizukuXAPI.ApkPatcher.restoreOriginal(pkg) } catch (ex: Exception) {
                        Timber.w(ex, "restoreOriginal failed for $pkg")
                    }
                }
                _busyPackages.value = _busyPackages.value - pkg
            }
        }
    }

    /**
     * Restores external data for [entry] from [externalTarUri] (a SAF document Uri pointing to an
     * external.tar.gz file produced by a prior backup or by a compatible tool like
     * Swift Backup / Neo Backup). Streams it to
     * [ShizukuXAPI.BackupRestoreExtra.restoreExternalData], which extracts it into
     * /sdcard/Android/data/<pkg>/ via shell privilege. Port of upstream f43a0a65.
     */
    fun restoreExternalData(entry: AppEntry, externalTarUri: Uri) {
        val pkg = entry.packageName
        if (pkg in _busyPackages.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _busyPackages.value = _busyPackages.value + pkg
            try {
                ShizukuXAPI.BackupRestoreExtra.forceStop(pkg)
                val cr = getApplication<Application>().contentResolver
                val ok = cr.openFileDescriptor(externalTarUri, "r")?.use { pfd ->
                    ShizukuXAPI.BackupRestoreExtra.restoreExternalData(pkg, pfd)
                } ?: run {
                    _events.emit(BackupEvent.Failure("无法打开 $pkg 的备份文件"))
                    return@launch
                }
                _events.emit(BackupEvent.RestoreComplete(pkg, ok))
            } catch (e: Exception) {
                Timber.e(e, "restoreExternalData failed for $pkg")
                _events.emit(BackupEvent.Failure("恢复 $pkg 失败：${e.message}"))
            } finally {
                _busyPackages.value = _busyPackages.value - pkg
            }
        }
    }

    /**
     * Stream a server-provided [ParcelFileDescriptor] (read end of a pipe) to a backup output,
     * guaranteeing the pfd is closed on EVERY path.
     *
     * Regression (P2 #5): previously each call site inlined `writeBackupStream(...) { out -> pfd.use { ...copyTo... } }`.
     * When SAF createDocument / openOutputStream failed, [writeBackupStream] returned `false` WITHOUT
     * invoking the write block — so `pfd.use{}` never ran and the server pipe's read-side fd leaked.
     * A backup that failed 1–3 times (data/ext/obb) leaked 1–3 fds per package; repeated backups
     * accumulated fds / pipe buffers until the app process PSS climbed (~2.5 GB) and LMK reaped it.
     *
     * Here the pfd is wrapped in its own `use{}` around the whole [writeBackupStream] call, so the
     * read end is released whether or not the output document was ever opened. The copy itself uses
     * an 8 KB streaming `copyTo` (no ByteArray whole-buffer accumulation).
     */
    private fun streamPfdToBackup(
        pfd: ParcelFileDescriptor,
        safTreeUri: Uri?,
        outputDir: File?,
        pkg: String,
        fileName: String,
        cr: ContentResolver
    ): Boolean = pfd.use { pf ->
        // The FileInputStream wraps pf's OWN fd. Do NOT close it explicitly / in its own use{}:
        // pfd.use (above) is the single owner that closes the fd once — an extra close here would
        // be a double close. If writeBackupStream never invokes the block (SAF output open
        // failure), the stream is never constructed and pfd.use still releases the read end.
        writeBackupStream(safTreeUri, outputDir, pkg, fileName, cr) { out ->
            FileInputStream(pf.fileDescriptor).copyTo(out)
        }
    }

    private fun writeBackupStream(
        safTreeUri: Uri?,
        outputDir: File?,
        pkg: String,
        fileName: String,
        cr: ContentResolver,
        block: (OutputStream) -> Unit
    ): Boolean {
        if (safTreeUri != null) {
            val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(
                safTreeUri,
                DocumentsContract.getTreeDocumentId(safTreeUri)
            )
            // Try to reuse an existing per-package subdirectory (query first), then create if
            // absent. Some providers (e.g. Downloads) don't support MIME_TYPE_DIR and return
            // null — fall back to a flat "{pkg}_{file}" name.
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                safTreeUri,
                DocumentsContract.getTreeDocumentId(safTreeUri)
            )
            val existingId = try { queryForDocument(childrenUri, cr, pkg) } catch (_: Exception) { null }
            val parentUri = if (existingId != null) {
                DocumentsContract.buildDocumentUriUsingTree(safTreeUri, existingId)
            } else {
                try {
                    DocumentsContract.createDocument(
                        cr,
                        treeDocUri,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        pkg
                    )
                } catch (_: Exception) { null }
            }

            val (targetUri, targetName) = if (parentUri != null) {
                parentUri to fileName
            } else {
                treeDocUri to "${pkg}_$fileName"
            }

            // SAF providers never overwrite: createDocument() on a name that already exists
            // silently succeeds as "data.tar.gz (1)" and leaves the previous archive in place.
            // Observed on device for all three legs (data/external/obb) — and because the
            // restore side matched display names exactly, a second backup followed by a
            // restore kept reading the FIRST backup while the UI reported success.
            // Clear the canonical name AND any "(n)" copies left behind by earlier builds,
            // so the directory holds exactly one archive per leg again.
            val staleChildrenId = try { DocumentsContract.getDocumentId(targetUri) } catch (_: Exception) { null }
            if (staleChildrenId != null) {
                val staleChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    safTreeUri, staleChildrenId
                )
                deleteBackupDuplicates(safTreeUri, staleChildrenUri, cr, targetName)
            }

            val fileUri = try {
                DocumentsContract.createDocument(cr, targetUri, "application/octet-stream", targetName)
            } catch (e: Exception) {
                Timber.w(e, "createDocument failed for $pkg/$targetName")
                null
            } ?: return false

            val out = cr.openOutputStream(fileUri) ?: run {
                // Created document but cannot open write stream — delete empty shell to avoid
                // misleading rollback lookup (a 0-byte file would be "found" but useless).
                try { DocumentsContract.deleteDocument(cr, fileUri) } catch (_: Exception) {}
                return false
            }
            out.use { block(it) }
            return true
        } else {
            // Neither SAF tree nor outputDir set: defensive guard instead of `outputDir!!` NPE.
            // All call sites currently guarantee one is non-null, but a future caller that forgets
            // would otherwise crash the IO coroutine (only surfaced as a Failure event).
            val dir = outputDir ?: return false
            val pkgDir = File(dir, pkg).also { it.mkdirs() }
            FileOutputStream(File(pkgDir, fileName)).use { block(it) }
            return true
        }
    }

    /**
     * Sequentially backs up all currently loaded (filtered) user apps.
     * Emits [BackupEvent.BatchComplete] with succeeded/failed counts when done.
     *
     * Uses the currently filtered list (what the user sees after typing in the search box),
     * matching the UI. Falls back to the full [allApps] only when state is not yet Loaded.
     */
    fun backupAll(outputDir: java.io.File? = null, safTreeUri: android.net.Uri? = null) {
        if (_batchRunning.value) return
        // If we are showing a filtered list, back up exactly what the user sees (including an
        // empty list as a no-op). Only when state isn't Loaded yet (e.g. cold start) do we fall
        // back to the master list.
        val loaded = _state.value as? UiState.Loaded
        val apps = if (loaded != null) loaded.apps else allApps
        if (apps.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _batchRunning.value = true
            var succeeded = 0
            var failed = 0
            val cr = getApplication<Application>().contentResolver
            val rootDir = rootBackupDir()
            val total = apps.size
            var index = 0
            for (entry in apps) {
                val pkg = entry.packageName
                if (pkg in _busyPackages.value) {
                    index++
                    continue
                }
                index++
                _batchProgress.value = BatchProgress(
                    current = index, total = total, currentPkg = pkg,
                    succeeded = succeeded, failed = failed
                )
                _busyPackages.value = _busyPackages.value + pkg
                var prepared = false
                try {
                    ShizukuXAPI.BackupRestoreExtra.forceStop(pkg)

                    // Root path: one tar per app, nothing is uninstalled.
                    if (rootDir != null) {
                        if (RootBackup.backup(pkg, rootDir)) succeeded++ else failed++
                        continue
                    }

                    prepared = try {
                        ShizukuXAPI.ApkPatcher.prepareTempDebug(pkg)
                    } catch (_: Exception) { false }

                    var backedUpSomething = false
                    val dataPfd = try { ShizukuXAPI.ApkPatcher.streamDataDir(pkg) } catch (_: Exception) { null }
                    if (dataPfd != null && streamPfdToBackup(dataPfd, safTreeUri, outputDir, pkg, "data.tar.gz", cr)) {
                        backedUpSomething = true
                    }
                    val extPfd = try {
                        ShizukuXAPI.BackupRestoreExtra.backupExternalData(
                            pkg
                        )
                    } catch (_: Exception) { null }
                    if (extPfd != null && streamPfdToBackup(extPfd, safTreeUri, outputDir, pkg, "external.tar.gz", cr)) {
                        backedUpSomething = true
                    }
                    // OBB data (upstream 737e394c): best-effort, null when the app has no OBB dir.
                    val obbPfd = try { ShizukuXAPI.BackupRestoreExtra.backupObbData(pkg) } catch (_: Exception) { null }
                    if (obbPfd != null && streamPfdToBackup(obbPfd, safTreeUri, outputDir, pkg, "obb.tar.gz", cr)) {
                        backedUpSomething = true
                    }
                    if (backedUpSomething) succeeded++ else failed++
                } catch (e: Exception) {
                    Timber.e(e, "Batch backup failed for $pkg")
                    failed++
                } finally {
                    if (prepared) {
                        try {
                            ShizukuXAPI.ApkPatcher.restoreOriginal(
                                pkg
                            )
                        } catch (e: Exception) {
                            Timber.e(
                                e,
                                "restoreOriginal failed for $pkg (post-backup cleanup)"
                            )
                        }
                    }
                    _busyPackages.value = _busyPackages.value - pkg
                }
            }
            val outputDesc = if (safTreeUri != null) {
                safTreeUri.lastPathSegment ?: "备份文件夹"
            } else {
                outputDir?.absolutePath ?: "备份文件夹"
            }
            _batchProgress.value = BatchProgress(current = total, total = total, succeeded = succeeded, failed = failed)
            _events.emit(BackupEvent.BatchComplete(succeeded, failed, outputDesc))
            _batchRunning.value = false
        }
    }

    /**
     * Back up current app data before restore, using timestamp-based .pre-restore names
     * to retain multiple snapshots. Called automatically by [restoreAppData].
     *
     * Backs up both internal data (data.pre-restore.{timestamp}.tar.gz) and external data
     * (external.pre-restore.{timestamp}.tar.gz) into the same SAF directory structure.
     * Records the snapshot timestamp via [PreRestoreManager] for rollback selection.
     *
     * Returns the timestamp string if at least one component was backed up, or null on failure.
     */
    private fun backupPreRestore(
        pkg: String,
        safTreeUri: android.net.Uri,
        cr: android.content.ContentResolver,
        requireInternal: Boolean = false
    ): String? {
        val timestamp = PreRestoreManager.generateTimestamp()
        val dataName = PreRestoreManager.getSnapshotFileName("data", timestamp)
        val extName = PreRestoreManager.getSnapshotFileName("external", timestamp)
        val obbName = PreRestoreManager.getSnapshotFileName("obb", timestamp)
        var backedUpSomething = false
        var internalBackedUp = false
        // Force-stop the app before snapshot to ensure a consistent tar state
        // (avoids capturing half-written files from a foreground-running app).
        try {
            ShizukuXAPI.BackupRestoreExtra.forceStop(
                pkg
            )
        } catch (e: Exception) { Timber.w(e, "forceStop failed for $pkg (pre-restore snapshot)") }
        // Internal data (requires app debuggable — caller must prepareTempDebug first)
        val dataPfd = try { ShizukuXAPI.ApkPatcher.streamDataDir(pkg) } catch (_: Exception) { null }
        if (dataPfd != null && streamPfdToBackup(dataPfd, safTreeUri, null, pkg, dataName, cr)) {
            backedUpSomething = true
            internalBackedUp = true
        }
        // External data (shell-level, no debuggable required)
        val extPfd = try { ShizukuXAPI.BackupRestoreExtra.backupExternalData(pkg) } catch (_: Exception) { null }
        if (extPfd != null && streamPfdToBackup(extPfd, safTreeUri, null, pkg, extName, cr)) {
            backedUpSomething = true
        }
        // OBB data — without it a rollback re-creates the same hole the restore path had:
        // the live OBB would survive untouched while data/external revert to the snapshot.
        val obbPfd = try { ShizukuXAPI.BackupRestoreExtra.backupObbData(pkg) } catch (_: Exception) { null }
        if (obbPfd != null && streamPfdToBackup(obbPfd, safTreeUri, null, pkg, obbName, cr)) {
            backedUpSomething = true
        }
        if (backedUpSomething && (!requireInternal || internalBackedUp)) {
            PreRestoreManager.recordSnapshot(getApplication(), pkg, timestamp, safTreeUri, cr)
            return timestamp
        }
        return null
    }

    /**
     * Restore a single app's data from a SAF backup directory.
     *
     * Flow: prepareTempDebug → **backupPreRestore** (auto-backup current data with
     * .pre-restore suffix; aborts restore if backup fails) → clearAppData →
     * restoreDataDir (internal, if [includeInternal]) → restoreExternalData → restoreOriginal.
     *
     * Internal data restore uses run-as + tar -xzf (requires app to be debuggable,
     * which prepareTempDebug ensures). External data restore uses shell-level extraction.
     *
     * Returns true if at least one data component was restored successfully.
     * Returns false if pre-restore backup failed (restore aborted to prevent data loss).
     */
    suspend fun restoreAppData(
        entry: AppEntry,
        safTreeUri: android.net.Uri?,
        includeInternal: Boolean = true,
        snapshotTimestamp: String? = null,
        isRollback: Boolean = false
    ): Boolean {
        val pkg = entry.packageName
        val cr = getApplication<Application>().contentResolver
        var prepared = false
        // Determine source file names: normal restore uses data.tar.gz, rollback uses snapshot
        val dataFileName = if (snapshotTimestamp != null) {
            PreRestoreManager.getSnapshotFileName("data", snapshotTimestamp)
        } else {
            "data.tar.gz"
        }
        val extFileName = if (snapshotTimestamp != null) {
            PreRestoreManager.getSnapshotFileName("external", snapshotTimestamp)
        } else {
            "external.tar.gz"
        }
        // OBB sits under /sdcard/Android/obb/<pkg>/ — external storage, not /data/data/,
        // so it rides with the external leg and is restored regardless of includeInternal.
        val obbFileName = if (snapshotTimestamp != null) {
            PreRestoreManager.getSnapshotFileName("obb", snapshotTimestamp)
        } else {
            "obb.tar.gz"
        }
        return try {
            // Root path: restore straight into /data/data/<pkg>, no APK patching, no uninstall.
            val rootDir = rootBackupDir()
            if (rootDir != null) {
                return restoreAppDataAsRoot(entry, rootDir, includeInternal, snapshotTimestamp)
            }
            val saf = safTreeUri ?: return false

            // Make app debuggable so run-as can access /data/data/<pkg>/
            prepared = try {
                ShizukuXAPI.ApkPatcher.prepareTempDebug(pkg)
            } catch (_: Exception) { false }

            // Auto-backup current data before restore (skip for rollbacks — we're restoring FROM a snapshot)
            if (!isRollback) {
                val preRestoreTs = backupPreRestore(pkg, saf, cr, requireInternal = includeInternal)
                if (preRestoreTs == null) {
                    Timber.w("Pre-restore backup failed for $pkg — aborting restore to prevent data loss")
                    recordRestoreHistory(
                        entry,
                        includeInternal,
                        success = false,
                        isRollback = false,
                        snapshotTimestamp = null
                    )
                    return@restoreAppData false
                }
            }

            // Clear existing data for clean restore state. If clear fails (IPC broken or
            // app actively resisting), abort to avoid restoreDataDir writing into a live/
            // inconsistent data directory.
            val cleared = try {
                ShizukuXAPI.BackupRestoreExtra.clearAppData(pkg)
            } catch (e: Exception) {
                Timber.w(e, "clearAppData failed for $pkg — aborting restore to avoid mixed writes")
                recordRestoreHistory(
                    entry,
                    includeInternal,
                    success = false,
                    isRollback = isRollback,
                    snapshotTimestamp = snapshotTimestamp
                )
                return@restoreAppData false
            }
            if (!cleared) {
                Timber.w("clearAppData returned false for $pkg — aborting restore")
                recordRestoreHistory(
                    entry,
                    includeInternal,
                    success = false,
                    isRollback = isRollback,
                    snapshotTimestamp = snapshotTimestamp
                )
                return@restoreAppData false
            }

            var restoredSomething = false

            // 1. Restore internal data (/data/data/<pkg>/)
            if (includeInternal) {
                val dataUri = findBackupFile(saf, pkg, dataFileName, cr)
                if (dataUri != null) {
                    val ok = cr.openFileDescriptor(dataUri, "r")?.use { pfd ->
                        ShizukuXAPI.ApkPatcher.restoreDataDir(pkg, pfd)
                    } ?: false
                    if (ok) restoredSomething = true
                } else {
                    Timber.w("No data.tar.gz found for $pkg (internal data skip)")
                }
            }

            // 2. Restore external data (/sdcard/Android/data/<pkg>/)
            val extUri = findBackupFile(saf, pkg, extFileName, cr)
            if (extUri != null) {
                val ok = cr.openFileDescriptor(extUri, "r")?.use { pfd ->
                    ShizukuXAPI.BackupRestoreExtra.restoreExternalData(pkg, pfd)
                } ?: false
                if (ok) restoredSomething = true
            } else {
                Timber.w("No external.tar.gz found for $pkg (external data skip)")
            }

            // 3. Restore OBB data (/sdcard/Android/obb/<pkg>/)
            // The backup side has always written obb.tar.gz (data/external/obb are produced
            // together), but nothing ever consumed it: restore stopped after external data.
            // An app with OBB assets (games, large resource packs) therefore came back with
            // its data intact and its OBB directory empty — unlaunchable, with no error shown.
            // Verified on device bc757a76 (Android 16): after deleting main.obb, restoring
            // reproduced neither the file nor an obb.pre-restore snapshot.
            val obbUri = findBackupFile(saf, pkg, obbFileName, cr)
            if (obbUri != null) {
                val ok = cr.openFileDescriptor(obbUri, "r")?.use { pfd ->
                    ShizukuXAPI.BackupRestoreExtra.restoreObbData(pkg, pfd)
                } ?: false
                if (ok) restoredSomething = true
            } else {
                Timber.d("No obb.tar.gz found for $pkg (obb data skip)")
            }

            recordRestoreHistory(entry, includeInternal, restoredSomething, isRollback, snapshotTimestamp)
            restoredSomething
        } catch (e: Exception) {
            Timber.e(e, "Restore failed for $pkg")
            recordRestoreHistory(
                entry,
                includeInternal,
                success = false,
                isRollback = isRollback,
                snapshotTimestamp = snapshotTimestamp
            )
            false
        } finally {
            if (prepared) {
                try {
                    ShizukuXAPI.ApkPatcher.restoreOriginal(
                        pkg
                    )
                } catch (e: Exception) { Timber.e(e, "restoreOriginal failed for $pkg (post-restore cleanup)") }
            }
        }
    }

    /**
     * The absolute backup directory when root is reachable, otherwise null (SAF path then applies).
     *
     * A SAF tree Uri can never point at `/data`, which is exactly the folder the user needs, so
     * the two mechanisms are kept separate instead of pretending one covers the other.
     */
    /**
     * The root-backed backup directory, or null when root is not usable.
     *
     * With root the directory defaults to [RootBackup.DEFAULT_DIR] instead of answering null:
     * a null answer would send the next backup down the APK-patching path, which uninstalls the
     * app twice — the exact behaviour the user asked us to remove. The default is only used while
     * root is actually available; without root nothing can be written outside shared storage
     * anyway, so the SAF path stays in charge there.
     */
    private fun rootBackupDir(): String? {
        if (!RootBackup.available()) return null
        return ShizukuSettings.getBackupRootDir()?.takeIf { it.startsWith("/") }
            ?: RootBackup.DEFAULT_DIR
    }

    /**
     * Restores one app from a root-owned archive directory.
     *
     * RootBackup keeps a `data.pre-restore.<ts>.tar.gz` next to the archive it is about to
     * overwrite, so a restore is itself reversible without a second SAF round-trip.
     */
    private fun restoreAppDataAsRoot(
        entry: AppEntry,
        rootDir: String,
        includeInternal: Boolean,
        snapshotTimestamp: String?
    ): Boolean {
        val pkg = entry.packageName
        return try {
            ShizukuXAPI.BackupRestoreExtra.forceStop(pkg)
            val ok = RootBackup.restore(pkg, rootDir, snapshotTimestamp, includeInternal)
            recordRestoreHistory(entry, includeInternal, ok, snapshotTimestamp != null, snapshotTimestamp)
            ok
        } catch (e: Exception) {
            Timber.e(e, "Root restore failed for $pkg")
            recordRestoreHistory(entry, includeInternal, false, snapshotTimestamp != null, snapshotTimestamp)
            false
        }
    }

    private fun recordRestoreHistory(
        entry: AppEntry,
        includeInternal: Boolean,
        success: Boolean,
        isRollback: Boolean,
        snapshotTimestamp: String?
    ) {
        val scope = if (includeInternal) "both" else "external"
        RestoreHistoryManager.addEntry(
            getApplication(),
            RestoreHistoryManager.Entry(
                id = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                packageName = entry.packageName,
                appLabel = entry.label,
                scope = scope,
                success = success,
                isRollback = isRollback,
                snapshotTimestamp = snapshotTimestamp
            )
        )
    }

    /**
     * Batch restore external data for multiple apps from a SAF backup directory.
     * Emits progress via [batchProgress] and a single [BatchComplete] at the end.
     * Does NOT emit [BackupEvent.RestoreComplete] per app — 30 Snackbars firing in a row
     * would drown the user and overwrite lastRestoredPkg in the Activity.
     * Clears app data before restore to ensure clean state.
     */
    fun restoreAll(
        entries: List<AppEntry>,
        safTreeUri: android.net.Uri?,
        includeInternal: Boolean = true,
        snapshotTimestamp: String? = null,
        isRollback: Boolean = false
    ) {
        if (_batchRunning.value) return
        if (entries.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _batchRunning.value = true
            var succeeded = 0
            var failed = 0
            val total = entries.size
            var index = 0
            for (entry in entries) {
                val pkg = entry.packageName
                index++
                _batchProgress.value = BatchProgress(
                    current = index, total = total, currentPkg = pkg,
                    succeeded = succeeded, failed = failed, isRestore = true
                )
                try {
                    val ok = restoreAppData(entry, safTreeUri, includeInternal, snapshotTimestamp, isRollback)
                    if (ok) succeeded++ else failed++
                    // Per-app RestoreComplete event intentionally skipped in batch path;
                    // BatchComplete at the end summarizes succeeded/failed counts.
                } catch (e: Exception) {
                    Timber.e(e, "Batch restore failed for $pkg")
                    failed++
                }
            }
            _batchProgress.value = BatchProgress(
                current = total, total = total, succeeded = succeeded, failed = failed, isRestore = true
            )
            _events.emit(BackupEvent.BatchComplete(succeeded, failed, "restore", isRestore = true))
            _batchRunning.value = false
        }
    }

    /**
     * Find a backup file (e.g. external.tar.gz) inside a package's subdirectory
     * of the SAF tree. Returns the document Uri, or null if not found.
     */
    private fun findBackupFile(
        safTreeUri: android.net.Uri,
        pkg: String,
        fileName: String,
        cr: android.content.ContentResolver
    ): android.net.Uri? {
        return try {
            val treeId = android.provider.DocumentsContract.getTreeDocumentId(safTreeUri)
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(safTreeUri, treeId)
            // First try to find the package subdirectory
            val pkgDirId = queryForDocument(childrenUri, cr, pkg)
            if (pkgDirId != null) {
                val pkgChildrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                    safTreeUri,
                    pkgDirId
                )
                val fileId = queryLatestDocument(pkgChildrenUri, cr, fileName)
                if (fileId != null) {
                    return android.provider.DocumentsContract.buildDocumentUriUsingTree(safTreeUri, fileId)
                }
            }
            // Fallback: try flat name "{pkg}_{fileName}" (Downloads provider)
            val flatId = queryLatestDocument(childrenUri, cr, "${pkg}_$fileName")
            if (flatId != null) {
                android.provider.DocumentsContract.buildDocumentUriUsingTree(safTreeUri, flatId)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryForDocument(
        childrenUri: android.net.Uri,
        cr: android.content.ContentResolver,
        displayName: String
    ): String? {
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        cr.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                if (name == displayName) {
                    return cursor.getString(0)
                }
            }
        }
        return null
    }

    /**
     * Resolves [baseName] — or any "baseName (n)" duplicate a SAF provider created — to the
     * NEWEST document id.
     *
     * A provider that cannot overwrite (Downloads, external storage) answers a second
     * createDocument() with "data.tar.gz (1)" and leaves the first archive untouched.
     * The restore path used to match display names exactly, so it kept resolving the stale
     * file: backups looked successful while every restore silently replayed the oldest data.
     * Deleting the previous document in writeBackupStream() prevents new duplicates; this
     * newest-wins lookup also recovers directories that already contain historical copies.
     */
    private fun queryLatestDocument(
        childrenUri: android.net.Uri,
        cr: android.content.ContentResolver,
        baseName: String
    ): String? {
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val duplicate = Regex(
            "^" + java.util.regex.Pattern.quote(baseName) + " \\(\\d+\\)$"
        )
        var bestId: String? = null
        var bestStamp = Long.MIN_VALUE
        cr.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = try { cursor.getString(1) } catch (_: Exception) { null } ?: continue
                if (name != baseName && !duplicate.matches(name)) continue
                // Providers may omit last_modified; treat that as oldest so a named
                // canonical file still wins over an undated duplicate.
                val stamp = try {
                    if (cursor.isNull(2)) 0L else cursor.getLong(2)
                } catch (_: Exception) { 0L }
                if (bestId == null || stamp >= bestStamp) {
                    bestId = cursor.getString(0)
                    bestStamp = stamp
                }
            }
        }
        return bestId
    }

    /**
     * Deletes every document in [childrenUri] whose display name is [baseName] or a
     * provider-generated "baseName (n)" copy.
     *
     * Without this, each backup leg only ever replaced the canonical name: the second
     * backup produced "data.tar.gz (1)" and the third "(2)", so the directory grew one
     * stale archive per backup for the life of the install (and the restore path had to
     * rely on newest-wins to avoid replaying the oldest one). Deleting all copies restores
     * the invariant that one package directory holds exactly one archive per leg.
     */
    private fun deleteBackupDuplicates(
        safTreeUri: android.net.Uri,
        childrenUri: android.net.Uri,
        cr: android.content.ContentResolver,
        baseName: String
    ) {
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        val match = Regex(
            "^" + java.util.regex.Pattern.quote(baseName) + "( \\(\\d+\\))?$"
        )
        try {
            cr.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = try { cursor.getString(1) } catch (_: Exception) { null } ?: continue
                    if (!match.matches(name)) continue
                    val id = try { cursor.getString(0) } catch (_: Exception) { null } ?: continue
                    try {
                        DocumentsContract.deleteDocument(
                            cr,
                            DocumentsContract.buildDocumentUriUsingTree(safTreeUri, id)
                        )
                        Timber.d("Removed previous $name before writing")
                    } catch (e: Exception) {
                        // Provider refused the delete. findBackupFile's newest-wins lookup
                        // still resolves the freshly written copy, so restore stays correct.
                        Timber.w(e, "Could not delete previous $name; newest-wins lookup applies")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not enumerate children for $baseName: ${e.message}")
        }
    }

    fun toggleFreeze(entry: AppEntry) {
        val pkg = entry.packageName
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nowFrozen = if (entry.isFrozen) {
                    ShizukuXAPI.BackupRestoreExtra.unfreezeApp(pkg)
                    false
                } else {
                    ShizukuXAPI.BackupRestoreExtra.freezeApp(pkg)
                    true
                }
                // Update the frozen state in the master list, then re-apply filter.
                allApps = allApps.map { if (it.packageName == pkg) it.copy(isFrozen = nowFrozen) else it }
                applyFilter()
                _events.emit(BackupEvent.FreezeChanged(pkg, nowFrozen))
            } catch (e: Exception) {
                Timber.e(e, "toggleFreeze failed for $pkg")
                _events.emit(BackupEvent.Failure("冻结/解冻失败：${e.message}"))
            }
        }
    }
}
