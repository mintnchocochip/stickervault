package com.stickervault.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stickervault.provider.PackStore
import com.stickervault.provider.StickerPackDef
import com.stickervault.provider.WhatsAppLink
import com.stickervault.vault.LibraryEntry
import com.stickervault.vault.LibraryGroup
import com.stickervault.vault.LibraryStore
import com.stickervault.vault.PackBuilder
import com.stickervault.vault.VaultImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportPhase {
    data object Empty : ImportPhase
    data class Importing(val imported: Int, val bytes: Long) : ImportPhase
    data object Browsing : ImportPhase
    data class Building(val done: Int, val total: Int) : ImportPhase
    data class Failed(val message: String) : ImportPhase
}

data class ImportState(
    val phase: ImportPhase = ImportPhase.Empty,
    val groups: List<LibraryGroup> = emptyList(),
    val libraryCount: Int = 0,
    val librarySize: Long = 0,
    val openGroup: String? = null,
    val selected: Set<String> = emptySet(),
    val packs: List<StickerPackDef> = emptyList(),
    val installed: Map<String, Boolean?> = emptyMap(),
    val notes: List<String> = emptyList(),
    val message: String? = null,
) {
    val openEntries: List<LibraryEntry>
        get() = groups.firstOrNull { it.name == openGroup }?.entries.orEmpty()
}

class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val (entries, packs, size) = withContext(Dispatchers.IO) {
                Triple(LibraryStore.load(ctx), PackStore.load(ctx), LibraryStore.totalBytes(ctx))
            }
            _state.value = _state.value.copy(
                phase = if (entries.isEmpty()) ImportPhase.Empty else ImportPhase.Browsing,
                groups = LibraryStore.group(entries),
                libraryCount = entries.size,
                librarySize = size,
                packs = packs,
            )
        }
    }

    fun importZip(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(phase = ImportPhase.Importing(0, 0), message = null)
            runCatching {
                VaultImporter(getApplication()).import(uri) { p ->
                    _state.value = _state.value.copy(
                        phase = ImportPhase.Importing(p.imported, p.bytesWritten),
                    )
                }
            }.onSuccess { result ->
                val note = buildString {
                    append("Imported ${result.imported} stickers")
                    if (result.alreadyPresent > 0) append(", ${result.alreadyPresent} already here")
                    if (result.rejected > 0) append(", ${result.rejected} rejected")
                    append('.')
                }
                _state.value = _state.value.copy(
                    message = note,
                    notes = result.rejectionSamples,
                )
                reload()
            }.onFailure { t ->
                _state.value = _state.value.copy(
                    phase = ImportPhase.Failed(t.message ?: "Import failed"),
                )
            }
        }
    }

    fun openGroup(name: String?) {
        _state.value = _state.value.copy(openGroup = name)
    }

    fun toggle(sha: String) {
        val current = _state.value.selected
        _state.value = _state.value.copy(
            selected = if (sha in current) current - sha else current + sha,
        )
    }

    fun toggleGroup(name: String) {
        val entries = _state.value.groups.firstOrNull { it.name == name }?.entries.orEmpty()
        val ids = entries.map { it.sha256 }
        val current = _state.value.selected
        val allSelected = ids.isNotEmpty() && ids.all { it in current }
        _state.value = _state.value.copy(
            selected = if (allSelected) current - ids.toSet() else current + ids,
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selected = emptySet())
    }

    fun buildPacks() {
        val selected = _state.value.selected
        if (selected.isEmpty()) return

        viewModelScope.launch {
            _state.value = _state.value.copy(phase = ImportPhase.Building(0, selected.size))
            val entries = _state.value.groups.flatMap { it.entries }.filter { it.sha256 in selected }

            runCatching {
                PackBuilder.build(getApplication(), entries) { p ->
                    _state.value = _state.value.copy(phase = ImportPhase.Building(p.done, p.total))
                }
            }.onSuccess { result ->
                _state.value = _state.value.copy(
                    phase = ImportPhase.Browsing,
                    packs = result.packs,
                    notes = result.skipped,
                    message = "Built ${result.packs.size} packs. Add each one below.",
                )
            }.onFailure { t ->
                _state.value = _state.value.copy(
                    phase = ImportPhase.Failed(t.message ?: "Could not build packs"),
                )
            }
        }
    }

    fun onAddResult(resultCode: Int, data: Intent?) {
        val validationError = data?.getStringExtra(WhatsAppLink.EXTRA_VALIDATION_ERROR)
        val text = when {
            validationError != null -> "WhatsApp rejected the pack: $validationError"
            resultCode == android.app.Activity.RESULT_OK -> "Added."
            else -> "Not added."
        }
        _state.value = _state.value.copy(message = text)
        refreshInstalled()
    }

    fun refreshInstalled() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val packs = _state.value.packs
            val map = withContext(Dispatchers.IO) {
                packs.associate { it.identifier to WhatsAppLink.isWhitelisted(ctx, it.identifier) }
            }
            _state.value = _state.value.copy(installed = map)
        }
    }

    fun report(text: String) {
        _state.value = _state.value.copy(message = text)
    }

    fun clearLibrary() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                LibraryStore.clear(getApplication())
                PackStore.clear(getApplication())
            }
            _state.value = ImportState()
            reload()
        }
    }
}
