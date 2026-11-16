package af.shizuku.manager.module.store

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import af.shizuku.manager.R
import af.shizuku.manager.module.AdbModuleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * State holder for [ModuleStoreScreen].
 *
 * Kept separate from [ModulesController] on purpose: the store is an independent screen with
 * its own network lifecycle, and the only thing it hands back to the installed-module page is
 * the fact that a ZIP was installed (the modules page re-reads the disk on resume anyway).
 */
class ModuleStoreController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onToast: (CharSequence) -> Unit = {}
) {

    var state by mutableStateOf(ModuleStoreUiState())
        private set

    /** Upload sheet state; kept apart from the catalog state so a failed submission cannot
     *  disturb the list the user is browsing. */
    var uploadState by mutableStateOf(ModuleUploadUiState())
        private set

    fun refresh() {
        if (state.loading) return
        scope.launch {
            state = state.copy(loading = true, error = null)
            val installed = runCatching { AdbModuleManager.listModules(context) }
                .getOrElse { emptyList() }
                .associate { it.id to it.versionCode }

            runCatching { ModuleStoreClient.fetchIndex() }
                .onSuccess { fetched ->
                    ModuleStoreClient.cacheIndex(context, fetched.index.modules)
                    state = state.copy(
                        loading = false,
                        loaded = true,
                        entries = fetched.index.modules.sortedWith(
                            compareByDescending<ModuleStoreEntry> { it.lastUpdated ?: 0L }
                                .thenBy { it.displayName }
                        ),
                        installedVersionCodes = installed,
                        error = null,
                        sourceName = fetched.source.name,
                        sourceHost = fetched.source.host,
                        lastUpdated = fetched.index.updatedAt
                    )
                }
                .onFailure { failure ->
                    state = state.copy(
                        loading = false,
                        loaded = true,
                        installedVersionCodes = installed,
                        error = failure.message ?: failure.toString()
                    )
                }
        }
    }

    fun install(entry: ModuleStoreEntry, onInstalled: () -> Unit = {}) {
        if (state.installingId != null) return
        scope.launch {
            state = state.copy(installingId = entry.id, installProgress = null, error = null)
            ModuleStoreClient.cleanupOldDownloads(context)
            runCatching {
                val zip = ModuleStoreClient.download(context, entry) { progress ->
                    state = state.copy(installProgress = progress)
                }
                AdbModuleManager.install(context, Uri.fromFile(zip))
            }.onSuccess { module ->
                state = state.copy(
                    installingId = null,
                    installProgress = null,
                    installedVersionCodes = state.installedVersionCodes +
                        (module.id to module.versionCode)
                )
                onToast(context.getString(R.string.modules_installed_ok, module.name))
                onInstalled()
            }.onFailure { failure ->
                state = state.copy(
                    installingId = null,
                    installProgress = null,
                    error = failure.message ?: failure.toString()
                )
            }
        }
    }

    fun dismissError() {
        state = state.copy(error = null)
    }

    // -- upload ------------------------------------------------------------

    fun openUpload() {
        uploadState = ModuleUploadUiState(visible = true)
    }

    fun dismissUpload() {
        uploadState = ModuleUploadUiState()
    }

    /**
     * Validates the picked ZIP. Everything runs locally first, so an archive that would be
     * rejected by the installer never reaches the network.
     */
    fun inspectUpload(uri: Uri) {
        if (uploadState.inspecting || uploadState.submitting) return
        scope.launch {
            uploadState = uploadState.copy(
                visible = true,
                inspecting = true,
                error = null,
                message = null,
                done = false
            )
            runCatching { ModuleUploadClient.inspect(context, uri) }
                .onSuccess { candidate ->
                    uploadState = uploadState.copy(
                        inspecting = false,
                        candidate = candidate,
                        draft = ModuleDraft.of(candidate)
                    )
                }
                .onFailure { failure ->
                    uploadState = uploadState.copy(
                        inspecting = false,
                        candidate = null,
                        draft = null,
                        error = context.getString(
                            R.string.modules_upload_inspect_failed,
                            failure.message ?: failure.toString()
                        )
                    )
                }
        }
    }

    /** Field-by-field edits from the upload sheet; nothing is repacked until submit. */
    fun updateDraft(draft: ModuleDraft) {
        if (uploadState.submitting) return
        uploadState = uploadState.copy(draft = draft, message = null)
    }

    fun submitUpload() {
        val candidate = uploadState.candidate ?: return
        val draft = uploadState.draft ?: ModuleDraft.of(candidate)
        if (uploadState.submitting) return
        scope.launch {
            uploadState = uploadState.copy(submitting = true, error = null, message = null)
            runCatching { ModuleUploadClient.applyDraft(candidate, draft) }
                .onSuccess { edited ->
                    uploadState = uploadState.copy(candidate = edited)
                    runCatching { ModuleUploadClient.submit(edited) }
                        .onSuccess {
                            uploadState = uploadState.copy(
                                submitting = false,
                                done = true,
                                message = context.getString(R.string.modules_upload_ok)
                            )
                            onToast(context.getString(R.string.modules_upload_ok))
                        }
                        .onFailure { failure ->
                            uploadState = uploadState.copy(
                                submitting = false,
                                error = context.getString(
                                    R.string.modules_upload_failed,
                                    failure.message ?: failure.toString()
                                )
                            )
                        }
                }
                .onFailure { failure ->
                    uploadState = uploadState.copy(
                        submitting = false,
                        error = context.getString(
                            R.string.modules_upload_failed,
                            failure.message ?: failure.toString()
                        )
                    )
                }
        }
    }

    /** No backend configured: hand the user the manifest so the module can still be submitted. */
    fun exportUpload() {
        val candidate = uploadState.candidate ?: return
        val manifest = ModuleUploadClient.manifestJson(candidate)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(
            android.content.ClipData.newPlainText("module.prop submission", manifest)
        )
        onToast(context.getString(R.string.modules_upload_copied))
    }
}
