package com.stickervault.provider

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Everything that talks to WhatsApp itself.
 *
 * Requires the <queries> entries in the manifest: since Android 11, package
 * visibility rules hide other apps by default, and without those declarations
 * both the whitelist query and the add-pack intent fail as though WhatsApp were
 * not installed at all.
 */
object WhatsAppLink {

    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"

    const val ACTION_ENABLE = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    private const val EXTRA_ID = "sticker_pack_id"
    private const val EXTRA_AUTHORITY = "sticker_pack_authority"
    private const val EXTRA_NAME = "sticker_pack_name"

    /** WhatsApp explains a rejected pack through this result extra. */
    const val EXTRA_VALIDATION_ERROR = "validation_error"

    private const val WHITELIST_QUERY = "is_whitelisted"
    private const val WHITELIST_RESULT_COLUMN = "result"

    data class Diagnostics(
        val authority: String,
        val consumerInstalled: Boolean,
        val businessInstalled: Boolean,
        val consumerResolves: Boolean,
        val businessResolves: Boolean,
    ) {
        val anyResolves: Boolean get() = consumerResolves || businessResolves
    }

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    fun installedTargets(context: Context): List<String> =
        listOf(CONSUMER, BUSINESS).filter { isInstalled(context, it) }

    private fun baseIntent(context: Context, pack: StickerPackDef): Intent =
        Intent(ACTION_ENABLE).apply {
            putExtra(EXTRA_ID, pack.identifier)
            putExtra(EXTRA_AUTHORITY, PackStore.authority(context))
            putExtra(EXTRA_NAME, pack.name)
        }

    /** Can this package actually handle the add-pack intent? */
    fun resolves(context: Context, pkg: String): Boolean {
        val probe = Intent(ACTION_ENABLE).setPackage(pkg)
        return context.packageManager.resolveActivity(probe, 0) != null
    }

    fun diagnostics(context: Context): Diagnostics = Diagnostics(
        authority = PackStore.authority(context),
        consumerInstalled = isInstalled(context, CONSUMER),
        businessInstalled = isInstalled(context, BUSINESS),
        consumerResolves = resolves(context, CONSUMER),
        businessResolves = resolves(context, BUSINESS),
    )

    /**
     * Builds the add-pack intent the way WhatsApp's own sample does.
     *
     * The target package must be set explicitly when only one WhatsApp variant
     * is present. An action-only implicit intent does not reach it under
     * Android 11+ package visibility, and the failure is silent - the intent
     * simply resolves to nothing.
     *
     * @return null when no WhatsApp variant can handle it, so the caller can say
     *   so rather than starting an activity that will never appear.
     */
    fun addPackIntent(context: Context, pack: StickerPackDef): Intent? {
        val targets = listOf(CONSUMER, BUSINESS)
            .filter { isInstalled(context, it) && resolves(context, it) }

        return when (targets.size) {
            0 -> null
            1 -> baseIntent(context, pack).setPackage(targets[0])
            else -> Intent.createChooser(
                baseIntent(context, pack),
                "Add sticker pack to",
            )
        }
    }

    /**
     * Asks WhatsApp whether it currently has this pack installed.
     *
     * This is how the pack-count ceiling gets measured rather than guessed: add
     * more packs than the documented maximum of 10, then ask WhatsApp which ones
     * actually stuck.
     *
     * @return true/false per WhatsApp, or null if the question could not be
     *   asked at all (WhatsApp absent, provider missing, query refused).
     */
    fun isWhitelisted(context: Context, packId: String, pkg: String = CONSUMER): Boolean? {
        val uri = Uri.parse("content://$pkg.provider.sticker_whitelist_check/$WHITELIST_QUERY")
            .buildUpon()
            .appendQueryParameter("authority", PackStore.authority(context))
            .appendQueryParameter("identifier", packId)
            .build()

        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val idx = c.getColumnIndex(WHITELIST_RESULT_COLUMN)
                if (idx < 0) null else c.getInt(idx) == 1
            }
        }.getOrNull()
    }
}
