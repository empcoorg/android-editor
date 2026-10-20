package org.empco.buyerverifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {

    @Test
    fun `valid imei passes luhn`() {
        // well-known valid example IMEI
        assertTrue(Identity.imeiLuhn("356938035643809"))
    }

    @Test
    fun `tampered last digit fails luhn`() {
        assertFalse(Identity.imeiLuhn("356938035643808"))
    }

    @Test
    fun `wrong length fails`() {
        assertFalse(Identity.imeiLuhn("12345"))
        assertFalse(Identity.imeiLuhn(""))
    }

    @Test
    fun `placeholders are detected separately from luhn`() {
        // all-zero passes the checksum but is still a placeholder — callers must
        // check isPlaceholder first, as SelfCheck does
        assertTrue(Identity.isPlaceholder("000000000000000"))
        assertTrue(Identity.isPlaceholder("111 111 111 111 111"))
        assertFalse(Identity.isPlaceholder("356938035643809"))
        assertTrue(Identity.imeiLuhn("000000000000000"))
    }

    @Test
    fun `digitsOnly strips formatting`() {
        assertEquals("356938035643809", Identity.digitsOnly("356-938-0356-43809"))
    }
}
