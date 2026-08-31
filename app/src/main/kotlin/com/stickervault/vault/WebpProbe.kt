package com.stickervault.vault

/**
 * Reads WebP dimensions, animation and alpha flags directly from the RIFF
 * container, without decoding the image.
 *
 * Decoding every file would be unusably slow and memory-hungry across a library
 * of thousands of stickers; every fact we need lives in the first ~30 bytes.
 *
 * Container layout:
 *   0..3    "RIFF"
 *   4..7    file size - 8 (LE)
 *   8..11   "WEBP"
 *   12..15  first chunk FourCC, one of "VP8 " (lossy), "VP8L" (lossless),
 *           or "VP8X" (extended - the only form that can be animated)
 *
 * Reference: https://developers.google.com/speed/webp/docs/riff_container
 */
object WebpProbe {

    /** Enough bytes to satisfy every branch below. */
    const val HEADER_BYTES = 32

    data class Info(
        val width: Int,
        val height: Int,
        val animated: Boolean,
        val hasAlpha: Boolean,
    )

    fun parse(b: ByteArray): Info? {
        if (b.size < 16) return null
        if (!tagEquals(b, 0, "RIFF")) return null
        if (!tagEquals(b, 8, "WEBP")) return null

        return when {
            tagEquals(b, 12, "VP8X") -> parseExtended(b)
            tagEquals(b, 12, "VP8L") -> parseLossless(b)
            tagEquals(b, 12, "VP8 ") -> parseLossy(b)
            else -> null
        }
    }

    /**
     * Extended format. Payload starts at offset 20:
     *   byte 0      flags: bit5 ICC, bit4 alpha, bit3 EXIF, bit2 XMP, bit1 animation
     *   bytes 1..3  reserved
     *   bytes 4..6  canvas width  - 1 (24-bit LE)
     *   bytes 7..9  canvas height - 1 (24-bit LE)
     */
    private fun parseExtended(b: ByteArray): Info? {
        if (b.size < 30) return null
        val flags = u8(b, 20)
        return Info(
            width = le24(b, 24) + 1,
            height = le24(b, 27) + 1,
            animated = flags and 0x02 != 0,
            hasAlpha = flags and 0x10 != 0,
        )
    }

    /**
     * Lossless. Payload starts at 20 with signature byte 0x2F, then a 32-bit LE
     * field packing width-1 (14 bits), height-1 (14 bits), alpha flag, version.
     */
    private fun parseLossless(b: ByteArray): Info? {
        if (b.size < 25) return null
        if (u8(b, 20) != 0x2F) return null
        val bits = le32(b, 21)
        return Info(
            width = ((bits and 0x3FFFL).toInt()) + 1,
            height = (((bits shr 14) and 0x3FFFL).toInt()) + 1,
            animated = false,
            hasAlpha = ((bits shr 28) and 0x1L) != 0L,
        )
    }

    /**
     * Simple lossy. Payload starts at 20: a 3-byte frame tag, the 3-byte start
     * code 9D 01 2A, then 16-bit LE width and height (low 14 bits significant).
     */
    private fun parseLossy(b: ByteArray): Info? {
        if (b.size < 30) return null
        if (u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) return null
        return Info(
            width = le16(b, 26) and 0x3FFF,
            height = le16(b, 28) and 0x3FFF,
            animated = false,
            hasAlpha = false,
        )
    }

    private fun tagEquals(b: ByteArray, off: Int, tag: String): Boolean {
        if (b.size < off + 4) return false
        for (i in 0 until 4) if (u8(b, off + i) != tag[i].code) return false
        return true
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun le32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or
            (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or
            (u8(b, i + 3).toLong() shl 24)
}
