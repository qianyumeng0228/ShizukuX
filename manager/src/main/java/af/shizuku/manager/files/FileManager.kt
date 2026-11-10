package af.shizuku.manager.files

import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuWait
import rikka.shizuku.Shizuku
import java.util.Locale

/** One entry of a directory, as the platform reports it. */
data class FileEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val link: Boolean,
    val size: Long,
    val mtime: Long,
    val mode: String,
    val owner: String,
) {
    /** `rw-`-style permission text, the form the platform prints in the mode's octal value. */
    val permissions: String
        get() {
            val bits = mode.toIntOrNull(8) ?: return mode
            val rwx = StringBuilder(9)
            listOf(6, 3, 0).forEach { shift ->
                rwx.append(if (bits and (4 shl shift) != 0) 'r' else '-')
                rwx.append(if (bits and (2 shl shift) != 0) 'w' else '-')
                rwx.append(if (bits and (1 shl shift) != 0) 'x' else '-')
            }
            return rwx.toString()
        }
}

/**
 * Reads the filesystem through the Shizuku user, without going through the app's own sandbox.
 *
 * Read-only on purpose: this screen exists to see what is on the device (the parts an ordinary app
 * cannot reach at all), and a mis-tap in a file manager that can write root-only paths is not a
 * bug a review can catch. Nothing here creates, moves or deletes.
 */
object FileManager {

    /** Paths a user actually goes to, in the order the shortcut chips show them. */
    val SHORTCUTS = listOf(
        "/",
        "/sdcard",
        "/data/adb",
        "/data/local/tmp",
        "/data/system",
        "/data/misc",
    )

    /** Whether the shell side is reachable at all. */
    fun isAvailable(): Boolean = ShizukuWait.ping()

    /** Whether this process can borrow root; probed once, because the probe may show a prompt. */
    private var rootChecked = false
    private var rootAvailable = false

    private fun hasRoot(): Boolean {
        if (rootChecked) return rootAvailable
        rootChecked = true
        // `su -c id -u` is the probe every manager accepts (Magisk, KernelSU, SukiSU). The probe
        // goes through the plain shell on purpose: it must not recurse into [shell], which needs
        // this answer.
        rootAvailable = raw("su -c id -u")?.trim() == "0"
        return rootAvailable
    }

    /** The entries of [path], directories first, or null when the directory cannot be read. */
    fun list(path: String): List<FileEntry>? {
        val quoted = quote(path)
        // `*` and `.*` together list hidden entries too; the existence test drops the literal
        // patterns a shell leaves behind when a directory is empty.
        // One `stat` for the whole directory: the per-file loop forked once per entry and a
        // 900-entry folder paid for 900 shell processes before it could draw anything. An empty
        // directory leaves the two globs unexpanded, so `stat` fails and its error is dropped on
        // purpose; the `|| true` keeps that from looking like a read failure.
        val cmd = "cd $quoted 2>/dev/null || exit 3; " +
            "stat -c '%n|%F|%s|%Y|%a|%U' -- * .[!.]* 2>/dev/null || true"
        // `cd` failing is a permission problem, not an empty folder: /data and /data/adb are
        // closed to the shell user, so the same listing is retried as root before answering null.
        // Without this the browser reported "0 entries / empty folder" for paths that are simply
        // out of the shell's reach.
        val out = listing(cmd) ?: return null
        val entries = out.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parse(it, path) }
            .toList()
        return entries.sortedWith(
            compareByDescending<FileEntry> { it.directory }.thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    /**
     * The stdout of a listing command, retried as root when the shell user came back empty.
     *
     * An empty answer has two causes that a plain `cd` cannot tell apart: the directory really is
     * empty, or the shell user was allowed to enter it but not to read it. `/data` is the second
     * case on this device - mode 0771 lets uid 2000 `cd` there, so `cd` exits 0, the globs stay
     * unexpanded, `stat` prints nothing and the browser showed "0 entries / empty folder" for a
     * folder full of entries. Only when nothing came back is the root shell asked, so a truly
     * empty folder costs one extra probe instead of being retried on every read.
     */
    private fun listing(cmd: String): String? {
        val direct = shell(cmd)
        if (!direct.isNullOrBlank()) return direct
        if (!hasRoot()) return direct
        val rooted = shellRoot(cmd)
        return if (rooted.isNullOrBlank()) direct else rooted
    }

    /** The first [limit] bytes of [path] as text, or null when it cannot be read at all. */
    fun readText(path: String, size: Long, limit: Int = 12_000): String? {
        // A zero-byte file answers itself: going out to the shell for it is the one read that
        // has nothing to bring back, and the caller can show an empty preview immediately.
        if (size == 0L) return ""
        val cmd = "head -c $limit ${quote(path)} 2>/dev/null"
        return shell(cmd) ?: (if (hasRoot()) shellRoot(cmd) else null)
    }

    /**
     * Whether [text] is not worth showing as text.
     *
     * A NUL byte is the platform's own marker for binary content; on top of that this counts the
     * C0 controls a text file never carries, so an ELF or a JPEG full of high bytes is not
     * rendered as one long line of garbage.
     */
    fun looksBinary(text: String): Boolean {
        if (text.indexOf('\u0000') >= 0) return true
        val sample = text.take(2048)
        if (sample.isEmpty()) return false
        val control = sample.count { it.code < 0x09 || (it.code in 0x0E..0x1F) || it.code == 0x7F }
        return control * 10 > sample.length
    }

    private fun parse(line: String, parent: String): FileEntry? {
        val parts = line.split('|')
        if (parts.size < 6) return null
        val name = parts[0]
        if (name.isEmpty() || name == "." || name == "..") return null
        val type = parts[1]
        return FileEntry(
            name = name,
            path = if (parent == "/") "/$name" else "$parent/$name",
            directory = type == "directory",
            link = type == "symbolic link",
            size = parts[2].toLongOrNull() ?: 0L,
            mtime = parts[3].toLongOrNull() ?: 0L,
            mode = parts[4],
            owner = parts.drop(5).joinToString("|")
        )
    }

    /** A path as a single shell word, quotes and spaces included. */
    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /** [cmd] as one double-quoted argument for `su -c`, which the shell re-parses once more. */
    private fun doubleQuote(cmd: String): String =
        "\"" + cmd.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$")
            .replace("`", "\\`") + "\""

    /** Runs [cmd] as the Shizuku user. */
    private fun shell(cmd: String): String? = raw(cmd)

    /** Runs [cmd] as root, through the manager's `su`. */
    private fun shellRoot(cmd: String): String? = raw("su -c " + doubleQuote(cmd))

    /** Runs one shell command and answers its stdout, or null when it did not succeed. */
    private fun raw(cmd: String): String? {
        if (!isAvailable()) return null
        var process: Process? = null
        return try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", "{ $cmd ; echo ; } 2>/dev/null"), null, null)
            val out = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) out else null
        } catch (t: Throwable) {
            LOGGER.w("files shell failed: ${t.message}")
            null
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
                // A dead binder makes destroy() throw as well; nothing left to clean up then.
            }
        }
    }
}

/** A byte count the way a file manager prints it. */
fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) String.format(Locale.ROOT, "%.0f %s", value, units[unit])
    else String.format(Locale.ROOT, "%.1f %s", value, units[unit])
}
