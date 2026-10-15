package org.empco.buyerverifier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReportBuilderTest {

    private val today = LocalDate.of(2026, 10, 14)
    private val meta = ReportMeta(generatedAt = "2026-10-14 12:00:00", appVersion = "1.0")

    /**
     * Terms the report must NEVER contain: the document records the device's
     * original firmware/software as received, so any reference to system state
     * changes would undermine it as evidence. This test is the enforcement.
     */
    private val forbidden = Regex(
        "(?i)\\b(root|rooted|magisk|zygisk|busybox|superuser|bootloader|unlock\\w*|" +
            "spoof\\w*|modif\\w*|shizuku|tamper\\w*|hijack\\w*)\\b|play integrity"
    )

    private fun cloneFacts() = DeviceFacts(
        marketName = "Demo X200",
        brand = "Acme",
        manufacturer = "alps",
        model = "T200",
        device = "k6789v1_64",
        board = "mt6789",
        hardware = "mt6789",
        buildType = "user",
        buildTags = "release-keys",
        debuggable = "0",
        secure = "1",
        fingerprint = "alps/k6789v1_64/k6789v1_64:12/SP1A/user/release-keys",
        vendorFingerprint = "alps/k6789v1_64/k6789v1_64:11/RP1A/user/release-keys",
        odmFingerprint = null,
        productFingerprint = null,
        bootFingerprint = null,
        androidRelease = "12",
        securityPatch = "2023-06",
        buildDate = "Wed Jun 7 03:11:22 UTC 2023",
        buildUser = "android-build",
        buildHost = "build-host",
        serial = "0123456789ABCDEF",
        ramGb = 4.0,
        cores = 8,
        storageTotalGb = 64,
        storageFreeGb = 30,
        screenResolution = "720x1600",
        densityDpi = 320,
        gyroscope = false,
        compass = true,
        cameraCount = 2
    )

    private fun cloneReport(): String {
        val facts = cloneFacts()
        val claims = Claims(ramGb = 12.0, storageGb = 256, androidVersion = "15", imei = "356938035643809")
        val self = SelfCheck.evaluate(facts, claims, today)
        val play = PlayWhatsAppCheck.evaluate(
            PlayWhatsAppFacts(
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
        )
        return ReportBuilder.build(facts, self + play, meta)
    }

    @Test
    fun `report carries header identity and purpose`() {
        val report = cloneReport()
        assertTrue(report.startsWith("DEVICE AUTHENTICITY AND GOOGLE PLAY APPROVAL REPORT"))
        assertTrue(report.contains("Generated: 2026-10-14 12:00:00  ·  buyer-verifier 1.0"))
        assertTrue(report.contains("Device: Demo X200 (Acme / alps)"))
        assertTrue(report.contains("Purpose: record the firmware and software loaded on this device"))
        assertTrue(report.contains("== Device as reported by the system =="))
    }

    @Test
    fun `report contains every major section`() {
        val report = cloneReport()
        assertTrue(report.contains("== 1. Firmware & software =="))
        assertTrue(report.contains("Freshness"))
        assertTrue(report.contains("Claimed vs actual"))
        assertTrue(report.contains("Identity documents"))
        assertTrue(report.contains("Google Play & WhatsApp"))
        assertTrue(report.contains("== VERDICT =="))
        assertTrue(report.contains("== Recommended next steps =="))
    }

    @Test
    fun `report details the firmware inconsistencies`() {
        val report = cloneReport()
        // generic manufacturer with the actual value
        assertTrue(report.contains("Manufacturer identity: alps"))
        // divergent partition fingerprint with its actual value
        assertTrue(report.contains("Vendor fingerprint: alps/k6789v1_64/k6789v1_64:11/RP1A/user/release-keys"))
        assertTrue(report.contains("Board identity: mt6789"))
        // claims vs actual
        assertTrue(report.contains("listing claims 12 GB — device reports 4 GB"))
        assertTrue(report.contains("listing claims 256 GB — device reports 64 GB"))
        assertTrue(report.contains("listing claims 15 — device reports 12"))
        // verdict reflects the hard failures
        assertTrue(report.contains("LIKELY COUNTERFEIT OR CLONE"))
    }

    @Test
    fun `report states whatsapp play approval`() {
        val report = cloneReport()
        assertTrue(report.contains("WhatsApp approval by Google Play: APPROVED"))
        assertTrue(report.contains("WhatsApp install source: com.android.vending (Google Play)"))
        assertTrue(report.contains("listed and reachable in the Play Store"))
    }

    @Test
    fun `report includes usb findings when a scan ran`() {
        val usb = UsbScanRules.evaluate(
            UsbDescriptor(vid = 0x0e8d, pid = 0x20, manufacturer = "alps", product = null, serial = null),
            expectedModel = null
        )
        val report = ReportBuilder.build(cloneFacts(), usb, meta)
        assertTrue(report.contains("USB-OTG descriptor"))
        assertTrue(report.contains("MediaTek reference design"))
    }

    @Test
    fun `report never mentions system state changes`() {
        val report = cloneReport()
        val hit = forbidden.find(report)
        assertTrue(
            "forbidden term found: ${hit?.value}\ncontext: " +
                report.lineSequence().filter { forbidden.containsMatchIn(it) }.joinToString("\n"),
            hit == null
        )
    }

    @Test
    fun `clean report states likely genuine`() {
        val fp = "samsung/e1s/e1s:14/UP1A/user/release-keys"
        val facts = DeviceFacts(
            marketName = "Galaxy S24", brand = "samsung", manufacturer = "samsung",
            model = "SM-S921B", device = "e1s", board = "e1s", hardware = "s5e9945",
            buildType = "user", buildTags = "release-keys", debuggable = "0", secure = "1",
            fingerprint = fp, vendorFingerprint = fp, odmFingerprint = fp,
            productFingerprint = fp, bootFingerprint = fp,
            androidRelease = "14", securityPatch = "2026-08",
            serial = "RF8N123ABCD", ramGb = 8.0, cores = 8,
            storageTotalGb = 256, storageFreeGb = 120,
            screenResolution = "1080x2340", densityDpi = 420,
            gyroscope = true, compass = true, cameraCount = 3
        )
        val report = ReportBuilder.build(facts, SelfCheck.evaluate(facts, Claims(), today), meta)
        assertTrue(report.contains("LIKELY GENUINE"))
        assertFalse(report.contains("[FAIL]"))
        val hit = forbidden.find(report)
        assertTrue("forbidden term: ${hit?.value}", hit == null)
    }
}
