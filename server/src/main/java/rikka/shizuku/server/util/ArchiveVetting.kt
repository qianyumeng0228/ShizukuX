package rikka.shizuku.server.util

import java.io.File
import java.io.IOException

/**
 * Decides whether a tar archive is safe to extract into a directory (ShizukuPlus f850e52b).
 * App data commonly holds symlinks (the `lib` link into /data/app, links apps make
 * themselves), so those are allowed; what isn't is an entry written through one, which is
 * how a crafted archive escapes the target. Hard links, device nodes and fifos, absolute
 * names and `..` are refused outright.
 */
object ArchiveVetting {

    /** Outcome of inspecting a staged archive. */
    enum class Verdict {
        /** Listed and every entry is safe to extract. */
        SAFE,

        /** Listed, but at least one entry escapes the target or writes through a link. */
        UNSAFE,

        /** tar could not list it at all (corrupt, truncated, not an archive). */
        UNREADABLE,
    }

    /**
     * @param names entry names from `tar -tf`.
     * @param verbose the matching `tar -tvf` lines, in the same order; the first character is
     *     the entry type, and links print "->" (toybox) or "link to" (GNU) after the name.
     */
    fun isSafe(names: List<String>, verbose: List<String>): Boolean {
        val normalized = names.map { normalize(stripLinkTarget(it)) ?: return false }

        // Entry type comes from the verbose listing. toybox and GNU both emit one line per
        // entry, so the two listings line up 1:1 for ordinary archives and the check is exact.
        // When they do not (an extra/merged line, a listing that omits one entry), matching by
        // index silently checks the wrong line against the wrong name, which is how a benign
        // archive gets killed. Fall back to matching each verbose line to a name by suffix and
        // fail closed on any line that cannot be attributed (audit P3-8).
        val modeByName = HashMap<String, Char>()
        val links = mutableSetOf<String>()
        if (names.size == verbose.size) {
            verbose.forEachIndexed { i, line ->
                val mode = line.firstOrNull() ?: return false
                when (mode) {
                    'd' -> Unit
                    // A regular-file mode with a link target is a hard link.
                    '-' -> if (hasLinkMarker(line)) return false
                    'l' -> if (normalized[i].isEmpty()) return false else links += normalized[i]
                    else -> return false
                }
                modeByName[normalized[i]] = mode
            }
        } else {
            for (line in verbose) {
                val mode = line.firstOrNull() ?: return false
                if (mode != 'd' && mode != '-' && mode != 'l') return false
                if (mode == '-' && hasLinkMarker(line)) return false
                val name = matchName(line, normalized) ?: return false
                // A repeated line for the same entry is tolerated as long as it agrees
                // with the earlier classification; a conflicting one is refused.
                if (modeByName.containsKey(name)) {
                    if (modeByName[name] != mode) return false
                    continue
                }
                modeByName[name] = mode
                if (mode == 'l') links += name
            }
            // Every entry must have been classified; anything unseen cannot be vetted.
            if (normalized.any { it !in modeByName }) return false
        }

        return normalized.none { name ->
            links.any { link -> name.startsWith("$link/") || (name == link && modeByName[name] != 'l') }
        }
    }

    /**
     * Lists the archive with tar and classifies it. The two failure modes are kept apart:
     * an unreadable archive is not a security finding, an unsafe one is.
     *
     * The listings are taken twice when needed: without a compression flag first (GNU tar
     * sniffs the magic and handles .tar.gz), then with `-z` for tar builds that do not
     * auto-detect (toybox versions differ). A gz archive that only the second form can read
     * must still be vetted — treating it as unreadable would wave it through unchecked.
     */
    fun vetArchive(archive: File): Verdict {
        val listings = listArchive(archive.path) ?: return Verdict.UNREADABLE
        val (names, verbose) = listings
        return if (isSafe(names, verbose)) Verdict.SAFE else Verdict.UNSAFE
    }

    /** (names, verbose) from the first tar invocation pair that can list the archive. */
    private fun listArchive(path: String): Pair<List<String>, List<String>>? {
        // "f" = plain, "zf" = gzip-flagged: the second form covers tar builds that do not
        // sniff the compression format themselves.
        for (suffix in listOf("f", "zf")) {
            val names = runTar("-t$suffix", path) ?: continue
            val verbose = runTar("-tv$suffix", path) ?: continue
            return names to verbose
        }
        return null
    }

    /**
     * Security gate for a staged archive that is about to be extracted. Refusals are loud:
     * they travel back to the caller as SecurityException. An archive tar cannot list is
     * refused as well, but as an IOException: the gate must never pass an archive it could
     * not read. A check that fails while the extractor still succeeds is a silent bypass, so
     * the gate is closed wherever its capability and the extractor's overlap. Callers fold
     * every non-security exception into a plain `false`, which is the outcome an unreadable
     * archive produced before 8ab9bec6 too.
     */
    fun vetArchiveOrThrow(archive: File) {
        when (vetArchive(archive)) {
            Verdict.SAFE -> Unit
            Verdict.UNREADABLE -> throw IOException(
                "Archive could not be listed for the security check"
            )
            Verdict.UNSAFE -> throw SecurityException(
                "Archive rejected by security check: an entry writes through a link or outside the target folder"
            )
        }
    }

    private fun hasLinkMarker(line: String): Boolean = " -> " in line || " link to " in line

    /**
     * Entry name with any link target trimmed off. GNU prints the bare name for `tar -tf`, but
     * toybox prints "name -> target" for a symlink even without -v (toybox 0.8.12-android on the
     * test device does), so the name-only listing can already carry the arrow. Left in place,
     * normalize() accepts that tail as part of the name, the link is recorded under a path no
     * entry ever matches, and an archive that writes through it vets as SAFE (real-device finding
     * 2026-11-06). Trimming keeps `names` and `verbose` describing the same entry.
     */
    private fun stripLinkTarget(name: String): String {
        val arrow = listOf(" -> ", " link to ").map { name.indexOf(it) }.filter { it >= 0 }.minOrNull()
        return if (arrow != null) name.substring(0, arrow) else name
    }

    /**
     * Longest entry name that the verbose line ends with (before a link marker, if any).
     * Names may contain spaces, so this matches suffixes instead of splitting on whitespace.
     */
    private fun matchName(line: String, normalized: List<String>): String? {
        val arrow = listOf(" -> ", " link to ").map { line.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1
        val head = (if (arrow >= 0) line.substring(0, arrow) else line).trimEnd('/')
        return normalized.filter { it.isNotEmpty() && head.endsWith(it) }.maxByOrNull { it.length }
    }

    private fun runTar(vararg args: String): List<String>? = try {
        val proc = ProcessBuilder("tar", *args).start()
        val lines = proc.inputStream.bufferedReader().readLines().filter { it.isNotBlank() }
        if (proc.waitFor() == 0) lines else null
    } catch (_: Exception) {
        null
    }

    /** "./a//b/" -> "a/b"; null when absolute or climbing out with "..". */
    private fun normalize(name: String): String? {
        if (name.startsWith("/")) return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }
}
