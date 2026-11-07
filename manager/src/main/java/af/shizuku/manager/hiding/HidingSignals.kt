package af.shizuku.manager.hiding

import af.shizuku.manager.R
import androidx.annotation.StringRes

/**
 * One setting a restrictive app reads, and the value it objects to.
 *
 * [namespace] and [name] are the arguments of `settings put`, and [hidden] is what the app sees
 * instead of the truth. Accessibility is two keys rather than one - the switch and the list of
 * what it turns on - so a signal is a list of them and counts as hidden only when every one of
 * them says so.
 */
data class SettingKey(
    val namespace: String,
    val name: String,
    val hidden: String,
    /**
     * What to write when the setting is hidden and nothing was recorded to put back.
     *
     * Null for the keys where no value can stand in for what the user had: an accessibility
     * service list is theirs and cannot be invented. For those the recorded value is the only
     * way back, which is why it is kept even after a restore.
     */
    val visible: String? = null
) {
    val id: String get() = "$namespace/$name"
}

/**
 * What a banking app, a payment app or a game looks at to decide this device is not one it will
 * run on.
 *
 * Every one of these is a real setting on the device, and hiding it means really changing it:
 * the shell writes the value and every app that reads it afterwards sees the lie. That is the
 * whole of what can be done without root, and it is worth being plain about the limit. An app
 * that inspects its own process can be lied to alone, which is what Xposed modules do; a setting
 * read through the settings provider is answered for whoever asks, and there is no way from
 * outside the system to answer one caller differently from the next.
 *
 * So a signal is hidden for the device while it is hidden, which is why the lists matter and why
 * the watch exists: hiding lasts for as long as the app that objects is in front, and no longer.
 */
enum class Signal(
    @StringRes val labelRes: Int,
    val keys: List<SettingKey>
) {
    DEVELOPER_OPTIONS(
        R.string.hiding_signal_developer_options,
        listOf(SettingKey("global", "development_settings_enabled", "0", visible = "1"))
    ),

    USB_DEBUGGING(
        R.string.hiding_signal_usb_debugging,
        listOf(SettingKey("global", "adb_enabled", "0", visible = "1"))
    ),

    WIRELESS_DEBUGGING(
        R.string.hiding_signal_wireless_debugging,
        listOf(SettingKey("global", "adb_wifi_enabled", "0", visible = "1"))
    ),

    ACCESSIBILITY(
        R.string.hiding_signal_accessibility,
        listOf(
            SettingKey("secure", "accessibility_enabled", "0", visible = "1"),
            SettingKey("secure", "enabled_accessibility_services", "")
        )
    ),

    /**
     * Both keys, because the mode is not the whole answer: a device on a hostname has that
     * hostname written down beside it, and an app that reads settings directly would still find
     * it there while the mode said off. Restoring puts the mode back and the hostname with it.
     */
    PRIVATE_DNS(
        R.string.hiding_signal_private_dns,
        listOf(
            SettingKey("global", "private_dns_mode", "off"),
            SettingKey("global", "private_dns_specifier", "")
        )
    ),

    /**
     * The tunnel, which is not a setting anywhere and cannot be written as one.
     *
     * There is no value in the settings provider that says a VPN is up: the state lives in a
     * service, as a live session, and an app reads it from the network it is routed over. So this
     * one is not hidden by writing anything - it is taken down by stopping the client that owns
     * the tunnel, and has to be started again, which is why it carries no keys.
     */
    VPN(
        R.string.hiding_signal_vpn,
        emptyList()
    )
}
