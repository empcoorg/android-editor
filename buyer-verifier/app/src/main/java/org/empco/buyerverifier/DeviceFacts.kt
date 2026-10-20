package org.empco.buyerverifier

/**
 * Everything the check reads from the device about its *original* firmware and
 * hardware. Collected by [DeviceFactsReader]; evaluated by [SelfCheck] so the
 * rules can be unit-tested without a device.
 */
data class DeviceFacts(
    val marketName: String? = null,
    val brand: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val device: String? = null,
    val board: String? = null,
    val hardware: String? = null,
    val buildType: String? = null,
    val buildTags: String? = null,
    val debuggable: String? = null,
    val secure: String? = null,
    val fingerprint: String? = null,
    val vendorFingerprint: String? = null,
    val odmFingerprint: String? = null,
    val productFingerprint: String? = null,
    val bootFingerprint: String? = null,
    val androidRelease: String? = null,
    val securityPatch: String? = null,
    val buildDate: String? = null,
    val buildUser: String? = null,
    val buildHost: String? = null,
    val serial: String? = null,
    val ramGb: Double? = null,
    val cores: Int? = null,
    val storageTotalGb: Long? = null,
    val storageFreeGb: Long? = null,
    val screenResolution: String? = null,
    val densityDpi: Int? = null,
    val gyroscope: Boolean = false,
    val compass: Boolean = false,
    val cameraCount: Int? = null
)
