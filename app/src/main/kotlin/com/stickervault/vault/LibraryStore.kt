package com.stickervault.vault

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One sticker in the imported library.
 *
 * Pack fields come from the WebP's embedded EXIF, so grouping survives even
 * though WhatsApp's own database is unreachable without root.
 */
data class LibraryEntry(
    val sha256: String,
    val bytes: Int,
    val width: Int,
    val height: Int,
    val animated: Boolean,
    val packName: String?,
    val publisher: String?,
    val emojis: List<String>,
    val sourceName: String?,
) {
    val fileName: String get() = "$sha256.webp"

    /** WhatsApp names received stickers STK-YYYYMMDD-WA####.webp. */
    val receivedOn: String?
        get() {
            val n = sourceName ?: return null
            val m = STK_DATE.find(n) ?: return null
            val (y, mo, d) = m.destructured
            return "$y-$mo-$d"
        }

    val group: String get() = packName ?: UNGROUPED

    fun toJson(): JSONObject = JSONObject().apply {
        put("sha256", sha256)
        put("bytes", bytes)
        put("width", width)
        put("height", height)
        put("animated", animated)
        packName?.let { put("packName", it) }
        publisher?.let { put("publisher", it) }
        if (emojis.isNotEmpty()) put("emojis", JSONArray(emojis))
        sourceName?.let { put("sourceName", it) }
    }

    companion object {
        const val UNGROUPED = "Ungrouped"
        private val STK_DATE = Regex("""STK-(\d{4})(\d{2})(\d{2})-""")

        fun fromJson(o: JSONObject): LibraryEntry {
            val arr = o.optJSONArray("emojis")
            return LibraryEntry(
                sha256 = o.getString("sha256"),
                bytes = o.optInt("bytes"),
                width = o.optInt("width"),
                height = o.optInt("height"),
                animated = o.optBoolean("animated"),
                packName = o.optString("packName").ifBlank { null },
                publisher = o.optString("publisher").ifBlank { null },
                emojis = if (arr == null) emptyList() else {
                    (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
                },
                sourceName = o.optString("sourceName").ifBlank { null },
            )
        }
    }
}

/** A recovered pack: the stickers that shared an embedded pack name. */
data class LibraryGroup(
    val name: String,
    val entries: List<LibraryEntry>,
)

/**
 * The imported sticker library on disk.
 *
 * Files are content-addressed, which makes imports idempotent and makes
 * integrity self-verifying: a file whose bytes do not hash to its own name is
 * corrupt or tampered with, and is rejected on the way in.
 */
object LibraryStore {

    fun dir(context: Context): File =
        File(context.filesDir, "library").apply { mkdirs() }

    fun fileFor(context: Context, sha256: String): File? {
        if (!isSha256(sha256)) return null
        val f = File(dir(context), "$sha256.webp")
        return if (f.isFile) f else null
    }

    private fun indexFile(context: Context): File = File(context.filesDir, "library.json")

    fun load(context: Context): List<LibraryEntry> {
        val f = indexFile(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { LibraryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, entries: List<LibraryEntry>) {
        indexFile(context).writeText(JSONArray(entries.map { it.toJson() }).toString())
    }

    fun clear(context: Context) {
        dir(context).deleteRecursively()
        indexFile(context).delete()
    }

    fun totalBytes(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Groups by recovered pack name, largest first, with ungrouped stickers last
     * however many there are.
     */
    fun group(entries: List<LibraryEntry>): List<LibraryGroup> =
        entries.groupBy { it.group }
            .map { (name, list) -> LibraryGroup(name, list) }
            .sortedWith(
                compareBy<LibraryGroup> { it.name == LibraryEntry.UNGROUPED }
                    .thenByDescending { it.entries.size },
            )

    fun isSha256(s: String): Boolean =
        s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }
}
