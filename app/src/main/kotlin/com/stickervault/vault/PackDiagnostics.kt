package com.stickervault.vault

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.stickervault.model.WhatsAppLimits
import com.stickervault.provider.PackStore
import com.stickervault.provider.StickerPackDef
import com.stickervault.provider.WhatsAppLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-demand checks over the packs currently being served to WhatsApp.
 *
 * The strict validation in [WebpValidator] and [PackBuilder] already runs on
 * every build, silently: a sticker that would not pass never reaches a pack, so
 * there is no reason to make a tester sit through a report before every
 * transfer. This re-runs the same checks against the files on disk *now*, and
 * additionally asks WhatsApp whether it actually accepted each pack - so a
 * "WhatsApp won't show my pack" report has something concrete attached.
 *
 * Debug builds only; wired to the hazard-triangle action in the top bar.
 */
object PackDiagnostics {

    data class StickerCheck(val fileName: String, val ok: Boolean, val detail: String)

    data class PackReport(
        val identifier: String,
        val name: String,
        val animated: Boolean,
        val stickerCount: Int,
        val trayOk: Boolean,
        val trayDetail: String,
        val stickers: List<StickerCheck>,
        /** WhatsApp's own answer: true installed, false rejected, null unknown. */
        val whitelisted: Boolean?,
    ) {
        val failing: List<StickerCheck> get() = stickers.filter { !it.ok }
        val servedCleanly: Boolean get() = trayOk && failing.isEmpty()
    }

    data class Report(
        val providerAuthority: String,
        val whatsAppReachable: Boolean,
        val packs: List<PackReport>,
    ) {
        val allClean: Boolean get() = packs.all { it.servedCleanly }
        val rejectedByWhatsApp: Boolean get() = packs.any { it.whitelisted == false }
    }

    suspend fun run(context: Context): Report = withContext(Dispatchers.IO) {
        val diag = WhatsAppLink.diagnostics(context)
        val packs = PackStore.load(context).map { inspect(context, it) }
        Report(
            providerAuthority = diag.authority,
            whatsAppReachable = diag.anyResolves,
            packs = packs,
        )
    }

    private fun inspect(context: Context, pack: StickerPackDef): PackReport {
        val dir = PackStore.packDir(context, pack.identifier)
        val (trayOk, trayDetail) = checkTray(File(dir, pack.trayFile))
        val stickers = pack.stickers.map { checkSticker(File(dir, it.fileName), pack.animated) }
        val whitelisted = runCatching {
            WhatsAppLink.isWhitelisted(context, pack.identifier)
        }.getOrNull()

        return PackReport(
            identifier = pack.identifier,
            name = pack.name,
            animated = pack.animated,
            stickerCount = pack.stickers.size,
            trayOk = trayOk,
            trayDetail = trayDetail,
            stickers = stickers,
            whitelisted = whitelisted,
        )
    }

    /** Exactly the gate PackBuilder applies, re-run against the file on disk. */
    private fun checkSticker(file: File, animated: Boolean): StickerCheck {
        val name = file.name
        if (!file.isFile) return StickerCheck(name, false, "missing from disk")
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return StickerCheck(name, false, "unreadable")

        val budget = if (animated) {
            WhatsAppLimits.MAX_ANIMATED_BYTES
        } else {
            WhatsAppLimits.MAX_STATIC_BYTES
        }
        if (bytes.size > budget) {
            return StickerCheck(name, false, "${bytes.size / 1024}KB, over the ${budget / 1024}KB limit")
        }

        val valid = when (val v = WebpValidator.validate(bytes)) {
            is WebpValidator.Result.Invalid -> return StickerCheck(name, false, "malformed WebP: ${v.reason}")
            is WebpValidator.Result.Valid -> v
        }
        if (valid.width != WhatsAppLimits.STICKER_DIMENSION ||
            valid.height != WhatsAppLimits.STICKER_DIMENSION
        ) {
            return StickerCheck(name, false, "${valid.width}x${valid.height}, not 512x512")
        }
        if (valid.animated != animated) {
            val what = if (valid.animated) "animated file in a static pack" else "static file in an animated pack"
            return StickerCheck(name, false, what)
        }

        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return StickerCheck(name, false, "the WebP decoder rejected it")
        val dims = decoded.width to decoded.height
        decoded.recycle()
        if (dims != WhatsAppLimits.STICKER_DIMENSION to WhatsAppLimits.STICKER_DIMENSION) {
            return StickerCheck(name, false, "decodes to ${dims.first}x${dims.second}")
        }
        return StickerCheck(name, true, "${bytes.size / 1024}KB, 512x512")
    }

    private fun checkTray(file: File): Pair<Boolean, String> {
        if (!file.isFile) return false to "tray.png missing"
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return false to "tray unreadable"
        val isPng = bytes.size > 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        if (!isPng) return false to "tray is not a PNG"
        if (bytes.size > WhatsAppLimits.MAX_TRAY_BYTES) {
            return false to "tray ${bytes.size / 1024}KB, over ${WhatsAppLimits.MAX_TRAY_BYTES / 1024}KB"
        }
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return false to "tray does not decode"
        val ok = decoded.width == WhatsAppLimits.TRAY_DIMENSION &&
            decoded.height == WhatsAppLimits.TRAY_DIMENSION
        val dims = decoded.width to decoded.height
        decoded.recycle()
        return if (ok) {
            true to "PNG, 96x96, ${bytes.size / 1024}KB"
        } else {
            false to "tray ${dims.first}x${dims.second}, not 96x96"
        }
    }

    /** Plain-text form of the report, for the clipboard and the issue body. */
    fun reportText(report: Report): String = buildString {
        appendLine("StickerVault diagnostics")
        appendLine("Provider authority: ${report.providerAuthority}")
        appendLine("WhatsApp reachable: ${report.whatsAppReachable}")
        appendLine("Packs served: ${report.packs.size}")
        appendLine()
        report.packs.forEach { p ->
            appendLine("## ${p.name}  (${p.identifier})")
            appendLine("${if (p.animated) "animated" else "static"} · ${p.stickerCount} stickers")
            appendLine("tray: ${p.trayDetail}")
            appendLine("in WhatsApp: ${p.whitelisted ?: "could not ask"}")
            if (p.failing.isEmpty()) {
                appendLine("all stickers pass validation")
            } else {
                appendLine("${p.failing.size} failing:")
                p.failing.take(25).forEach { appendLine("  - ${it.fileName}: ${it.detail}") }
            }
            appendLine()
        }
        appendLine(
            "Device: Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    /**
     * A prefilled "new issue" URL. The app declares no INTERNET permission; this
     * string is handed to the browser through ACTION_VIEW, which does the
     * network. GitHub truncates an over-long querystring, so the full report is
     * also copied to the clipboard by the caller.
     */
    fun issueUrl(report: Report): String {
        val title = Uri.encode("Sticker pack not accepted by WhatsApp")
        val body = Uri.encode(reportText(report).take(MAX_ISSUE_BODY))
        return "$ISSUES_URL/new?title=$title&body=$body"
    }

    const val ISSUES_URL = "https://github.com/mintnchocochip/stickervault/issues"
    private const val MAX_ISSUE_BODY = 6000
}
