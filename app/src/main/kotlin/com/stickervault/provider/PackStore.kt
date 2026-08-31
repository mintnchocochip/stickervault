package com.stickervault.provider

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * On-disk home for the packs we serve to WhatsApp.
 *
 * Everything here must work from a cold process. WhatsApp queries our
 * ContentProvider when our app is not running - Android spawns the process,
 * hits the provider, and expects an answer. So this reads straight from disk
 * and must never depend on an Activity, a ViewModel, Compose, or anything
 * initialised in onCreate. That coupling is the single likeliest way to ship a
 * provider that works while the app is open and fails the moment it is not.
 *
 * Layout:
 *   filesDir/packs.json          index of every pack
 *   filesDir/packs/<id>/tray.png tray icon (PNG, 96x96, <=50KB)
 *   filesDir/packs/<id>/NAME.webp the stickers
 */
object PackStore {

    fun packsDir(context: Context): File =
        File(context.filesDir, "packs").apply { mkdirs() }

    fun packDir(context: Context, identifier: String): File =
        File(packsDir(context), identifier)

    private fun indexFile(context: Context): File =
        File(context.filesDir, "packs.json")

    @Volatile
    private var cachedStamp: Long = -1

    @Volatile
    private var cached: List<StickerPackDef> = emptyList()

    /**
     * Reads the index, re-parsing only when the file has actually changed.
     * WhatsApp queries repeatedly, so parsing on every call would be wasteful,
     * but holding a stale list would serve packs that no longer exist.
     */
    fun load(context: Context): List<StickerPackDef> {
        val file = indexFile(context)
        if (!file.exists()) return emptyList()

        val stamp = file.lastModified() xor (file.length() shl 1)
        cached.let { current ->
            if (stamp == cachedStamp && current.isNotEmpty()) return current
        }

        val parsed = runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { StickerPackDef.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        cachedStamp = stamp
        cached = parsed
        return parsed
    }

    fun save(context: Context, packs: List<StickerPackDef>) {
        val arr = JSONArray(packs.map { it.toJson() })
        indexFile(context).writeText(arr.toString())
        // Force the next load to re-read rather than trust the cache.
        cachedStamp = -1
        cached = emptyList()
    }

    fun clear(context: Context) {
        packsDir(context).deleteRecursively()
        indexFile(context).delete()
        cachedStamp = -1
        cached = emptyList()
    }

    /**
     * Resolves an asset path, refusing anything that escapes the pack directory.
     * The provider is exported, so its inputs are untrusted.
     */
    fun resolveAsset(context: Context, identifier: String, fileName: String): File? {
        val root = packsDir(context).canonicalFile
        val target = File(File(root, identifier), fileName).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) return null
        return if (target.isFile) target else null
    }

    fun authority(context: Context): String =
        context.packageName + ".stickercontentprovider"
}
