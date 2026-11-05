package rikka.shizuku.server.util

import java.io.File

/**
 * Owner-only staging area for archives that are about to be vetted and extracted.
 * `/data/local/tmp` is readable by other apps by default, so a staged app-data archive
 * must not sit there world-readable during the vetting window (audit P3-4). The tar
 * child process runs as the same uid, so owner-only permissions are enough.
 */
object PrivateStaging {

    private const val DIR = "/data/local/tmp"

    /**
     * Creates an empty staging file whose permissions are already owner-only.
     *
     * The file must exist before [File.setReadable]/[File.setWritable] take effect: on Android
     * they map to chmod and silently return false for a missing path, which would leave the
     * later outputStream() creating it with the process umask.
     */
    fun create(prefix: String): File {
        val dir = File(DIR).apply { mkdirs() }
        val file = File(dir, "$prefix-${System.nanoTime()}.archive")
        file.createNewFile()
        // Strip group/other first, then grant the owner: granting owner-first would leave a
        // window in which the freshly created file is still group/other readable.
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        return file
    }
}
