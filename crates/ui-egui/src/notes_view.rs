// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
//! MailCraft notes: editable, app-owned notes with imported PST notes left read-only.

use egui::{Align, Layout, Ui};
use serde_json::{Value, json};

use crate::MailApp;

pub fn show(app: &mut MailApp, ui: &mut Ui) {
    let device_id = app.ui.notes_sync_device_id.clone();
    let list = app.execute("notes.list", json!({ "limit": 5000 })).unwrap_or(Value::Null);
    let notes: Vec<Value> = list.get("notes").and_then(Value::as_array).cloned().unwrap_or_default();
    let selection_key = egui::Id::unique("mailcraft.notes.selected");
    let mut selected = ui.ctx().data(|data| data.get_temp::<u64>(selection_key));
    if !selected.is_some_and(|id| notes.iter().any(|note| note.get("id").and_then(Value::as_u64) == Some(id))) {
        selected = notes.first().and_then(|note| note.get("id").and_then(Value::as_u64));
        if let Some(id) = selected {
            ui.ctx().data_mut(|data| data.insert_temp(selection_key, id));
        }
    }

    ui.horizontal(|ui| {
        if ui.button(crate::tl!("New note")).clicked()
            && let Ok(created) = app.run("notes.new", json!({ "deviceId": device_id.as_str() }))
            && let Some(id) = created.get("id").and_then(Value::as_u64)
        {
            selected = Some(id);
            ui.ctx().data_mut(|data| data.insert_temp(selection_key, id));
        }
        if ui.button(crate::tl!("Choose Google Drive folder…")).clicked() {
            let _ = app.run("ui.notesSyncFolder", json!({}));
        }
        if let Some(folder) = (!app.ui.notes_sync_folder.is_empty()).then_some(app.ui.notes_sync_folder.as_str()) {
            ui.label(format!("{}: {folder} / MailCraft Notes", crate::tl!("Drive folder")));
        } else {
            ui.label(crate::tl!("Choose the local folder that Google Drive syncs with your phone."));
        }
    });
    ui.separator();
    ui.columns(2, |columns| {
        columns[0].with_layout(Layout::top_down(Align::Min), |ui| {
            ui.set_min_width(210.0);
            ui.heading(crate::tl!("Your notes"));
            egui::ScrollArea::vertical().show(ui, |ui| {
                for note in &notes {
                    let Some(id) = note.get("id").and_then(Value::as_u64) else { continue };
                    let title = note.get("subject").and_then(Value::as_str).filter(|value| !value.is_empty()).unwrap_or(crate::tl!("Untitled note"));
                    let snippet = note.get("snippet").and_then(Value::as_str).unwrap_or_default();
                    if ui.selectable_label(selected == Some(id), format!("{title}\n{snippet}")).clicked() {
                        selected = Some(id);
                        ui.ctx().data_mut(|data| data.insert_temp(selection_key, id));
                    }
                }
            });
        });
        columns[1].with_layout(Layout::top_down(Align::Min), |ui| {
            let Some(id) = selected else {
                ui.heading(crate::tl!("No notes yet"));
                ui.label(crate::tl!("Create a note to start syncing it between your devices."));
                return;
            };
            let note = app.execute("notes.get", json!({ "id": id })).unwrap_or(Value::Null);
            let read_only = note.get("readOnly").and_then(Value::as_bool).unwrap_or(true);
            let text_key = egui::Id::unique(("mailcraft.notes.draft", id));
            let original = note.get("body").and_then(Value::as_str).unwrap_or_default().to_string();
            let cached = ui.ctx().data(|data| data.get_temp::<(String, String)>(text_key));
            let mut draft = cached.map_or_else(
                || original.clone(),
                |(old_original, old_draft)| {
                    if old_original == old_draft { original.clone() } else { old_draft }
                },
            );
            ui.add_enabled(
                !read_only,
                egui::TextEdit::multiline(&mut draft).desired_rows(22).hint_text(crate::tl!("Write your note…")).desired_width(f32::INFINITY),
            );
            ui.ctx().data_mut(|data| data.insert_temp(text_key, (original.clone(), draft.clone())));
            ui.horizontal(|ui| {
                if ui.add_enabled(!read_only && draft != original, egui::Button::new(crate::tl!("Save note"))).clicked() {
                    let _ = app.run("notes.edit", json!({ "id": id, "text": draft, "deviceId": device_id.as_str() }));
                    ui.ctx().data_mut(|data| data.insert_temp(text_key, (original.clone(), draft.clone())));
                }
                if ui.add_enabled(!read_only, egui::Button::new(crate::tl!("Delete note"))).clicked() {
                    let _ = app.run("notes.delete", json!({ "id": id, "deviceId": device_id.as_str() }));
                    ui.ctx().data_mut(|data| data.remove::<u64>(selection_key));
                    ui.ctx().data_mut(|data| data.remove::<(String, String)>(text_key));
                }
            });
            if read_only {
                ui.label(crate::tl!("Imported PST notes are read-only."));
            }
            ui.label(crate::tl!("Changes sync automatically when MailCraft is open and the Drive folder is available."));
        });
    });
}
