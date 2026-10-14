package org.empco.buyerverifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {

    private fun finding(sev: Severity) =
        Finding("s", "label", "value", null, sev)

    @Test
    fun `clean device is likely genuine`() {
        val v = VerdictEngine.from(listOf(finding(Severity.OK), finding(Severity.INFO)))
        assertEquals("LIKELY GENUINE", v.title)
        assertEquals(0, v.score)
        assertFalse(v.hardFail)
    }

    @Test
    fun `single warn is minor`() {
        val v = VerdictEngine.from(listOf(finding(Severity.WARN)))
        assertEquals("SUSPICIOUS — VERIFY (minor)", v.title)
        assertEquals(1, v.score)
        assertFalse(v.hardFail)
    }

    @Test
    fun `four warns cross the suspicious threshold`() {
        val v = VerdictEngine.from(List(4) { finding(Severity.WARN) })
        assertEquals("SUSPICIOUS — VERIFY", v.title)
        assertEquals(4, v.score)
        assertFalse(v.hardFail)
    }

    @Test
    fun `any fail forces the hard verdict`() {
        val v = VerdictEngine.from(listOf(finding(Severity.FAIL), finding(Severity.OK)))
        assertEquals("LIKELY COUNTERFEIT OR CLONE", v.title)
        assertEquals(3, v.score)
        assertTrue(v.hardFail)
    }
}
