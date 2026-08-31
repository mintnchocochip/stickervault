package com.stickervault.vault

import android.net.Uri
import com.stickervault.model.VaultSummary
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared state between the UI and the export service.
 *
 * The service runs in the same process, so a plain singleton is enough and
 * avoids serialising eleven thousand file handles through an Intent, which would
 * blow the Binder transaction limit many times over. If the process dies the
 * service dies with it, so there is no state here worth persisting.
 */
object VaultRepository {

    sealed interface Status {
        data object Idle : Status
        data class Archiving(val done: Int, val total: Int) : Status
        data class Finished(
            val summary: VaultSummary,
            val uri: Uri,
            val displayName: String,
        ) : Status

        data class Failed(val message: String) : Status
    }

    val status = MutableStateFlow<Status>(Status.Idle)

    /** Populated by the scan so the service does not have to repeat it. */
    @Volatile
    var files: List<StickerScanner.ScannedFile> = emptyList()

    fun isBusy(): Boolean = status.value is Status.Archiving
}
