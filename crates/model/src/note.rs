// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0
//! Notes and journal entries, as they come from a .pst file (#815): kept, listed and searched;
//! views of their own come later.

use mailcraft_time::Timestamp;
use serde::{Deserialize, Serialize};

use crate::{Address, Id};

/// A sticky note.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Note {
    pub id: Id,
    pub text: String,
    /// `blue`, `green`, `pink`, `yellow` or `white`.
    pub color: String,
    pub categories: Vec<String>,
    pub created: Timestamp,
    pub modified: Timestamp,
    /// What it is known by in the file it came from, so that a second import skips it.
    pub imported: String,
    /// Stable identity shared across MailCraft installations; empty for imported PST notes.
    pub sync_id: String,
    /// Installation that last changed this note, used as a deterministic merge tie-breaker.
    pub updated_by: String,
    /// Per-note edit counter; concurrent devices starting from the same revision can be retained.
    pub revision: u64,
    /// A tombstone kept in Drive snapshots so a stale device cannot restore a deleted note.
    pub deleted: bool,
}

/// A record of something done, and when.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct JournalEntry {
    pub id: Id,
    pub subject: String,
    /// What kind of thing it records, in the file's words (`Phone call`).
    pub activity: String,
    pub start: Timestamp,
    pub end: Timestamp,
    pub minutes: u32,
    pub text: String,
    pub people: Vec<Address>,
    pub categories: Vec<String>,
    pub imported: String,
}
