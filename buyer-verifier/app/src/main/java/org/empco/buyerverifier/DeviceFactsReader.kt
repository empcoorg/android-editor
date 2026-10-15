package org.empco.buyerverifier

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.app.ActivityManager
import kotlin.math.roundToInt

/**
 * Collects [DeviceFacts] on the device: build/product properties via
 * [PropSource] (getprop) plus platform APIs for hardware stats. Deliberately
 * reads only identity, build and hardware values — nothing about how the
 * system is currently running.
 */
class DeviceFactsReader(
    private val context: Context,
    private val props: PropSource = SystemPropSource()
) {

    fun read(): DeviceFacts {
        fun p(name: String): String? = props.get(name)

        val marketName = p("ro.product.marketname")
            ?: p("ro.product.system.marketname")
            ?: p("ro.product.vendor.marketname")

        val statFs = StatFs(Environment.getDataDirectory().path)
        val totalBytes = statFs.totalBytes
        val freeBytes = statFs.availableBytes

        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val cameraIds = try {
            (context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager)?.cameraIdList?.size
        } catch (e: Exception) {
            null
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val ramGb = memInfo.totalMem
            .takeIf { it > 0L }
            ?.let { (it.toDouble() / (1024.0 * 1024 * 1024) * 10).roundToInt() / 10.0 }

        val metrics = context.resources.displayMetrics
        val gmsVersion = p("ro.com.google.gmsversion")

        return DeviceFacts(
            marketName = marketName,
            brand = p("ro.product.brand") ?: Build.BRAND,
            manufacturer = p("ro.product.manufacturer") ?: Build.MANUFACTURER,
            model = p("ro.product.model") ?: Build.MODEL,
            device = p("ro.product.device") ?: Build.DEVICE,
            board = p("ro.board.platform"),
            hardware = p("ro.hardware") ?: Build.HARDWARE,
            buildType = p("ro.build.type"),
            buildTags = p("ro.build.tags"),
            debuggable = p("ro.debuggable"),
            secure = p("ro.secure"),
            fingerprint = p("ro.build.fingerprint") ?: Build.FINGERPRINT,
            vendorFingerprint = p("ro.vendor.build.fingerprint"),
            odmFingerprint = p("ro.odm.build.fingerprint"),
            productFingerprint = p("ro.product.build.fingerprint"),
            bootFingerprint = p("ro.bootimage.build.fingerprint"),
            androidRelease = Build.VERSION.RELEASE,
            securityPatch = p("ro.build.version.security_patch") ?: Build.VERSION.SECURITY_PATCH,
            buildDate = p("ro.build.date"),
            buildUser = p("ro.build.user"),
            buildHost = p("ro.build.host"),
            serial = (p("ro.serialno") ?: p("ro.boot.serialno"))
                ?.takeIf { it.isNotBlank() && it != "unknown" },
            ramGb = ramGb,
            cores = Runtime.getRuntime().availableProcessors(),
            storageTotalGb = totalBytes / (1024L * 1024 * 1024),
            storageFreeGb = freeBytes / (1024L * 1024 * 1024),
            screenResolution = "${metrics.widthPixels}x${metrics.heightPixels}",
            densityDpi = metrics.densityDpi,
            gyroscope = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
            compass = sensors?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) != null,
            cameraCount = cameraIds
        )
    }

    fun hasPackage(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
