package af.shizuku.manager.wifi

import android.util.Xml
import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuWait
import org.xmlpull.v1.XmlPullParser
import rikka.shizuku.Shizuku
import java.io.StringReader

/** One saved network: [key] is null for an open network or an unsupported (enterprise) one. */
data class WifiNetwork(
    val ssid: String,
    val key: String?,
    val enterprise: Boolean = false,
) {
    val open: Boolean get() = key == null && !enterprise
}

/**
 * Reads the saved Wi-Fi passwords out of the platform's own config store.
 *
 * There is no API for this: the passwords are deliberately kept away from apps, and only a root
 * shell can read the store. The file is the same one Settings' own "share password" reads.
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

    private const val SSID_TAG = "SSID"
    private const val KEY_TAG = "PreSharedKey"
    private const val NETWORK_TAG = "WifiConfiguration"
    private const val SECURITY_TYPE = "SecurityType"
    private const val SECURITY_ENTERPRISE = 3

    fun isAvailable(): Boolean = ShizukuWait.ping()

    /** Every saved network with a readable name, or null when the store cannot be read. */
    fun load(): List<WifiNetwork>? {
        // The store is 0600 system:system, so SELinux denies the Shizuku shell (uid 2000) even
        // though the file is not encrypted: the plain shell path can never work on this platform
        // generation. A root shell is therefore the primary reader, and the plain shell is only a
        // fallback for builds whose store is group-readable.
        val xml = readStores(asRoot = true) ?: readStores(asRoot = false) ?: return null
        return parse(xml)
    }

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
