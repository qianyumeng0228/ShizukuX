package af.shizuku.manager.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.ShizukuXAPI
import af.shizuku.manager.utils.ShizukuStateMachine
import timber.log.Timber
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
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

    sealed class UiState {
        object Loading : UiState()
        data class Loaded(val apps: List<AppEntry>) : UiState()
        data class Error(val msg: String) : UiState()
        object ServiceNotRunning : UiState()
    }

    sealed class BackupEvent {
        data class BackupComplete(val pkg: String, val path: String) : BackupEvent()
        data class BatchComplete(val succeeded: Int, val failed: Int, val path: String) : BackupEvent()
        data class FreezeChanged(val pkg: String, val nowFrozen: Boolean) : BackupEvent()
        data class Failure(val msg: String) : BackupEvent()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state

    private val _events = MutableSharedFlow<BackupEvent>()
    val events: SharedFlow<BackupEvent> = _events

    // Packages currently being backed up — drives per-row busy state in the adapter.
    private val _busyPackages = MutableStateFlow<Set<String>>(emptySet())
    val busyPackages: StateFlow<Set<String>> = _busyPackages

    // Batch backup state
    private val _batchRunning = MutableStateFlow(false)
    val batchRunning: StateFlow<Boolean> = _batchRunning

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
        val filtered = if (q.isEmpty()) allApps
        else allApps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
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
                _state.value = UiState.Error(e.message ?: "Unknown error")
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

                val dataPfd = try { ShizukuXAPI.ApkPatcher.streamDataDir(pkg) } catch (e: Exception) {
                    Timber.w(e, "streamDataDir failed for $pkg")
                    null
                }
                if (dataPfd != null) {
                    if (writeBackupStream(safTreeUri, outputDir, pkg, "data.tar.gz", cr) { out ->
                        dataPfd.use { pfd ->
                            FileInputStream(pfd.fileDescriptor).use { input -> input.copyTo(out) }
                        }
                    }) backedUpSomething = true
                }

                val extPfd = try { ShizukuXAPI.BackupRestoreExtra.backupExternalData(pkg) } catch (e: Exception) {
                    Timber.w(e, "backupExternalData failed for $pkg")
                    null
                }
                if (extPfd != null) {
                    if (writeBackupStream(safTreeUri, outputDir, pkg, "external.tar.gz", cr) { out ->
                        extPfd.use { pfd ->
                            FileInputStream(pfd.fileDescriptor).use { input -> input.copyTo(out) }
                        }
                    }) backedUpSomething = true
                }

                if (backedUpSomething) {
                    val outputDesc = if (safTreeUri != null) {
                        safTreeUri.lastPathSegment ?: "backup folder"
                    } else {
                        outputDir?.absolutePath ?: "backup folder"
                    }
                    _events.emit(BackupEvent.BackupComplete(pkg, outputDesc))
                } else {
                    _events.emit(BackupEvent.Failure("No data could be read for $pkg. The app may block backup access."))
                }
            } catch (e: Exception) {
                Timber.e(e, "Backup failed for $pkg")
                _events.emit(BackupEvent.Failure("Backup failed for $pkg: ${e.message}"))
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
                safTreeUri, DocumentsContract.getTreeDocumentId(safTreeUri)
            )
            // Try to create a per-package subdirectory; some providers (e.g. Downloads) don't
            // support MIME_TYPE_DIR and return null — fall back to a flat "{pkg}_{file}" name.
            val parentUri = try {
                DocumentsContract.createDocument(
                    cr, treeDocUri, DocumentsContract.Document.MIME_TYPE_DIR, pkg
                )
            } catch (_: Exception) { null }

            val (targetUri, targetName) = if (parentUri != null) {
                parentUri to fileName
            } else {
                treeDocUri to "${pkg}_$fileName"
            }

            val fileUri = try {
                DocumentsContract.createDocument(cr, targetUri, "application/octet-stream", targetName)
            } catch (e: Exception) {
                Timber.w(e, "createDocument failed for $pkg/$targetName")
                null
            } ?: return false

            cr.openOutputStream(fileUri)?.use { block(it) }
            return true
        } else {
            val pkgDir = File(outputDir!!, pkg).also { it.mkdirs() }
            FileOutputStream(File(pkgDir, fileName)).use { block(it) }
            return true
        }
    }

    /**
     * Sequentially backs up all currently loaded (filtered) user apps.
     * Emits [BackupEvent.BatchComplete] with succeeded/failed counts when done.
     */
    fun backupAll(outputDir: java.io.File? = null, safTreeUri: android.net.Uri? = null) {
        if (_batchRunning.value) return
        val apps = allApps.ifEmpty { return }
        viewModelScope.launch(Dispatchers.IO) {
            _batchRunning.value = true
            var succeeded = 0
            var failed = 0
            val cr = getApplication<Application>().contentResolver
            for (entry in apps) {
                val pkg = entry.packageName
                if (pkg in _busyPackages.value) continue
                _busyPackages.value = _busyPackages.value + pkg
                var prepared = false
                try {
                    ShizukuXAPI.BackupRestoreExtra.forceStop(pkg)
                    prepared = try {
                        ShizukuXAPI.ApkPatcher.prepareTempDebug(pkg)
                    } catch (_: Exception) { false }

                    var backedUpSomething = false
                    val dataPfd = try { ShizukuXAPI.ApkPatcher.streamDataDir(pkg) } catch (_: Exception) { null }
                    if (dataPfd != null) {
                        if (writeBackupStream(safTreeUri, outputDir, pkg, "data.tar.gz", cr) { out ->
                            dataPfd.use { pfd -> FileInputStream(pfd.fileDescriptor).use { it.copyTo(out) } }
                        }) backedUpSomething = true
                    }
                    val extPfd = try { ShizukuXAPI.BackupRestoreExtra.backupExternalData(pkg) } catch (_: Exception) { null }
                    if (extPfd != null) {
                        if (writeBackupStream(safTreeUri, outputDir, pkg, "external.tar.gz", cr) { out ->
                            extPfd.use { pfd -> FileInputStream(pfd.fileDescriptor).use { it.copyTo(out) } }
                        }) backedUpSomething = true
                    }
                    if (backedUpSomething) succeeded++ else failed++
                } catch (e: Exception) {
                    Timber.e(e, "Batch backup failed for $pkg")
                    failed++
                } finally {
                    if (prepared) try { ShizukuXAPI.ApkPatcher.restoreOriginal(pkg) } catch (_: Exception) {}
                    _busyPackages.value = _busyPackages.value - pkg
                }
            }
            val outputDesc = if (safTreeUri != null) {
                safTreeUri.lastPathSegment ?: "backup folder"
            } else {
                outputDir?.absolutePath ?: "backup folder"
            }
            _events.emit(BackupEvent.BatchComplete(succeeded, failed, outputDesc))
            _batchRunning.value = false
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
                _events.emit(BackupEvent.Failure("Freeze toggle failed: ${e.message}"))
            }
        }
    }
}
