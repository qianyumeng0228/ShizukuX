package rikka.shizuku.server.util

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPOutputStream

/**
 * Guards the rework of [ArchiveVetting.isSafe]: the old exact 1:1 line-count precondition
 * could refuse a benign archive when toybox and GNU tar disagree on how many lines they print.
 * The replacement matches verbose lines to names by suffix instead, but must stay fail-closed:
 * an entry that escapes the target, writes through a symlink, or cannot be classified at all
 * is still refused.
 */
class ArchiveVettingTest {

    @Test
    fun `archive with an entry written through a symlink is refused`() {
        val names = listOf("./", "./files", "./files/a.txt", "./lib", "./lib/x.so")
        val verbose = listOf(
            "drwxrwxrwx 0 0 0 0 2026-01-01 00:00 ./",
            "drwxrwxrwx 0 0 0 0 2026-01-01 00:00 ./files",
            "-rw-rw-rw- 1 1 12 2026-01-01 00:00 ./files/a.txt",
            "lrwxrwxrwx 0 0 0 0 2026-01-01 00:00 ./lib -> /data/app/x/lib",
            "-rw-rw-rw- 1 1 12 2026-01-01 00:00 ./lib/x.so",
        )
        // ./lib/x.so is listed under a symlink name here on purpose: a backup that contains
        // the real file under the same path is still a write through the link and must fail.
        assertFalse(ArchiveVetting.isSafe(names, verbose))
    }

    @Test
    fun `archive with a symlink body but no entry through it is accepted`() {
        val names = listOf("./", "./a.txt", "./lib")
        val verbose = listOf(
            "drwxrwxrwx 0 0 0 0 2026-01-01 00:00 ./",
            "-rw-rw-rw- 1 1 12 2026-01-01 00:00 ./a.txt",
            "lrwxrwxrwx 0 0 0 0 2026-01-01 00:00 ./lib -> /data/app/x/lib",
        )
        assertTrue(ArchiveVetting.isSafe(names, verbose))
    }

    // ── toybox prints the link target in the name-only listing as well ──
    // Real-device finding (2026-11-06 run): `tar -tf` on toybox 0.8.12-android emits
    // "evil -> /data/local/tmp/x" for a symlink, so the bare name list already carries the
    // arrow. Unstripped, the link is recorded under that whole string, no entry starts with
    // it, and the archive vetted as SAFE: the symlink landed on the device.

    @Test
    fun `toybox name list carrying a link target still refuses a write through the link`() {
        val names = listOf("evil -> /data/local/tmp/pwned_by_shizukux", "evil/x.txt")
        val verbose = listOf(
            "lrwxrwxrwx 0/0 0 1970-01-01 08:00 evil -> /data/local/tmp/pwned_by_shizukux",
            "-rw-r--r-- 0/0 6 1970-01-01 08:00 evil/x.txt",
        )
        assertFalse(ArchiveVetting.isSafe(names, verbose))
    }

    @Test
    fun `toybox name list carrying a link target still accepts a bare symlink`() {
        val names = listOf("lib -> /data/app/x/lib", "a.txt")
        val verbose = listOf(
            "lrwxrwxrwx 0/0 0 1970-01-01 08:00 lib -> /data/app/x/lib",
            "-rw-r--r-- 0/0 6 1970-01-01 08:00 a.txt",
        )
        assertTrue(ArchiveVetting.isSafe(names, verbose))
    }

    @Test
    fun `mismatched listings with a duplicated line are tolerated`() {        // toybox-style duplicate for the same entry: same classification, so it is benign.
        val names = listOf("./a.txt")
        val verbose = listOf(
            "-rw-rw-rw- 1 1 12 2026-01-01 00:00 ./a.txt",
            "-rw-rw-rw- 1 1 12 2026-01-01 00:00 ./a.txt",
        )
        assertTrue(ArchiveVetting.isSafe(names, verbose))
    }

    @Test
    fun `mismatched listings still refuse an escaping entry`() {
        val names = listOf("../escape", "./a.txt")
        val verbose = listOf(
            "lrwxrwxrwx 0 0 0 0 2026-01-01 00:00 ../escape -> /data/data/com.victim",
            "-rw-rw-rw- 1 1 12 2026-01-01 00:00 ./a.txt",
        )
        // normalize() rejects the ".." name outright.
        assertFalse(ArchiveVetting.isSafe(names, verbose))
    }

    @Test
    fun `mismatched listings refuse a hard link`() {
        val names = listOf("./a", "./b")
        val verbose = listOf(
            "-rw-rw-rw- 1 1 12 2026-01-01 00:00 ./a",
            "-rw-rw-rw- 2 1 12 2026-01-01 00:00 ./b link to ./a",
        )
        assertFalse(ArchiveVetting.isSafe(names, verbose))
    }

    @Test
    fun `unclassified entries fail closed`() {
        val names = listOf("./a.txt", "./not-listed")
        val verbose = listOf("-rw-rw-rw- 1 1 12 2026-01-01 00:00 ./a.txt")
        // ./not-listed never appears in the verbose listing, so it cannot be vetted.
        assertFalse(ArchiveVetting.isSafe(names, verbose))
    }

    @Test
    fun `device nodes are refused`() {
        val names = listOf("./dev")
        val verbose = listOf("crw-rw-rw- 0 0 0 0 2026-01-01 00:00 ./dev")
        assertFalse(ArchiveVetting.isSafe(names, verbose))
    }

    // ── gz path: app-data backups are tar.gz, so vetting must read them, not skip them ──

    @Test
    fun `gzip archive with an escaping entry is refused`() {
        val archive = File.createTempFile("ssu-vet-gz-", ".tar.gz")
        try {
            val tar = craftTar(type = '2', name = "../escape", linkTarget = "/data/data/com.victim") + ByteArray(1024)
            archive.writeBytes(gzipOf(tar))
            assertThrows(SecurityException::class.java) { ArchiveVetting.vetArchiveOrThrow(archive) }
        } finally {
            archive.delete()
        }
    }

    @Test
    fun `gzip archive of ordinary entries is accepted`() {
        val archive = File.createTempFile("ssu-vet-gz-", ".tar.gz")
        try {
            val tar = craftTar(type = '5', name = "./") + craftTar(type = '0', name = "./a.txt") + ByteArray(1024)
            archive.writeBytes(gzipOf(tar))
            assertDoesNotThrow { ArchiveVetting.vetArchiveOrThrow(archive) }
        } finally {
            archive.delete()
        }
    }

    @Test
    fun `archive that cannot be listed is refused instead of extracted`() {
        // fail-closed: a gate that cannot read the archive must not let the extractor try,
        // or a check that fails while extraction succeeds is a silent bypass.
        val missing = File.createTempFile("ssu-vet-missing-", ".tar.gz")
        missing.delete()
        assertThrows(IOException::class.java) { ArchiveVetting.vetArchiveOrThrow(missing) }
    }

    private fun gzipOf(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /**
     * One POSIX ustar header block (no data block: every entry here has size 0).
     * type '0' = file, '2' = symlink, '5' = dir. Callers append the two zero blocks that
     * close an archive. Hand-built so the test does not depend on a tar binary to create it.
     */
    private fun craftTar(type: Char, name: String, linkTarget: String? = null): ByteArray {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray()
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.size.coerceAtMost(100))
        header[100] = '0'.code.toByte(); header[101] = '7'.code.toByte(); header[102] = '7'.code.toByte()
        header[103] = '7'.code.toByte(); header[104] = '7'.code.toByte(); header[105] = '7'.code.toByte()
        header[106] = '7'.code.toByte(); header[107] = 0 // mode 0000777
        header[156] = type.code.toByte()
        if (linkTarget != null) {
            val targetBytes = linkTarget.toByteArray()
            System.arraycopy(targetBytes, 0, header, 157, targetBytes.size.coerceAtMost(100))
        }
        System.arraycopy("ustar".toByteArray(), 0, header, 257, 5)
        header[262] = 0
        header[263] = '0'.code.toByte(); header[264] = '0'.code.toByte() // version "00"
        var sum = 0
        for (i in 0 until 512) {
            sum += if (i in 148..155) ' '.code.toByte().toInt() and 0xFF else header[i].toInt() and 0xFF
        }
        val checksum = String.format("%06o", sum).toByteArray()
        System.arraycopy(checksum, 0, header, 148, checksum.size)
        header[154] = 0
        header[155] = ' '.code.toByte()
        return header
    }
}
