package com.stickervault.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * A sticker pack as WhatsApp expects to receive it.
 *
 * Field names here mirror WhatsApp's ContentProvider contract rather than our
 * own vocabulary, deliberately - a mismatch between this and the cursor columns
 * is silent and shows up only as a pack WhatsApp refuses to display.
 */
data class StickerDef(
    val fileName: String,
    /** 1-3 emoji. WhatsApp's only search lever for installed stickers. */
    val emojis: List<String>,
    val accessibilityText: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("fileName", fileName)
        put("emojis", JSONArray(emojis))
        put("accessibilityText", accessibilityText)
    }

    companion object {
        fun fromJson(o: JSONObject): StickerDef {
            val arr = o.optJSONArray("emojis") ?: JSONArray()
            return StickerDef(
                fileName = o.getString("fileName"),
                emojis = (0 until arr.length()).map { arr.getString(it) },
                accessibilityText = o.optString("accessibilityText", ""),
            )
        }
    }
}

data class StickerPackDef(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayFile: String,
    /**
     * Bump whenever the pack's bytes change, or WhatsApp keeps serving the old
     * assets. It always caches as of 2.25.9.78 - the opt-out is ignored.
     */
    val imageDataVersion: String,
    /** A pack is all-animated or all-static. Mixing is rejected. */
    val animated: Boolean,
    val stickers: List<StickerDef>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("identifier", identifier)
        put("name", name)
        put("publisher", publisher)
        put("trayFile", trayFile)
        put("imageDataVersion", imageDataVersion)
        put("animated", animated)
        put("stickers", JSONArray(stickers.map { it.toJson() }))
    }

    companion object {
        fun fromJson(o: JSONObject): StickerPackDef {
            val arr = o.optJSONArray("stickers") ?: JSONArray()
            return StickerPackDef(
                identifier = o.getString("identifier"),
                name = o.getString("name"),
                publisher = o.getString("publisher"),
                trayFile = o.getString("trayFile"),
                imageDataVersion = o.optString("imageDataVersion", "1"),
                animated = o.optBoolean("animated", false),
                stickers = (0 until arr.length()).map { StickerDef.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}
