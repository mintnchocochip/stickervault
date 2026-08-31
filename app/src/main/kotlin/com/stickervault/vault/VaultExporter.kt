package com.stickervault.vault

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.stickervault.model.Compliance
import com.stickervault.model.StickerEntry
import com.stickervault.model.VaultSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the durable archive: content-addressed .webp files plus a manifest.
 *
 * The archive format is deliberately dumb - a flat folder of ordinary WebP files
 * any computer can open, and one JSON file describing them. If this app is ever
 * abandoned, or WhatsApp changes its sticker API, the collection is still just
 * files. Do not replace this with a proprietary container.
 */
class VaultExporter(private val context: Context) {

    data class Progress(val done: Int, val total: Int)

    data class ExportResult(
        val uri: Uri,
        val displayName: String,
        val summary: VaultSummary,
        val entries: List<StickerEntry>,
        val unreadableFiles: List<String>,
    )

    private data class Loaded(
        val file: StickerScanner.ScannedFile,
        val bytes: ByteArray?,
        val hash: String?,
    )

    private val resolver: ContentResolver get() = context.contentResolver

    suspend fun export(
        files: List<StickerScanner.ScannedFile>,
        onProgress: (Progress) -> Unit,
    ): ExportResult = withContext(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val displayName = "stickervault-" + stamp + ".zip"

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val target = resolver.insert(collection, values)
            ?: error("Could not create " + displayName + " in Downloads")

        val entries = mutableListOf<StickerEntry>()
        val seen = HashSet<String>()
        val unreadable = mutableListOf<String>()
        var duplicates = 0
        var totalBytes = 0L
        var done = 0

        try {
            val stream = resolver.openOutputStream(target)
                ?: error("Could not open " + displayName + " for writing")

            stream.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    // Reading through SAF costs an IPC round trip per file, so a
                    // serial loop spends nearly all its time waiting. Reading and
                    // hashing a batch concurrently turns minutes into far less;
                    // the zip itself must still be written serially.
                    for (chunk in files.chunked(READ_PARALLELISM)) {
                        val loaded = coroutineScope {
                            chunk.map { file ->
                                async(Dispatchers.IO) { load(file) }
                            }.awaitAll()
                        }

                        for (item in loaded) {
                            val bytes = item.bytes
                            val hash = item.hash

                            if (bytes == null || hash == null || bytes.isEmpty()) {
                                unreadable += item.file.name
                            } else if (!seen.add(hash)) {
                                // Identical bytes already archived. Four years of
                                // forwarded stickers produce a great many of these.
                                duplicates++
                            } else {
                                val info = WebpProbe.parse(bytes)
                                val entry = StickerEntry(
                                    sha256 = hash,
                                    sourceName = item.file.name,
                                    bytes = bytes.size,
                                    width = info?.width ?: 0,
                                    height = info?.height ?: 0,
                                    animated = info?.animated ?: false,
                                    hasAlpha = info?.hasAlpha ?: false,
                                    compliance = if (info == null) {
                                        Compliance.UNREADABLE
                                    } else {
                                        StickerEntry.classify(
                                            info.animated,
                                            info.width,
                                            info.height,
                                            bytes.size,
                                        )
                                    },
                                )

                                zip.putNextEntry(ZipEntry(entry.zipName))
                                zip.write(bytes)
                                zip.closeEntry()

                                entries += entry
                                totalBytes += bytes.size
                            }
                        }

                        done += chunk.size
                        onProgress(Progress(done, files.size))
                    }

                    zip.putNextEntry(ZipEntry("manifest.json"))
                    zip.write(
                        buildManifest(entries, files.size, duplicates, unreadable)
                            .toByteArray(Charsets.UTF_8),
                    )
                    zip.closeEntry()
                }
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(target, values, null, null)
        } catch (t: Throwable) {
            // Never leave a half-written archive sitting in Downloads looking
            // like a real backup. Losing the collection is the whole problem
            // this app exists to solve.
            runCatching { resolver.delete(target, null, null) }
            throw t
        }

        ExportResult(
            uri = target,
            displayName = displayName,
            summary = summarize(entries, files.size, duplicates, unreadable.size, totalBytes),
            entries = entries,
            unreadableFiles = unreadable,
        )
    }

    private fun load(file: StickerScanner.ScannedFile): Loaded {
        val bytes = runCatching {
            resolver.openInputStream(file.uri)?.use { it.readBytes() }
        }.getOrNull()
        return Loaded(file, bytes, bytes?.let { sha256Hex(it) })
    }

    private fun summarize(
        entries: List<StickerEntry>,
        filesSeen: Int,
        duplicates: Int,
        unreadableCount: Int,
        totalBytes: Long,
    ) = VaultSummary(
        filesSeen = filesSeen,
        unique = entries.size,
        duplicatesCollapsed = duplicates,
        animated = entries.count { it.animated },
        ok = entries.count { it.compliance == Compliance.OK },
        repairable = entries.count { it.compliance == Compliance.REPAIRABLE },
        vaultOnly = entries.count { it.compliance == Compliance.VAULT_ONLY },
        unreadable = entries.count { it.compliance == Compliance.UNREADABLE } + unreadableCount,
        totalBytes = totalBytes,
    )

    private fun buildManifest(
        entries: List<StickerEntry>,
        filesSeen: Int,
        duplicates: Int,
        unreadable: List<String>,
    ): String {
        val stickers = JSONArray()
        entries.forEach { e ->
            stickers.put(
                JSONObject().apply {
                    put("sha256", e.sha256)
                    put("file", e.sha256 + ".webp")
                    put("sourceName", e.sourceName)
                    put("bytes", e.bytes)
                    put("width", e.width)
                    put("height", e.height)
                    put("animated", e.animated)
                    put("hasAlpha", e.hasAlpha)
                    put("compliance", e.compliance.name)
                },
            )
        }

        val device = Build.MANUFACTURER + " " + Build.MODEL +
            " (Android " + Build.VERSION.RELEASE + ")"

        return JSONObject().apply {
            put("schema", 1)
            put("app", "StickerVault")
            put("exportedAt", isoNow())
            put("sourceDevice", device)
            put("filesSeen", filesSeen)
            put("duplicatesCollapsed", duplicates)
            put("unreadable", JSONArray(unreadable))
            put("stickers", stickers)
        }.toString(2)
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ", Locale.US).format(Date())

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

        /**
         * Files read concurrently per batch. Sized so a batch of stickers stays
         * a few megabytes in memory at most, while still keeping enough SAF
         * requests in flight to hide the IPC latency.
         */
        const val READ_PARALLELISM = 16
    }
}
