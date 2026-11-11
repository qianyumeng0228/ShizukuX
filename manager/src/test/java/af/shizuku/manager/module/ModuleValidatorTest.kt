package af.shizuku.manager.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The 8 safety constraints — path traversal, ZIP bombs, entry/prop/script caps.
 * Pure JVM tests against [ModuleValidator].
 */
class ModuleValidatorTest {

    // -- Constraint #2: path traversal ----------------------------------------

    @Test
    fun `cleanRelativePath rejects parent traversal`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleValidator.cleanRelativePath("../evil.sh")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModuleValidator.cleanRelativePath("a/../../evil.sh")
        }
    }

    @Test
    fun `cleanRelativePath rejects absolute and drive paths`() {
        // Drive-rooted and traversal paths are rejected outright.
        assertThrows(IllegalArgumentException::class.java) {
            ModuleValidator.cleanRelativePath("C:\\Windows\\evil.exe")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModuleValidator.cleanRelativePath("..\\..\\etc\\passwd")
        }
        // A leading slash is normalized away (defensive), never kept as absolute.
        assertEquals("system/bin/evil", ModuleValidator.cleanRelativePath("/system/bin/evil"))
    }

    @Test
    fun `cleanRelativePath normalizes safe paths and backslashes`() {
        assertEquals("a/b/c.sh", ModuleValidator.cleanRelativePath("a\\b\\c.sh"))
        assertEquals("x/y", ModuleValidator.cleanRelativePath("/x/y/"))
        assertEquals("plain.sh", ModuleValidator.cleanRelativePath("plain.sh"))
    }

    // -- Constraint #3: ZIP bomb ----------------------------------------------

    @Test
    fun `copyStreamBounded enforces byte cap`() {
        val payload = ByteArray(4096) { 1 }
        val capped = assertThrows(IllegalArgumentException::class.java) {
            ModuleValidator.copyStreamBounded(
                ByteArrayInputStream(payload),
                ByteArrayOutputStream(),
                maxBytes = 1024
            )
        }
        assertTrue(capped.message!!.contains("maximum allowed size"))
    }

    @Test
    fun `copyStreamBounded copies within budget`() {
        val payload = ByteArray(1024) { 7 }
        val out = ByteArrayOutputStream()
        val copied = ModuleValidator.copyStreamBounded(
            ByteArrayInputStream(payload),
            out,
            maxBytes = 2048
        )
        assertEquals(1024L, copied)
        assertEquals(1024, out.size())
    }

    // -- Constraints #1/#4/#5: caps -------------------------------------------

    @Test
    fun `entry count and prop size caps enforced`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleValidator.checkEntryCount(ModuleValidator.MAX_ENTRY_COUNT + 1)
        }
        ModuleValidator.checkEntryCount(ModuleValidator.MAX_ENTRY_COUNT) // boundary ok
        assertThrows(IllegalArgumentException::class.java) {
            ModuleValidator.checkPropSize(ModuleValidator.MAX_PROP_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModuleValidator.checkScriptSize(ModuleValidator.MAX_SCRIPT_BYTES + 1L)
        }
    }

    // -- Constraint #2b: directory containment ----------------------------------

    @Test
    fun `ensureInside rejects out of root siblings`() {
        val root = File.createTempFile("root", "").apply { delete(); mkdirs() }
        val outside = File(root.parentFile, "sibling.sh")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                ModuleValidator.ensureInside(root, outside)
            }
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    @Test
    fun `isInside returns false for traversal probes without throwing`() {
        val root = File.createTempFile("root", "").apply { delete(); mkdirs() }
        try {
            val probe = File(root, "..")
            assertEquals(false, ModuleValidator.isInside(root, probe))
        } finally {
            root.deleteRecursively()
        }
    }
}
