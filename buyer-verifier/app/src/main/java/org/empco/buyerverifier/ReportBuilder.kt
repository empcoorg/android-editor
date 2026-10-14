package org.empco.buyerverifier

/** Values stamped into the report header/footer. */
data class ReportMeta(
    val generatedAt: String,
    val appVersion: String
)

/**
 * Turns findings into the plain-text report that is both shown on screen and
 * rendered to PDF by [PdfReport]. Pure function — the "no mention of system
 * modification" rule is enforced by a unit test over its output.
 */
object ReportBuilder {

    private val STEPS = listOf(
        "Compare every inconsistency above with the seller's listing and invoice, and ask for " +
            "written confirmation of the claimed specifications.",
        "Dial *#06# on the device and compare the IMEI with the box label and the invoice.",
        "Check the build date and security patch level above against how the item was sold " +
            "(new, current model, sealed box).",
        "In the Play Store open Profile > Settings and read the device certification notice; " +
            "reinstall WhatsApp from the Play Store and confirm its install source shows Google Play.",
        "Keep this PDF together with the invoice. If the device does not match the listing, " +
            "present both when requesting a return, replacement or refund."
    )

    fun build(
        facts: DeviceFacts,
        findings: List<Finding>,
        meta: ReportMeta
    ): String {
        val sb = StringBuilder()
        sb += "DEVICE AUTHENTICITY AND GOOGLE PLAY APPROVAL REPORT"
        sb += "Generated: ${meta.generatedAt}  ·  buyer-verifier ${meta.appVersion}"
        sb += ""
        sb += "Device: ${facts.marketName?.takeIf { it.isNotBlank() } ?: facts.model} " +
            "(${facts.brand.ifBlank { "?" }} / ${facts.manufacturer.ifBlank { "?" }})"
        sb += "Purpose: record the firmware and software loaded on this device exactly as it " +
            "reports them, for presentation to the seller."
        sb += ""

        sb += "== Device as reported by the system =="
        row(sb, "Marketed name", facts.marketName ?: "(not declared)")
        row(sb, "Brand", facts.brand.ifBlank { "?" })
        row(sb, "Manufacturer", facts.manufacturer.ifBlank { "?" })
        row(sb, "Model / device", "${facts.model.ifBlank { "?" }} / ${facts.device ?: "?"}")
        row(sb, "Board / hardware", "${facts.board ?: "?"} / ${facts.hardware ?: "?"}")
        row(sb, "Android", "${facts.androidRelease ?: "?"} (patch ${facts.securityPatch ?: "?"})")
        row(sb, "Serial", facts.serial?.takeIf { it.isNotBlank() } ?: "(not readable from an app)")
        row(sb, "Fingerprint", facts.fingerprint ?: "(unknown)")
        sb += ""

        val grouped = LinkedHashMap<String, MutableList<Finding>>()
        for (f in findings) grouped.getOrPut(f.section) { mutableListOf() }.add(f)
        var index = 0
        for ((section, items) in grouped) {
            index++
            sb += "== $index. $section =="
            for (f in items) {
                sb += "  ${f.tag()} ${f.label}: ${f.observed}"
                f.expected?.let { sb += "          expected: $it" }
            }
            sb += ""
        }

        val verdict = VerdictEngine.from(findings)
        val counts = findings.groupingBy { it.severity }.eachCount()
        sb += "== VERDICT =="
        sb += "  ${verdict.title}"
        sb += "  ${verdict.detail}"
        sb += "  (heuristic score ${verdict.score} · " +
            "fail ${counts[Severity.FAIL] ?: 0}, warn ${counts[Severity.WARN] ?: 0}, " +
            "ok ${counts[Severity.OK] ?: 0}, info ${counts[Severity.INFO] ?: 0})"
        sb += ""

        sb += "== Recommended next steps =="
        STEPS.forEachIndexed { i, step -> sb += "  ${i + 1}. $step" }
        sb += ""

        sb += "== Notes =="
        sb += "  - This report is a heuristic screener, not a certificate. It records what the " +
            "device reports about its own firmware and software and compares that with what " +
            "retail devices are expected to ship."
        sb += "  - Values on \"expected:\" lines state the retail expectation for that signal."
        sb += "  - buyer-verifier ${meta.appVersion} · generated ${meta.generatedAt}"
        return sb.toString()
    }

    private operator fun StringBuilder.plusAssign(line: String) {
        append(line).append('\n')
    }

    private fun row(sb: StringBuilder, label: String, value: String) {
        sb += "  ${label.padEnd(17)}: $value"
    }
}
