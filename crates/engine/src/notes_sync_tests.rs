// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0

use mailcraft_model::Note;

use crate::notes_sync::{NoteSnapshot, SNAPSHOT_VERSION, merge_snapshots};

fn note(sync_id: &str, text: &str, modified: i64, deleted: bool) -> Note {
    Note { sync_id: sync_id.into(), text: text.into(), modified, deleted, updated_by: "device".into(), ..Note::default() }
}

fn snapshot(device_id: &str, notes: Vec<Note>) -> NoteSnapshot {
    NoteSnapshot { version: SNAPSHOT_VERSION, device_id: device_id.into(), notes }
}

fn merged(snapshots: &[NoteSnapshot]) -> Vec<Note> {
    merge_snapshots(snapshots).unwrap()
}

#[test]
fn merge_keeps_notes_created_on_each_device() {
    let merged =
        merged(&[snapshot("pc", vec![note("pc:1", "From PC", 10, false)]), snapshot("phone", vec![note("phone:1", "From phone", 11, false)])]);

    assert_eq!(merged.iter().map(|n| n.sync_id.as_str()).collect::<Vec<_>>(), ["pc:1", "phone:1"]);
}

#[test]
fn merge_uses_the_newer_version_of_a_note() {
    let merged = merged(&[snapshot("pc", vec![note("shared:1", "Old", 10, false)]), snapshot("phone", vec![note("shared:1", "New", 11, false)])]);

    assert_eq!(merged.len(), 1);
    assert_eq!(merged[0].text, "New");
}

#[test]
fn merge_uses_the_edit_counter_before_clock_time() {
    let mut newer_revision = note("shared:1", "New revision", 10, false);
    newer_revision.revision = 2;
    let mut later_clock = note("shared:1", "Old revision", 50, false);
    later_clock.revision = 1;

    let merged = merged(&[snapshot("pc", vec![newer_revision]), snapshot("phone", vec![later_clock])]);

    assert_eq!(merged.len(), 1);
    assert_eq!(merged[0].text, "New revision");
}

#[test]
fn merge_keeps_a_deletion_tombstone_over_an_older_copy() {
    let merged = merged(&[snapshot("pc", vec![note("shared:1", "Keep", 10, false)]), snapshot("phone", vec![note("shared:1", "", 11, true)])]);

    assert_eq!(merged.len(), 1);
    assert!(merged[0].deleted);
}

#[test]
fn merge_ignores_legacy_notes_without_a_sync_identity() {
    let legacy = Note { text: "Imported from PST".into(), ..Note::default() };
    let merged = merged(&[snapshot("pc", vec![legacy])]);

    assert!(merged.is_empty());
}

#[test]
fn merge_preserves_concurrent_edits_and_is_independent_of_snapshot_order() {
    let mut alpha = note("shared:1", "Alpha", 10, false);
    alpha.updated_by = "alpha".into();
    let mut beta = note("shared:1", "Beta", 10, false);
    beta.updated_by = "beta".into();
    let left = snapshot("alpha", vec![alpha]);
    let right = snapshot("beta", vec![beta]);

    let forward = merged(&[left.clone(), right.clone()]);
    let backward = merged(&[right, left]);
    assert_eq!(forward, backward);
    assert_eq!(forward.len(), 2);
    assert!(forward.iter().any(|n| n.text == "Alpha"));
    assert!(forward.iter().any(|n| n.text == "Beta"));
}

#[test]
fn merge_rejects_an_unknown_snapshot_version() {
    let mut unknown = snapshot("pc", Vec::new());
    unknown.version = SNAPSHOT_VERSION.saturating_add(1);

    assert!(merge_snapshots(&[unknown]).is_err());
}
