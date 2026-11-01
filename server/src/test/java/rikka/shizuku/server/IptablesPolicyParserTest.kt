package rikka.shizuku.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IptablesPolicyParserTest {

    @Test
    fun `parses uid from --uid-owner and defaults to restricted`() {
        val policy = IptablesPolicyParser.parse(
            arrayOf("iptables", "-A", "OUTPUT", "-m", "owner", "--uid-owner", "10234", "-j", "DROP")
        )
        assertNotNull(policy)
        assertEquals(10234, policy!!.uid)
        assertTrue(policy.restricted, "append rule should be restricted")
    }

    @Test
    fun `parses uid from --uid-owner with -I insert`() {
        val policy = IptablesPolicyParser.parse(
            arrayOf("iptables", "-I", "OUTPUT", "1", "--uid-owner", "1000", "-j", "DROP")
        )
        assertNotNull(policy)
        assertEquals(1000, policy!!.uid)
        assertTrue(policy.restricted)
    }

    @Test
    fun `-D delete rule is unrestrict`() {
        val policy = IptablesPolicyParser.parse(
            arrayOf("iptables", "-D", "OUTPUT", "-m", "owner", "--uid-owner", "10234", "-j", "DROP")
        )
        assertNotNull(policy)
        assertEquals(10234, policy!!.uid)
        assertFalse(policy.restricted, "delete rule should unrestrict")
    }

    @Test
    fun `returns null when --uid-owner is missing`() {
        assertNull(IptablesPolicyParser.parse(arrayOf("iptables", "-A", "OUTPUT", "-j", "DROP")))
    }

    @Test
    fun `returns null when --uid-owner has no value`() {
        assertNull(IptablesPolicyParser.parse(arrayOf("iptables", "-A", "OUTPUT", "--uid-owner")))
    }

    @Test
    fun `returns null when uid is not numeric`() {
        assertNull(IptablesPolicyParser.parse(arrayOf("iptables", "-A", "OUTPUT", "--uid-owner", "not-a-uid")))
    }

    @Test
    fun `returns null on null input`() {
        assertNull(IptablesPolicyParser.parse(null))
    }
}
