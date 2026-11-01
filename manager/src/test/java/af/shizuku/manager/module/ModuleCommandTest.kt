package af.shizuku.manager.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-4: command review policy — SAFE whitelist, dangerous binaries, FULL bypass,
 * CUSTOM constraints (constraint #8).
 */
class ModuleCommandTest {

    @Test
    fun `safe mode allows whitelisted commands`() {
        assertTrue(ModuleCommandPolicy.isSafeToAutoRun("echo hello", ModuleSettings.AccessMode.SAFE))
        assertTrue(ModuleCommandPolicy.isSafeToAutoRun("id", ModuleSettings.AccessMode.SAFE))
        assertTrue(ModuleCommandPolicy.isSafeToAutoRun("cat /proc/version", ModuleSettings.AccessMode.SAFE))
    }

    @Test
    fun `safe mode blocks unknown and dangerous binaries`() {
        assertFalse(ModuleCommandPolicy.isSafeToAutoRun("curl http://x", ModuleSettings.AccessMode.SAFE))
        assertFalse(ModuleCommandPolicy.isSafeToAutoRun("rm -rf /", ModuleSettings.AccessMode.SAFE))
        assertFalse(ModuleCommandPolicy.isSafeToAutoRun("sh service.sh", ModuleSettings.AccessMode.SAFE))
        assertFalse(ModuleCommandPolicy.isSafeToAutoRun("", ModuleSettings.AccessMode.SAFE))
    }

    @Test
    fun `full mode allows everything but dangerous binaries still flagged`() {
        assertTrue(ModuleCommandPolicy.isSafeToAutoRun("curl http://x", ModuleSettings.AccessMode.FULL))
        assertFalse(ModuleCommandPolicy.isSafeToAutoRun("rm -rf /", ModuleSettings.AccessMode.FULL))
        assertTrue(ModuleCommandPolicy.isSafeToAutoRun("echo ok", ModuleSettings.AccessMode.FULL))
    }

    @Test
    fun `custom mode behaves like whitelist for unknown binaries`() {
        assertFalse(ModuleCommandPolicy.isSafeToAutoRun("curl http://x", ModuleSettings.AccessMode.CUSTOM))
        assertTrue(ModuleCommandPolicy.isSafeToAutoRun("echo ok", ModuleSettings.AccessMode.CUSTOM))
    }

    @Test
    fun `whyNeedsReview explains the reason or returns null`() {
        assertNull(ModuleCommandPolicy.whyNeedsReview("echo ok", ModuleSettings.AccessMode.SAFE))
        assertNotNull(ModuleCommandPolicy.whyNeedsReview("curl http://x", ModuleSettings.AccessMode.SAFE))
        assertTrue(
            ModuleCommandPolicy.whyNeedsReview("rm -rf /", ModuleSettings.AccessMode.FULL)!!
                .contains("dangerous")
        )
        assertNotNull(ModuleCommandPolicy.whyNeedsReview("", ModuleSettings.AccessMode.SAFE))
    }
}
