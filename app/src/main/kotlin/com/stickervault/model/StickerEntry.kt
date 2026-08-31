package com.stickervault.model

/**
 * WhatsApp's published limits for third-party sticker packs.
 * Source: https://github.com/WhatsApp/stickers/blob/main/Android/README.md
 */
object WhatsAppLimits {
    const val STICKER_DIMENSION = 512
    const val MAX_STATIC_BYTES = 100 * 1024
    const val MAX_ANIMATED_BYTES = 500 * 1024

    const val TRAY_DIMENSION = 96
    const val MAX_TRAY_BYTES = 50 * 1024

    const val MIN_STICKERS_PER_PACK = 3
    const val MAX_STICKERS_PER_PACK = 30

    /**
     * Documented as 1..10. Whether WhatsApp actually *enforces* this at runtime
     * is unverified - see Phase 0 recon. If it turns out to be advisory, this
     * ceiling disappears and one app install can hold the whole library.
     */
    const val MAX_PACKS_PER_APP = 10

    const val MAX_STICKERS_PER_APP = MAX_PACKS_PER_APP * MAX_STICKERS_PER_PACK
}

enum class Compliance {
    /** Meets every WhatsApp rule already; can be packed untouched. */
    OK,

    /** Static but wrong size or over budget. Phase 2 can resize/recompress it. */
    REPAIRABLE,

    /**
     * Animated and non-compliant. Android ships no animated-WebP encoder, so
     * this cannot be repaired on-device. It is still exported and kept in the
     * vault - it just cannot be pushed into WhatsApp.
     */
    VAULT_ONLY,

    /** Not a WebP we could parse. Exported verbatim so nothing is ever lost. */
    UNREADABLE,
}

data class StickerEntry(
    val sha256: String,
    val sourceName: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val hasAlpha: Boolean,
    val compliance: Compliance,
) {
    /** Name used inside the zip. Content-addressed, so dedupe is inherent. */
    val zipName: String get() = "stickers/$sha256.webp"

    companion object {
        fun classify(animated: Boolean, width: Int, height: Int, bytes: Int): Compliance {
            val dimensionsOk = width == WhatsAppLimits.STICKER_DIMENSION &&
                height == WhatsAppLimits.STICKER_DIMENSION
            val budget = if (animated) WhatsAppLimits.MAX_ANIMATED_BYTES
            else WhatsAppLimits.MAX_STATIC_BYTES

            return when {
                dimensionsOk && bytes <= budget -> Compliance.OK
                animated -> Compliance.VAULT_ONLY
                else -> Compliance.REPAIRABLE
            }
        }
    }
}

/** Summary shown after a scan or export. */
data class VaultSummary(
    val filesSeen: Int,
    val unique: Int,
    val duplicatesCollapsed: Int,
    val animated: Int,
    val ok: Int,
    val repairable: Int,
    val vaultOnly: Int,
    val unreadable: Int,
    val totalBytes: Long,
)
