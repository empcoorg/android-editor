package org.empco.buyerverifier

/** One observed signal. [expected] explains what a retail device should show, if applicable. */
enum class Severity { OK, INFO, WARN, FAIL }

data class Finding(
    val section: String,
    val label: String,
    val observed: String,
    val expected: String? = null,
    val severity: Severity
) {
    fun tag(): String = when (severity) {
        Severity.OK -> "[ OK ]"
        Severity.INFO -> "[INFO]"
        Severity.WARN -> "[WARN]"
        Severity.FAIL -> "[FAIL]"
    }
}
