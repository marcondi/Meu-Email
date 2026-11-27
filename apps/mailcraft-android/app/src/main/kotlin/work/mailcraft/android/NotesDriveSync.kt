// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
package work.mailcraft.android

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import uniffi.mailcraft_mobile.Core
import uniffi.mailcraft_mobile.CoreException
import java.nio.charset.StandardCharsets
import java.util.UUID

private const val DRIVE_PREFS = "notes-sync"
private const val FOLDER_KEY = "folder-uri"
internal const val SNAPSHOT_PREFIX = "notes-"
internal const val MAX_SNAPSHOT_FILES = 64
internal const val KEEP_DEVICE_SNAPSHOTS = 3
internal const val MAX_SNAPSHOT_BYTES = 70_000_000
private const val MAX_TOTAL_SNAPSHOT_BYTES = 100_000_000
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
        .edit { putString(FOLDER_KEY, uri.toString()) }
    true
} catch (_: SecurityException) {
    false
}

/** Read, merge, and publish note-only files in the app-owned child folder of a selected Drive folder. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun syncDriveNotes(
    core: Core,
    context: Context,
    texts: Texts,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): DriveSyncResult = withContext(dispatcher) {
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
        if (!publishSnapshot(core, resolver, root, folder, deviceId)) {
            return@withContext DriveSyncResult(
                texts.t("MailCraft notes use too much storage for one Drive snapshot."),
                false,
            )
        }
        cleanupOldSnapshots(resolver, root, folder, deviceId)
        DriveSyncResult(texts.t("Notes synced with Google Drive."), true)
    } catch (_: Exception) {
        DriveSyncResult(
            texts.t("Notes are saved on this device. Google Drive sync will be retried."),
            false,
        )
    }
}

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

private suspend fun mergeSnapshots(core: Core, deviceId: String, snapshots: List<JSONObject>) {
    for (snapshot in snapshots) {
        try {
            val request = JSONObject().put("deviceId", deviceId).put("snapshots", JSONArray().put(snapshot))
            core.execute("notes.syncMerge", request.toString())
        } catch (_: CoreException) {
            // A malformed or newer snapshot is skipped; other device snapshots still merge.
        }
    }
}

private suspend fun publishSnapshot(
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

