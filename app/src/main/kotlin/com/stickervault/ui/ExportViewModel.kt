package com.stickervault.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stickervault.model.VaultSummary
import com.stickervault.vault.ExportService
import com.stickervault.vault.StickerScanner
import com.stickervault.vault.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ExportUiState {
    /** No folder granted yet, or a previous grant was revoked. */
    data object NeedsFolder : ExportUiState

    /**
     * The picker came back empty. Android refuses some folders outright and
     * silently returns null, so this must be surfaced rather than ignored.
     */
    data object GrantRefused : ExportUiState

    data class Scanning(val found: Int) : ExportUiState

    data class Ready(
        val files: List<StickerScanner.ScannedFile>,
        val folderLabel: String,
    ) : ExportUiState

    data class Exporting(val done: Int, val total: Int) : ExportUiState

    data class Done(
        val summary: VaultSummary,
        val uri: Uri,
        val displayName: String,
    ) : ExportUiState

    data class Failed(val message: String) : ExportUiState
}

class ExportViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs =
        app.getSharedPreferences("stickervault", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<ExportUiState>(ExportUiState.NeedsFolder)
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    private var grantedTree: Uri? = null

    init {
        restorePreviousGrant()
        observeExportService()
    }

    /**
     * The archive runs in a service that outlives this ViewModel, so its status
     * is the source of truth. Reopening the app mid-archive lands back on the
     * progress screen instead of looking like nothing is happening.
     */
    private fun observeExportService() {
        viewModelScope.launch {
            VaultRepository.status.collect { status ->
                when (status) {
                    is VaultRepository.Status.Archiving ->
                        _state.value = ExportUiState.Exporting(status.done, status.total)

                    is VaultRepository.Status.Finished ->
                        _state.value = ExportUiState.Done(
                            summary = status.summary,
                            uri = status.uri,
                            displayName = status.displayName,
                        )

                    is VaultRepository.Status.Failed ->
                        _state.value = ExportUiState.Failed(status.message)

                    VaultRepository.Status.Idle -> Unit
                }
            }
        }
    }

    private fun restorePreviousGrant() {
        val saved = prefs.getString(KEY_TREE_URI, null) ?: return
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: return

        val stillHeld = getApplication<Application>().contentResolver
            .persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }

        if (stillHeld) {
            grantedTree = uri
            scan(uri)
        } else {
            prefs.edit().remove(KEY_TREE_URI).apply()
        }
    }

    /**
     * @param uri null when the system refused the grant, which it does without
     *   explanation for some folders. Treated as a reportable outcome, never as
     *   a no-op - a silent no-op here is indistinguishable from a frozen app.
     */
    fun onFolderPicked(uri: Uri?) {
        if (uri == null) {
            _state.value = ExportUiState.GrantRefused
            return
        }

        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        prefs.edit().putString(KEY_TREE_URI, uri.toString()).apply()
        grantedTree = uri
        scan(uri)
    }

    fun rescan() {
        val tree = grantedTree
        if (tree == null) {
            _state.value = ExportUiState.NeedsFolder
        } else {
            scan(tree)
        }
    }

    private fun scan(treeUri: Uri) {
        viewModelScope.launch {
            _state.value = ExportUiState.Scanning(0)
            runCatching {
                withContext(Dispatchers.IO) {
                    StickerScanner(getApplication<Application>().contentResolver)
                        .scan(treeUri) { found ->
                            _state.value = ExportUiState.Scanning(found)
                        }
                }
            }.onSuccess { files ->
                VaultRepository.files = files
                _state.value = ExportUiState.Ready(
                    files = files,
                    folderLabel = folderLabel(treeUri),
                )
            }.onFailure { t ->
                _state.value = ExportUiState.Failed(t.message ?: "Scan failed")
            }
        }
    }

    fun export() {
        val ready = _state.value as? ExportUiState.Ready ?: return
        if (ready.files.isEmpty()) return

        VaultRepository.files = ready.files
        // Optimistic, so the UI switches immediately rather than waiting for the
        // service to spin up and publish its first status.
        _state.value = ExportUiState.Exporting(0, ready.files.size)
        ExportService.start(getApplication())
    }

    fun reset() {
        VaultRepository.status.value = VaultRepository.Status.Idle
        rescan()
    }

    /** Human-readable tail of the granted tree, for confirming the right folder. */
    private fun folderLabel(treeUri: Uri): String {
        val decoded = Uri.decode(treeUri.toString())
        return decoded.substringAfterLast(':', decoded).ifBlank { decoded }
    }

    private companion object {
        const val KEY_TREE_URI = "tree_uri"
    }
}
