package rikka.shizuku.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `cmd overlay list` fallback parser — the pure-JVM half of the
 * getAllOverlays() dual-path (reflection first, command line as fallback).
 */
class OverlayManagerTest {

    @Test
    fun `parses enabled and disabled entries from cmd output`() {
        val output = """
            com.android.settings
              [x] com.android.theme.color.green (enabled)
              [ ] com.android.theme.color.blue (disabled)
            com.android.systemui
              [x] com.android.theme.font (enabled)
        """.trimIndent()
        val parsed = OverlayManagerPlusImpl.parseOverlayListText(output)
        assertEquals(3, parsed.size)
        assertTrue(parsed.contains("com.android.theme.color.green:true"))
        assertTrue(parsed.contains("com.android.theme.color.blue:false"))
        assertTrue(parsed.contains("com.android.theme.font:true"))
    }

    @Test
    fun `ignores target headers and malformed lines`() {
        val output = """
            com.android.settings
              [x] com.android.valid.overlay (enabled)
              some garbage line
              [ ] no-package-marker
              (disabled)
        """.trimIndent()
        val parsed = OverlayManagerPlusImpl.parseOverlayListText(output)
        assertEquals(1, parsed.size)
        assertEquals("com.android.valid.overlay:true", parsed[0])
    }

    @Test
    fun `empty or header-only output yields empty list`() {
        assertEquals(emptyList<String>(), OverlayManagerPlusImpl.parseOverlayListText(""))
        assertEquals(emptyList<String>(), OverlayManagerPlusImpl.parseOverlayListText("com.android.settings\n"))
    }
}
