package org.empco.buyerverifier

/**
 * What the seller's listing claims. Typed in by the user before the check so
 * the report can diff claims against what the device actually reports.
 */
data class Claims(
    val ramGb: Double? = null,
    val storageGb: Long? = null,
    val screen: String? = null,
    val androidVersion: String? = null,
    val imei: String? = null
) {
    fun isEmpty(): Boolean =
        ramGb == null && storageGb == null && screen.isNullOrBlank() &&
            androidVersion.isNullOrBlank() && imei.isNullOrBlank()
}
