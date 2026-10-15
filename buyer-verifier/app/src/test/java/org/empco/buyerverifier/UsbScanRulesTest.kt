package org.empco.buyerverifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbScanRulesTest {

    private fun desc(
        vid: Int = 0x04e8,
        pid: Int = 0x6860,
        manufacturer: String? = "Samsung Electronics",
        product: String? = "SM-S921B",
        serial: String? = "RF8N123ABCD"
    ) = UsbDescriptor(vid = vid, pid = pid, manufacturer = manufacturer, product = product, serial = serial)

    private fun one(out: List<Finding>, label: String): Finding = out.first { it.label == label }

    @Test
    fun `mediatek reference vid warns`() {
        val out = UsbScanRules.evaluate(desc(vid = 0x0e8d), null)
        val f = one(out, "USB vendor ID")
        assertEquals(Severity.WARN, f.severity)
        assertTrue(f.observed.contains("MediaTek"))
    }

    @Test
    fun `established brand vid is ok`() {
        val out = UsbScanRules.evaluate(desc(vid = 0x04e8), null)
        assertEquals(Severity.OK, one(out, "USB vendor ID").severity)
        assertTrue(one(out, "USB vendor ID").observed.contains("Samsung"))
    }

    @Test
    fun `unknown vid is informational`() {
        val out = UsbScanRules.evaluate(desc(vid = 0x1234), null)
        assertEquals(Severity.INFO, one(out, "USB vendor ID").severity)
    }

    @Test
    fun `generic manufacturer string warns`() {
        val out = UsbScanRules.evaluate(desc(manufacturer = "alps"), null)
        assertEquals(Severity.WARN, one(out, "Manufacturer string").severity)
        val absent = UsbScanRules.evaluate(desc(manufacturer = null), null)
        assertEquals(Severity.WARN, one(absent, "Manufacturer string").severity)
    }

    @Test
    fun `product string matching expected model is ok`() {
        val out = UsbScanRules.evaluate(desc(product = "X200"), expectedModel = "Acme X200")
        assertEquals(Severity.OK, one(out, "Product vs expected model").severity)
    }

    @Test
    fun `product string contradicting expected model warns`() {
        val out = UsbScanRules.evaluate(desc(product = "Mystery X1"), expectedModel = "Acme X200")
        assertEquals(Severity.WARN, one(out, "Product vs expected model").severity)
    }

    @Test
    fun `fake serials are detected`() {
        assertTrue(UsbScanRules.looksFake("1111111111111111"))
        assertTrue(UsbScanRules.looksFake("0123456789abcdef"))
        assertTrue(UsbScanRules.looksFake("0123456789"))
        assertTrue(UsbScanRules.looksFake("ab"))
        assertFalse(UsbScanRules.looksFake("RF8N123ABCD"))
        assertFalse(UsbScanRules.looksFake("0000000000000000ABC1")) // mixed, not all-same

        val out = UsbScanRules.evaluate(desc(serial = "9999999999999999"), null)
        assertEquals(Severity.FAIL, one(out, "Serial number").severity)
    }

    @Test
    fun `serial without permission is informational not a failure`() {
        val out = UsbScanRules.evaluate(desc(serial = null), null)
        assertEquals(Severity.INFO, one(out, "Serial number").severity)
    }
}
