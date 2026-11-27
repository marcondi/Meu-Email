// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
package work.mailcraft.android

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.mailcraft_mobile.Core
import uniffi.mailcraft_mobile.CoreException
import java.util.UUID

internal data class NoteRow(val id: ULong, val title: String, val snippet: String, val readOnly: Boolean)

internal sealed interface NotesState {
    data object Loading : NotesState
    class Ready(val rows: List<NoteRow>) : NotesState
    class Failed(val message: String) : NotesState
}

internal data class NoteDraft(val id: ULong?, val text: String, val readOnly: Boolean = false)

internal class NotesData(
    val state: NotesState,
    val editor: NoteDraft? = null,
    val notice: String? = null,
    val busy: Boolean = false,
    val syncStatus: String? = null,
)

internal class NotesActions(
    val onNew: () -> Unit = {},
    val onOpen: (NoteRow) -> Unit = {},
    val onText: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onChooseDriveFolder: (Uri) -> Unit = {},
)

private const val NOTE_LIST_LIMIT = 5_000
internal const val MAX_NOTE_BYTES = 1_000_000

internal suspend fun loadNotes(
    core: Core,
    texts: Texts,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): NotesState = withContext(dispatcher) {
    try {
        val response = JSONObject(core.execute("notes.list", "{\"limit\":$NOTE_LIST_LIMIT}"))
        val records = response.optJSONArray("notes")
            ?: return@withContext NotesState.Failed(texts.t("The notes could not be read."))
        val rows = (0 until records.length()).mapNotNull { index ->
            val record = records.optJSONObject(index) ?: return@mapNotNull null
            val rawId = record.optLong("id", -1L)
            if (rawId < 0) return@mapNotNull null
            NoteRow(
                rawId.toULong(),
                record.optString("subject"),
                record.optString("snippet"),
                record.optBoolean("readOnly", true),
            )
        }
        NotesState.Ready(rows)
    } catch (_: CoreException) {
        NotesState.Failed(texts.t("The notes could not be read."))
    } catch (_: org.json.JSONException) {
        NotesState.Failed(texts.t("The notes could not be read."))
    }
}

internal suspend fun loadNote(
    core: Core,
    id: ULong,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): NoteDraft? = withContext(dispatcher) {
    try {
        val response = JSONObject(core.execute("notes.get", jsonText(mapOf("id" to id))))
        NoteDraft(id, response.optString("body"), response.optBoolean("readOnly", true))
    } catch (_: CoreException) {
        null
    } catch (_: org.json.JSONException) {
        null
    }
}

internal fun noteDeviceId(context: Context): String {
    val preferences = context.getSharedPreferences("notes-sync", Context.MODE_PRIVATE)
    return synchronized(preferences) {
        preferences.getString("device-id", null) ?: UUID.randomUUID().toString().also { id ->
            preferences.edit { putString("device-id", id) }
        }
    }
}

