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

    /**
     * Progress of a batch backup/restore operation.
     */
    data class BatchProgress(
        val current: Int = 0,
        val total: Int = 0,
        val currentPkg: String = "",
        val succeeded: Int = 0,
        val failed: Int = 0,
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
        data class RestoreComplete(val pkg: String, val success: Boolean) : BackupEvent()
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
    private fun backupPreRestore(pkg: String, safTreeUri: android.net.Uri, cr: android.content.ContentResolver): String? {
        val timestamp = PreRestoreManager.generateTimestamp()
        val dataName = PreRestoreManager.getSnapshotFileName("data", timestamp)
        val extName = PreRestoreManager.getSnapshotFileName("external", timestamp)
        var backedUpSomething = false
        // Internal data (requires app debuggable — caller must prepareTempDebug first)
        val dataPfd = try { ShizukuXAPI.ApkPatcher.streamDataDir(pkg) } catch (_: Exception) { null }
        if (dataPfd != null) {
            if (writeBackupStream(safTreeUri, null, pkg, dataName, cr) { out ->
                dataPfd.use { pfd -> FileInputStream(pfd.fileDescriptor).use { it.copyTo(out) } }
            }) backedUpSomething = true
        }
        // External data (shell-level, no debuggable required)
        val extPfd = try { ShizukuXAPI.BackupRestoreExtra.backupExternalData(pkg) } catch (_: Exception) { null }
        if (extPfd != null) {
            if (writeBackupStream(safTreeUri, null, pkg, extName, cr) { out ->
                extPfd.use { pfd -> FileInputStream(pfd.fileDescriptor).use { it.copyTo(out) } }
            }) backedUpSomething = true
        }
        if (backedUpSomething) {
            PreRestoreManager.recordSnapshot(getApplication(), pkg, timestamp)
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
        safTreeUri: android.net.Uri,
        includeInternal: Boolean = true,
        snapshotTimestamp: String? = null,
        isRollback: Boolean = false
    ): Boolean {
        val pkg = entry.packageName
        val cr = getApplication<Application>().contentResolver
        var prepared = false
        // Determine source file names: normal restore uses data.tar.gz, rollback uses snapshot
        val dataFileName = if (snapshotTimestamp != null)
            PreRestoreManager.getSnapshotFileName("data", snapshotTimestamp) else "data.tar.gz"
        val extFileName = if (snapshotTimestamp != null)
            PreRestoreManager.getSnapshotFileName("external", snapshotTimestamp) else "external.tar.gz"
        return try {
            // Make app debuggable so run-as can access /data/data/<pkg>/
            prepared = try {
                ShizukuXAPI.ApkPatcher.prepareTempDebug(pkg)
            } catch (_: Exception) { false }

            // Auto-backup current data before restore (skip for rollbacks — we're restoring FROM a snapshot)
            if (!isRollback) {
                val preRestoreTs = backupPreRestore(pkg, safTreeUri, cr)
                if (preRestoreTs == null) {
                    Timber.w("Pre-restore backup failed for $pkg — aborting restore to prevent data loss")
                    recordRestoreHistory(entry, includeInternal, success = false, isRollback = false, snapshotTimestamp = null)
                    return@restoreAppData false
                }
            }

            // Clear existing data for clean restore state
            try { ShizukuXAPI.BackupRestoreExtra.clearAppData(pkg) } catch (_: Exception) {}

            var restoredSomething = false

            // 1. Restore internal data (/data/data/<pkg>/)
            if (includeInternal) {
                val dataUri = findBackupFile(safTreeUri, pkg, dataFileName, cr)
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
            val extUri = findBackupFile(safTreeUri, pkg, extFileName, cr)
            if (extUri != null) {
                val ok = cr.openFileDescriptor(extUri, "r")?.use { pfd ->
                    ShizukuXAPI.BackupRestoreExtra.restoreExternalData(pkg, pfd)
                } ?: false
                if (ok) restoredSomething = true
            } else {
                Timber.w("No external.tar.gz found for $pkg (external data skip)")
            }

            recordRestoreHistory(entry, includeInternal, restoredSomething, isRollback, snapshotTimestamp)
            restoredSomething
        } catch (e: Exception) {
            Timber.e(e, "Restore failed for $pkg")
            recordRestoreHistory(entry, includeInternal, success = false, isRollback = isRollback, snapshotTimestamp = snapshotTimestamp)
            false
        } finally {
            if (prepared) try { ShizukuXAPI.ApkPatcher.restoreOriginal(pkg) } catch (_: Exception) {}
        }
    }

    private fun recordRestoreHistory(
        entry: AppEntry, includeInternal: Boolean, success: Boolean,
        isRollback: Boolean, snapshotTimestamp: String?
    ) {
        val scope = if (includeInternal) "both" else "external"
        RestoreHistoryManager.addEntry(getApplication(), RestoreHistoryManager.Entry(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            packageName = entry.packageName,
            appLabel = entry.label,
            scope = scope,
            success = success,
            isRollback = isRollback,
            snapshotTimestamp = snapshotTimestamp
        ))
    }

    /**
     * Batch restore external data for multiple apps from a SAF backup directory.
     * Emits progress via [batchProgress] and [RestoreComplete] per app.
     * Clears app data before restore to ensure clean state.
     */
    fun restoreAll(entries: List<AppEntry>, safTreeUri: android.net.Uri, includeInternal: Boolean = true, snapshotTimestamp: String? = null, isRollback: Boolean = false) {
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
                    succeeded = succeeded, failed = failed
                )
                try {
                    val ok = restoreAppData(entry, safTreeUri, includeInternal, snapshotTimestamp, isRollback)
                    if (ok) succeeded++ else failed++
                    _events.emit(BackupEvent.RestoreComplete(pkg, ok))
                } catch (e: Exception) {
                    Timber.e(e, "Batch restore failed for $pkg")
                    failed++
                }
            }
            _batchProgress.value = BatchProgress(current = total, total = total, succeeded = succeeded, failed = failed)
            _events.emit(BackupEvent.BatchComplete(succeeded, failed, "restore"))
            _batchRunning.value = false
        }
    }

    /**
     * Find a backup file (e.g. external.tar.gz) inside a package's subdirectory
     * of the SAF tree. Returns the document Uri, or null if not found.
     */
    private fun findBackupFile(
        safTreeUri: android.net.Uri, pkg: String, fileName: String,
        cr: android.content.ContentResolver
    ): android.net.Uri? {
        return try {
            val treeId = android.provider.DocumentsContract.getTreeDocumentId(safTreeUri)
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(safTreeUri, treeId)
            // First try to find the package subdirectory
            val pkgDirId = queryForDocument(childrenUri, cr, pkg)
            if (pkgDirId != null) {
                val pkgChildrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(safTreeUri, pkgDirId)
                val fileId = queryForDocument(pkgChildrenUri, cr, fileName)
                if (fileId != null) {
                    return android.provider.DocumentsContract.buildDocumentUriUsingTree(safTreeUri, fileId)
                }
            }
            // Fallback: try flat name "{pkg}_{fileName}" (Downloads provider)
            val flatId = queryForDocument(childrenUri, cr, "${pkg}_$fileName")
            if (flatId != null) {
                android.provider.DocumentsContract.buildDocumentUriUsingTree(safTreeUri, flatId)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun queryForDocument(
        childrenUri: android.net.Uri, cr: android.content.ContentResolver, displayName: String
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
