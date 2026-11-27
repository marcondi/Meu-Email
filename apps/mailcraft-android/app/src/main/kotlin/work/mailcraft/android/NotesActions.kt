// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
package work.mailcraft.android

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uniffi.mailcraft_mobile.Core
import uniffi.mailcraft_mobile.CoreException

internal typealias NoteMutation = (String, String, () -> Unit) -> Unit

internal data class NoteMutationContext(
    val scope: CoroutineScope,
    val core: Core,
    val texts: Texts,
    val isBusy: () -> Boolean,
    val setBusy: (Boolean) -> Unit,
    val setNotice: (String?) -> Unit,
    val onMutated: () -> Unit,
)

internal data class NoteActionContext(
    val core: Core,
    val texts: Texts,
    val context: Context,
    val scope: CoroutineScope,
    val isBusy: () -> Boolean,
    val currentDraft: () -> NoteDraft?,
    val setEditor: (NoteDraft?) -> Unit,
    val setNotice: (String?) -> Unit,
    val mutate: NoteMutation,
    val setSyncStatus: (String) -> Unit,
    val retrySync: () -> Unit,
)

internal fun noteMutationHandler(model: NoteMutationContext): NoteMutation {
    fun mutate(command: String, params: String, after: () -> Unit) {
        if (model.isBusy()) return
        model.setBusy(true)
        model.scope.launch {
            try {
                model.core.execute(command, params)
                model.setNotice(null)
                after()
                model.onMutated()
            } catch (error: CoreException) {
                model.setNotice(reasonOf(error) ?: model.texts.t("That could not be saved."))
            } finally {
                model.setBusy(false)
            }
        }
    }
    return ::mutate
}

internal fun createNoteActions(model: NoteActionContext): NotesActions = NotesActions(
    onNew = {
        if (!model.isBusy()) {
            model.setNotice(null)
            model.setEditor(NoteDraft(null, ""))
        }
    },
    onOpen = { row ->
        if (!model.isBusy()) {
            model.scope.launch {
                val draft = loadNote(model.core, row.id)
                if (draft == null) model.setNotice(model.texts.t("The note could not be opened."))
                else model.setEditor(draft)
            }
        }
    },
    onText = { text ->
        if (!model.isBusy() && text.toByteArray(Charsets.UTF_8).size <= MAX_NOTE_BYTES) {
            model.setEditor(model.currentDraft()?.copy(text = text))
        }
    },
    onSave = {
        val draft = model.currentDraft()
        if (draft != null && !model.isBusy()) {
            val params = mutableMapOf<String, Any>("text" to draft.text, "deviceId" to noteDeviceId(model.context))
            draft.id?.let { params["id"] = it }
            model.mutate(if (draft.id == null) "notes.new" else "notes.edit", jsonText(params)) {
                model.setEditor(null)
            }
        }
    },
    onDelete = {
        model.currentDraft()?.id?.takeIf { !model.isBusy() }?.let { id ->
            val params = jsonText(mapOf("id" to id, "deviceId" to noteDeviceId(model.context)))
            model.mutate("notes.delete", params) { model.setEditor(null) }
        }
    },
    onClose = { if (!model.isBusy()) model.setEditor(null) },
    onChooseDriveFolder = { uri: Uri -> chooseDriveFolder(model, uri) },
)

private fun chooseDriveFolder(model: NoteActionContext, uri: Uri) {
    if (rememberNoteFolder(model.context, uri)) {
        model.setSyncStatus(model.texts.t("Syncing notes with Google Drive…"))
        model.retrySync()
    } else {
        model.setSyncStatus(model.texts.t("MailCraft could not keep access to that folder."))
    }
}
