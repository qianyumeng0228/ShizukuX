package rikka.shizuku.server

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Random

/**
 * Regression guard for 8ab9bec6: the archive-vetting refusal used to be thrown
 * inside a try whose `catch (e: Exception)` swallowed it, so the binder caller
 * got a plain `false` and the commit message's promise ("travels across the
 * binder") never held. The gate must throw for real.
 *
 * Note on scope: this exercises [StorageProxyImpl.vetArchiveOrThrow] directly.
 * The full restoreTarDirectory path is not unit-testable on a desktop host
 * (allowedPath is gated by Android storage prefixes), so the rethrow-first
 * catch chain in restoreTarDirectory is covered by code review plus this test.
 */
class StorageProxyVetRefusalTest {

    @Test
    fun `vetArchiveOrThrow throws SecurityException for an unsafe archive`() {
        val staged = File.createTempFile("ssu-vet-", ".archive")
        try {
            // Garbage bytes are not a tar: tar -tf rejects them, runTar returns
            // null, isArchiveSafe is false, and the gate must refuse loudly.
            val bytes = ByteArray(600)
            Random().nextBytes(bytes)
            staged.writeBytes(bytes)

            val impl = StorageProxyImpl({ true }, { true })
            assertThrows(SecurityException::class.java) {
                impl.vetArchiveOrThrow(staged)
            }
        } finally {
            staged.delete()
        }
    }
}
