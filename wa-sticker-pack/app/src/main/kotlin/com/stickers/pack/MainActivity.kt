package com.stickers.pack

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * One screen: a button per pack that asks WhatsApp to add it. The stickers are
 * served by [StickerContentProvider]; this just sends the public
 * ENABLE_STICKER_PACK intent WhatsApp listens for.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setPadding(0, 0, 0, (16 * resources.displayMetrics.density).toInt())
        })

        val authority = "$packageName.stickercontentprovider"
        for ((identifier, name) in readPacks()) {
            root.addView(Button(this).apply {
                text = getString(R.string.add_to_whatsapp, name)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { addToWhatsApp(authority, identifier, name) }
            })
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.footer)
            gravity = Gravity.CENTER
            setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun readPacks(): List<Pair<String, String>> = try {
        val json = assets.open("contents.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val arr = JSONObject(json).getJSONArray("sticker_packs")
        (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            p.getString("identifier") to p.getString("name")
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun addToWhatsApp(authority: String, identifier: String, name: String) {
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", identifier)
            putExtra("sticker_pack_authority", authority)
            putExtra("sticker_pack_name", name)
        }
        try {
            startActivityForResult(intent, 200)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_whatsapp), Toast.LENGTH_LONG).show()
        }
    }
}
