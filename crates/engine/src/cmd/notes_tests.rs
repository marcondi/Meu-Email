// SPDX-FileCopyrightText: MailCraft contributors
// SPDX-License-Identifier: MIT OR Apache-2.0

use serde_json::{Value, json};

use crate::Session;

const NOW: i64 = 1_790_000_000;

fn session() -> Session {
    Session::at(crate::sample::mailbox(NOW, 0), NOW, 0)
}

fn run(s: &mut Session, command: &str, params: Value) -> Value {
    s.run(command, &params).unwrap_or_else(|error| panic!("{command} {params}: {error}"))
}

#[test]
fn a_native_note_gets_a_stable_identity_and_can_be_edited() {
    let mut s = session();
    let id = run(&mut s, "notes.new", json!({"text": "First", "deviceId": "pc"}))["id"].as_u64().unwrap();
    let local_id = id;
    let sync_id = s.mailbox.notes.get(&id).map(|note| note.sync_id.clone()).unwrap_or_default();

    assert!(!sync_id.is_empty());
    run(&mut s, "notes.edit", json!({"id": local_id, "text": "Updated", "deviceId": "pc"}));
    assert_eq!(s.mailbox.notes.get(&local_id).map(|note| note.text.as_str()), Some("Updated"));
    assert_eq!(s.mailbox.notes.get(&local_id).map(|note| note.sync_id.as_str()), Some(sync_id.as_str()));
}

#[test]
fn deleting_a_native_note_keeps_a_tombstone_and_hides_it_from_the_list() {
    let mut s = session();
    let id = run(&mut s, "notes.new", json!({"text": "Private text", "deviceId": "pc"}))["id"].as_u64().unwrap();
    let sync_id = s.mailbox.notes.get(&id).map(|note| note.sync_id.clone()).unwrap_or_default();

    run(&mut s, "notes.delete", json!({"id": id, "deviceId": "pc"}));

    let tombstone = s.mailbox.notes.get(&id);
    assert!(tombstone.is_some_and(|note| note.deleted && note.text.is_empty() && note.sync_id == sync_id));
    assert_eq!(run(&mut s, "notes.list", json!({"query": "Private"}))["total"], 0);
}

#[test]
fn sync_merge_keeps_offline_notes_from_both_devices() {
    let mut pc = session();
    run(&mut pc, "notes.new", json!({"text": "Made on PC", "deviceId": "pc"}));
    let pc_snapshot = run(&mut pc, "notes.syncSnapshot", json!({"deviceId": "pc"}));

    let mut phone = session();
    run(&mut phone, "notes.new", json!({"text": "Made on phone", "deviceId": "phone"}));
    let phone_snapshot = run(&mut phone, "notes.syncSnapshot", json!({"deviceId": "phone"}));
    run(&mut phone, "notes.syncMerge", json!({"deviceId": "phone", "snapshots": [pc_snapshot, phone_snapshot]}));

    let all = run(&mut phone, "notes.list", json!({"query": ""}));
    assert!(all["notes"].as_array().is_some_and(|notes| notes.iter().any(|note| note["subject"] == "Made on PC")));
    assert!(all["notes"].as_array().is_some_and(|notes| notes.iter().any(|note| note["subject"] == "Made on phone")));
}
