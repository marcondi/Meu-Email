// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
@file:Suppress("MagicNumber")

package work.mailcraft.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val Gutter = 16.dp
private val ListPane = 380.dp

@Composable
internal fun NotesScreen(texts: Texts, data: NotesData, actions: NotesActions, modifier: Modifier = Modifier) {
    BackHandler(enabled = data.editor != null && !data.busy) { actions.onClose() }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= TwoPaneWidth
        when {
            wide -> Row(modifier = Modifier.fillMaxSize()) {
                NoteList(texts, data, actions, Modifier.width(ListPane))
                VerticalDivider()
                NoteEditor(texts, data, actions, showBack = false, Modifier.weight(1f))
            }
            data.editor != null -> NoteEditor(texts, data, actions, showBack = true, Modifier.fillMaxSize())
            else -> NoteList(texts, data, actions, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NoteList(texts: Texts, data: NotesData, actions: NotesActions, modifier: Modifier) {
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(actions.onChooseDriveFolder)
    }
    Box(modifier = modifier) {
        when (val state = data.state) {
            NotesState.Loading -> Centered(texts.t("Loading…"))
            is NotesState.Failed -> Centered(state.message)
            is NotesState.Ready -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    data.notice?.let { item(key = "notice") { Notice(it) } }
                    item(key = "drive-sync") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
                        ) {
                            Text(data.syncStatus.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { folderPicker.launch(noteFolderUri(context)) }) {
                                Text(texts.t("Choose Google Drive folder"))
                            }
                        }
                    }
                    if (state.rows.isEmpty()) {
                        item(key = "empty") { Centered(texts.t("No notes.")) }
                    }
                    items(state.rows, key = { it.id }) { row ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Gutter, vertical = 6.dp)
                                .clickable { actions.onOpen(row) },
                        ) {
                            Column(
                                modifier = Modifier.padding(Gutter),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    row.title.ifBlank { texts.t("Untitled note") },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    row.snippet,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (row.readOnly) {
                                    Text(texts.t("Read-only import"), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = actions.onNew,
                    icon = { Icon(AppIcons.Add, contentDescription = null) },
                    text = { Text(texts.t("New note")) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(Gutter),
                )
            }
        }
    }
}

@Composable
private fun NoteEditor(texts: Texts, data: NotesData, actions: NotesActions, showBack: Boolean, modifier: Modifier) {
    val draft = data.editor
    if (draft == null) {
        Centered(texts.t("Select a note to read it."), modifier)
        return
    }
    Column(modifier = modifier.fillMaxSize().padding(Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = actions.onClose, enabled = !data.busy) {
                Icon(AppIcons.Back, contentDescription = texts.t("Back"))
            }
            Text(texts.t(if (draft.id == null) "New note" else "Note"), style = MaterialTheme.typography.titleLarge)
        }
        data.notice?.let { Notice(it) }
        OutlinedTextField(
            value = draft.text,
            onValueChange = actions.onText,
            label = { Text(texts.t("Note")) },
            modifier = Modifier.fillMaxWidth().weight(1f),
            enabled = !data.busy && !draft.readOnly,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onSave, enabled = !data.busy && !draft.readOnly) { Text(texts.t("Save")) }
            if (draft.id != null && !draft.readOnly) {
                TextButton(onClick = actions.onDelete, enabled = !data.busy) { Text(texts.t("Delete")) }
            }
            if (!showBack) TextButton(onClick = actions.onClose, enabled = !data.busy) { Text(texts.t("Close")) }
        }
        HorizontalDivider()
    }
}
