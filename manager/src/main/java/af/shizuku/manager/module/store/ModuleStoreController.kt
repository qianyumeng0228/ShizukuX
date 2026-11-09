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
}
