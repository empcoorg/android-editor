package org.empco.buyerverifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SelfCheckTest {

    private val today = LocalDate.of(2026, 10, 14)

    private fun genuine(): DeviceFacts {
        val fp = "samsung/e1s/e1s:14/UP1A.240405.002/S921BXXU1AXA1:user/release-keys"
        return DeviceFacts(
            marketName = "Galaxy S24",
            brand = "samsung",
            manufacturer = "samsung",
            model = "SM-S921B",
            device = "e1s",
            board = "e1s",
            hardware = "s5e9945",
            buildType = "user",
            buildTags = "release-keys",
            debuggable = "0",
            secure = "1",
            fingerprint = fp,
            vendorFingerprint = fp,
            odmFingerprint = fp,
            productFingerprint = fp,
            bootFingerprint = fp,
            androidRelease = "14",
            securityPatch = "2026-08",
            buildDate = "Mon Aug 3 10:00:00 UTC 2026",
            buildUser = "builder",
            buildHost = "buildhost",
            serial = "RF8N123ABCD",
            ramGb = 8.0,
            cores = 8,
            storageTotalGb = 256,
            storageFreeGb = 120,
            screenResolution = "1080x2340",
            densityDpi = 420,
            gyroscope = true,
            compass = true,
            cameraCount = 3
        )
    }

    private fun severities(findings: List<Finding>, label: String): List<Severity> =
        findings.filter { it.label == label }.map { it.severity }

    @Test
    fun `genuine device with no claims produces no warnings or failures`() {
        val out = SelfCheck.evaluate(genuine(), Claims(), today)
        val bad = out.filter { it.severity == Severity.WARN || it.severity == Severity.FAIL }
        assertTrue("unexpected WARN/FAIL: $bad", bad.isEmpty())
    }

    @Test
    fun `generic manufacturer identity fails`() {
        val f = genuine().copy(manufacturer = "alps", brand = "Acme")
        val out = SelfCheck.evaluate(f, Claims(), today)
        assertEquals(listOf(Severity.FAIL), severities(out, "Manufacturer identity"))
    }

    @Test
    fun `non-user build type fails`() {
        val out = SelfCheck.evaluate(genuine().copy(buildType = "userdebug"), Claims(), today)
        assertEquals(listOf(Severity.FAIL), severities(out, "Build type"))
    }

    @Test
    fun `debuggable and insecure build fail`() {
        val out = SelfCheck.evaluate(
            genuine().copy(debuggable = "1", secure = "0"),
            Claims(), today
        )
        assertEquals(listOf(Severity.FAIL), severities(out, "ro.debuggable"))
        assertEquals(listOf(Severity.FAIL), severities(out, "ro.secure"))
    }

    @Test
    fun `divergent vendor fingerprint is flagged with the actual value`() {
        val f = genuine().copy(vendorFingerprint = "alps/e1s/e1s:11/RP1A/userdebug/test-keys")
        val out = SelfCheck.evaluate(f, Claims(), today)
        assertEquals(listOf(Severity.WARN), severities(out, "Vendor fingerprint"))
        val finding = out.first { it.label == "Vendor fingerprint" }
        assertTrue(finding.observed.contains("alps/e1s"))
        assertTrue(finding.expected!!.contains("should match"))
        // only vendor diverges — the other partitions stay clean
        assertEquals(emptyList<Severity>(), severities(out, "ODM fingerprint"))
    }

    @Test
    fun `mediatek reference board warns`() {
        val out = SelfCheck.evaluate(genuine().copy(board = "mt6789"), Claims(), today)
        assertEquals(listOf(Severity.WARN), severities(out, "Board identity"))
    }

    @Test
    fun `ram below the listing claim fails`() {
        val out = SelfCheck.evaluate(
            genuine().copy(ramGb = 4.0),
            Claims(ramGb = 12.0),
            today
        )
        val label = "RAM vs listing"
        assertEquals(listOf(Severity.FAIL), severities(out, label))
        assertTrue(out.first { it.label == label }.observed.contains("claims 12 GB"))
        assertTrue(out.first { it.label == label }.observed.contains("reports 4 GB"))
    }

    @Test
    fun `matching ram claim passes`() {
        val out = SelfCheck.evaluate(genuine(), Claims(ramGb = 8.0), today)
        assertEquals(listOf(Severity.OK), severities(out, "RAM vs listing"))
    }

    @Test
    fun `storage far below claim fails`() {
        val out = SelfCheck.evaluate(
            genuine().copy(storageTotalGb = 64),
            Claims(storageGb = 256),
            today
        )
        assertEquals(listOf(Severity.FAIL), severities(out, "Storage vs listing"))
    }

    @Test
    fun `android version below claim fails`() {
        val out = SelfCheck.evaluate(genuine(), Claims(androidVersion = "15"), today)
        assertEquals(listOf(Severity.FAIL), severities(out, "Android vs listing"))
        val same = SelfCheck.evaluate(genuine(), Claims(androidVersion = "Android 14"), today)
        assertEquals(listOf(Severity.OK), severities(same, "Android vs listing"))
    }

    @Test
    fun `claim resolution mismatch fails but free text stays informational`() {
        val bad = SelfCheck.evaluate(genuine(), Claims(screen = "1440x3200"), today)
        assertEquals(listOf(Severity.FAIL), severities(bad, "Screen vs listing"))
        val ok = SelfCheck.evaluate(genuine(), Claims(screen = "1080x2340"), today)
        assertEquals(listOf(Severity.OK), severities(ok, "Screen vs listing"))
        val free = SelfCheck.evaluate(genuine(), Claims(screen = "6.2 inch AMOLED"), today)
        assertEquals(listOf(Severity.INFO), severities(free, "Screen vs listing"))
    }

    @Test
    fun `stale security patch warns`() {
        val out = SelfCheck.evaluate(
            genuine().copy(securityPatch = "2024-01"),
            Claims(), today
        )
        assertEquals(listOf(Severity.WARN), severities(out, "Patch age"))
    }

    @Test
    fun `patch age math`() {
        assertEquals(2, SelfCheck.patchAgeMonths("2026-08", today))
        assertEquals(30, SelfCheck.patchAgeMonths("2024-04", today))
        assertEquals(null, SelfCheck.patchAgeMonths(null, today))
        assertEquals(null, SelfCheck.patchAgeMonths("not-a-date", today))
    }

    @Test
    fun `imei verdicts`() {
        val valid = SelfCheck.evaluate(genuine(), Claims(imei = "356938035643809"), today)
        assertEquals(listOf(Severity.OK), severities(valid, "IMEI"))

        val placeholder = SelfCheck.evaluate(genuine(), Claims(imei = "000000000000000"), today)
        assertEquals(listOf(Severity.FAIL), severities(placeholder, "IMEI"))

        val short = SelfCheck.evaluate(genuine(), Claims(imei = "12345"), today)
        assertEquals(listOf(Severity.FAIL), severities(short, "IMEI"))

        val none = SelfCheck.evaluate(genuine(), Claims(), today)
        assertEquals(listOf(Severity.INFO), severities(none, "IMEI"))
    }
}
