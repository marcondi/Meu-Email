// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
//! Merge note-only snapshots shared through a folder synced by a cloud-drive client.

use std::collections::BTreeMap;
use std::sync::Arc;

use mailcraft_model::{Mailbox, Note};
use serde::{Deserialize, Serialize};

/// Snapshot format understood by every MailCraft installation.
pub const SNAPSHOT_VERSION: u32 = 1;
/// Most notes one installation will read from all snapshots combined.
pub const MAX_SYNC_NOTES: usize = 20_000;
/// Total note records accepted across a read of snapshots (snapshots may repeat notes).
pub const MAX_SYNC_RECORDS: usize = 100_000;
/// Longest stable note or device identity accepted from a snapshot.
pub const MAX_SYNC_ID_BYTES: usize = 256;
/// Longest text body accepted on the note-only sync path.
pub const MAX_SYNC_NOTE_BYTES: usize = 1_000_000;
/// Maximum combined note text considered in one merge, including repeated snapshots.
pub const MAX_SYNC_TEXT_BYTES: usize = 64_000_000;
/// Combined identities, colours, and category labels in the snapshots being merged.
pub const MAX_SYNC_METADATA_BYTES: usize = 16_000_000;
/// Maximum snapshot files considered at once.
pub const MAX_SYNC_SNAPSHOTS: usize = 64;

/// One installation's note state; callers store one file per `device_id` to avoid write races.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct NoteSnapshot {
    pub version: u32,
    pub device_id: String,
    pub notes: Vec<Note>,
}

/// Why a Drive snapshot could not safely be merged.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NoteSnapshotError {
    UnsupportedVersion(u32),
    InvalidDeviceId,
    TooManyNotes,
    NoteTooLarge,
    SnapshotTooLarge,
    InvalidNoteContent,
    InvalidNoteId,
    IdentityUnavailable,
}

impl std::fmt::Display for NoteSnapshotError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::UnsupportedVersion(version) => write!(f, "unsupported note snapshot version {version}"),
            Self::InvalidDeviceId => f.write_str("invalid note snapshot device id"),
            Self::TooManyNotes => f.write_str("note snapshot contains too many notes"),
            Self::NoteTooLarge => f.write_str("note text is too large to sync"),
            Self::SnapshotTooLarge => f.write_str("combined note snapshot text is too large"),
            Self::InvalidNoteContent => f.write_str("note snapshot contains invalid note content"),
            Self::InvalidNoteId => f.write_str("note snapshot contains an invalid note id"),
            Self::IdentityUnavailable => f.write_str("could not create a stable note identity"),
        }
    }
}

impl std::error::Error for NoteSnapshotError {}

/// A fresh identity for a note, independent of the mailbox's local numeric id.
pub fn new_sync_id() -> Result<String, NoteSnapshotError> {
    #[cfg(not(target_arch = "wasm32"))]
    {
        use ring::rand::SecureRandom;
        let mut bytes = [0_u8; 16];
        ring::rand::SystemRandom::new().fill(&mut bytes).map_err(|_| NoteSnapshotError::IdentityUnavailable)?;
        let mut id = String::with_capacity(bytes.len() * 2);
        for byte in bytes {
            use std::fmt::Write;
            write!(&mut id, "{byte:02x}").map_err(|_| NoteSnapshotError::IdentityUnavailable)?;
        }
        Ok(id)
    }
    #[cfg(target_arch = "wasm32")]
    {
        use std::sync::atomic::{AtomicU64, Ordering};
        use std::time::{SystemTime, UNIX_EPOCH};

        static NEXT_ID: AtomicU64 = AtomicU64::new(0);
        let ticks = SystemTime::now().duration_since(UNIX_EPOCH).map_err(|_| NoteSnapshotError::IdentityUnavailable)?.as_nanos();
        Ok(format!("{ticks:032x}{:016x}", NEXT_ID.fetch_add(1, Ordering::Relaxed)))
    }
}

/// Validate an installation id before using it to label a snapshot or resolve a tie.
pub fn valid_device_id(device_id: &str) -> bool {
    !device_id.is_empty()
        && device_id.len() <= MAX_SYNC_ID_BYTES
        && device_id.bytes().all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_'))
}

/// The note-only snapshot for one installation. Imported PST notes are never included.
pub fn snapshot_for(mailbox: &Mailbox, device_id: &str) -> Result<NoteSnapshot, NoteSnapshotError> {
    if !valid_device_id(device_id) {
        return Err(NoteSnapshotError::InvalidDeviceId);
    }
    let mut notes = Vec::new();
    let mut total_text = 0usize;
    let mut total_metadata = 0usize;
    for note in mailbox.notes.values().filter(|note| !note.sync_id.is_empty()) {
        if notes.len() >= MAX_SYNC_NOTES {
            return Err(NoteSnapshotError::TooManyNotes);
        }
        let mut note = note.as_ref().clone();
        if note.text.len() > MAX_SYNC_NOTE_BYTES {
            return Err(NoteSnapshotError::NoteTooLarge);
        }
        total_text = total_text.saturating_add(note.text.len());
        if total_text > MAX_SYNC_TEXT_BYTES {
            return Err(NoteSnapshotError::SnapshotTooLarge);
        }
        if note.sync_id.len() > MAX_SYNC_ID_BYTES
            || note.color.len() > 32
            || note.categories.len() > 128
            || note.categories.iter().any(|category| category.len() > 256)
        {
            return Err(NoteSnapshotError::InvalidNoteContent);
        }
        if note.updated_by.is_empty() {
            note.updated_by = device_id.to_string();
        }
        if !valid_device_id(&note.updated_by) {
            return Err(NoteSnapshotError::InvalidNoteContent);
        }
        total_metadata = total_metadata
            .saturating_add(note.sync_id.len())
            .saturating_add(note.updated_by.len())
            .saturating_add(note.color.len())
            .saturating_add(note.categories.iter().map(String::len).sum::<usize>());
        if total_metadata > MAX_SYNC_METADATA_BYTES {
            return Err(NoteSnapshotError::SnapshotTooLarge);
        }
        notes.push(note);
    }
    Ok(NoteSnapshot { version: SNAPSHOT_VERSION, device_id: device_id.to_string(), notes })
}

/// Merge snapshots with local managed notes, keeping mailbox-local ids and imported notes intact.
pub fn merge_into_mailbox(mailbox: &mut Mailbox, device_id: &str, snapshots: &[NoteSnapshot]) -> Result<usize, NoteSnapshotError> {
    if !valid_device_id(device_id) {
        return Err(NoteSnapshotError::InvalidDeviceId);
    }
    if snapshots.len() > MAX_SYNC_SNAPSHOTS {
        return Err(NoteSnapshotError::TooManyNotes);
    }
    let mut all = snapshots.to_vec();
    let local = snapshot_for(mailbox, device_id)?;
    all.push(local);
    let merged = merge_snapshots(&all)?;
    let mut changed = 0usize;
    if merged.len() > MAX_SYNC_NOTES {
        return Err(NoteSnapshotError::TooManyNotes);
    }
    let local_ids: BTreeMap<String, mailcraft_model::Id> =
        mailbox.notes.iter().filter_map(|(id, note)| (!note.sync_id.is_empty()).then(|| (note.sync_id.clone(), *id))).collect();
    for mut note in merged {
        if note.text.len() > MAX_SYNC_NOTE_BYTES {
            return Err(NoteSnapshotError::NoteTooLarge);
        }
        let existing = local_ids.get(&note.sync_id).copied();
        if let Some(id) = existing {
            if let Some(current) = mailbox.notes.get_mut(&id) {
                note.id = id;
                if **current != note {
                    *Arc::make_mut(current) = note;
                    changed = changed.saturating_add(1);
                }
            }
        } else {
            mailbox.add_note(note);
            changed = changed.saturating_add(1);
        }
    }
    Ok(changed)
}

/// Validate snapshots and choose one state per stable note identity.
///
/// Higher per-note edit counters win, then timestamps; tombstones and the lexically greater
/// `updated_by` settle ties so every device makes the same choice. Divergent edits at one counter
/// are retained as a conflict copy. PST-imported notes have no sync identity and are excluded.
pub fn merge_snapshots(snapshots: &[NoteSnapshot]) -> Result<Vec<Note>, NoteSnapshotError> {
    let mut merged: BTreeMap<String, Note> = BTreeMap::new();
    let mut total = 0usize;
    let mut total_text = 0usize;
    let mut total_metadata = 0usize;

    for snapshot in snapshots {
        if snapshot.version != SNAPSHOT_VERSION {
            return Err(NoteSnapshotError::UnsupportedVersion(snapshot.version));
        }
        if snapshot.device_id.is_empty() || snapshot.device_id.len() > MAX_SYNC_ID_BYTES {
            return Err(NoteSnapshotError::InvalidDeviceId);
        }
        total = total.saturating_add(snapshot.notes.len());
        if total > MAX_SYNC_RECORDS {
            return Err(NoteSnapshotError::TooManyNotes);
        }
        for candidate in &snapshot.notes {
            if candidate.sync_id.is_empty() {
                continue;
            }
            if candidate.sync_id.len() > MAX_SYNC_ID_BYTES {
                return Err(NoteSnapshotError::InvalidNoteId);
            }
            if candidate.text.len() > MAX_SYNC_NOTE_BYTES {
                return Err(NoteSnapshotError::NoteTooLarge);
            }
            total_text = total_text.saturating_add(candidate.text.len());
            if total_text > MAX_SYNC_TEXT_BYTES {
                return Err(NoteSnapshotError::SnapshotTooLarge);
            }
            let mut candidate = candidate.clone();
            if candidate.updated_by.is_empty() {
                candidate.updated_by.clone_from(&snapshot.device_id);
            }
            if !valid_device_id(&candidate.updated_by)
                || candidate.color.len() > 32
                || candidate.categories.len() > 128
                || candidate.categories.iter().any(|category| category.len() > 256)
            {
                return Err(NoteSnapshotError::InvalidNoteContent);
            }
            total_metadata = total_metadata
                .saturating_add(candidate.sync_id.len())
                .saturating_add(candidate.updated_by.len())
                .saturating_add(candidate.color.len())
                .saturating_add(candidate.categories.iter().map(String::len).sum::<usize>());
            if total_metadata > MAX_SYNC_METADATA_BYTES {
                return Err(NoteSnapshotError::SnapshotTooLarge);
            }
            if let Some(current) = merged.get(&candidate.sync_id).cloned() {
                if concurrent_edits(&current, &candidate) {
                    let losing_revision = if revision_key(&current) < revision_key(&candidate) { &current } else { &candidate };
                    let copy = conflict_copy(losing_revision);
                    if merged.len() >= MAX_SYNC_NOTES && !merged.contains_key(&copy.sync_id) {
                        return Err(NoteSnapshotError::TooManyNotes);
                    }
                    merged.entry(copy.sync_id.clone()).or_insert(copy);
                }
                if revision_key(&current) < revision_key(&candidate) {
                    merged.insert(candidate.sync_id.clone(), candidate);
                }
            } else {
                merged.insert(candidate.sync_id.clone(), candidate);
            }
        }
    }

    if merged.len() > MAX_SYNC_NOTES {
        return Err(NoteSnapshotError::TooManyNotes);
    }

    Ok(merged.into_values().collect())
}

fn revision_key(note: &Note) -> (u64, i64, bool, &str) {
    (note.revision, note.modified, note.deleted, &note.updated_by)
}

fn concurrent_edits(left: &Note, right: &Note) -> bool {
    !left.deleted
        && !right.deleted
        && left.revision == right.revision
        && left.updated_by != right.updated_by
        && (left.text != right.text || left.color != right.color || left.categories != right.categories)
}

fn conflict_copy(note: &Note) -> Note {
    let mut copy = note.clone();
    copy.sync_id = format!("conflict:{:032x}", conflict_hash(&note.sync_id, &note.updated_by));
    copy
}

/// Stable FNV-1a 128-bit identity for a preserved concurrent revision.
fn conflict_hash(sync_id: &str, updated_by: &str) -> u128 {
    let mut hash = 0x6c62272e07bb014262b821756295c58d_u128;
    for byte in sync_id.bytes().chain([0]).chain(updated_by.bytes()) {
        hash = (hash ^ u128::from(byte)).wrapping_mul(0x0000000001000000000000000000013b_u128);
    }
    hash
}
