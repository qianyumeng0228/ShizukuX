package af.shizuku.manager.module

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import androidx.annotation.StringRes

/**
 * Access-policy settings for ADB modules (local variant, ported from Nightzuku's
 * ModuleSettings). Persists through the existing [ShizukuSettings] preferences.
 *
 * Strategy tiers (roadmap P1-3):
 *   SAFE  (default) — action/service scripts blocked unless the module is trusted
 *   FULL            — everything allowed (each run still goes through command review)
 *   CUSTOM          — per-capability toggles
 */
object ModuleSettings {

    private const val KEY_ACCESS_MODE = "adb_modules_access_mode"
    private const val KEY_ALLOW_BACKGROUND_ACTIONS = "adb_modules_allow_background_actions"
    private const val KEY_TRUSTED_MODULES = "adb_modules_trusted_modules"

    enum class AccessMode(val value: String, @StringRes val labelRes: Int) {
        SAFE("safe", R.string.modules_access_mode_safe),
        CUSTOM("custom", R.string.modules_access_mode_custom),
        FULL("full", R.string.modules_access_mode_full);

        companion object {
            fun fromValue(value: String?): AccessMode {
                return entries.firstOrNull { it.value == value } ?: SAFE
            }
        }
    }

    data class CustomPermissions(
        val action: Boolean,
        val service: Boolean,
        val webBridge: Boolean,
        val webNetwork: Boolean,
        val webDownload: Boolean
    )

    private const val KEY_CUSTOM_ACTION = "adb_modules_custom_action"
    private const val KEY_CUSTOM_SERVICE = "adb_modules_custom_service"
    private const val KEY_CUSTOM_WEB_BRIDGE = "adb_modules_custom_web_bridge"
    private const val KEY_CUSTOM_WEB_NETWORK = "adb_modules_custom_web_network"
    private const val KEY_CUSTOM_WEB_DOWNLOAD = "adb_modules_custom_web_download"

    fun getAccessMode(): AccessMode {
        return AccessMode.fromValue(
            ShizukuSettings.getPreferences().getString(KEY_ACCESS_MODE, AccessMode.SAFE.value)
        )
    }

    fun setAccessMode(mode: AccessMode) {
        ShizukuSettings.getPreferences().edit().putString(KEY_ACCESS_MODE, mode.value).apply()
    }

    fun allowBackgroundActions(): Boolean {
        return ShizukuSettings.getPreferences().getBoolean(KEY_ALLOW_BACKGROUND_ACTIONS, false)
    }

    fun setAllowBackgroundActions(value: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_ALLOW_BACKGROUND_ACTIONS, value).apply()
    }

    fun getCustomPermissions(): CustomPermissions {
        val prefs = ShizukuSettings.getPreferences()
        return CustomPermissions(
            action = prefs.getBoolean(KEY_CUSTOM_ACTION, false),
            service = prefs.getBoolean(KEY_CUSTOM_SERVICE, false),
            webBridge = prefs.getBoolean(KEY_CUSTOM_WEB_BRIDGE, false),
            webNetwork = prefs.getBoolean(KEY_CUSTOM_WEB_NETWORK, false),
            webDownload = prefs.getBoolean(KEY_CUSTOM_WEB_DOWNLOAD, false)
        )
    }

    fun setCustomPermissions(value: CustomPermissions) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(KEY_CUSTOM_ACTION, value.action)
            .putBoolean(KEY_CUSTOM_SERVICE, value.service)
            .putBoolean(KEY_CUSTOM_WEB_BRIDGE, value.webBridge)
            .putBoolean(KEY_CUSTOM_WEB_NETWORK, value.webNetwork)
            .putBoolean(KEY_CUSTOM_WEB_DOWNLOAD, value.webDownload)
            .apply()
    }

    fun canRunAction(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().action
        }
    }

    fun canRunAction(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canRunAction()
    }

    fun canRunService(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().service
        }
    }

    fun canRunService(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canRunService()
    }

    fun canExposeWebBridge(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().webBridge
        }
    }

    fun canExposeWebBridge(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canExposeWebBridge()
    }

    fun canUseWebNetwork(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> false
            AccessMode.CUSTOM -> getCustomPermissions().webNetwork
        }
    }

    fun canUseWebNetwork(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canUseWebNetwork()
    }

    fun canDownloadWebFiles(): Boolean {
        return when (getAccessMode()) {
            AccessMode.SAFE -> false
            AccessMode.FULL -> true
            AccessMode.CUSTOM -> getCustomPermissions().webDownload
        }
    }

    fun canDownloadWebFiles(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || canDownloadWebFiles()
    }

    fun canRunBackground(module: AdbModule): Boolean {
        return isModuleTrusted(module.id) || allowBackgroundActions()
    }

    fun isModuleTrusted(moduleId: String): Boolean {
        return ShizukuSettings.getPreferences()
            .getStringSet(KEY_TRUSTED_MODULES, emptySet())
            ?.contains(moduleId)
            ?: false
    }

    fun setModuleTrusted(moduleId: String, trusted: Boolean) {
        val prefs = ShizukuSettings.getPreferences()
        val current = prefs.getStringSet(KEY_TRUSTED_MODULES, emptySet()).orEmpty().toMutableSet()
        if (trusted) {
            current += moduleId
        } else {
            current -= moduleId
        }
        prefs.edit().putStringSet(KEY_TRUSTED_MODULES, current).apply()
    }
}
