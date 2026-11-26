// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
package work.mailcraft.android

import android.content.Context
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
private const val KEEP_DEVICE_SNAPSHOTS = 3
private const val MAX_SNAPSHOT_BYTES = 70_000_000
private const val MAX_TOTAL_SNAPSHOT_BYTES = 100_000_000
private const val DIRECTORY_MIME_TYPE = "vnd.android.document/directory"

internal data class DriveSyncResult(val status: String, val synchronized: Boolean)

internal fun noteFolderUri(context: Context): Uri? = context.getSharedPreferences(DRIVE_PREFS, Context.MODE_PRIVATE)
    .getString(FOLDER_KEY, null)
    ?.let(Uri::parse)

internal fun rememberNoteFolder(context: Context, uri: Uri): Boolean = try {
    val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, flags)
    context.getSharedPreferences(DRIVE_PREFS, Context.MODE_PRIVATE).edit().putString(FOLDER_KEY, uri.toString()).commit()
} catch (_: SecurityException) {
    false
}

/** Read, merge, and publish note-only files in the app-owned child folder of a selected Drive folder. */
internal suspend fun syncDriveNotes(core: Core, context: Context, texts: Texts): DriveSyncResult = withContext(Dispatchers.IO) {
    val root = noteFolderUri(context) ?: return@withContext DriveSyncResult(texts.t("Choose a Google Drive folder to sync notes."), false)
    try {
        val deviceId = noteDeviceId(context)
        val resolver = context.contentResolver
        val folder = appFolder(resolver, root) ?: return@withContext DriveSyncResult(texts.t("Google Drive could not open the MailCraft notes folder."), false)
        val files = children(resolver, root, folder)
            .filter { it.name.startsWith(SNAPSHOT_PREFIX) && it.name.endsWith(".json") }
            .sortedByDescending { it.name }
        if (files.size > MAX_SNAPSHOT_FILES) {
            return@withContext DriveSyncResult(texts.t("There are too many MailCraft note snapshots in this folder."), false)
        }
        val snapshots = ArrayList<JSONObject>()
        var totalBytes = 0
        for (file in files) {
            val content = readBounded(resolver.openInputStream(documentUri(root, file.id)) ?: continue, MAX_SNAPSHOT_BYTES) ?: continue
            totalBytes += content.size
            if (totalBytes > MAX_TOTAL_SNAPSHOT_BYTES) {
                return@withContext DriveSyncResult(texts.t("MailCraft note snapshots use too much storage to sync at once."), false)
            }
            try {
                val snapshot = JSONObject(String(content, StandardCharsets.UTF_8))
                if (snapshot.optInt("version", -1) == 1 && snapshot.optJSONArray("notes") != null && snapshot.optString("deviceId").isNotBlank()) {
                    snapshots.add(snapshot)
                }
            } catch (_: JSONException) {
                // An incomplete provider upload or unrelated file is ignored; the local mailbox is retained.
            }
        }
        for (snapshot in snapshots) {
            try {
                val one = JSONArray().put(snapshot)
                core.execute("notes.syncMerge", JSONObject().put("deviceId", deviceId).put("snapshots", one).toString())
            } catch (_: CoreException) {
                // A malformed or newer snapshot is skipped; other device snapshots still merge.
            }
        }
        val ownSnapshot = core.execute("notes.syncSnapshot", JSONObject().put("deviceId", deviceId).toString())
        if (ownSnapshot.toByteArray(StandardCharsets.UTF_8).size > MAX_SNAPSHOT_BYTES) {
            return@withContext DriveSyncResult(texts.t("MailCraft notes use too much storage for one Drive snapshot."), false)
        }
        val stamp = System.currentTimeMillis().toString().padStart(13, '0')
        val finalName = "$SNAPSHOT_PREFIX$deviceId-$stamp-${UUID.randomUUID()}.json"
        writeSnapshot(resolver, root, folder, finalName, ownSnapshot.toByteArray(StandardCharsets.UTF_8))
        cleanupOldSnapshots(resolver, root, folder, deviceId)
        DriveSyncResult(texts.t("Notes synced with Google Drive."), true)
    } catch (_: Exception) {
        DriveSyncResult(texts.t("Notes are saved on this device. Google Drive sync will be retried."), false)
    }
}

private data class DriveDocument(val id: String, val name: String, val mimeType: String)

private fun appFolder(resolver: android.content.ContentResolver, tree: Uri): String? {
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    val rootChildren = children(resolver, tree, rootId)
    val found = rootChildren.firstOrNull { it.name == MAILCRAFT_FOLDER && it.mimeType == DIRECTORY_MIME_TYPE }
    if (found != null) return found.id
    if (rootChildren.size > MAX_SNAPSHOT_FILES * 4) return null
    return DocumentsContract.createDocument(documentUri(tree, rootId), DIRECTORY_MIME_TYPE, MAILCRAFT_FOLDER)
        ?.let { DocumentsContract.getDocumentId(it) }
}

private fun children(resolver: android.content.ContentResolver, tree: Uri, parentId: String): List<DriveDocument> {
    val address = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
    val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    val result = ArrayList<DriveDocument>()
    resolver.query(address, columns, null, null, null)?.use { cursor ->
        val idColumn = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
        val nameColumn = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        val mimeColumn = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
        if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) return result
        while (cursor.moveToNext() && result.size <= MAX_SNAPSHOT_FILES * 4) {
            val id = cursor.getString(idColumn) ?: continue
            val name = cursor.getString(nameColumn) ?: continue
            val mime = cursor.getString(mimeColumn) ?: continue
            result.add(DriveDocument(id, name, mime))
        }
    }
    return result
}

private fun documentUri(tree: Uri, documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)

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

private fun writeSnapshot(resolver: android.content.ContentResolver, tree: Uri, folderId: String, name: String, bytes: ByteArray) {
    val folderUri = documentUri(tree, folderId)
    val pendingName = ".pending-${UUID.randomUUID()}.json"
    val pending = DocumentsContract.createDocument(resolver, folderUri, "application/json", pendingName)
        ?: error("Google Drive did not create the temporary snapshot")
    try {
        resolver.openOutputStream(pending, "w")?.use { it.write(bytes) } ?: error("Google Drive did not open the temporary snapshot")
        DocumentsContract.renameDocument(resolver, pending, name) ?: error("Google Drive could not finish the snapshot")
    } catch (error: Exception) {
        runCatching { DocumentsContract.deleteDocument(resolver, pending) }
        throw error
    }
}

private fun cleanupOldSnapshots(resolver: android.content.ContentResolver, tree: Uri, folderId: String, deviceId: String) {
    val prefix = "$SNAPSHOT_PREFIX$deviceId-"
    val ownFiles = children(resolver, tree, folderId).filter { it.name.startsWith(prefix) && it.name.endsWith(".json") }.sortedByDescending { it.name }
    ownFiles.drop(KEEP_DEVICE_SNAPSHOTS).forEach { old ->
        runCatching { DocumentsContract.deleteDocument(resolver, documentUri(tree, old.id)) }
    }
}

