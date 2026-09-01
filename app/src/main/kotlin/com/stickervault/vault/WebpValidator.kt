package com.stickervault.vault

/**
 * Strict structural validation of a WebP file, for the one place it matters:
 * the bytes about to be handed to WhatsApp.
 *
 * [WebpProbe] is a deliberately permissive sniffer - it reads a handful of
 * header bytes so a library scan can label dimensions, and it treats anything
 * it does not recognise as "not a WebP" rather than erroring. That is the right
 * trade for scanning thousands of files, but it is far too weak to be a
 * security boundary: a 30-byte blob of `RIFF....WEBPVP8X` plus padding
 * satisfies it, and such a file would previously be copied verbatim into a pack
 * and served to WhatsApp's WebP decoder - the exact shape of input that
 * CVE-2023-4863 (libwebp) was exploited through.
 *
 * This validator instead walks the entire RIFF chunk list and rejects anything
 * that is not internally consistent:
 *
 *  - the RIFF size field must agree with the actual byte count;
 *  - every chunk header must be in bounds and its declared size must not overrun
 *    the file - a truncated or lying chunk is rejected, not read past;
 *  - the chunk walk must consume the whole file, so trailing garbage is caught;
 *  - a real image bitstream must be present: `VP8 `/`VP8L` for a still, and
 *    `ANIM` + at least one `ANMF` for an animation;
 *  - the extended-format header must be exactly 10 bytes and its animation flag
 *    must match the chunks actually present;
 *  - announced metadata chunks (EXIF/XMP/ICCP) must actually be present, and
 *    vice versa;
 *  - canvas dimensions must be within WebP's documented range.
 *
 * It does not decode the bitstream - callers that can afford to (PackBuilder)
 * additionally run the platform decoder. Structure first, because it is cheap
 * and catches the crafted-blob case without feeding anything to libwebp.
 *
 * Keep this in step with tools/verify_webp_validator.py by hand.
 *
 * Reference: https://developers.google.com/speed/webp/docs/riff_container
 */
object WebpValidator {

    sealed interface Result {
        data class Valid(
            val width: Int,
            val height: Int,
            val animated: Boolean,
            val hasAlpha: Boolean,
        ) : Result

        data class Invalid(val reason: String) : Result
    }

    /** RIFF header (12) + one chunk header (8) + at least one payload byte. */
    private const val MIN_SIZE = 21

    /** VP8 and VP8L canvas fields are 14-bit; the VP8X canvas is 24-bit. */
    private const val MAX_DIMENSION_SIMPLE = 1 shl 14
    private const val MAX_DIMENSION_EXTENDED = 1 shl 24

    fun isValid(bytes: ByteArray): Boolean = validate(bytes) is Result.Valid

    fun validate(bytes: ByteArray): Result {
        if (bytes.size < MIN_SIZE) return Result.Invalid("file too small (${bytes.size} bytes)")
        if (!tag(bytes, 0, "RIFF")) return Result.Invalid("missing RIFF signature")
        if (!tag(bytes, 8, "WEBP")) return Result.Invalid("missing WEBP signature")

        // The RIFF size counts every byte after the 8-byte RIFF/size prefix. A
        // well-formed encoder - Android's and Pillow's included - sets this
        // exactly; tolerate only a single omitted trailing pad byte.
        val riffSize = le32(bytes, 4)
        val expected = bytes.size.toLong() - 8
        if (riffSize != expected && riffSize != expected - 1) {
            return Result.Invalid("RIFF size $riffSize does not match file (${bytes.size} bytes)")
        }

        // --- walk every chunk, checking bounds at each step ---
        val present = mutableListOf<String>()
        var i = 12
        val limit = bytes.size
        while (i + 8 <= limit) {
            val fourcc = ascii(bytes, i, 4)
                ?: return Result.Invalid("non-ASCII chunk id at offset $i")
            val size = le32(bytes, i + 4)
            if (i + 8 + size > limit) {
                return Result.Invalid("chunk '$fourcc' size $size overruns file")
            }
            present += fourcc
            // Chunks are padded to an even length.
            i += (8 + size + (size and 1L)).toInt()
        }
        // The walk must land exactly at the end (a missing final pad byte is the
        // one tolerated slack). Anything else is trailing or truncated data.
        if (i != limit && i != limit + 1) {
            return Result.Invalid("chunk walk ended at $i, expected $limit")
        }
        if (present.isEmpty()) return Result.Invalid("no chunks")

        return when (val first = present.first()) {
            "VP8 ", "VP8L" -> validateSimple(bytes, first, present)
            "VP8X" -> validateExtended(bytes, present)
            else -> Result.Invalid("first chunk is '$first', not a WebP bitstream")
        }
    }

    /** Simple (non-extended) form: exactly one bitstream chunk, no metadata. */
    private fun validateSimple(b: ByteArray, kind: String, present: List<String>): Result {
        if (present.size != 1) {
            return Result.Invalid("simple-format WebP has extra chunks: $present")
        }
        return when (kind) {
            "VP8L" -> {
                // Payload: signature byte 0x2F, then 32 LE bits packing
                // width-1 (14), height-1 (14), alpha (1), version (3).
                if (b.size < 25) return Result.Invalid("VP8L chunk truncated")
                if (u8(b, 20) != 0x2F) return Result.Invalid("bad VP8L signature byte")
                val bits = le32(b, 21)
                val w = (bits and 0x3FFF).toInt() + 1
                val h = ((bits shr 14) and 0x3FFF).toInt() + 1
                if (w !in 1..MAX_DIMENSION_SIMPLE || h !in 1..MAX_DIMENSION_SIMPLE) {
                    return Result.Invalid("implausible VP8L dimensions ${w}x$h")
                }
                Result.Valid(w, h, animated = false, hasAlpha = ((bits shr 28) and 1L) != 0L)
            }

            else -> { // "VP8 "
                if (b.size < 30) return Result.Invalid("VP8 chunk truncated")
                if (u8(b, 23) != 0x9D || u8(b, 24) != 0x01 || u8(b, 25) != 0x2A) {
                    return Result.Invalid("bad VP8 start code")
                }
                val w = le16(b, 26) and 0x3FFF
                val h = le16(b, 28) and 0x3FFF
                if (w !in 1 until MAX_DIMENSION_SIMPLE || h !in 1 until MAX_DIMENSION_SIMPLE) {
                    return Result.Invalid("implausible VP8 dimensions ${w}x$h")
                }
                Result.Valid(w, h, animated = false, hasAlpha = false)
            }
        }
    }

    /**
     * Extended (`VP8X`) form. The 10-byte header carries feature flags and the
     * canvas size; the real image data follows as separate chunks whose presence
     * must match those flags.
     */
    private fun validateExtended(b: ByteArray, present: List<String>): Result {
        if (b.size < 30) return Result.Invalid("VP8X header truncated")
        val vp8xSize = le32(b, 16)
        if (vp8xSize != 10L) return Result.Invalid("VP8X chunk size is $vp8xSize, must be 10")

        val flags = u8(b, 20)
        // Bits 0, 6 and 7 are reserved and must be zero.
        if (flags and 0xC1 != 0) return Result.Invalid("VP8X reserved flag bits set")
        val animated = flags and 0x02 != 0
        val hasAlpha = flags and 0x10 != 0
        val flagExif = flags and 0x08 != 0
        val flagXmp = flags and 0x04 != 0
        val flagIcc = flags and 0x20 != 0

        val w = le24(b, 24) + 1
        val h = le24(b, 27) + 1
        if (w !in 1..MAX_DIMENSION_EXTENDED || h !in 1..MAX_DIMENSION_EXTENDED) {
            return Result.Invalid("implausible canvas ${w}x$h")
        }

        val has = { id: String -> present.contains(id) }
        val anmf = present.count { it == "ANMF" }
        val stills = present.count { it == "VP8 " || it == "VP8L" }

        if (animated) {
            if (!has("ANIM")) return Result.Invalid("animation flag set but no ANIM chunk")
            if (anmf == 0) return Result.Invalid("animation flag set but no ANMF frames")
            if (stills > 0) return Result.Invalid("animated WebP has a top-level still bitstream")
        } else {
            if (stills != 1) {
                return Result.Invalid("expected exactly one still bitstream, found $stills")
            }
            if (has("ANIM") || anmf > 0) {
                return Result.Invalid("still WebP carries animation chunks")
            }
        }

        // A separate ALPH chunk carries alpha only for a lossy still; VP8L and
        // animation frames carry it internally.
        if (hasAlpha && !animated && has("VP8 ") && !has("ALPH")) {
            return Result.Invalid("alpha flag set on a lossy still with no ALPH chunk")
        }
        // A chunk announced but absent - or present but unannounced - is a
        // malformed header. Decoders disagree on which side to trust; refuse it.
        if (has("EXIF") != flagExif) return Result.Invalid("EXIF chunk/flag mismatch")
        if (has("XMP ") != flagXmp) return Result.Invalid("XMP chunk/flag mismatch")
        if (has("ICCP") != flagIcc) return Result.Invalid("ICCP chunk/flag mismatch")

        return Result.Valid(w, h, animated, hasAlpha)
    }

    private fun tag(b: ByteArray, off: Int, s: String): Boolean {
        if (b.size < off + 4) return false
        for (i in 0 until 4) if ((b[off + i].toInt() and 0xFF) != s[i].code) return false
        return true
    }

    private fun ascii(b: ByteArray, off: Int, len: Int): String? {
        val sb = StringBuilder(len)
        for (k in 0 until len) {
            val c = b[off + k].toInt() and 0xFF
            if (c < 0x20 || c > 0x7E) return null
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int) =
        u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)

    /** Unsigned 32-bit little-endian, widened so it is never negative. */
    private fun le32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 0xFF) or
            ((b[i + 1].toLong() and 0xFF) shl 8) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or
            ((b[i + 3].toLong() and 0xFF) shl 24)
}
