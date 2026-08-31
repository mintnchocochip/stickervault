package com.stickervault.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.stickervault.provider.StickerPackDef
import com.stickervault.provider.WhatsAppLink
import com.stickervault.vault.LibraryEntry
import com.stickervault.vault.LibraryStore
import java.io.File

@Composable
fun ImportScreen(vm: ImportViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    // Resolved once: the directory lookup touches the filesystem and must not
    // run per item during scrolling.
    val libraryDir = remember { LibraryStore.dir(context) }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importZip(uri) }

    val addLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> vm.onAddResult(result.resultCode, result.data) }

    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = state.phase,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "phase",
            modifier = Modifier.weight(1f),
        ) { phase ->
            when (phase) {
                is ImportPhase.Empty -> EmptyState {
                    zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }

                is ImportPhase.Importing -> Busy(
                    title = "Extracting stickers",
                    detail = "${phase.imported} imported · ${phase.bytes / 1_000_000} MB",
                    note = "Each file is verified against its own hash as it lands.",
                )

                is ImportPhase.Building -> Busy(
                    title = "Preparing packs",
                    detail = "${phase.done} of ${phase.total}",
                    progress = if (phase.total == 0) null else phase.done.toFloat() / phase.total,
                )

                is ImportPhase.Failed -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Import failed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(phase.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = {
                        zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }) { Text("Choose another file") }
                }

                is ImportPhase.Browsing -> Browsing(
                    state = state,
                    libraryDir = libraryDir,
                    vm = vm,
                    onPickZip = {
                        zipPicker.launch(
                            arrayOf("application/zip", "application/octet-stream", "*/*"),
                        )
                    },
                    onAdd = { pack ->
                        val intent = WhatsAppLink.addPackIntent(context, pack)
                        if (intent == null) {
                            vm.report("No installed WhatsApp can accept sticker packs.")
                        } else {
                            runCatching { addLauncher.launch(intent) }
                                .onFailure { vm.report("Could not open WhatsApp: $it") }
                        }
                    },
                )
            }
        }

        // Selection bar rises only when there is something to act on.
        AnimatedVisibility(
            visible = state.selected.isNotEmpty() && state.phase is ImportPhase.Browsing,
            enter = expandVertically(spring()) + fadeIn(),
            exit = shrinkVertically(spring()) + fadeOut(),
        ) {
            SelectionBar(
                count = state.selected.size,
                onClear = vm::clearSelection,
                onBuild = vm::buildPacks,
            )
        }
    }
}

/**
 * Action bar for the current selection. Lives at the bottom so the primary
 * action stays under the thumb while scrolling a long grid one-handed.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onClear: () -> Unit,
    onBuild: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("$count selected", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Grouped into packs of 30, animated kept separate",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onClear) { Text("Clear") }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onBuild) { Text("Prepare") }
        }
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Bring your stickers back", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "Choose a vault zip exported from this app. Its stickers are copied " +
                "into private storage, grouped back into their original packs, " +
                "and offered to WhatsApp.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("What gets checked", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Every file must match its own hash, parse as a real WebP, and " +
                        "sit inside strict size limits before it is written. " +
                        "Anything else is refused.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text("Choose vault zip")
        }
    }
}

@Composable
private fun Busy(
    title: String,
    detail: String,
    note: String? = null,
    progress: Float? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress == null) {
            CircularProgressIndicator()
        } else {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        note?.let {
            Spacer(Modifier.height(10.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Browsing(
    state: ImportState,
    libraryDir: File,
    vm: ImportViewModel,
    onPickZip: () -> Unit,
    onAdd: (StickerPackDef) -> Unit,
) {
    AnimatedContent(
        targetState = state.openGroup,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(220))) togetherWith
                    (fadeOut(tween(140)))
            } else {
                (fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(160)))
            }
        },
        label = "drill",
    ) { open ->
        if (open == null) {
            GroupList(state, libraryDir, vm, onPickZip, onAdd)
        } else {
            StickerGrid(
                title = open,
                entries = state.openEntries,
                selected = state.selected,
                libraryDir = libraryDir,
                onBack = { vm.openGroup(null) },
                onToggle = vm::toggle,
                onToggleAll = { vm.toggleGroup(open) },
            )
        }
    }
}

@Composable
private fun GroupList(
    state: ImportState,
    libraryDir: File,
    vm: ImportViewModel,
    onPickZip: () -> Unit,
    onAdd: (StickerPackDef) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Column {
                Text(
                    "${state.libraryCount} stickers · ${state.groups.size} packs",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${state.librarySize / 1_000_000} MB in private storage",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (state.packs.isNotEmpty()) {
            item(key = "packs-title") {
                Text("Ready for WhatsApp", style = MaterialTheme.typography.titleSmall)
            }
            items(state.packs, key = { "pack-" + it.identifier }) { pack ->
                PackCard(
                    pack = pack,
                    installed = state.installed[pack.identifier],
                    onAdd = { onAdd(pack) },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        item(key = "groups-title") {
            Text("Your packs", style = MaterialTheme.typography.titleSmall)
        }

        items(state.groups, key = { "group-" + it.name }) { group ->
            val selectedHere = remember(state.selected, group) {
                group.entries.count { it.sha256 in state.selected }
            }
            GroupCard(
                name = group.name,
                count = group.entries.size,
                selectedCount = selectedHere,
                preview = group.entries.take(5),
                libraryDir = libraryDir,
                onOpen = { vm.openGroup(group.name) },
                onToggleAll = { vm.toggleGroup(group.name) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "footer") {
            Column {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onPickZip, modifier = Modifier.fillMaxWidth()) {
                    Text("Import another vault")
                }
                TextButton(onClick = vm::clearLibrary, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete imported library")
                }
                if (state.notes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Skipped", style = MaterialTheme.typography.labelLarge)
                            state.notes.take(8).forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun GroupCard(
    name: String,
    count: Int,
    selectedCount: Int,
    preview: List<LibraryEntry>,
    libraryDir: File,
    onOpen: () -> Unit,
    onToggleAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        if (selectedCount > 0) "$selectedCount of $count selected" else "$count stickers",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onToggleAll) {
                    Text(if (selectedCount == count && count > 0) "None" else "All")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                preview.forEach { entry ->
                    Thumb(
                        entry = entry,
                        libraryDir = libraryDir,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StickerGrid(
    title: String,
    entries: List<LibraryEntry>,
    selected: Set<String>,
    libraryDir: File,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleAll) { Text("All") }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(76.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.sha256 }) { entry ->
                val isSelected by remember(selected, entry.sha256) {
                    derivedStateOf { entry.sha256 in selected }
                }
                SelectableSticker(
                    entry = entry,
                    libraryDir = libraryDir,
                    selected = isSelected,
                    onClick = { onToggle(entry.sha256) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun SelectableSticker(
    entry: LibraryEntry,
    libraryDir: File,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.55f),
        label = "scale",
    )
    val corner by animateDpAsState(
        targetValue = if (selected) 14.dp else 4.dp,
        label = "corner",
    )

    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(corner))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Thumb(
            entry = entry,
            libraryDir = libraryDir,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
                .scale(scale),
        )
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                Modifier
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun Thumb(entry: LibraryEntry, libraryDir: File, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(entry.sha256) {
        ImageRequest.Builder(context)
            .data(File(libraryDir, entry.fileName))
            // Thumbnails are small; decoding at full 512px wastes memory and
            // makes long grids stutter.
            .size(160)
            .crossfade(true)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

@Composable
private fun PackCard(
    pack: StickerPackDef,
    installed: Boolean?,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(pack.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    "${pack.stickers.size} stickers" +
                        if (installed == true) " · in WhatsApp" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Default,
                )
            }
            Button(onClick = onAdd) { Text(if (installed == true) "Re-add" else "Add") }
        }
    }
}
