package com.stickervault.ui

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.stickervault.model.VaultSummary
import com.stickervault.vault.StickerScanner

/**
 * Thumbnails rendered on the confirmation screen. The real folder can hold five
 * figures of stickers; the grid is only there to prove we found actual stickers,
 * and nobody scrolls eleven thousand thumbnails to check that.
 */
private const val PREVIEW_LIMIT = 240

@Composable
fun ExportScreen(vm: ExportViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> vm.onFolderPicked(uri) }

    // Archiving posts a progress notification. Ask first, but never block the
    // export on the answer - the work matters more than being able to announce it.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { vm.export() }

    val startExport = {
        val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED

        if (needsAsk) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.export()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        when (val s = state) {
            is ExportUiState.NeedsFolder -> NeedsFolder {
                picker.launch(StickerScanner.whatsAppStickersHint())
            }

            is ExportUiState.GrantRefused -> GrantRefused(
                onRetry = { picker.launch(null) },
            )

            is ExportUiState.Scanning -> Centered {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Scanning… ${s.found} stickers found")
            }

            is ExportUiState.Ready -> ReadyToExport(
                state = s,
                onExport = startExport,
                onChangeFolder = { picker.launch(StickerScanner.whatsAppStickersHint()) },
            )

            is ExportUiState.Exporting -> Centered {
                Text("Archiving ${s.done} of ${s.total}")
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "You can leave the app — this keeps running and you'll get " +
                        "a notification when it's done.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            is ExportUiState.Done -> DoneScreen(
                state = s,
                onShare = { shareVault(context, s.uri, s.displayName) },
                onOpenDownloads = { openDownloads(context) },
                onAgain = vm::reset,
            )

            is ExportUiState.Failed -> Centered {
                Text("Something went wrong", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(s.message, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = vm::rescan) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun NeedsFolder(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Back up your stickers", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "WhatsApp keeps received and starred stickers in a folder on this " +
                "phone. Grant access to it and StickerVault will archive every " +
                "one into a single zip you can copy to Drive.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Pick this folder:", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Android / media / com.whatsapp / WhatsApp / Media / WhatsApp Stickers",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The picker should open there already. Tap \"Use this folder\" " +
                        "to confirm.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text("Choose sticker folder")
        }
    }
}

@Composable
private fun GrantRefused(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Folder access wasn't granted", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Android returned nothing when that folder was confirmed. It does this " +
                "silently for folders it refuses to share, and it also happens if " +
                "the picker was dismissed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Try this", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open the picker again and navigate to the sticker folder by " +
                        "hand rather than relying on it opening there:\n\n" +
                        "Internal storage → Android → media → com.whatsapp → " +
                        "WhatsApp → Media → WhatsApp Stickers\n\n" +
                        "then tap \"Use this folder\" and allow.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Open picker again")
        }
    }
}

@Composable
private fun ReadyToExport(
    state: ExportUiState.Ready,
    onExport: () -> Unit,
    onChangeFolder: () -> Unit,
) {
    val preview = remember(state.files) { state.files.take(PREVIEW_LIMIT) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "${state.files.size} stickers found",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            state.folderLabel,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )

        if (state.files.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "No .webp files here. This is probably the wrong folder — make " +
                    "sure you picked \"WhatsApp Stickers\", not its parent.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onChangeFolder) { Text("Pick a different folder") }
            return@Column
        }

        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(64.dp),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(preview, key = { it.uri.toString() }) { file ->
                AsyncImage(
                    model = file.uri,
                    contentDescription = file.name,
                    modifier = Modifier.size(64.dp),
                )
            }
        }

        if (state.files.size > preview.size) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Showing the first ${preview.size}. All ${state.files.size} will be exported.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
            Text("Export to zip")
        }
        OutlinedButton(onClick = onChangeFolder, modifier = Modifier.fillMaxWidth()) {
            Text("Change folder")
        }
    }
}

@Composable
private fun DoneScreen(
    state: ExportUiState.Done,
    onShare: () -> Unit,
    onOpenDownloads: () -> Unit,
    onAgain: () -> Unit,
) {
    val s: VaultSummary = state.summary
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text("Vault saved", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Downloads / ${state.displayName}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                SummaryRow("Files scanned", s.filesSeen.toString())
                SummaryRow("Unique stickers archived", s.unique.toString())
                SummaryRow("Duplicates collapsed", s.duplicatesCollapsed.toString())
                SummaryRow("Animated", s.animated.toString())
                SummaryRow("Archive size", formatBytes(s.totalBytes))
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("WhatsApp readiness", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                SummaryRow("Ready as-is", s.ok.toString())
                SummaryRow("Need resizing on import", s.repairable.toString())
                SummaryRow("Vault only (animated, oversized)", s.vaultOnly.toString())
                SummaryRow("Unreadable", s.unreadable.toString())
            }
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Getting it off the phone", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "The share sheet often fails on large archives — Drive in " +
                        "particular tends to give up silently. If that happens, " +
                        "open the Drive app and upload from Downloads directly, or " +
                        "use Quick Share to a computer. The file is already saved; " +
                        "sharing is only a convenience.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
            Text("Share vault")
        }
        OutlinedButton(onClick = onOpenDownloads, modifier = Modifier.fillMaxWidth()) {
            Text("Open Downloads")
        }
        OutlinedButton(onClick = onAgain, modifier = Modifier.fillMaxWidth()) {
            Text("Scan again")
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

/**
 * The read grant must be on the chooser itself. Flags set only on the inner
 * intent are not reliably carried across, which leaves the receiving app unable
 * to open the file - it looks like a silent failure on their side.
 */
private fun shareVault(context: Context, uri: Uri, name: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Send sticker vault").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(chooser)
}

private fun openDownloads(context: Context) {
    runCatching {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000.0)
    b >= 1_000 -> "%.0f KB".format(b / 1_000.0)
    else -> "$b B"
}
