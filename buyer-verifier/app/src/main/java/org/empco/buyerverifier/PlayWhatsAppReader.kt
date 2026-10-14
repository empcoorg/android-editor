package org.empco.buyerverifier

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the Google Play system state and how WhatsApp is installed, then
 * answers whether WhatsApp is approved by Google Play ([PlayWhatsAppCheck]).
 */
class PlayWhatsAppReader(
    private val context: Context,
    private val props: PropSource = SystemPropSource()
) {

    /** Fast, offline-capable facts. Safe to call on a background thread. */
    fun read(): PlayWhatsAppFacts {
        val pm = context.packageManager
        val playInstalled = hasPackage(pm, PlayWhatsAppCheck.PLAY_PKG)
        val gmsInstalled = hasPackage(pm, "com.google.android.gms")
        val gmsVersion = props.get("ro.com.google.gmsversion")

        var waVersion: String? = null
        var waInstaller: String? = null
        var waFirstInstall: String? = null
        var waInstalled = false
        try {
            val info = pm.getPackageInfo(PlayWhatsAppCheck.WHATSAPP_PKG, 0)
            waInstalled = true
            waVersion = info.versionName
            waFirstInstall = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                .format(Date(info.firstInstallTime))
            waInstaller = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    pm.getInstallSourceInfo(PlayWhatsAppCheck.WHATSAPP_PKG).installingPackageName
                } catch (e: Exception) {
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(PlayWhatsAppCheck.WHATSAPP_PKG)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            waInstalled = false
        }

        val marketResolvesToPlay = try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=${PlayWhatsAppCheck.WHATSAPP_PKG}")
            )
            val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName == PlayWhatsAppCheck.PLAY_PKG
        } catch (e: Exception) {
            null
        }

        return PlayWhatsAppFacts(
            playInstalled = playInstalled,
            gmsInstalled = gmsInstalled,
            gmsVersion = gmsVersion,
            whatsappInstalled = waInstalled,
            whatsappVersion = waVersion,
            whatsappInstaller = waInstaller,
            whatsappFirstInstall = waFirstInstall,
            marketResolvesToPlay = marketResolvesToPlay,
            listingReachable = null // filled in by checkListing() when online
        )
    }

    /**
     * Whether the WhatsApp listing opens on Google Play. Call off the main
     * thread. Returns true (listed), false (listing refused) or null (offline /
     * network error), so a report generated without internet stays honest.
     */
    fun checkListing(): Boolean? = try {
        val conn = URL(
            "https://play.google.com/store/apps/details?id=${PlayWhatsAppCheck.WHATSAPP_PKG}&hl=en"
        ).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)")
        val code = conn.responseCode
        conn.disconnect()
        code == 200
    } catch (e: Exception) {
        null
    }

    private fun hasPackage(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
