// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
package work.mailcraft.android

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uniffi.mailcraft_mobile.Core
import uniffi.mailcraft_mobile.CoreException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val DRIVE_PREFS = "notes-sync"
private const val FOLDER_KEY = "folder-uri"
private const val MAILCRAFT_FOLDER = "MailCraft Notes"
private const val SNAPSHOT_PREFIX = "notes-"
private const val MAX_SNAPSHOT_FILES = 64
private const val DRIVE_SCAN_MULTIPLIER = 4
private const val MAX_DRIVE_DOCUMENTS = MAX_SNAPSHOT_FILES * DRIVE_SCAN_MULTIPLIER
private const val KEEP_DEVICE_SNAPSHOTS = 3
private const val MAX_SNAPSHOT_BYTES = 70_000_000
private const val MAX_TOTAL_SNAPSHOT_BYTES = 100_000_000
private const val DIRECTORY_MIME_TYPE = "vnd.android.document/directory"
private const val TIMESTAMP_WIDTH = 13

internal data class DriveSyncResult(val status: String, val synchronized: Boolean)

internal fun noteFolderUri(context: Context): Uri? = context.getSharedPreferences(DRIVE_PREFS, Context.MODE_PRIVATE)
    .getString(FOLDER_KEY, null)
    ?.let(Uri::parse)

internal fun rememberNoteFolder(context: Context, uri: Uri): Boolean = try {
    val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, flags)
    context.getSharedPreferences(DRIVE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(FOLDER_KEY, uri.toString())
        .commit()
} catch (_: SecurityException) {
    false
}

/** Read, merge, and publish note-only files in the app-owned child folder of a selected Drive folder. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun syncDriveNotes(
    core: Core,
    context: Context,
    texts: Texts,
): DriveSyncResult = withContext(Dispatchers.IO) {
    val root = noteFolderUri(context)
        ?: return@withContext DriveSyncResult(texts.t("Choose a Google Drive folder to sync notes."), false)
    try {
        val deviceId = noteDeviceId(context)
        val resolver = context.contentResolver
        val folder = appFolder(resolver, root)
            ?: return@withContext DriveSyncResult(
                texts.t("Google Drive could not open the MailCraft notes folder."),
                false,
            )
        val files = snapshotFiles(resolver, root, folder)
        if (files.size > MAX_SNAPSHOT_FILES) {
            return@withContext DriveSyncResult(
                texts.t("There are too many MailCraft note snapshots in this folder."),
                false,
            )
        }
        val snapshots = loadSnapshots(resolver, root, files)
            ?: return@withContext DriveSyncResult(
                texts.t("MailCraft note snapshots use too much storage to sync at once."),
                false,
            )
        mergeSnapshots(core, deviceId, snapshots)
        publishSnapshot(core, resolver, root, folder, deviceId)
            ?: return@withContext DriveSyncResult(
                texts.t("MailCraft notes use too much storage for one Drive snapshot."),
                false,
            )
        cleanupOldSnapshots(resolver, root, folder, deviceId)
        DriveSyncResult(texts.t("Notes synced with Google Drive."), true)
    } catch (_: Exception) {
        DriveSyncResult(
            texts.t("Notes are saved on this device. Google Drive sync will be retried."),
            false,
        )
    }
}

private data class DriveDocument(val id: String, val name: String, val mimeType: String)

private fun snapshotFiles(resolver: android.content.ContentResolver, tree: Uri, folder: String): List<DriveDocument> =
    children(resolver, tree, folder)
        .filter { it.name.startsWith(SNAPSHOT_PREFIX) && it.name.endsWith(".json") }
        .sortedByDescending { it.name }

private fun loadSnapshots(
    resolver: android.content.ContentResolver,
    tree: Uri,
    files: List<DriveDocument>,
): List<JSONObject>? {
    val snapshots = ArrayList<JSONObject>()
    var totalBytes = 0
    for (file in files) {
        val content = resolver.openInputStream(documentUri(tree, file.id))
            ?.let { readBounded(it, MAX_SNAPSHOT_BYTES) }
            ?: continue
        totalBytes += content.size
        if (totalBytes > MAX_TOTAL_SNAPSHOT_BYTES) return null
        parseSnapshot(content)?.let(snapshots::add)
    }
    return snapshots
}

private fun parseSnapshot(content: ByteArray): JSONObject? = try {
    JSONObject(String(content, StandardCharsets.UTF_8)).takeIf { snapshot ->
        snapshot.optInt("version", -1) == 1 &&
            snapshot.optJSONArray("notes") != null &&
            snapshot.optString("deviceId").isNotBlank()
    }
} catch (_: JSONException) {
    // An incomplete provider upload or unrelated file is ignored; the local mailbox is retained.
    null
}

private fun mergeSnapshots(core: Core, deviceId: String, snapshots: List<JSONObject>) {
    for (snapshot in snapshots) {
        try {
            val request = JSONObject().put("deviceId", deviceId).put("snapshots", JSONArray().put(snapshot))
            core.execute("notes.syncMerge", request.toString())
        } catch (_: CoreException) {
            // A malformed or newer snapshot is skipped; other device snapshots still merge.
        }
    }
}

private fun publishSnapshot(
    core: Core,
    resolver: android.content.ContentResolver,
    tree: Uri,
    folder: String,
    deviceId: String,
): Boolean {
    val ownSnapshot = core.execute("notes.syncSnapshot", JSONObject().put("deviceId", deviceId).toString())
    val bytes = ownSnapshot.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > MAX_SNAPSHOT_BYTES) return false
    val stamp = System.currentTimeMillis().toString().padStart(TIMESTAMP_WIDTH, '0')
    val finalName = "$SNAPSHOT_PREFIX$deviceId-$stamp-${UUID.randomUUID()}.json"
    writeSnapshot(resolver, tree, folder, finalName, bytes)
    return true
}

private fun appFolder(resolver: android.content.ContentResolver, tree: Uri): String? {
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val rootChildren = children(resolver, tree, rootId)
    val found = rootChildren.firstOrNull { it.name == MAILCRAFT_FOLDER && it.mimeType == DIRECTORY_MIME_TYPE }
    if (found != null) return found.id
    if (rootChildren.size > MAX_DRIVE_DOCUMENTS) return null
    return DocumentsContract.createDocument(resolver, documentUri(tree, rootId), DIRECTORY_MIME_TYPE, MAILCRAFT_FOLDER)
        ?.let { DocumentsContract.getDocumentId(it) }
}

private fun children(resolver: android.content.ContentResolver, tree: Uri, parentId: String): List<DriveDocument> {
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
    val id = cursor.getString(columns.id) ?: return null
    val name = cursor.getString(columns.name) ?: return null
    val mime = cursor.getString(columns.mime) ?: return null
    return DriveDocument(id, name, mime)
}

private fun documentUri(tree: Uri, documentId: String): Uri =
    DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray? = stream.use { input ->
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
private fun writeSnapshot(
    resolver: android.content.ContentResolver,
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

private fun cleanupOldSnapshots(
    resolver: android.content.ContentResolver,
    tree: Uri,
    folderId: String,
    deviceId: String,
) {
    val prefix = "$SNAPSHOT_PREFIX$deviceId-"
    val ownFiles = children(resolver, tree, folderId)
        .filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }
        .sortedByDescending { it.name }
    ownFiles.drop(KEEP_DEVICE_SNAPSHOTS).forEach { old ->
        runCatching { DocumentsContract.deleteDocument(resolver, documentUri(tree, old.id)) }
    }
}

