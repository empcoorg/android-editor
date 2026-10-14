package org.empco.buyerverifier

/** USB descriptor of the candidate phone as seen from a buyer phone over OTG. */
data class UsbDescriptor(
    val vid: Int,
    val pid: Int,
    val manufacturer: String? = null,
    val product: String? = null,
    val serial: String? = null
) {
    fun vidHex(): String = String.format("0x%04X", vid)
    fun pidHex(): String = String.format("0x%04X", pid)
}

/**
 * Tier-1 rules from docs/buyer-verifier-app.md and docker/bin/android-authenticity-check,
 * applied to USB descriptors instead of getprop. NOTE: descriptor behavior per OEM
 * still needs a real two-phone test (open question 1 in the design doc).
 */
object UsbScanRules {

    /** Vendor IDs used by reference designs — common on clones and white boxes. */
    val REFERENCE_VIDS: Map<Int, String> = mapOf(
        0x0e8d to "MediaTek reference design",
        0x1782 to "Spreadtrum/Unisoc reference design",
        0x2207 to "Rockchip reference design",
        0x1f3a to "Allwinner reference design",
        0x05c6 to "Qualcomm reference design"
    )

    /** Vendor IDs of established brands (informational cross-check only). */
    val KNOWN_BRAND_VIDS: Map<Int, String> = mapOf(
        0x18d1 to "Google",
        0x04e8 to "Samsung",
        0x2717 to "Xiaomi",
        0x2a70 to "OnePlus",
        0x22d9 to "OPPO",
        0x2a45 to "vivo",
        0x22b8 to "Motorola",
        0x1004 to "LG",
        0x054c to "Sony",
        0x12d1 to "Huawei",
        0x19d2 to "ZTE",
        0x17ef to "Lenovo",
        0x0b05 to "ASUS",
        0x0bb4 to "HTC"
    )

    private val GENERIC_STRINGS = setOf("alps", "unknown", "generic", "android", "aosp", "dummy", "")

    fun evaluate(d: UsbDescriptor, expectedModel: String?): List<Finding> {
        val sec = "USB-OTG descriptor"
        val out = mutableListOf<Finding>()

        val ref = REFERENCE_VIDS[d.vid]
        val known = KNOWN_BRAND_VIDS[d.vid]
        out += when {
            ref != null -> Finding(
                sec, "USB vendor ID", "${d.vidHex()} — $ref",
                "an established brand's vendor ID; reference-design IDs are common on clones",
                Severity.WARN
            )
            known != null -> Finding(sec, "USB vendor ID", "${d.vidHex()} ($known)", null, Severity.OK)
            else -> Finding(
                sec, "USB vendor ID", "${d.vidHex()} (unrecognised vendor)",
                "compare against the marketed brand's official vendor ID",
                Severity.INFO
            )
        }
        out += Finding(sec, "USB product ID", d.pidHex(), null, Severity.INFO)

        val mfr = d.manufacturer?.trim()
        out += if (mfr == null || mfr.lowercase() in GENERIC_STRINGS) {
            Finding(
                sec, "Manufacturer string", mfr?.takeIf { it.isNotBlank() } ?: "(absent)",
                "a real manufacturer name — absent/generic strings come from reference firmware",
                Severity.WARN
            )
        } else {
            Finding(sec, "Manufacturer string", mfr, null, Severity.OK)
        }

        val product = d.product?.trim()
        out += if (product == null || product.lowercase() in GENERIC_STRINGS) {
            Finding(
                sec, "Product string", product?.takeIf { it.isNotBlank() } ?: "(absent)",
                "the marketed model name",
                Severity.WARN
            )
        } else {
            Finding(sec, "Product string", product, null, Severity.INFO)
        }

        if (!expectedModel.isNullOrBlank() && !product.isNullOrBlank()) {
            val wanted = normalize(expectedModel)
            val got = normalize(product)
            out += if (wanted.isNotEmpty() && got.isNotEmpty() &&
                (got.contains(wanted) || wanted.contains(got))
            ) {
                Finding(sec, "Product vs expected model", "\"$product\" matches \"$expectedModel\"", null, Severity.OK)
            } else {
                Finding(
                    sec, "Product vs expected model",
                    "expected \"$expectedModel\", device reports \"$product\"",
                    "the product string to match the marketed model",
                    Severity.WARN
                )
            }
        }

        val serial = d.serial?.trim()
        out += when {
            serial.isNullOrEmpty() -> Finding(
                sec, "Serial number", "(not readable — USB permission not granted, or empty)",
                "an opaque unique serial",
                Severity.INFO
            )
            looksFake(serial) -> Finding(
                sec, "Serial number", serial,
                "a unique serial — all-same-digit or trivially sequential values are clone markers",
                Severity.FAIL
            )
            else -> Finding(sec, "Serial number", serial, null, Severity.OK)
        }

        return out
    }

    fun looksFake(serial: String): Boolean {
        val s = serial.trim()
        if (s.length < 4) return true
        if (s.all { it == s[0] }) return true
        val lower = s.lowercase()
        if (lower in setOf("0123456789abcdef", "123456789012345", "0000000000000000")) return true
        if (s.all { it.isDigit() } && s.length >= 6) {
            val ascending = s.indices.all { i -> i == 0 || s[i] == s[i - 1] + 1 }
            val descending = s.indices.all { i -> i == 0 || s[i] == s[i - 1] - 1 }
            if (ascending || descending) return true
        }
        return false
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")
}
