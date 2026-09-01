package com.stickervault.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream

/**
 * Serves sticker packs to WhatsApp.
 *
 * Column names and URI shapes below are WhatsApp's contract, copied verbatim
 * from its official sample. They are not ours to tidy: a renamed column does
 * not error, it just makes WhatsApp quietly ignore the pack.
 *
 * Note AVOID_CACHE is "whatsapp_will_not_cache_stickers" - an unguessable name,
 * and ignored by WhatsApp since 2.25.9.78 anyway. It is still emitted because
 * the cursor is expected to carry the column.
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var authority: String
    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        authority = PackStore.authority(ctx)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, METADATA, CODE_METADATA_ALL)
            addURI(authority, "$METADATA/*", CODE_METADATA_SINGLE)
            addURI(authority, "$STICKERS/*", CODE_STICKERS)
            addURI(authority, "$STICKERS_ASSET/*/*", CODE_STICKER_ASSET)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val packs = PackStore.load(ctx)

        return when (matcher.match(uri)) {
            CODE_METADATA_ALL -> metadataCursor(uri, packs)

            CODE_METADATA_SINGLE -> {
                val id = uri.lastPathSegment
                metadataCursor(uri, packs.filter { it.identifier == id })
            }

            CODE_STICKERS -> {
                val id = uri.lastPathSegment
                stickerCursor(uri, packs.firstOrNull { it.identifier == id })
            }

            else -> null
        }
    }

    private fun metadataCursor(uri: Uri, packs: List<StickerPackDef>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                STICKER_PACK_IDENTIFIER,
                STICKER_PACK_NAME,
                STICKER_PACK_PUBLISHER,
                STICKER_PACK_ICON,
                ANDROID_APP_DOWNLOAD_LINK,
                IOS_APP_DOWNLOAD_LINK,
                PUBLISHER_EMAIL,
                PUBLISHER_WEBSITE,
                PRIVACY_POLICY_WEBSITE,
                LICENSE_AGREEMENT_WEBSITE,
                IMAGE_DATA_VERSION,
                AVOID_CACHE,
                ANIMATED_STICKER_PACK,
            ),
        )
        packs.forEach { pack ->
            cursor.newRow()
                .add(pack.identifier)
                .add(pack.name)
                .add(pack.publisher)
                .add(pack.trayFile)
                .add("")
                .add("")
                .add("")
                .add("")
                .add("")
                .add("")
                .add(pack.imageDataVersion)
                .add(0)
                .add(if (pack.animated) 1 else 0)
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    private fun stickerCursor(uri: Uri, pack: StickerPackDef?): Cursor {
        val cursor = MatrixCursor(
            arrayOf(STICKER_FILE_NAME, STICKER_FILE_EMOJI, STICKER_FILE_ACCESSIBILITY_TEXT),
        )
        pack?.stickers?.forEach { sticker ->
            cursor.newRow()
                .add(sticker.fileName)
                .add(sticker.emojis.joinToString(","))
                .add(sticker.accessibilityText)
        }
        cursor.setNotificationUri(context!!.contentResolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != CODE_STICKER_ASSET) return null
        val ctx = context ?: return null

        val segments = uri.pathSegments
        if (segments.size != 3) return null
        val identifier = segments[1]
        val fileName = segments[2]

        val file = PackStore.resolveAsset(ctx, identifier, fileName) ?: return null
        // Last-ditch content check before a byte reaches WhatsApp: a .webp asset
        // must open with a RIFF/WEBP header, a .png tray icon with the PNG
        // signature. Everything here is written by PackBuilder, which validates
        // far more thoroughly - this guards only against on-disk corruption and
        // against a future code path that forgets to.
        if (!hasExpectedMagic(file, fileName)) return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0L, file.length())
    }

    private fun hasExpectedMagic(file: File, fileName: String): Boolean {
        val head = ByteArray(12)
        val read = runCatching {
            FileInputStream(file).use { stream ->
                var n = 0
                while (n < head.size) {
                    val r = stream.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                n
            }
        }.getOrDefault(0)
        if (read < 12) return false

        return if (fileName.endsWith(".png", ignoreCase = true)) {
            head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() &&
                head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte()
        } else {
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
                head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
                head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte() &&
                head[10] == 'B'.code.toByte() && head[11] == 'P'.code.toByte()
        }
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_METADATA_ALL -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        CODE_METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        CODE_STICKER_ASSET -> {
            if (uri.lastPathSegment?.endsWith(".png", true) == true) "image/png" else "image/webp"
        }
        else -> null
    }

    // Read-only by contract.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?,
    ): Int = 0

    companion object {
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"

        private const val CODE_METADATA_ALL = 1
        private const val CODE_METADATA_SINGLE = 2
        private const val CODE_STICKERS = 3
        private const val CODE_STICKER_ASSET = 4

        // Verbatim from WhatsApp's sample provider. Do not rename.
        const val STICKER_PACK_IDENTIFIER = "sticker_pack_identifier"
        const val STICKER_PACK_NAME = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER = "sticker_pack_publisher"
        const val STICKER_PACK_ICON = "sticker_pack_icon"
        const val ANDROID_APP_DOWNLOAD_LINK = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

        const val STICKER_FILE_NAME = "sticker_file_name"
        const val STICKER_FILE_EMOJI = "sticker_emoji"
        const val STICKER_FILE_ACCESSIBILITY_TEXT = "sticker_accessibility_text"
    }
}
