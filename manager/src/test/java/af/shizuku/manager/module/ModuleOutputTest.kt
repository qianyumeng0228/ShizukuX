package af.shizuku.manager.module

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1-4: output handling — tail truncation (constraint #7) and combined-output
 * assembly for the action/service result view.
 */
class ModuleOutputTest {

    @Test
    fun `truncateTail keeps last maxChars characters`() {
        val text = "0123456789"
        assertEquals("6789", ModuleRuntimeUtils.truncateTail(text, 4))
        assertEquals(text, ModuleRuntimeUtils.truncateTail(text, 10))
        assertEquals(text, ModuleRuntimeUtils.truncateTail(text, 99))
    }

    @Test
    fun `truncateTail handles empty and unicode input`() {
        assertEquals("", ModuleRuntimeUtils.truncateTail("", 5))
        val unicode = "你好世界abc"
        assertEquals("世界abc", ModuleRuntimeUtils.truncateTail(unicode, 5))
    }

    @Test
    fun `combinedOutput joins stdout and stderr with blank fallback`() {
        assertEquals("out", ModuleActionResult(0, "out", "").combinedOutput)
        assertEquals("out\n\nerr", ModuleActionResult(0, "out", "err").combinedOutput)
        assertEquals("No output.", ModuleActionResult(0, "", "").combinedOutput)
        assertEquals("err", ModuleActionResult(1, "", "err").combinedOutput)
    }
}
