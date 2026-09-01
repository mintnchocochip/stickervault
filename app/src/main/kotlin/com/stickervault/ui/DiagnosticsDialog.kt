package com.stickervault.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.stickervault.vault.PackDiagnostics

/**
 * The hazard-triangle diagnostics sheet. Runs [PackDiagnostics] over whatever
 * packs are currently on disk, shows what would keep WhatsApp from accepting
 * them, and offers a one-tap "report on GitHub" that also copies the full
 * report to the clipboard. Debug builds only - the caller gates on
 * BuildConfig.DEBUG.
 */
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var report by remember { mutableStateOf<PackDiagnostics.Report?>(null) }
    var running by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val r = runCatching { PackDiagnostics.run(context) }.getOrNull()
        report = r
        failed = r == null
        running = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pack diagnostics") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Checking every served sticker…")
                    }

                    failed || report == null -> Text("Diagnostics could not run.")

                    else -> ReportBody(report!!)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = report != null,
                onClick = {
                    val r = report ?: return@TextButton
                    clipboard.setText(AnnotatedString(PackDiagnostics.reportText(r)))
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(PackDiagnostics.issueUrl(r)))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            ) { Text("Report on GitHub") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ReportBody(report: PackDiagnostics.Report) {
    val summary = when {
        report.packs.isEmpty() -> "No packs built yet."
        report.rejectedByWhatsApp -> "WhatsApp rejected at least one pack."
        report.allClean -> "All packs pass validation."
        else -> "Some stickers would be refused."
    }
    Text(summary, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.size(4.dp))
    Text(
        "Provider: ${report.providerAuthority}\n" +
            "WhatsApp reachable: ${report.whatsAppReachable}",
        style = MaterialTheme.typography.bodySmall,
    )

    report.packs.forEach { pack ->
        Spacer(Modifier.size(12.dp))
        Text(pack.name, style = MaterialTheme.typography.titleSmall)
        val head = buildString {
            append(if (pack.animated) "animated" else "static")
            append(" · ${pack.stickerCount} stickers · tray ")
            append(if (pack.trayOk) "ok" else "FAIL")
            append(" · in WhatsApp: ")
            append(
                when (pack.whitelisted) {
                    true -> "yes"
                    false -> "no"
                    null -> "unknown"
                },
            )
        }
        Text(head, style = MaterialTheme.typography.bodySmall)
        if (!pack.trayOk) {
            Text("tray: ${pack.trayDetail}", style = MaterialTheme.typography.bodySmall)
        }
        if (pack.failing.isEmpty()) {
            Text("all stickers valid", style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                pack.failing.take(12).forEach {
                    Text("• ${it.fileName}: ${it.detail}", style = MaterialTheme.typography.bodySmall)
                }
                if (pack.failing.size > 12) {
                    Text("…and ${pack.failing.size - 12} more", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
