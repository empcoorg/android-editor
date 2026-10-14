package org.empco.buyerverifier

import java.time.LocalDate
import java.time.YearMonth

/**
 * Port of the heuristic rules in docker/bin/android-authenticity-check to a
 * pure function over [DeviceFacts]. Reads nothing, so every rule is unit-tested.
 */
object SelfCheck {

    private val GENERIC_MANUFACTURERS = setOf(
        "alps", "unknown", "generic", "mtk", "mediatek", "spreadtrum",
        "unisoc", "android", "dummy", ""
    )

    private val GENERIC_BOARD_PREFIXES = listOf("mt6", "mt8", "sc9", "ums", "sp")

    private const val SEC_FW = "Firmware & software"
    private const val SEC_AGE = "Freshness"
    private const val SEC_SPEC = "Claimed vs actual"
    private const val SEC_ID = "Identity documents"

    fun evaluate(f: DeviceFacts, claims: Claims, today: LocalDate = LocalDate.now()): List<Finding> {
        val out = mutableListOf<Finding>()

        // ---- identity rows (context for everything below) ----
        out += Finding(SEC_FW, "Marketed name", f.marketName?.takeIf { it.isNotBlank() } ?: "(not declared)", null, Severity.INFO)
        out += Finding(SEC_FW, "Brand / manufacturer", "${f.brand.ifBlank { "?" }} / ${f.manufacturer.ifBlank { "?" }}", null, Severity.INFO)
        out += Finding(SEC_FW, "Build model / device", "${f.model.ifBlank { "?" }} / ${f.device ?: "?"}", null, Severity.INFO)
        out += Finding(SEC_FW, "Board / hardware", "${f.board ?: "?"} / ${f.hardware ?: "?"}", null, Severity.INFO)
        out += Finding(SEC_FW, "Build fingerprint", f.fingerprint ?: "(unknown)", null, Severity.INFO)

        val marketName = f.marketName?.trim().orEmpty()
        if (marketName.isNotBlank() && f.model.isNotBlank() && !f.model.equals(marketName, ignoreCase = true)) {
            out += Finding(
                SEC_FW, "Marketed name vs build model",
                "\"$marketName\" vs \"${f.model}\"",
                "both usually agree on retail units; a codename-only model is fine, a different " +
                    "human-readable name is worth checking against the box",
                Severity.INFO
            )
        }

        val lowMfr = f.manufacturer.trim().lowercase()
        if (lowMfr in GENERIC_MANUFACTURERS) {
            out += Finding(
                SEC_FW, "Manufacturer identity",
                f.manufacturer.ifBlank { "(empty)" },
                "a real brand name — empty or generic values come from vendor reference " +
                    "firmware and are a classic white-box/clone marker",
                Severity.FAIL
            )
        }

        val board = f.board?.trim()?.lowercase().orEmpty()
        if (GENERIC_BOARD_PREFIXES.any { board.startsWith(it) }) {
            out += Finding(
                SEC_FW, "Board identity", "${f.board} (hardware ${f.hardware ?: "?"})",
                "retail boards are named after the marketed line; reference-design names " +
                    "(MediaTek/Spreadtrum and similar) are common on clones",
                Severity.WARN
            )
        }

        out += if (f.buildType == "user") {
            Finding(SEC_FW, "Build type", "user", null, Severity.OK)
        } else {
            Finding(
                SEC_FW, "Build type", f.buildType ?: "(unknown)",
                "user — retail devices ship user builds; userdebug/eng are development firmware",
                Severity.FAIL
            )
        }

        out += if (f.buildTags == "release-keys") {
            Finding(SEC_FW, "Build tags", "release-keys", null, Severity.OK)
        } else {
            Finding(
                SEC_FW, "Build tags", f.buildTags ?: "(unknown)",
                "release-keys — the signature retail firmware must carry",
                Severity.WARN
            )
        }

        out += if (f.debuggable == "0") {
            Finding(SEC_FW, "ro.debuggable", "0", null, Severity.OK)
        } else {
            Finding(SEC_FW, "ro.debuggable", f.debuggable ?: "(unknown)", "0 on a retail device", Severity.FAIL)
        }

        out += if (f.secure == "1") {
            Finding(SEC_FW, "ro.secure", "1", null, Severity.OK)
        } else {
            Finding(SEC_FW, "ro.secure", f.secure ?: "(unknown)", "1 on a retail device", Severity.FAIL)
        }
        // ---- partition fingerprints vs the system fingerprint ----
        val mainFp = f.fingerprint
        val parts = listOf(
            "Vendor fingerprint" to f.vendorFingerprint,
            "ODM fingerprint" to f.odmFingerprint,
            "Product fingerprint" to f.productFingerprint,
            "Boot image fingerprint" to f.bootFingerprint
        )
        for ((label, value) in parts) {
            if (!value.isNullOrBlank() && !mainFp.isNullOrBlank() && value != mainFp) {
                out += Finding(
                    SEC_FW, label, value,
                    "should match the system fingerprint ($mainFp) — a mismatch means the " +
                        "software was assembled from different build sources",
                    Severity.WARN
                )
            }
        }

        // ---- freshness ----
        out += Finding(SEC_AGE, "Android version", f.androidRelease ?: "?", null, Severity.INFO)
        out += Finding(SEC_AGE, "Security patch", f.securityPatch ?: "?", null, Severity.INFO)
        out += Finding(SEC_AGE, "Build date", f.buildDate ?: "?", null, Severity.INFO)
        out += Finding(
            SEC_AGE, "Build user / host",
            "${f.buildUser ?: "?"} / ${f.buildHost ?: "?"}", null, Severity.INFO
        )

        val months = patchAgeMonths(f.securityPatch, today)
        when {
            months == null -> Unit
            months > 24 -> out += Finding(
                SEC_AGE, "Patch age", "$months months behind the current patch level",
                "a device sold as current carries a patch from the last year",
                Severity.WARN
            )
            months > 12 -> out += Finding(SEC_AGE, "Patch age", "$months months behind the current patch level", null, Severity.INFO)
            else -> out += Finding(SEC_AGE, "Patch age", "$months months", null, Severity.OK)
        }
        // ---- claimed vs actual ----
        out += Finding(SEC_SPEC, "RAM (reported)", f.ramGb?.let { fmt(it) + " GB" } ?: "unknown", null, Severity.INFO)
        out += Finding(SEC_SPEC, "CPU cores (reported)", f.cores?.toString() ?: "unknown", null, Severity.INFO)
        out += Finding(
            SEC_SPEC, "Storage /data (reported)",
            f.storageTotalGb?.let { "$it GB total, ${f.storageFreeGb ?: "?"} GB free" } ?: "unknown",
            null, Severity.INFO
        )
        out += Finding(
            SEC_SPEC, "Screen (reported)",
            listOfNotNull(f.screenResolution, f.densityDpi?.let { "$it dpi" }).joinToString(" @ ").ifEmpty { "unknown" },
            null, Severity.INFO
        )
        out += Finding(
            SEC_SPEC, "Cameras / sensors",
            "${f.cameraCount ?: "?"} camera(s); gyroscope=${yn(f.gyroscope)}, compass=${yn(f.compass)}",
            null, Severity.INFO
        )

        claims.ramGb?.let { claim ->
            val actual = f.ramGb
            out += when {
                actual == null -> Finding(SEC_SPEC, "RAM vs listing", "claims $claim GB, device could not be read", null, Severity.INFO)
                actual + 0.75 < claim -> Finding(
                    SEC_SPEC, "RAM vs listing",
                    "listing claims ${fmt(claim)} GB — device reports ${fmt(actual)} GB",
                    "at least what the listing claims",
                    Severity.FAIL
                )
                else -> Finding(SEC_SPEC, "RAM vs listing", "listing claims ${fmt(claim)} GB — device reports ${fmt(actual)} GB", null, Severity.OK)
            }
        }

        claims.storageGb?.let { claim ->
            val actual = f.storageTotalGb
            out += when {
                actual == null -> Finding(SEC_SPEC, "Storage vs listing", "claims $claim GB, device could not be read", null, Severity.INFO)
                actual < claim * 9 / 10 -> Finding(
                    SEC_SPEC, "Storage vs listing",
                    "listing claims $claim GB — device reports $actual GB",
                    "within ~10% of the listing (formatting overhead)",
                    Severity.FAIL
                )
                else -> Finding(SEC_SPEC, "Storage vs listing", "listing claims $claim GB — device reports $actual GB", null, Severity.OK)
            }
        }

        claims.screen?.takeIf { it.isNotBlank() }?.let { claim ->
            val wanted = claim.lowercase().replace(" ", "")
            val actual = f.screenResolution?.lowercase()
            out += when {
                actual == null -> Finding(SEC_SPEC, "Screen vs listing", "claims $claim — device could not be read", null, Severity.INFO)
                wanted == actual -> Finding(SEC_SPEC, "Screen vs listing", "claims $claim — device reports $actual", null, Severity.OK)
                Regex("^\\d+x\\d+$").matches(wanted) -> Finding(
                    SEC_SPEC, "Screen vs listing",
                    "listing claims ${claim.replace(" ", "")} — device reports $actual",
                    "the resolution in the listing",
                    Severity.FAIL
                )
                else -> Finding(
                    SEC_SPEC, "Screen vs listing",
                    "listing claims \"$claim\" — device reports $actual (free-text claim, compare manually)",
                    null, Severity.INFO
                )
            }
        }

        claims.androidVersion?.takeIf { it.isNotBlank() }?.let { claim ->
            val wanted = claim.removePrefix("Android ").removePrefix("android ").trim()
            val actual = f.androidRelease
            out += when {
                actual == null -> Finding(SEC_SPEC, "Android vs listing", "claims $claim, device could not be read", null, Severity.INFO)
                actual == wanted || actual.startsWith("$wanted.") || wanted.startsWith(actual) ->
                    Finding(SEC_SPEC, "Android vs listing", "listing claims $wanted — device reports $actual", null, Severity.OK)
                else -> Finding(
                    SEC_SPEC, "Android vs listing",
                    "listing claims $wanted — device reports $actual",
                    "the Android version in the listing",
                    Severity.FAIL
                )
            }
        }

        if (claims.ramGb == null && f.ramGb != null && f.ramGb < 3.0) {
            out += Finding(
                SEC_SPEC, "RAM", "${fmt(f.ramGb)} GB is low-end",
                "check the listing did not promise 8 GB or more",
                Severity.WARN
            )
        }
        if (!f.gyroscope) {
            out += Finding(
                SEC_SPEC, "Gyroscope", "absent",
                "most retail devices include one; games and AR break without it",
                Severity.WARN
            )
        }
        // ---- identity documents ----
        out += if (f.serial.isNullOrBlank()) {
            Finding(
                SEC_ID, "Serial", "not readable from an app",
                "check the box label or dial *#06# and compare",
                Severity.INFO
            )
        } else {
            Finding(SEC_ID, "Serial", f.serial, null, Severity.INFO)
        }

        val imei = claims.imei?.let { Identity.digitsOnly(it) }
        out += when {
            imei.isNullOrEmpty() -> Finding(
                SEC_ID, "IMEI", "not entered",
                "dial *#06# on the device and type the IMEI in to have it checked",
                Severity.INFO
            )
            Identity.isPlaceholder(imei) -> Finding(
                SEC_ID, "IMEI", imei,
                "a placeholder IMEI (all same digits) is illegal and a classic clone marker",
                Severity.FAIL
            )
            imei.length != 15 -> Finding(
                SEC_ID, "IMEI", "${imei.length} digits entered",
                "an IMEI has exactly 15 digits",
                Severity.FAIL
            )
            Identity.imeiLuhn(imei) -> Finding(SEC_ID, "IMEI", "$imei passes the Luhn check (well-formed)", null, Severity.OK)
            else -> Finding(
                SEC_ID, "IMEI", "$imei fails the Luhn check",
                "well-formed IMEIs pass the Luhn checksum — a failure suggests a malformed or " +
                    "incorrect number",
                Severity.FAIL
            )
        }

        return out
    }

    /** Months between a yyyy-MM security patch level and [today]; null if unparseable. */
    fun patchAgeMonths(patch: String?, today: LocalDate): Int? {
        if (patch.isNullOrBlank()) return null
        return try {
            val then = YearMonth.parse(patch.take(7))
            val now = YearMonth.from(today)
            (now.year - then.year) * 12 + (now.monthValue - then.monthValue)
        } catch (e: Exception) {
            null
        }
    }

    private fun yn(b: Boolean) = if (b) "present" else "ABSENT"

    private fun fmt(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString()
        else String.format("%.1f", d)
}
