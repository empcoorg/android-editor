package com.stickers.pack

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONObject

/**
 * Serves a WhatsApp sticker pack to WhatsApp, following the public sticker-app
 * contract (github.com/WhatsApp/stickers). The pack is defined by
 * assets/contents.json and the WebP files beside it; this provider only reads
 * them. Generic: no pack content is hard-coded here.
 */
class StickerContentProvider : ContentProvider() {

    data class Sticker(val fileName: String, val emojis: List<String>, val accessibility: String)
    data class Pack(
        val identifier: String,
        val name: String,
        val publisher: String,
        val trayImageFile: String,
        val imageDataVersion: String,
        val avoidCache: Boolean,
        val animated: Boolean,
        val publisherEmail: String,
        val publisherWebsite: String,
        val privacyPolicyWebsite: String,
        val licenseAgreementWebsite: String,
        val stickers: List<Sticker>,
    )

    private lateinit var authority: String
    private val matcher = UriMatcher(UriMatcher.NO_MATCH)
    private var packs: List<Pack> = emptyList()
    private var androidLink: String = ""
    private var iosLink: String = ""

    override fun onCreate(): Boolean {
        authority = ctx().packageName + ".stickercontentprovider"
        matcher.addURI(authority, METADATA, CODE_METADATA_ALL)
        matcher.addURI(authority, "$METADATA/*", CODE_METADATA_ONE)
        matcher.addURI(authority, "$STICKERS/*", CODE_STICKERS)
        matcher.addURI(authority, "$STICKERS_ASSET/*/*", CODE_ASSET)
        loadContents()
        return true
    }

    private fun ctx() = context ?: throw IllegalStateException("no context")

    private fun loadContents() {
        val json = ctx().assets.open("contents.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(json)
        androidLink = root.optString("android_play_store_link", "")
        iosLink = root.optString("ios_app_store_link", "")
        val arr = root.getJSONArray("sticker_packs")
        packs = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val stickersArr = p.getJSONArray("stickers")
            val stickers = (0 until stickersArr.length()).map { j ->
                val s = stickersArr.getJSONObject(j)
                val emojiArr = s.optJSONArray("emojis")
                val emojis = if (emojiArr != null) (0 until emojiArr.length()).map { emojiArr.getString(it) } else emptyList()
                Sticker(s.getString("image_file"), emojis, s.optString("accessibility_text", ""))
            }
            Pack(
                identifier = p.getString("identifier"),
                name = p.getString("name"),
                publisher = p.getString("publisher"),
                trayImageFile = p.getString("tray_image_file"),
                imageDataVersion = p.optString("image_data_version", "1"),
                avoidCache = p.optBoolean("avoid_cache", false),
                animated = p.optBoolean("animated_sticker_pack", false),
                publisherEmail = p.optString("publisher_email", ""),
                publisherWebsite = p.optString("publisher_website", ""),
                privacyPolicyWebsite = p.optString("privacy_policy_website", ""),
                licenseAgreementWebsite = p.optString("license_agreement_website", ""),
                stickers = stickers,
            )
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        when (matcher.match(uri)) {
            CODE_METADATA_ALL -> metadataCursor(uri, packs)
            CODE_METADATA_ONE -> metadataCursor(uri, packs.filter { it.identifier == uri.lastPathSegment })
            CODE_STICKERS -> stickersCursor(uri)
            else -> null
        }

    private fun metadataCursor(uri: Uri, which: List<Pack>): Cursor {
        val cols = arrayOf(
            "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher",
            "sticker_pack_icon", "android_play_store_link", "ios_app_download_link",
            "sticker_pack_publisher_email", "sticker_pack_publisher_website",
            "sticker_pack_privacy_policy_website", "sticker_pack_license_agreement_website",
            "image_data_version", "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
        )
        val c = MatrixCursor(cols)
        for (p in which) {
            c.addRow(arrayOf<Any>(
                p.identifier, p.name, p.publisher, p.trayImageFile, androidLink, iosLink,
                p.publisherEmail, p.publisherWebsite, p.privacyPolicyWebsite, p.licenseAgreementWebsite,
                p.imageDataVersion, if (p.avoidCache) 1 else 0, if (p.animated) 1 else 0,
            ))
        }
        c.setNotificationUri(ctx().contentResolver, uri)
        return c
    }

    private fun stickersCursor(uri: Uri): Cursor {
        val id = uri.lastPathSegment
        val pack = packs.firstOrNull { it.identifier == id }
        val c = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        pack?.stickers?.forEach { s ->
            c.addRow(arrayOf<Any>(s.fileName, s.emojis.joinToString(","), s.accessibility))
        }
        c.setNotificationUri(ctx().contentResolver, uri)
        return c
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != CODE_ASSET) return null
        val segments = uri.pathSegments
        if (segments.size != 3) return null
        val identifier = segments[1]
        val fileName = segments[2]
        if (packs.none { it.identifier == identifier }) return null
        return ctx().assets.openFd("$identifier/$fileName")
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        CODE_METADATA_ALL -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        CODE_METADATA_ONE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        CODE_ASSET -> "image/webp"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    // Read-only provider.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"
        private const val CODE_METADATA_ALL = 1
        private const val CODE_METADATA_ONE = 2
        private const val CODE_STICKERS = 3
        private const val CODE_ASSET = 4
    }
}
