package af.shizuku.manager.wifi

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.os.Process as AndroidProcess
import android.util.Xml
import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuWait
import org.lsposed.hiddenapibypass.HiddenApiBypass
import org.xmlpull.v1.XmlPullParser
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.StringReader
import java.util.BitSet

/** One saved network: [key] is null for an open network, an unsupported (enterprise) one, or when only names are readable. */
data class WifiNetwork(
    val ssid: String,
    val key: String?,
    val enterprise: Boolean = false,
    val connected: Boolean = false,
) {
    val open: Boolean get() = key == null && !enterprise
}

/**
 * What the screen can actually show.
 *
 * [withKeys] false means the passwords are out of reach, not that every network is open - the UI
 * must not read a null key as "open network" in that mode. It stays true whenever the source itself
 * carries the password field, even if that particular list happens to be all open networks.
 */
data class WifiSnapshot(
    val networks: List<WifiNetwork>,
    val withKeys: Boolean,
)

/**
 * Reads the saved Wi-Fi passwords.
 *
 * Three sources, tried in this order:
 *
 * 1. **The platform's own privileged list over Shizuku** - `IWifiManager.getPrivilegedConfiguredNetworks`.
 *    It needs `NETWORK_SETTINGS`, which the `shell` uid (2000) already holds, so an ADB-mode Shizuku
 *    can call it without root. This is the path Settings' password-sharing screen effectively uses,
 *    and the only one that works with no su on the device.
 * 2. **The config store file as root** - `/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml`
 *    is 0600 system:system, so only a root shell can read it. Kept for devices where Shizuku is not
 *    available but su is.
 * 3. **`cmd wifi list-networks`** - names and security type only, never the key. Listing those beats
 *    failing the whole page, so the screen degrades to SSID-only.
 *
 * Note that `dumpsys wifi` never helps here: its `PreSharedKey` hits are `HasEncryptedPreSharedKey`
 * booleans, not secrets.
 */
object WifiPasswordManager {

    /**
     * The config store on this platform generation.
     *
     * Wi-Fi moved into an APEX module, and with it the store moved from `/data/misc/wifi` (which
     * now only holds the supplicant's own files) to `/data/misc/apexdata/com.android.wifi`.
     */
    private const val STORE = "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml"

    /** The pre-APEX location, kept as a fallback for builds that never moved. */
    private const val LEGACY_STORE = "/data/misc/wifi/WifiConfigStore.xml"

    /** Shell-visible listing; carries no key, which is exactly why it works without root. */
    private const val CMD_LIST = "cmd wifi list-networks"

    /** The only shell-visible place that says which network is currently joined. */
    private const val CMD_STATUS = "cmd wifi status"

    private const val SSID_TAG = "SSID"
    private const val KEY_TAG = "PreSharedKey"
    private const val NETWORK_TAG = "WifiConfiguration"
    private const val SECURITY_TYPE = "SecurityType"
    private const val SECURITY_ENTERPRISE = 3

    /** `WifiConfiguration.KEY_MGMT_EAP` - the bit that marks an enterprise network. */
    private const val KEY_MGMT_EAP = 3

    /** Key the platform reads the caller attribution from on Android 13+. */
    private const val EXTRA_ATTRIBUTION = "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE"

    private const val WIFI_SERVICE = "wifi"
    private const val WIFI_STUB = "android.net.wifi.IWifiManager\$Stub"
    private const val PRIVILEGED = "getPrivilegedConfiguredNetworks"

    /** The package this app talks to the platform as, matching what shell-run Shizuku reports. */
    private const val SHELL_PACKAGE = "com.android.shell"
    private val CONNECTED = Regex("Wifi is connected to \"(.+)\"")

    /** `<id>  <ssid>  <security>`, with a trailing `^` marking a transition mode. */
    private val LIST_ROW = Regex("^\\s*(\\d+)\\s+(.+)$")

    fun isAvailable(): Boolean = ShizukuWait.ping()

    /**
     * Route that survives a release build.
     *
     * [LOGGER] is a debug-only channel here, and a refused privileged call leaves no trace on the
     * system side (it never reaches the Wi-Fi service), so the cause has to be recorded locally.
     */
    private fun trace(message: String) {
        LOGGER.w(message)
        runCatching { android.util.Log.w("SXWIFI", message) }
    }

    /**
     * Every saved network, with passwords when either privileged reader works and with names only
     * otherwise; null when no path produced anything.
     */
    fun load(): WifiSnapshot? {
        val connected = connectedSsid()
        // The privileged list is tried first because it costs nothing extra and is the only source
        // that needs neither root nor a plain-shell read of the config store.
        val privileged = privilegedNetworks()
        if (privileged != null) return WifiSnapshot(mark(privileged, connected), withKeys = true)
        // The store is 0600 system:system, so SELinux denies the Shizuku shell (uid 2000) even
        // though the file is not encrypted: the plain shell path can never work on this platform
        // generation. A root shell is therefore the fallback reader.
        val xml = readStores(asRoot = true) ?: readStores(asRoot = false)
        if (xml != null) {
            val parsed = parse(xml)
            if (!parsed.isNullOrEmpty()) return WifiSnapshot(mark(parsed, connected), withKeys = true)
            // A readable but unparsable store is not a reason to show nothing: the shell listing
            // still names the networks, so fall through to it instead of failing the page.
            LOGGER.w("wifi store parsed to nothing, falling back to shell listing")
        }
        val listed = listNetworks() ?: return null
        return WifiSnapshot(mark(listed, connected), withKeys = false)
    }

    /**
     * The saved networks as the platform reports them to a privileged caller, or null when the
     * privileged call is unavailable or refused.
     *
     * `IWifiManager` is not exposed to apps, so the binder stub and the method are reached reflectively;
     * `ShizukuBinderWrapper` makes the transact arrive with Shizuku's uid instead of this app's, which
     * is what turns the `NETWORK_SETTINGS` check from a denial into an allow.
     */
    private fun privilegedNetworks(): List<WifiNetwork>? {
        if (!isAvailable()) return null
        // No checkSelfPermission gate: it answers -1 on this build even though newProcess already
        // works, and the authority that actually matters is the Wi-Fi service checking the uid
        // behind the wrapper. A refusal surfaces as an exception from the call itself.
        val service = runCatching {
            val binder: IBinder = SystemServiceHelper.getSystemService(WIFI_SERVICE) ?: return null
            Class.forName(WIFI_STUB)
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(binder))
        }
            .onFailure { trace("wifi privileged: service unreachable: $it") }
            .getOrNull() ?: return null
        val extras = attributionExtras()
        // The signature grew an attribution argument with Android 11 and a bundle with Android 13,
        // and some ROMs carry only one of the two, so every arity is offered rather than guessed.
        for (arguments in argumentSets(extras)) {
            val slice = runCatching { callPrivileged(service, arguments) }
                .onFailure { trace("wifi privileged: ${arguments.size}-arg call failed: $it") }
                .getOrNull()
            if (slice == null) {
                trace("wifi privileged: ${arguments.size}-arg returned null")
                continue
            }
            val list = runCatching { sliceList(slice) }
                .onFailure { trace("wifi privileged: slice unreadable (${slice.javaClass.name}): ${it.message}") }
                .getOrNull()
            if (list == null) {
                trace("wifi privileged: no list inside ${slice.javaClass.name}")
                continue
            }
            trace("wifi privileged: ${arguments.size}-arg -> ${list.size} configuration(s)")
            val networks = list.mapNotNull { readConfiguration(it) }
                .distinctBy { it.ssid }
                .sortedBy { it.ssid.lowercase() }
            trace("wifi privileged: ${networks.size} readable network(s)")
            if (networks.isNotEmpty()) return networks
        }
        return null
    }

    private fun callPrivileged(service: Any, arguments: Array<Any?>): Any? {
        val types = when (arguments.size) {
            3 -> arrayOf<Class<*>>(String::class.java, String::class.java, Bundle::class.java)
            2 -> arrayOf<Class<*>>(String::class.java, String::class.java)
            else -> arrayOf<Class<*>>(String::class.java)
        }
        val method = HiddenApiBypass.getDeclaredMethod(service.javaClass, PRIVILEGED, *types)
        return method.invoke(service, *arguments)
    }

    /** The argument shapes to try: `shell` as the caller name first, then the real package. */
    private fun argumentSets(extras: Bundle): List<Array<Any?>> {
        val user = shizukuUser
        return listOf(
            arrayOf<Any?>(user, SHELL_PACKAGE, extras),
            arrayOf<Any?>(SHELL_PACKAGE, SHELL_PACKAGE, extras),
            arrayOf<Any?>(user, SHELL_PACKAGE),
            arrayOf<Any?>(SHELL_PACKAGE, SHELL_PACKAGE),
            arrayOf<Any?>(user),
            arrayOf<Any?>(SHELL_PACKAGE),
        )
    }

    /** What Shizuku itself runs as; the platform checks the uid behind the binder, not this name. */
    private val shizukuUser: String
        get() = when (Shizuku.getUid()) {
            AndroidProcess.ROOT_UID -> "root"
            AndroidProcess.SYSTEM_UID -> "android"
            else -> "shell"
        }

    /**
     * The caller-attribution bundle Android 13+ reads the privileged list from.
     *
     * Without it the platform falls back to the Wi-Fi service's own attribution and refuses; the
     * source has to describe the uid that is actually behind the binder wrapper.
     */
    private fun attributionExtras(): Bundle {
        val extras = Bundle()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return extras
        val uid = Shizuku.getUid().takeIf { it > 0 } ?: AndroidProcess.SHELL_UID
        val source = runCatching {
            // The constructor's shape has changed repeatedly and is not reachable through
            // `getDeclaredConstructor` on this build, so the public builder is used instead.
            val builderType = Class.forName("android.content.AttributionSource\$Builder")
            val builder = builderType.getDeclaredConstructor(Int::class.javaPrimitiveType!!)
                .apply { isAccessible = true }
                .newInstance(uid)
            builderType.getMethod("setPackageName", String::class.java).invoke(builder, SHELL_PACKAGE)
            runCatching {
                builderType.getMethod("setAttributionTag", String::class.java).invoke(builder, SHELL_PACKAGE)
            }
            builderType.getMethod("build").invoke(builder)
        }
            .onFailure { trace("wifi privileged: attribution builder failed: $it") }
            .getOrNull()
        if (source is Parcelable) extras.putParcelable(EXTRA_ATTRIBUTION, source)
        return extras
    }

    /** `ParceledListSlice.getList()`, or the list itself on builds that hand it back unwrapped. */
    private fun sliceList(slice: Any): List<*>? = when (slice) {
        is List<*> -> slice
        else -> runCatching {
            slice.javaClass.getMethod("getList").invoke(slice) as? List<*>
        }.getOrNull()
    }

    /** One `WifiConfiguration` reduced to what the list shows. */
    private fun readConfiguration(configuration: Any?): WifiNetwork? {
        if (configuration == null) return null
        val rawSsid = readString(configuration, "SSID") ?: runCatching {
            configuration.javaClass.getMethod("getSsid").invoke(configuration)?.toString()
        }.getOrNull()
        val ssid = rawSsid?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return null
        val key = readString(configuration, "preSharedKey")?.let(::clean)?.takeIf { it.isNotEmpty() }
        return WifiNetwork(ssid, key, isEnterprise(configuration))
    }

    private fun readString(configuration: Any, name: String): String? = runCatching {
        val field = configuration.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field.get(configuration) as? String
    }
        .onFailure { trace("wifi privileged: field $name unreadable: ${it.message}") }
        .getOrNull()

    /** Enterprise networks hide their credentials elsewhere, so their null key is not "open". */
    private fun isEnterprise(configuration: Any): Boolean = runCatching {
        val field = configuration.javaClass.getDeclaredField("allowedKeyManagement")
            .apply { isAccessible = true }
        val suites = field.get(configuration) as? BitSet ?: return false
        suites.get(KEY_MGMT_EAP)
    }.getOrDefault(false)

    /** Puts the connected network first and tags it, matching the other Wi-Fi managers' layout. */
    private fun mark(networks: List<WifiNetwork>, connected: String?): List<WifiNetwork> =
        networks
            .map { if (it.connected) it else it.copy(connected = connected != null && it.ssid == connected) }
            .sortedWith(compareByDescending<WifiNetwork> { it.connected }.thenBy { it.ssid.lowercase() })

    private fun readStores(asRoot: Boolean): String? {
        for (path in listOf(STORE, LEGACY_STORE)) {
            val out = if (asRoot) rootShell("cat $path") else shell("cat $path")
            // A refusal ("Permission denied", "su: not found") is not the store: only a document
            // that actually starts with '<' is handed to the parser.
            if (out != null && out.trimStart().startsWith("<")) return out
        }
        return null
    }

    /** Runs [cmd] as root through the device's su; null when su is missing or refuses. */
    private fun rootShell(cmd: String): String? = exec("su -c " + quote(cmd)) { it.trimStart().startsWith("<") }

    private fun shell(cmd: String): String? = exec(cmd) { it.isNotBlank() }

    /** Single quotes, with any embedded quote escaped the POSIX way. */
    private fun quote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /** The currently joined SSID, or null when nothing is connected or the shell cannot say. */
    private fun connectedSsid(): String? {
        val out = shell(CMD_STATUS) ?: return null
        return CONNECTED.find(out)?.groupValues?.get(1)
    }

    /** Saved network names as the platform lists them; no keys in this path. */
    private fun listNetworks(): List<WifiNetwork>? {
        val out = shell(CMD_LIST) ?: return null
        return parseList(out).ifEmpty { null }
    }

    /**
     * Parses `cmd wifi list-networks`.
     *
     * The listing is a table, but its column widths follow the longest SSID, so the split is
     * anchored on the security token at the end of the line instead of on fixed columns. One
     * network appears twice when it has both a WPA2 and a WPA3 transition entry, hence the
     * deduplication by SSID.
     */
    private fun parseList(output: String): List<WifiNetwork> {
        val networks = mutableListOf<WifiNetwork>()
        for (raw in output.lineSequence()) {
            val row = LIST_ROW.find(raw.trimEnd()) ?: continue
            var rest = row.groupValues[2].trim()
            // Drop the transition marker first, then peel the trailing security token off. An SSID
            // ending in a word like "psk" would be misread only if it were the last token and the
            // row carried no security type at all, which the shell never emits.
            if (rest.endsWith("^")) rest = rest.dropLast(1).trimEnd()
            val cut = rest.lastIndexOf("  ")
            if (cut <= 0) continue
            val security = rest.substring(cut).trim().lowercase()
            if (!security.contains("psk") && !security.contains("sae") && !security.contains("owe") &&
                !security.contains("wep") && !security.contains("eap") && security != "open"
            ) {
                continue
            }
            val ssid = rest.substring(0, cut).trim()
            if (ssid.isEmpty()) continue
            networks.add(
                WifiNetwork(
                    ssid = ssid.removeSurrounding("\""),
                    key = null,
                    enterprise = security.contains("eap"),
                )
            )
        }
        return networks.distinctBy { it.ssid }.sortedBy { it.ssid.lowercase() }
    }

    private fun exec(cmd: String, accept: (String) -> Boolean): String? {
        if (!isAvailable()) return null
        var process: Process? = null
        return try {
            // `</dev/null` keeps a su that reads its confirmation from stdin from waiting forever.
            process = Shizuku.newProcess(arrayOf("sh", "-c", "{ $cmd ; } 2>/dev/null </dev/null"), null, null)
            val out = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0 && accept(out)) out else null
        } catch (t: Throwable) {
            LOGGER.w("wifi shell failed: ${t.message}")
            null
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
                // A dead binder makes destroy() throw as well; nothing left to clean up then.
            }
        }
    }

    /**
     * Parses the store.
     *
     * The store is not an element-per-field document: every field is written as
     * `<string name="SSID">"105"</string>` or `<int name="SecurityType" value="2" />`, so the field
     * is identified by its `name` attribute, not by the element name. Reading it through the
     * framework parser also keeps a SSID that happens to carry an escaped quote intact, which a
     * regex would break on.
     */
    private fun parse(xml: String): List<WifiNetwork>? {
        val networks = mutableListOf<WifiNetwork>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var ssid: String? = null
            var key: String? = null
            var enterprise = false
            var inNetwork = false
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val element = parser.name
                        val field = parser.getAttributeValue(null, "name")
                        when {
                            element == NETWORK_TAG -> {
                                inNetwork = true
                                ssid = null
                                key = null
                                enterprise = false
                            }
                            !inNetwork -> Unit
                            field == SECURITY_TYPE -> {
                                val type = parser.getAttributeValue(null, "value")?.toIntOrNull()
                                if (type == SECURITY_ENTERPRISE) enterprise = true
                            }
                            field == SSID_TAG -> ssid = clean(parser.nextText())
                            field == KEY_TAG -> key = clean(parser.nextText())
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == NETWORK_TAG) {
                        inNetwork = false
                        val name = ssid
                        if (!name.isNullOrEmpty()) {
                            networks.add(WifiNetwork(name, key?.takeIf { it.isNotEmpty() }, enterprise))
                        }
                    }
                }
                event = parser.next()
            }
        } catch (t: Throwable) {
            LOGGER.w("wifi store parse failed: ${t.message}")
            return null
        }
        return networks.distinctBy { it.ssid }.sortedBy { it.ssid.lowercase() }
    }

    /** The store quotes names and escapes them; both are storage detail, not the name. */
    private fun clean(raw: String): String =
        raw.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
}
