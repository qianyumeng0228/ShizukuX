package af.shizuku.manager.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Module.prop parsing tolerance — malformed input must never throw and
 * must degrade to sensible defaults.
 */
class ModulePropTest {

    @Test
    fun `parses simple key value pairs`() {
        val props = ModuleProp.parse("id=my.module\nname=My Module\nversion=1.0")
        assertEquals("my.module", props["id"])
        assertEquals("My Module", props["name"])
        assertEquals("1.0", props["version"])
    }

    @Test
    fun `skips comments blank lines and unparsable lines`() {
        val props = ModuleProp.parse(
            """
            # a comment
            id=ok.module

            not-a-pair
            name=With = equals
            """.trimIndent()
        )
        assertEquals("ok.module", props["id"])
        assertEquals("With = equals", props["name"])
        assertFalse("comment-only line must not produce a key", props.containsKey("# a comment"))
    }

    @Test
    fun `empty and blank input produce empty map`() {
        assertEquals(emptyMap<String, String>(), ModuleProp.parse(""))
        assertEquals(emptyMap<String, String>(), ModuleProp.parse("   \n  \n"))
    }

    @Test
    fun `trims surrounding whitespace on keys and values`() {
        val props = ModuleProp.parse("  id  =  spaced.module  ")
        assertEquals("spaced.module", props["id"])
    }

    @Test
    fun `module id grammar validation`() {
        assertTrue(ModuleProp.isValidModuleId("com.example.module"))
        assertTrue(ModuleProp.isValidModuleId("ab"))
        assertTrue(ModuleProp.isValidModuleId("a.b"))
        assertFalse("must start with a letter", ModuleProp.isValidModuleId("1abc"))
        assertFalse("must not be empty", ModuleProp.isValidModuleId(""))
        assertFalse("spaces not allowed", ModuleProp.isValidModuleId("bad id"))
        assertFalse("too long", ModuleProp.isValidModuleId("a".repeat(65)))
    }
}
