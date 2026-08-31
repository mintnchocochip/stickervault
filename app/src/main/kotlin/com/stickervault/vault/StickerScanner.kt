package com.stickervault.vault

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Walks a granted document tree collecting .webp files.
 *
 * Uses DocumentsContract cursors rather than DocumentFile.listFiles(), which
 * issues a separate IPC round-trip per child and becomes painful across a
 * library of thousands of stickers.
 */
class StickerScanner(private val resolver: ContentResolver) {

    data class ScannedFile(
        val uri: Uri,
        val name: String,
        val size: Long,
    )

    /**
     * WhatsApp keeps downscaled previews alongside the real stickers. Including
     * them would pollute the vault with wrong-resolution near-duplicates that
     * content-hashing cannot collapse, because their bytes genuinely differ.
     */
    private val skipDirs = setOf(".StickerThumbs", ".Thumbs", ".thumbnails", ".trash")

    fun scan(treeUri: Uri, onProgress: (found: Int) -> Unit = {}): List<ScannedFile> {
        val out = mutableListOf<ScannedFile>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        val pending = ArrayDeque<String>()
        pending.addLast(DocumentsContract.getTreeDocumentId(treeUri))

        while (pending.isNotEmpty()) {
            val parentId = pending.removeLast()
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)

            // A folder we cannot read should not abort the whole scan.
            val cursor = runCatching {
                resolver.query(childrenUri, projection, null, null, null)
            }.getOrNull() ?: continue

            cursor.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) 0L else c.getLong(3)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name !in skipDirs) pending.addLast(docId)
                    } else if (name.endsWith(".webp", ignoreCase = true)) {
                        out += ScannedFile(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            name = name,
                            size = size,
                        )
                        if (out.size % 50 == 0) onProgress(out.size)
                    }
                }
            }
        }

        onProgress(out.size)
        return out
    }

    companion object {
        /**
         * Where WhatsApp keeps received and starred stickers. Used only to open
         * the folder picker in the right place - the user still makes the grant,
         * and a wrong guess here costs nothing but a bit of navigation.
         */
        const val WHATSAPP_STICKERS_DOC_ID =
            "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers"

        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        fun whatsAppStickersHint(): Uri = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            WHATSAPP_STICKERS_DOC_ID,
        )
    }
}
