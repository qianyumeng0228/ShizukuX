package af.shizuku.manager.module

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import af.shizuku.manager.R
import af.shizuku.manager.module.store.ModuleStoreClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * State and operations of the ADB module page, lifted out of [ModulesActivity].
 *
 * The page used to own these eleven `mutableStateOf` fields plus every coroutine that talks to
 * [AdbModuleManager] directly on the activity. That made the screen impossible to host anywhere
 * else, which blocks folding the five destinations into one pager container. Keeping the state
 * here lets both hosts - the standalone activity (deep link, shortcut, TV) and the shell - render
 * the very same [ModulesScreen] without a second implementation.
 */
class ModulesController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onToast: (CharSequence) -> Unit = {}
) {

    var modules by mutableStateOf<List<AdbModule>>(emptyList())
        private set
    var health by mutableStateOf<Map<String, ModuleHealthStatus>>(emptyMap())
        private set
    var healthChecked by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var installing by mutableStateOf(false)
        private set
    var checking by mutableStateOf(false)
        private set
    var busyModule by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var output by mutableStateOf<ModuleOutput?>(null)
        private set
    var pendingDelete by mutableStateOf<AdbModule?>(null)
        private set
    // Trust lives in ModuleSettings (not in AdbModule), so it needs its own observable state -
    // otherwise granting trust would write the preference without ever recomposing the card.
    var trustedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    // Chinese name/description published by the store catalog, cached on the last successful
    // store refresh. Installed cards prefer it on a Chinese device; a module without a Chinese
    // rendition keeps the English text from its own module.prop.
    var catalogLocalization by mutableStateOf<Map<String, Pair<String?, String?>>>(emptyMap())
        private set

    fun serviceRunning(): Boolean = Shizuku.pingBinder()

    fun refresh() {
        scope.launch {
            catalogLocalization = ModuleStoreClient.cachedLocalization(context)
            runCatching { AdbModuleManager.listModules(context) }
                .onSuccess { list ->
                    modules = list
                    trustedIds = list.filter { ModuleSettings.isModuleTrusted(it.id) }.map { it.id }.toSet()
                }
                .onFailure { message = it.message ?: it.toString() }
            loaded = true
        }
    }

    /** Called when the host comes back to the foreground; only refreshes once the first load ran. */
    fun onHostResumed() {
        if (loaded) refresh()
    }

    fun install(uri: Uri) {
        if (installing) return
        scope.launch {
            installing = true
            runCatching { AdbModuleManager.install(context, uri) }
                .onSuccess { module ->
                    // Success must not go through `message`: that composable is the failure dialog
                    // ("模块操作失败"), so a successful install used to pop a failure dialog whose
                    // body said the module was installed.
                    onToast(context.getString(R.string.modules_installed_ok, module.name))
                    healthChecked = false
                }
                .onFailure {
                    message = context.getString(
                        R.string.modules_install_failed,
                        it.message ?: it.toString()
                    )
                }
            installing = false
            refresh()
        }
    }

    fun checkHealth() {
        if (checking) return
        scope.launch {
            checking = true
            runCatching {
                val list = AdbModuleManager.listModules(context)
                list to list.associate { it.id to AdbModuleManager.inspectModuleHealth(it) }
            }.onSuccess { (list, map) ->
                modules = list
                health = map
                healthChecked = true
            }.onFailure { message = it.message ?: it.toString() }
            checking = false
        }
    }

    fun toggle(module: AdbModule) {
        if (busyModule != null) return
        scope.launch {
            busyModule = module.id
            runCatching { AdbModuleManager.setEnabled(module, !module.enabled) }
                .onFailure { message = it.message ?: it.toString() }
            busyModule = null
            healthChecked = false
            refresh()
        }
    }

    fun runScript(module: AdbModule, isService: Boolean) {
        if (busyModule != null) return
        if (!Shizuku.pingBinder()) {
            message = context.getString(R.string.modules_need_service)
            return
        }
        scope.launch {
            busyModule = module.id
            runCatching {
                if (isService) AdbModuleManager.runService(module)
                else AdbModuleManager.runAction(module)
            }.onSuccess { result ->
                output = ModuleOutput(
                    title = module.name + " · " + if (isService) "service.sh" else "action.sh",
                    result = result
                )
                healthChecked = false
            }.onFailure { message = it.message ?: it.toString() }
            busyModule = null
            refresh()
        }
    }

    fun delete(module: AdbModule) {
        scope.launch {
            runCatching { AdbModuleManager.delete(module) }
                .onFailure { message = it.message ?: it.toString() }
            pendingDelete = null
            healthChecked = false
            refresh()
        }
    }

    fun setTrusted(module: AdbModule, trusted: Boolean) {
        ModuleSettings.setModuleTrusted(module.id, trusted)
        trustedIds = if (trusted) trustedIds + module.id else trustedIds - module.id
        healthChecked = false
    }

    fun askDelete(module: AdbModule) {
        pendingDelete = module
    }

    fun dismissMessage() {
        message = null
    }

    fun dismissOutput() {
        output = null
    }

    fun cancelDelete() {
        pendingDelete = null
    }
}
