package com.stickervault.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Extracts a vault archive into the app's private storage.
 *
 * The archive is untrusted input - it arrives from the user's storage, may have
 * crossed a cloud drive and another device, and nothing guarantees this app
 * produced it. Every defence below exists for a reason:
 *
 *  - **Path traversal (Zip Slip).** Entry names are matched against a strict
 *    allowlist and the resulting file is confined to one flat directory. A name
 *    like `../../databases/x` never reaches the filesystem.
 *  - **Declared sizes are not trusted.** `ZipEntry.size` is attacker-controlled
 *    metadata; actual bytes read are counted instead, and capped.
 *  - **Zip bombs.** Per-entry, total-bytes and entry-count ceilings, plus a
 *    free-space check before anything is written.
 *  - **Content integrity.** Files are content-addressed, so a sticker whose
 *    bytes do not hash to its own filename is corrupt or tampered with and is
 *    rejected. This is a stronger guarantee than a checksum file, because the
 *    name *is* the checksum.
 *  - **Type confusion.** Every accepted entry must parse as a real WebP before
 *    it is written, so a renamed executable or HTML file cannot land in storage.
 *  - **Untrusted metadata.** Pack names and emoji come from EXIF inside the
 *    files; they are length-capped and never used to build a path.
 */
class VaultImporter(private val context: Context) {

    data class Progress(val imported: Int, val bytesWritten: Long)

    data class Result(
        val imported: Int,
        val alreadyPresent: Int,
        val rejected: Int,
        val rejectionSamples: List<String>,
        val bytesWritten: Long,
    )

    class ImportException(message: String) : Exception(message)

    suspend fun import(
        zipUri: Uri,
        onProgress: (Progress) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val target = LibraryStore.dir(context)
        checkFreeSpace(zipUri, target)

        val existing = LibraryStore.load(context).associateBy { it.sha256 }.toMutableMap()
        val rejections = mutableListOf<String>()
        var imported = 0
        var alreadyPresent = 0
        var rejected = 0
        var totalBytes = 0L
        var entryCount = 0

        // Populated from manifest.json, which our exporter writes last.
        var sourceNames: Map<String, String> = emptyMap()

        val stream = context.contentResolver.openInputStream(zipUri)
            ?: throw ImportException("Could not open the selected file.")

        stream.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRIES) {
                        throw ImportException("Archive has too many entries.")
                    }

                    val name = entry.name
                    try {
                        when {
                            entry.isDirectory -> Unit

                            name == MANIFEST -> {
                                val bytes = readBounded(zip, MAX_MANIFEST_BYTES, name)
                                sourceNames = parseSourceNames(bytes)
                            }

                            else -> {
                                val sha = stickerHashOrNull(name)
                                if (sha == null) {
                                    rejected++
                                    if (rejections.size < MAX_SAMPLES) {
                                        rejections += "unexpected entry: ${name.take(60)}"
                                    }
                                } else {
                                    val bytes = readBounded(zip, MAX_ENTRY_BYTES, name)
                                    totalBytes += bytes.size
                                    if (totalBytes > MAX_TOTAL_BYTES) {
                                        throw ImportException("Archive is unreasonably large.")
                                    }

                                    when (val outcome = accept(sha, bytes, target, existing)) {
                                        Outcome.Imported -> {
                                            imported++
                                            onProgress(Progress(imported, totalBytes))
                                        }

                                        Outcome.Present -> alreadyPresent++
                                        is Outcome.Rejected -> {
                                            rejected++
                                            if (rejections.size < MAX_SAMPLES) {
                                                rejections += outcome.reason
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        runCatching { zip.closeEntry() }
                    }
                }
            }
        }

        // Fold in the original filenames, which carry the received date. Purely
        // cosmetic, so a missing or malformed manifest is not fatal.
        val merged = existing.values.map { e ->
            val src = sourceNames[e.sha256]
            if (src != null && e.sourceName == null) e.copy(sourceName = src) else e
        }
        LibraryStore.save(context, merged)

        Result(imported, alreadyPresent, rejected, rejections, totalBytes)
    }

    private sealed interface Outcome {
        data object Imported : Outcome
        data object Present : Outcome
        data class Rejected(val reason: String) : Outcome
    }

    private fun accept(
        sha: String,
        bytes: ByteArray,
        target: File,
        index: MutableMap<String, LibraryEntry>,
    ): Outcome {
        // The filename is the checksum, so this verifies integrity outright.
        if (sha256Hex(bytes) != sha) {
            return Outcome.Rejected("content does not match its hash: ${sha.take(12)}…")
        }

        // Must be a structurally sound WebP, not something renamed to look like
        // one and not a crafted blob with plausible header bytes. This is the
        // gate that keeps malformed content out of the library and therefore out
        // of anything later handed to WhatsApp.
        val info = when (val v = WebpValidator.validate(bytes)) {
            is WebpValidator.Result.Invalid ->
                return Outcome.Rejected("malformed WebP (${v.reason}): ${sha.take(12)}…")
            is WebpValidator.Result.Valid -> v
        }

        if (index.containsKey(sha) && File(target, "$sha.webp").isFile) {
            return Outcome.Present
        }

        val meta = runCatching { RiffWebp.readMeta(bytes) }.getOrDefault(RiffWebp.EMPTY)

        // Confined to one flat directory, named from validated hex only.
        val out = File(target, "$sha.webp")
        val tmp = File(target, "$sha.part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) {
            tmp.delete()
            return Outcome.Rejected("could not write ${sha.take(12)}…")
        }

        index[sha] = LibraryEntry(
            sha256 = sha,
            bytes = bytes.size,
            width = info.width,
            height = info.height,
            animated = info.animated,
            packName = meta.packName,
            publisher = meta.publisher,
            emojis = meta.emojis,
            sourceName = null,
        )
        return Outcome.Imported
    }

    /**
     * Reads one entry with a hard ceiling, counting real bytes rather than
     * trusting the size the archive declares.
     */
    private fun readBounded(input: InputStream, limit: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > limit) {
                throw ImportException("Entry is far larger than expected: ${name.take(60)}")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Accepts only `stickers/<64 hex>.webp`, returning the hash.
     *
     * Because the pattern is anchored and hex-only, a traversal sequence, an
     * absolute path, a backslash or a control character cannot match - the
     * filename we later build is a validated hash and nothing else.
     */
    private fun stickerHashOrNull(entryName: String): String? {
        // Reject control characters before pattern matching. A trailing newline
        // is a classic filter bypass, because in most regex flavours `$` matches
        // just before a final newline - so "…webp\n" can slip past a pattern
        // that looks anchored. matchEntire below should already refuse it, but a
        // security boundary should not rest on that subtlety.
        if (entryName.length > MAX_ENTRY_NAME) return null
        if (entryName.any { it.code < 0x20 || it.code == 0x7F }) return null

        val m = STICKER_ENTRY.matchEntire(entryName) ?: return null
        return m.groupValues[1]
    }

    private fun parseSourceNames(bytes: ByteArray): Map<String, String> = runCatching {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val arr = root.optJSONArray("stickers") ?: return@runCatching emptyMap()
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sha = o.optString("sha256")
                val src = o.optString("sourceName")
                if (LibraryStore.isSha256(sha) && src.isNotBlank()) {
                    put(sha, src.take(MAX_NAME_FIELD))
                }
            }
        }
    }.getOrDefault(emptyMap())

    private fun checkFreeSpace(zipUri: Uri, target: File) {
        val declared = runCatching {
            context.contentResolver.query(zipUri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
            } ?: -1L
        }.getOrDefault(-1L)

        if (declared <= 0) return // Unknown size; the running total still caps it.

        // Sticker archives barely compress (WebP is already compressed), so the
        // zip size is a good estimate of what extraction will need.
        val needed = declared + (declared / 5)
        val free = target.freeSpace
        if (free in 1 until needed) {
            throw ImportException(
                "Not enough free space. Needs about ${needed / 1_000_000} MB, " +
                    "${free / 1_000_000} MB available.",
            )
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private companion object {
        val HEX = "0123456789abcdef".toCharArray()
        val STICKER_ENTRY = Regex("""^stickers/([0-9a-f]{64})\.webp$""")

        const val MANIFEST = "manifest.json"

        const val MAX_ENTRIES = 200_000
        /** Animated stickers cap at 500KB; this leaves generous headroom. */
        const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
        const val MAX_SAMPLES = 8
        const val MAX_ENTRY_NAME = 512
        const val MAX_NAME_FIELD = 128
    }
}
