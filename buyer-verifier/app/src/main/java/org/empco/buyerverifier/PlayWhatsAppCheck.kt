package org.empco.buyerverifier

/**
 * Facts about the Google Play system on the device and how WhatsApp is
 * installed on it; read by [PlayWhatsAppReader].
 */
data class PlayWhatsAppFacts(
    val playInstalled: Boolean,
    val gmsInstalled: Boolean,
    val gmsVersion: String? = null,
    val whatsappInstalled: Boolean,
    val whatsappVersion: String? = null,
    val whatsappInstaller: String? = null,
    val whatsappFirstInstall: String? = null,
    val marketResolvesToPlay: Boolean? = null,
    val listingReachable: Boolean? = null
)

/**
 * Answers "is this device part of the Google Play system, and is WhatsApp
 * approved by it?" Pure function; unit-tested.
 */
object PlayWhatsAppCheck {

    const val PLAY_PKG = "com.android.vending"
    const val WHATSAPP_PKG = "com.whatsapp"

    private const val SEC = "Google Play & WhatsApp"

    fun evaluate(f: PlayWhatsAppFacts): List<Finding> {
        val out = mutableListOf<Finding>()

        out += if (f.playInstalled) {
            Finding(SEC, "Google Play Store", "installed ($PLAY_PKG)", null, Severity.OK)
        } else {
            Finding(
                SEC, "Google Play Store", "absent",
                "a device approved by the Google Play system ships the Play Store",
                Severity.FAIL
            )
        }

        out += when {
            f.gmsVersion != null -> Finding(SEC, "Play services", "installed (version ${f.gmsVersion})", null, Severity.OK)
            f.gmsInstalled -> Finding(SEC, "Play services", "installed (version not declared)", null, Severity.OK)
            else -> Finding(
                SEC, "Play services", "absent",
                "Google certification, Play-delivered apps and WhatsApp depend on it",
                Severity.FAIL
            )
        }

        out += Finding(
            SEC, "Play certification (manual check)",
            "Play Store > Profile > Settings — read the device certification notice there",
            "the device must be certified",
            Severity.INFO
        )

        when (f.marketResolvesToPlay) {
            true -> out += Finding(SEC, "Play Store links", "market: links open in the Play Store", null, Severity.OK)
            false -> out += Finding(
                SEC, "Play Store links", "market: links do not open the Play Store",
                "another store or no store claims app links on genuine Play devices",
                Severity.WARN
            )
            null -> Unit
        }

        if (!f.whatsappInstalled) {
            out += Finding(
                SEC, "WhatsApp (installed)", "not installed on this device",
                "install it from the Play Store and re-run the check for a full verdict",
                Severity.INFO
            )
        } else {
            out += Finding(SEC, "WhatsApp version", f.whatsappVersion ?: "(unknown)", null, Severity.INFO)
            out += Finding(SEC, "WhatsApp first install", f.whatsappFirstInstall ?: "(unknown)", null, Severity.INFO)
            out += when (f.whatsappInstaller) {
                PLAY_PKG -> Finding(
                    SEC, "WhatsApp install source", "$PLAY_PKG (Google Play)",
                    "delivered by the Google Play system",
                    Severity.OK
                )
                null, "" -> Finding(
                    SEC, "WhatsApp install source", "no installer recorded (sideloaded / unknown source)",
                    "approved installs record com.android.vending as the installer",
                    Severity.WARN
                )
                else -> Finding(
                    SEC, "WhatsApp install source", f.whatsappInstaller,
                    "expected $PLAY_PKG (Google Play)",
                    Severity.WARN
                )
            }
        }

        out += when (f.listingReachable) {
            true -> Finding(SEC, "WhatsApp on Google Play", "listed and reachable in the Play Store", null, Severity.OK)
            false -> Finding(
                SEC, "WhatsApp on Google Play", "the Play listing did not open",
                "com.whatsapp must be listed on Google Play",
                Severity.WARN
            )
            null -> Finding(SEC, "WhatsApp on Google Play", "not checked (no internet connection on the checking device)", null, Severity.INFO)
        }

        out += combinedVerdict(f)
        return out
    }

    private fun combinedVerdict(f: PlayWhatsAppFacts): Finding = when {
        !f.playInstalled || !f.gmsInstalled -> Finding(
            SEC, "WhatsApp approval by Google Play",
            "NOT APPROVED — the device is not part of the Google Play system " +
                "(Play Store=${f.playInstalled}, Play services=${f.gmsInstalled})",
            "the Play system present and certified on the device",
            Severity.FAIL
        )
        f.listingReachable == false -> Finding(
            SEC, "WhatsApp approval by Google Play",
            "NOT CONFIRMED — the Play Store could not open the WhatsApp listing",
            "com.whatsapp listed and reachable on Google Play",
            Severity.FAIL
        )
        f.whatsappInstalled && f.whatsappInstaller != PLAY_PKG -> Finding(
            SEC, "WhatsApp approval by Google Play",
            "PARTIAL — the Play system is present, but the installed WhatsApp did not come " +
                "from Google Play",
            "Play present and WhatsApp delivered by Google Play",
            Severity.WARN
        )
        !f.whatsappInstalled -> Finding(
            SEC, "WhatsApp approval by Google Play",
            "APPROVED — the Google Play system is present and the WhatsApp listing is " +
                "available on this device (WhatsApp itself is not installed)",
            null,
            Severity.OK
        )
        else -> Finding(
            SEC, "WhatsApp approval by Google Play",
            "APPROVED — the Google Play system is present and WhatsApp is delivered by it",
            null,
            Severity.OK
        )
    }
}
