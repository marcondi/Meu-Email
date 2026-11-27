// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
//! Desktop side of note-only synchronization through a local Google Drive mirror.

use std::{fs, path::Path};

use mailcraft_engine::notes_sync::{MAX_SYNC_SNAPSHOTS, NoteSnapshot, SNAPSHOT_VERSION};
use serde_json::json;

use crate::MailApp;

const APP_FOLDER: &str = "MailCraft Notes";
const MAX_FILE_BYTES: u64 = 70_000_000;
const MAX_TOTAL_BYTES: u64 = 100_000_000;
const KEEP_SNAPSHOTS: usize = 3;

/// Read peer snapshots, merge only managed notes, and atomically publish this device's state.
pub(crate) fn sync(app: &mut MailApp) {
    let root = app.ui.notes_sync_folder.trim();
    if root.is_empty() {
        return;
    }
    let device_id = app.ui.notes_sync_device_id.clone();
    if !mailcraft_engine::notes_sync::valid_device_id(&device_id) {
        app.status("Google Drive notes sync has an invalid device identity.");
        return;
    }
    let directory = Path::new(root).join(APP_FOLDER);
    if let Err(error) = ensure_app_directory(&directory) {
        app.status(format!("Google Drive note folder is unavailable: {error}"));
        return;
    }
    let snapshots = match read_snapshots(&directory) {
        Ok(snapshots) => snapshots,
        Err(error) => {
            app.status(format!("Google Drive notes could not be read: {error}"));
            return;
        }
    };
    for snapshot in snapshots {
        let params = json!({ "deviceId": device_id, "snapshots": [snapshot] });
        if let Err(error) = app.execute("notes.syncMerge", params) {
            app.status(format!("A MailCraft note snapshot was skipped: {error}"));
        }
    }
    let snapshot = match app.execute("notes.syncSnapshot", json!({ "deviceId": device_id })) {
        Ok(snapshot) => snapshot,
        Err(error) => {
            app.status(format!("MailCraft notes could not be prepared for Drive: {error}"));
            return;
        }
    };
    let bytes = match serde_json::to_vec(&snapshot) {
        Ok(bytes) if bytes.len() as u64 <= MAX_FILE_BYTES => bytes,
        Ok(_) => {
            app.status("MailCraft notes use too much space for one Drive snapshot.");
            return;
        }
        Err(error) => {
            app.status(format!("MailCraft notes could not be encoded: {error}"));
            return;
        }
    };
    if let Err(error) = publish(&directory, &device_id, &bytes) {
        app.status(format!("MailCraft notes could not be saved to Drive: {error}"));
        return;
    }
    app.status("Notes synced with Google Drive.");
}

fn ensure_app_directory(path: &Path) -> Result<(), String> {
    if path.exists() {
        let metadata = fs::symlink_metadata(path).map_err(|error| error.to_string())?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err("the MailCraft Notes path is not a regular folder".into());
        }
        return Ok(());
    }
    fs::create_dir(path).map_err(|error| error.to_string())
}

fn read_snapshots(directory: &Path) -> Result<Vec<NoteSnapshot>, String> {
    let entries = fs::read_dir(directory).map_err(|error| error.to_string())?;
    let mut paths = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|error| error.to_string())?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.starts_with("notes-") && name.ends_with(".json") {
            paths.push(entry.path());
            if paths.len() > MAX_SYNC_SNAPSHOTS {
                return Err("there are too many MailCraft note snapshots".into());
            }
        }
    }
    paths.sort();
    let mut total_bytes = 0u64;
    let mut snapshots = Vec::new();
    for path in paths {
        let metadata = fs::symlink_metadata(&path).map_err(|error| error.to_string())?;
        if metadata.file_type().is_symlink() || !metadata.is_file() {
            continue;
        }
        let size = metadata.len();
        if size > MAX_FILE_BYTES {
            continue;
        }
        total_bytes = total_bytes.saturating_add(size);
        if total_bytes > MAX_TOTAL_BYTES {
            return Err("combined note snapshots exceed the read limit".into());
        }
        let data = fs::read(path).map_err(|error| error.to_string())?;
        if let Ok(snapshot) = serde_json::from_slice::<NoteSnapshot>(&data)
            && snapshot.version == SNAPSHOT_VERSION
            && mailcraft_engine::notes_sync::valid_device_id(&snapshot.device_id)
        {
            snapshots.push(snapshot);
        }
    }
    Ok(snapshots)
}

fn publish(directory: &Path, device_id: &str, bytes: &[u8]) -> Result<(), String> {
    let stamp = crate::now_ms().max(0.0) as u64;
    let serial = mailcraft_engine::notes_sync::new_sync_id().map_err(|error| error.to_string())?;
    let filename = format!("notes-{device_id}-{stamp:013}-{serial}.json");
    let temp = directory.join(format!(".pending-{serial}.json"));
    let final_path = directory.join(filename);
    fs::write(&temp, bytes).map_err(|error| error.to_string())?;
    if let Err(error) = fs::rename(&temp, &final_path) {
        let _ = fs::remove_file(&temp);
        return Err(error.to_string());
    }
    cleanup(directory, device_id)
}

fn cleanup(directory: &Path, device_id: &str) -> Result<(), String> {
    let prefix = format!("notes-{device_id}-");
    let mut files = Vec::new();
    for entry in fs::read_dir(directory).map_err(|error| error.to_string())? {
        let entry = entry.map_err(|error| error.to_string())?;
        let name = entry.file_name();
        if name.to_string_lossy().starts_with(&prefix) && name.to_string_lossy().ends_with(".json") {
            files.push(entry.path());
        }
    }
    files.sort();
    let remove_count = files.len().saturating_sub(KEEP_SNAPSHOTS);
    for path in files.into_iter().take(remove_count) {
        let metadata = fs::symlink_metadata(&path).map_err(|error| error.to_string())?;
        if metadata.is_file() && !metadata.file_type().is_symlink() {
            fs::remove_file(path).map_err(|error| error.to_string())?;
        }
    }
    Ok(())
}
