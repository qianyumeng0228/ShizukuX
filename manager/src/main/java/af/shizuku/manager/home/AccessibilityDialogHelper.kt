package af.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.TypefaceSpan
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.core.content.asActivity
import timber.log.Timber
import af.shizuku.manager.R
import af.shizuku.manager.adb.AdbPairingAccessibilityService
import af.shizuku.manager.utils.SettingsPage

fun Context.showAccessibilityDialog() {
    // The accessibility dialogs are MaterialAlertDialogBuilders, which require an Activity
    // theme (Material's ThemeEnforcement checks the AppCompat isLightTheme attribute).
    // Building them with an application/plain context crashes with "The style on this component
    // requires your app theme to be Theme.AppCompat" (observed on k2012: 一键启动 → 启用).
    // Resolve the host Activity first and refuse to show when none is available.
    val host = asActivity<android.app.Activity>() ?: return
    host.run {
        val hasWriteSecureSettings = (checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)

        if (isAccessibilityEnabled()) {
            showNavigateDialog()
        } else if (hasWriteSecureSettings) {
            if (enableAccessibilityService()) return
            showEnableDialog()
        } else {
            showEnableDialog()
        }
    }
}

/**
 * The one dialog between the automated way and nothing happening, and it names both reasons the
 * switch can refuse to move.
 *
 * A fresh install has no WRITE_SECURE_SETTINGS, so it cannot turn the service on by itself; and
 * Android's restricted settings keep the accessibility switch of an app that did not come from
 * an app store greyed out and inert, which is what the list shows as "Controlled by Restricted
 * Setting". The second one cannot be read back from the platform - the app-op behind it reports
 * the same state before and after Android decides to grey the row - so it is described rather
 * than detected, and the same dialog has to serve both: sending somebody to a dead switch with
 * no explanation is exactly how they end up hunting through App info by themselves.
 */
private fun Context.showEnableDialog() {
    // The reason this dialog is here, then the whole of what the greyed-out row means and both
    // ways to open it: the App info menu on the phone, the command with a computer attached.
    val message = pairingEnableMessage()

    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_adb_pairing_title)
        .setMessage(message)
        .setPositiveButton(R.string.enable) { _, _ ->
            SettingsPage.Accessibility.launch(this)
        }
        .setNeutralButton(R.string.dialog_adb_pairing_open_app_info) { _, _ ->
            SettingsPage.ApplicationDetails.launch(this)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/**
 * The two paragraphs of the dialog, with the one ADB command in them set in monospace.
 *
 * The placeholders are filled in here rather than with TextUtils.expandTemplate: that one parses
 * the whole message as one template and throws for a placeholder a translation carries over from
 * a string it no longer belongs to, and a dialog is a poor place to crash.
 */
private fun Context.pairingEnableMessage(): CharSequence {
    val command = "adb shell cmd appops set $packageName ACCESS_RESTRICTED_SETTINGS allow"
    val text = buildString {
        append(getString(R.string.dialog_adb_pairing_accessibility_enable))
        append("\n\n")
        append(
            getString(R.string.dialog_adb_pairing_accessibility_permission_template)
                .replace("^1", "ACCESS_RESTRICTED_SETTINGS")
                .replace("^2", command),
        )
    }

    return SpannableString(text).apply {
        val start = indexOf(command)
        if (start >= 0) {
            setSpan(
                TypefaceSpan("monospace"),
                start,
                start + command.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}

private fun Context.showNavigateDialog() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.dialog_adb_pairing_title)
        .setMessage(R.string.dialog_adb_pairing_accessibility_navigate)
        .setPositiveButton(R.string.development_settings) { _, _ ->
            SettingsPage.Developer.HighlightWirelessDebugging.launch(this)
        }.setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun Context.getEnabledAccessibilityServices(): List<String>? {
    val enabledServices =
        Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    return enabledServices?.split(":")
}

private fun Context.isAccessibilityEnabled(): Boolean {
    val accessibilityServiceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
    return getEnabledAccessibilityServices()?.any { it.equals(accessibilityServiceName) } ?: false
}

private fun Context.enableAccessibilityService(): Boolean {
    if (isAccessibilityEnabled()) return true

    val accessibilityServiceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
    val enabledServices = getEnabledAccessibilityServices()
    val newServices =
        if (enabledServices.isNullOrEmpty()) {
            accessibilityServiceName
        } else {
            enabledServices.joinToString(":") + ":$accessibilityServiceName"
        }

    Settings.Secure.putString(
        contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        newServices,
    )

    return isAccessibilityEnabled()
}

/** Public entry: turn the pairing assistant (accessibility service) on directly. */
fun Context.enablePairingAssistant(): Boolean {
    // enableAccessibilityService() writes Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, which
    // requires WRITE_SECURE_SETTINGS. Without it the write throws SecurityException on the
    // caller's thread — observed as a hard crash (点击"配对"→"启用") on non-rooted
    // Android 17 / HyperOS where the permission was never granted via adb (k2010, pairing-crash).
    // Return false so callers fall back to showAccessibilityDialog(), which has its own
    // permission-gated flow instead of crashing.
    if (checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
    return try {
        enableAccessibilityService()
    } catch (e: Throwable) {
        Timber.w(e, "enablePairingAssistant: failed to enable accessibility service")
        false
    }
}

/** Remove the pairing assistant from the enabled accessibility services. */
fun Context.disablePairingAssistant() {
    runCatching {
        val accessibilityServiceName = "$packageName/${AdbPairingAccessibilityService::class.java.canonicalName}"
        val enabledServices = getEnabledAccessibilityServices()
        if (enabledServices.isNullOrEmpty()) return
        val newServices = enabledServices.filterNot { it.equals(accessibilityServiceName) }.joinToString(":")
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newServices)
    }
}

/** Whether the pairing assistant accessibility service is currently enabled. */
fun Context.isPairingAssistantEnabled(): Boolean = isAccessibilityEnabled()
