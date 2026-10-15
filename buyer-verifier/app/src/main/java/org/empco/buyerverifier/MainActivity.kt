package org.empco.buyerverifier

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var claimRam: EditText
    private lateinit var claimStorage: EditText
    private lateinit var claimScreen: EditText
    private lateinit var claimAndroid: EditText
    private lateinit var claimImei: EditText
    private lateinit var usbModel: EditText
    private lateinit var btnCheck: Button
    private lateinit var btnUsb: Button
    private lateinit var btnPdf: Button
    private lateinit var btnShare: Button
    private lateinit var preview: TextView

    private var facts: DeviceFacts? = null
    private var selfFindings: List<Finding> = emptyList()
    private var playFindings: List<Finding> = emptyList()
    private var usbFindings: List<Finding> = emptyList()
    private var lastReport: String? = null
    private var lastPdf: File? = null

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var usbManager: UsbManager
    private var usbReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        claimRam = findViewById(R.id.claimRam)
        claimStorage = findViewById(R.id.claimStorage)
        claimScreen = findViewById(R.id.claimScreen)
        claimAndroid = findViewById(R.id.claimAndroid)
        claimImei = findViewById(R.id.claimImei)
        usbModel = findViewById(R.id.usbModel)
        btnCheck = findViewById(R.id.btnCheck)
        btnUsb = findViewById(R.id.btnUsb)
        btnPdf = findViewById(R.id.btnPdf)
        btnShare = findViewById(R.id.btnShare)
        preview = findViewById(R.id.preview)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        btnCheck.setOnClickListener { runChecks() }
        btnUsb.setOnClickListener { scanUsb() }
        btnPdf.setOnClickListener { generatePdf() }
        btnShare.setOnClickListener { sharePdf() }

        registerUsbReceiver()
        preview.text = getString(R.string.preview_initial)
    }

    private fun readClaims() = Claims(
        ramGb = claimRam.text.toString().trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
        storageGb = claimStorage.text.toString().trim().takeIf { it.isNotEmpty() }?.toLongOrNull(),
        screen = claimScreen.text.toString().trim().ifEmpty { null },
        androidVersion = claimAndroid.text.toString().trim().ifEmpty { null },
        imei = claimImei.text.toString().trim().ifEmpty { null }
    )

    private fun runChecks() {
        val claims = readClaims()
        btnCheck.isEnabled = false
        preview.text = "Running checks…"
        io.execute {
            val reader = DeviceFactsReader(applicationContext)
            val deviceFacts = reader.read()
            val playReader = PlayWhatsAppReader(applicationContext)
            val play = playReader.read().copy(listingReachable = playReader.checkListing())
            val self = SelfCheck.evaluate(deviceFacts, claims)
            val playEval = PlayWhatsAppCheck.evaluate(play)
            runOnUiThread {
                facts = deviceFacts
                selfFindings = self
                playFindings = playEval
                btnCheck.isEnabled = true
                rebuildPreview()
                Toast.makeText(
                    this,
                    "Check complete — ${VerdictEngine.from(allFindings()).title}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun allFindings(): List<Finding> = selfFindings + playFindings + usbFindings

    private fun meta() = ReportMeta(
        generatedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
        appVersion = BuildConfig.VERSION_NAME
    )

    private fun rebuildPreview() {
        val f = facts
        if (f == null) {
            preview.text = buildString {
                if (usbFindings.isNotEmpty()) {
                    append("USB-OTG scan\n")
                    usbFindings.forEach { append("  ${it.tag()} ${it.label}: ${it.observed}\n") }
                }
                append("\nRun the device check to produce the full report.")
            }
            return
        }
        val report = ReportBuilder.build(f, allFindings(), meta())
        lastReport = report
        preview.text = colorize(report)
        btnPdf.isEnabled = true
    }

    private fun colorize(text: String): CharSequence {
        val ssb = SpannableStringBuilder(text)
        val regex = Regex("(\\[FAIL\\]|\\[WARN\\]|\\[ OK \\]|\\[INFO\\])")
        for (m in regex.findAll(text)) {
            val color = when (m.value) {
                "[FAIL]" -> Color.rgb(190, 30, 30)
                "[WARN]" -> Color.rgb(210, 110, 0)
                "[ OK ]" -> Color.rgb(0, 120, 70)
                else -> Color.rgb(70, 70, 70)
            }
            ssb.setSpan(ForegroundColorSpan(color), m.range.first, m.range.last + 1, 0)
        }
        return ssb
    }

    private fun registerUsbReceiver() {
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    scanUsb()
                } else {
                    Toast.makeText(context, R.string.usb_denied, Toast.LENGTH_LONG).show()
                }
            }
        }
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION))
    }

    private fun scanUsb() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            Toast.makeText(this, R.string.no_usb_host, Toast.LENGTH_LONG).show()
            return
        }
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            preview.text = getString(R.string.usb_none)
            return
        }

        val missing = devices.firstOrNull { !usbManager.hasPermission(it) }
        if (missing != null) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), flags
            )
            usbManager.requestPermission(missing, pi)
            // the receiver fires and rescans once permission is granted
        }

        val expected = usbModel.text.toString().trim().ifEmpty { null }
        val descriptors = devices.map { d ->
            UsbDescriptor(
                vid = d.vendorId,
                pid = d.productId,
                manufacturer = runCatching { d.manufacturerName }.getOrNull(),
                product = runCatching { d.productName }.getOrNull(),
                serial = if (usbManager.hasPermission(d)) {
                    runCatching { d.serialNumber }.getOrNull()
                } else {
                    null
                }
            )
        }
        usbFindings = descriptors.flatMap { UsbScanRules.evaluate(it, expected) }
        rebuildPreview()
    }

    private fun generatePdf() {
        val f = facts
        if (f == null || lastReport == null) {
            Toast.makeText(this, R.string.run_check_first, Toast.LENGTH_SHORT).show()
            return
        }
        btnPdf.isEnabled = false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "BuyerVerifier_${sanitize(f.model.ifBlank { "device" })}_$stamp.pdf"
        val target = File(File(getExternalFilesDir(null), "reports"), name)
        val report = lastReport!!
        io.execute {
            val written = PdfReport.write(applicationContext, report, target)
            runOnUiThread {
                lastPdf = written
                btnPdf.isEnabled = true
                btnShare.isEnabled = true
                Toast.makeText(
                    this,
                    getString(R.string.pdf_saved, written.absolutePath),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun sharePdf() {
        val file = lastPdf
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.run_check_first, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.pdf_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40)

    override fun onDestroy() {
        super.onDestroy()
        usbReceiver?.let { runCatching { unregisterReceiver(it) } }
        io.shutdown()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "org.empco.buyerverifier.USB_PERMISSION"
    }
}
