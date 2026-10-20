package org.empco.buyerverifier

/**
 * Verdict scoring mirrors docker/bin/android-authenticity-check: each WARN costs
 * 1 point, each FAIL costs 3 and forces the hard verdict.
 */
data class Verdict(
    val title: String,
    val detail: String,
    val score: Int,
    val hardFail: Boolean
)

object VerdictEngine {

    fun from(findings: List<Finding>): Verdict {
        var score = 0
        var hard = false
        for (f in findings) {
            when (f.severity) {
                Severity.WARN -> score += 1
                Severity.FAIL -> {
                    score += 3
                    hard = true
                }
                else -> Unit
            }
        }
        return when {
            hard -> Verdict(
                title = "LIKELY COUNTERFEIT OR CLONE",
                detail = "The firmware and software loaded on this device contain strong " +
                    "inconsistencies with what a retail unit must ship. Do not accept the item " +
                    "as described until every [FAIL] above is resolved by the seller.",
                score = score,
                hardFail = true
            )
            score >= 4 -> Verdict(
                title = "SUSPICIOUS — VERIFY",
                detail = "Several inconsistencies were found (score $score). Check the claimed " +
                    "specifications, IMEI and Play certification against the seller's listing " +
                    "before accepting the device.",
                score = score,
                hardFail = false
            )
            score >= 1 -> Verdict(
                title = "SUSPICIOUS — VERIFY (minor)",
                detail = "A few soft signals are off (score $score). Worth double-checking with " +
                    "the seller, not alarming on its own.",
                score = score,
                hardFail = false
            )
            else -> Verdict(
                title = "LIKELY GENUINE",
                detail = "No inconsistencies found by this screener. Still confirm the listed " +
                    "specifications and Play certification with the seller before paying.",
                score = score,
                hardFail = false
            )
        }
    }
}
