package rikka.shizuku.server

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.util.Random

/**
 * Regression guards for 8ab9bec6 / df25d9bf on the archive-vetting gate.
 *
 * - 8ab9bec6 threw SecurityException inside a try whose `catch (e: Exception)`
 *   swallowed it, so the binder caller got a plain false and the commit's promise
 *   ("travels across the binder") never held. df25d9bf added a rethrow-first
 *   catch chain and made the gate unit-testable.
 * - df25d9bf then split the gate's two failure modes: an unreadable archive stays
 *   a silent false (pre-8ab9bec6 behaviour), while an archive that lists but
 *   contains escaping/link entries must throw SecurityException.
 * - 2026-11-06: the unreadable branch is no longer waved through. It is refused as
 *   IOException, which the caller folds into the same plain false, so the extractor
 *   never runs on an archive the gate could not read. A gate that cannot read what
 *   the extractor can is a fail-open bypass, not a generic failure.
 *
 * Note on scope: this exercises [StorageProxyImpl.vetArchiveOrThrow] directly.
 * The full restoreTarDirectory path is not unit-testable on a desktop host
 * (allowedPath is gated by Android storage prefixes), so the rethrow-first catch
 * chain in restoreTarDirectory is covered by code review plus these tests.
 */
class StorageProxyVetRefusalTest {

    @Test
    fun `unreadable archive is refused as IOException - not a security refusal`() {
        val staged = File.createTempFile("ssu-vet-", ".archive")
        try {
            // Garbage bytes are not a tar: tar -tf rejects them. That is a generic restore
            // failure, not a security finding, so it must not surface as SecurityException.
            // It is still refused (IOException) so nothing is extracted from an archive the
            // gate could not read.
            val bytes = ByteArray(600)
            Random().nextBytes(bytes)
            staged.writeBytes(bytes)

            val impl = StorageProxyImpl({ true }, { true })
            assertThrows(IOException::class.java) { impl.vetArchiveOrThrow(staged) }
        } finally {
            staged.delete()
        }
    }

    @Test
    fun `archive with escaping entry throws SecurityException`() {
        val staged = File.createTempFile("ssu-vet-", ".archive")
        try {
            staged.writeBytes(craftSymlinkTar(name = "../escape", target = "/data/data/com.victim"))

            val impl = StorageProxyImpl({ true }, { true })
            assertThrows(SecurityException::class.java) {
                impl.vetArchiveOrThrow(staged)
            }
        } finally {
            staged.delete()
        }
    }

    /** Minimal POSIX ustar archive holding one symlink entry, platform-independent. */
    private fun craftSymlinkTar(name: String, target: String): ByteArray {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray()
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.size.coerceAtMost(100))
        header[100] = '0'.code.toByte(); header[101] = '7'.code.toByte(); header[102] = '7'.code.toByte()
        header[103] = '7'.code.toByte(); header[104] = '7'.code.toByte(); header[105] = '7'.code.toByte()
        header[106] = '7'.code.toByte(); header[107] = 0 // mode 0000777
        header[156] = '2'.code.toByte() // typeflag: symlink
        val targetBytes = target.toByteArray()
        System.arraycopy(targetBytes, 0, header, 157, targetBytes.size.coerceAtMost(100))
        System.arraycopy("ustar".toByteArray(), 0, header, 257, 5)
        header[262] = 0
        header[263] = '0'.code.toByte(); header[264] = '0'.code.toByte() // version "00"
        // checksum: sum over the header with the checksum field as 8 spaces, then store octal
        var sum = 0
        for (i in 0 until 512) {
            sum += if (i in 148..155) ' '.code.toByte().toInt() and 0xFF else header[i].toInt() and 0xFF
        }
        val checksum = String.format("%06o", sum).toByteArray()
        System.arraycopy(checksum, 0, header, 148, checksum.size)
        header[154] = 0
        header[155] = ' '.code.toByte()
        // two trailing zero blocks to close the archive
        return header + ByteArray(1024)
    }
}
