// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
package work.mailcraft.android

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

private const val MAILCRAFT_FOLDER = "MailCraft Notes"
private const val DRIVE_SCAN_MULTIPLIER = 4
private const val MAX_DRIVE_DOCUMENTS = MAX_SNAPSHOT_FILES * DRIVE_SCAN_MULTIPLIER
private const val DIRECTORY_MIME_TYPE = "vnd.android.document/directory"

internal data class DriveDocument(val id: String, val name: String, val mimeType: String)

internal fun appFolder(resolver: ContentResolver, tree: Uri): String? {
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val rootChildren = children(resolver, tree, rootId)
    val found = rootChildren.firstOrNull { it.name == MAILCRAFT_FOLDER && it.mimeType == DIRECTORY_MIME_TYPE }
    if (found != null) return found.id
    if (rootChildren.size > MAX_DRIVE_DOCUMENTS) return null
    return DocumentsContract.createDocument(resolver, documentUri(tree, rootId), DIRECTORY_MIME_TYPE, MAILCRAFT_FOLDER)
        ?.let { DocumentsContract.getDocumentId(it) }
}

internal fun children(resolver: ContentResolver, tree: Uri, parentId: String): List<DriveDocument> {
    val address = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    val result = ArrayList<DriveDocument>()
    resolver.query(address, projection, null, null, null)?.use { cursor ->
        val indexes = DocumentColumns(
            cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID),
            cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME),
            cursor.getColumnIndex(Document.COLUMN_MIME_TYPE),
        )
        if (!indexes.valid) return result
        while (cursor.moveToNext() && result.size < MAX_DRIVE_DOCUMENTS) {
            readDocument(cursor, indexes)?.let(result::add)
        }
    }
    return result
}

private data class DocumentColumns(val id: Int, val name: Int, val mime: Int) {
    val valid: Boolean get() = id >= 0 && name >= 0 && mime >= 0
}

private fun readDocument(cursor: Cursor, columns: DocumentColumns): DriveDocument? {
    val id = cursor.getString(columns.id)
    val name = cursor.getString(columns.name)
    val mime = cursor.getString(columns.mime)
    return if (id == null || name == null || mime == null) null else DriveDocument(id, name, mime)
}

internal fun documentUri(tree: Uri, documentId: String): Uri =
    DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

internal fun readBounded(stream: InputStream, maxBytes: Int): ByteArray? = stream.use { input ->
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) return null
        out.write(buffer, 0, count)
    }
    out.toByteArray()
}

@Suppress("TooGenericExceptionCaught")
internal fun writeSnapshot(
    resolver: ContentResolver,
    tree: Uri,
    folderId: String,
    name: String,
    bytes: ByteArray,
) {
    val folderUri = documentUri(tree, folderId)
    val pendingName = ".pending-${UUID.randomUUID()}.json"
    val pending = DocumentsContract.createDocument(resolver, folderUri, "application/json", pendingName)
        ?: error("Google Drive did not create the temporary snapshot")
    try {
        resolver.openOutputStream(pending, "w")?.use { it.write(bytes) }
            ?: error("Google Drive did not open the temporary snapshot")
        DocumentsContract.renameDocument(resolver, pending, name) ?: error("Google Drive could not finish the snapshot")
    } catch (error: Exception) {
        runCatching { DocumentsContract.deleteDocument(resolver, pending) }
        throw error
    }
}

internal fun cleanupOldSnapshots(resolver: ContentResolver, tree: Uri, folderId: String, deviceId: String) {
    val prefix = "${SNAPSHOT_PREFIX}$deviceId-"
    val ownFiles = children(resolver, tree, folderId)
        .filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }
        .sortedByDescending { it.name }
    ownFiles.drop(KEEP_DEVICE_SNAPSHOTS).forEach { old ->
        runCatching { DocumentsContract.deleteDocument(resolver, documentUri(tree, old.id)) }
    }
}
