package com.stickervault.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.stickervault.model.WhatsAppLimits
import com.stickervault.provider.PackStore
import com.stickervault.provider.StickerDef
import com.stickervault.provider.StickerPackDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Turns chosen library stickers into packs WhatsApp will accept.
 *
 * WhatsApp's rules are unforgiving and it reports violations only as a refusal,
 * so everything is enforced here rather than discovered later: exactly 512x512,
 * static under 100KB, animated under 500KB, 3-30 stickers, never mixing static
 * and animated in one pack, and a 96x96 PNG tray icon per pack.
 */
object PackBuilder {

    data class Progress(val done: Int, val total: Int)

    data class BuildResult(
        val packs: List<StickerPackDef>,
        val skipped: List<String>,
    )

    /**
     * @param groupsFirst when true, stickers keep their recovered pack grouping
     *   and only overflow is split. Fragments too small to form a pack are
     *   merged together rather than dropped.
     */
    suspend fun build(
        context: Context,
        entries: List<LibraryEntry>,
        publisher: String = "StickerVault",
        groupsFirst: Boolean = true,
        onProgress: (Progress) -> Unit = {},
    ): BuildResult = withContext(Dispatchers.IO) {
        PackStore.clear(context)

        val usable = mutableListOf<LibraryEntry>()
        val skipped = mutableListOf<String>()

        entries.forEach { e ->
            // Animated stickers cannot be re-encoded on Android, so an oversized
            // one can never be made to fit. Say so instead of failing silently.
            if (e.animated && e.bytes > WhatsAppLimits.MAX_ANIMATED_BYTES) {
                if (skipped.size < MAX_SKIP_SAMPLES) {
                    skipped += "${e.sha256.take(8)}… animated and over 500KB"
                }
            } else {
                usable += e
            }
        }

        val buckets = bucket(usable, groupsFirst)
        val packs = mutableListOf<StickerPackDef>()
        var done = 0
        val total = buckets.sumOf { it.entries.size }

        buckets.forEachIndexed { index, bucket ->
            coroutineContext.ensureActive()
            val identifier = identifierFor(bucket.name, index)
            val dir = PackStore.packDir(context, identifier).apply { mkdirs() }

            val stickers = mutableListOf<StickerDef>()
            bucket.entries.forEach { entry ->
                val source = LibraryStore.fileFor(context, entry.sha256)
                if (source == null) {
                    if (skipped.size < MAX_SKIP_SAMPLES) {
                        skipped += "${entry.sha256.take(8)}… missing from library"
                    }
                } else {
                    val fileName = "${entry.sha256.take(24)}.webp"
                    val written = materialise(entry, source, File(dir, fileName))
                    if (written) {
                        stickers += StickerDef(
                            fileName = fileName,
                            emojis = entry.emojis.take(3).ifEmpty { listOf(DEFAULT_EMOJI) },
                            accessibilityText = entry.packName.orEmpty().take(120),
                        )
                    } else if (skipped.size < MAX_SKIP_SAMPLES) {
                        skipped += "${entry.sha256.take(8)}… could not be resized to fit"
                    }
                }
                done++
                onProgress(Progress(done, total))
            }

            if (stickers.size >= WhatsAppLimits.MIN_STICKERS_PER_PACK) {
                val tray = File(dir, TRAY_FILE)
                val traySource = File(dir, stickers.first().fileName)
                if (writeTray(traySource, tray)) {
                    packs += StickerPackDef(
                        identifier = identifier,
                        name = bucket.name.take(MAX_NAME).ifBlank { "Stickers" },
                        publisher = publisher,
                        trayFile = TRAY_FILE,
                        imageDataVersion = System.currentTimeMillis().toString(),
                        animated = bucket.animated,
                        stickers = stickers,
                    )
                } else {
                    dir.deleteRecursively()
                    skipped += "${bucket.name}: tray icon could not be made"
                }
            } else {
                // Below WhatsApp's minimum of 3; nothing to serve.
                dir.deleteRecursively()
            }
        }

        PackStore.save(context, packs)
        BuildResult(packs, skipped)
    }

    private data class Bucket(
        val name: String,
        val animated: Boolean,
        val entries: List<LibraryEntry>,
    )

    /**
     * Splits into pack-sized buckets. Static and animated never share a pack,
     * and leftovers from small groups are pooled so they still reach the
     * three-sticker minimum instead of being discarded.
     */
    private fun bucket(entries: List<LibraryEntry>, groupsFirst: Boolean): List<Bucket> {
        val out = mutableListOf<Bucket>()
        val leftovers = mutableMapOf<Boolean, MutableList<LibraryEntry>>()

        val grouped = if (groupsFirst) {
            entries.groupBy { it.group }
        } else {
            mapOf("Stickers" to entries)
        }

        grouped.forEach { (name, list) ->
            list.groupBy { it.animated }.forEach { (animated, sameKind) ->
                sameKind.chunked(WhatsAppLimits.MAX_STICKERS_PER_PACK).forEach { chunk ->
                    if (chunk.size >= WhatsAppLimits.MIN_STICKERS_PER_PACK) {
                        out += Bucket(name, animated, chunk)
                    } else {
                        leftovers.getOrPut(animated) { mutableListOf() } += chunk
                    }
                }
            }
        }

        leftovers.forEach { (animated, list) ->
            list.chunked(WhatsAppLimits.MAX_STICKERS_PER_PACK)
                .filter { it.size >= WhatsAppLimits.MIN_STICKERS_PER_PACK }
                .forEachIndexed { i, chunk ->
                    val label = if (animated) "Mixed animated" else "Mixed"
                    out += Bucket("$label ${i + 1}", animated, chunk)
                }
        }

        return out.take(WhatsAppLimits.MAX_PACKS_PER_APP.coerceAtLeast(out.size))
    }

    /** Copies a compliant sticker as-is, or repairs a static one to fit. */
    private fun materialise(entry: LibraryEntry, source: File, target: File): Boolean {
        val compliant = entry.width == WhatsAppLimits.STICKER_DIMENSION &&
            entry.height == WhatsAppLimits.STICKER_DIMENSION &&
            entry.bytes <= if (entry.animated) {
                WhatsAppLimits.MAX_ANIMATED_BYTES
            } else {
                WhatsAppLimits.MAX_STATIC_BYTES
            }

        if (compliant && copyIfWellFormed(entry, source, target)) return true

        // Re-encoding an animated WebP would flatten it to a single frame, and
        // Android ships no animated-WebP encoder - a malformed animated sticker
        // cannot be salvaged, so refuse it rather than serve it broken.
        if (entry.animated) return false

        return runCatching { resizeStatic(source, target) }.getOrDefault(false)
    }

    /**
     * Copies a sticker verbatim only if its bytes are a structurally sound WebP
     * of the expected shape *and* the platform decoder accepts them.
     *
     * The vault archive is untrusted input, and "it is 512x512 and small
     * enough" - all the previous compliant path checked, from metadata recorded
     * at import - is not a guarantee the file is real WebP. The bytes handed to
     * WhatsApp's decoder must be validated here, against the file on disk, right
     * before it is served. A file that fails falls through to the re-encode
     * path (static) or is dropped (animated).
     */
    private fun copyIfWellFormed(entry: LibraryEntry, source: File, target: File): Boolean {
        val bytes = runCatching { source.readBytes() }.getOrNull() ?: return false
        if (bytes.size != entry.bytes) return false

        val valid = WebpValidator.validate(bytes) as? WebpValidator.Result.Valid ?: return false
        if (valid.width != WhatsAppLimits.STICKER_DIMENSION ||
            valid.height != WhatsAppLimits.STICKER_DIMENSION ||
            valid.animated != entry.animated
        ) {
            return false
        }
        val budget = if (entry.animated) {
            WhatsAppLimits.MAX_ANIMATED_BYTES
        } else {
            WhatsAppLimits.MAX_STATIC_BYTES
        }
        if (bytes.size > budget) return false

        // Exercise the real WebP decoder. For an animation this decodes the
        // first frame; combined with the structural ANIM/ANMF checks in
        // WebpValidator that is as far as validation can go without an animated
        // decoder on the device.
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return false
        val okDims = decoded.width == WhatsAppLimits.STICKER_DIMENSION &&
            decoded.height == WhatsAppLimits.STICKER_DIMENSION
        decoded.recycle()
        if (!okDims) return false

        return runCatching { source.copyTo(target, overwrite = true) }.isSuccess
    }

    /**
     * Fits a static sticker to exactly 512x512, preserving aspect and
     * transparency by padding rather than stretching, then steps quality down
     * until it is inside WhatsApp's byte budget.
     */
    private fun resizeStatic(source: File, target: File): Boolean {
        val decoded = BitmapFactory.decodeFile(source.path) ?: return false
        val side = WhatsAppLimits.STICKER_DIMENSION
        val canvasBitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)

        val scale = minOf(side.toFloat() / decoded.width, side.toFloat() / decoded.height)
        val w = decoded.width * scale
        val h = decoded.height * scale
        val left = (side - w) / 2f
        val top = (side - h) / 2f

        canvas.drawBitmap(
            decoded,
            Rect(0, 0, decoded.width, decoded.height),
            RectF(left, top, left + w, top + h),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        decoded.recycle()

        val ok = compressWithin(
            canvasBitmap,
            target,
            WhatsAppLimits.MAX_STATIC_BYTES,
            Bitmap.CompressFormat.WEBP_LOSSY,
        )
        canvasBitmap.recycle()
        if (!ok) return false

        // The encoder should always produce a clean file, but these bytes are
        // about to go to WhatsApp - verify rather than assume.
        val out = runCatching { target.readBytes() }.getOrNull()
        val check = out?.let { WebpValidator.validate(it) } as? WebpValidator.Result.Valid
        if (check == null || check.width != side || check.height != side || check.animated) {
            target.delete()
            return false
        }
        return true
    }

    private fun writeTray(source: File, target: File): Boolean {
        val decoded = BitmapFactory.decodeFile(source.path) ?: return false
        val side = WhatsAppLimits.TRAY_DIMENSION
        val scaled = Bitmap.createScaledBitmap(decoded, side, side, true)
        decoded.recycle()
        // Tray icons must be PNG, never WebP.
        val ok = compressWithin(
            scaled,
            target,
            WhatsAppLimits.MAX_TRAY_BYTES,
            Bitmap.CompressFormat.PNG,
        )
        scaled.recycle()
        return ok
    }

    private fun compressWithin(
        bitmap: Bitmap,
        target: File,
        budget: Int,
        format: Bitmap.CompressFormat,
    ): Boolean {
        val qualities = if (format == Bitmap.CompressFormat.PNG) {
            intArrayOf(100)
        } else {
            intArrayOf(95, 85, 75, 60, 45, 30, 20)
        }
        for (q in qualities) {
            val wrote = runCatching {
                target.outputStream().use { bitmap.compress(format, q, it) }
            }.getOrDefault(false)
            if (!wrote) return false
            if (target.length() <= budget) return true
        }
        target.delete()
        return false
    }

    /**
     * Identifiers may contain only a-z, A-Z, 0-9, underscore, hyphen, dot and
     * space. Rather than sanitise a user-visible name into a path-like value,
     * this derives a fixed safe token - names arrive from file metadata this app
     * did not author.
     */
    private fun identifierFor(name: String, index: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray())
        val hex = StringBuilder()
        for (i in 0 until 6) {
            val v = digest[i].toInt() and 0xFF
            hex.append("0123456789abcdef"[v ushr 4])
            hex.append("0123456789abcdef"[v and 0x0F])
        }
        return "pack_%03d_%s".format(index, hex)
    }

    const val TRAY_FILE = "tray.png"
    private const val DEFAULT_EMOJI = "😀"
    private const val MAX_NAME = 96
    private const val MAX_SKIP_SAMPLES = 10
}
