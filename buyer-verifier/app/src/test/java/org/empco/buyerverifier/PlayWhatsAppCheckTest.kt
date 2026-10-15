package org.empco.buyerverifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayWhatsAppCheckTest {

    private fun full(): PlayWhatsAppFacts = PlayWhatsAppFacts(
        playInstalled = true,
        gmsInstalled = true,
        gmsVersion = "26.39.99",
        whatsappInstalled = true,
        whatsappVersion = "2.24.1.78",
        whatsappInstaller = "com.android.vending",
        whatsappFirstInstall = "2026-10-11 18:02",
        marketResolvesToPlay = true,
        listingReachable = true
    )

    private fun verdict(f: PlayWhatsAppFacts): Finding =
        PlayWhatsAppCheck.evaluate(f).first { it.label == "WhatsApp approval by Google Play" }

    @Test
    fun `play-delivered whatsapp is approved`() {
        val v = verdict(full())
        assertEquals(Severity.OK, v.severity)
        assertTrue(v.observed.contains("APPROVED"))
    }

    @Test
    fun `missing play system is a hard failure`() {
        val v = verdict(full().copy(playInstalled = false, gmsInstalled = false))
        assertEquals(Severity.FAIL, v.severity)
        assertTrue(v.observed.contains("NOT APPROVED"))
    }

    @Test
    fun `sideloaded whatsapp downgrades to partial`() {
        val v = verdict(full().copy(whatsappInstaller = null))
        assertEquals(Severity.WARN, v.severity)
        assertTrue(v.observed.contains("PARTIAL"))
    }

    @Test
    fun `unreachable listing fails`() {
        val v = verdict(full().copy(listingReachable = false))
        assertEquals(Severity.FAIL, v.severity)
        assertTrue(v.observed.contains("NOT CONFIRMED"))
    }

    @Test
    fun `offline listing check stays informational`() {
        val v = verdict(full().copy(listingReachable = null))
        assertEquals(Severity.OK, v.severity)
        val out = PlayWhatsAppCheck.evaluate(full().copy(listingReachable = null))
        val listing = out.first { it.label == "WhatsApp on Google Play" }
        assertEquals(Severity.INFO, listing.severity)
    }

    @Test
    fun `whatsapp absent but play present is approved for the listing`() {
        val v = verdict(full().copy(whatsappInstalled = false))
        assertEquals(Severity.OK, v.severity)
        assertTrue(v.observed.contains("not installed"))
    }

    @Test
    fun `install source finding names the installer`() {
        val out = PlayWhatsAppCheck.evaluate(full().copy(whatsappInstaller = "com.sec.android.app.samsungstore"))
        val src = out.first { it.label == "WhatsApp install source" }
        assertEquals(Severity.WARN, src.severity)
        assertTrue(src.observed.contains("com.sec.android.app.samsungstore"))
    }
}
