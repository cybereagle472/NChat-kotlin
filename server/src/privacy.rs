//! Privacy rules — port of `worker/src/lib/privacy.ts`.
//!
//! Each user owns a row of visibility tiers (`everyone` / `contacts` /
//! `nobody`). Profiles are redacted from the *viewer's* point of view, so a
//! contact who hides their last seen stays hidden while you still see your own
//! profile in full.

use rusqlite::{params, params_from_iter, Connection, OptionalExtension};
use serde_json::Value;
use std::collections::{HashMap, HashSet};

pub const OPTIONS: [&str; 3] = ["everyone", "contacts", "nobody"];

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Visibility {
    Everyone,
    Contacts,
    Nobody,
}

impl Visibility {
    pub fn parse(raw: &str) -> Option<Self> {
        match raw {
            "everyone" => Some(Self::Everyone),
            "contacts" => Some(Self::Contacts),
            "nobody" => Some(Self::Nobody),
            _ => None,
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Everyone => "everyone",
            Self::Contacts => "contacts",
            Self::Nobody => "nobody",
        }
    }

    /// `isVisible(setting, isSelf, isFriend)`.
    pub fn allows(&self, is_self: bool, is_friend: bool) -> bool {
        if is_self {
            return true;
        }
        match self {
            Self::Everyone => true,
            Self::Contacts => is_friend,
            Self::Nobody => false,
        }
    }
}

#[derive(Clone, Debug)]
pub struct PrivacySettings {
    pub user_id: String,
    pub last_seen_visibility: Visibility,
    pub profile_photo_visibility: Visibility,
    pub about_visibility: Visibility,
    pub status_visibility: Visibility,
    pub read_receipts: bool,
    pub online_status_enabled: bool,
}

impl PrivacySettings {
    pub fn defaults(user_id: &str) -> Self {
        Self {
            user_id: user_id.to_string(),
            last_seen_visibility: Visibility::Everyone,
            profile_photo_visibility: Visibility::Everyone,
            about_visibility: Visibility::Everyone,
            status_visibility: Visibility::Contacts,
            read_receipts: true,
            online_status_enabled: true,
        }
    }

    fn from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Self> {
        let user_id: String = row.get("user_id")?;
        let defaults = Self::defaults(&user_id);
        let parse = |value: Option<String>, fallback: Visibility| {
            value
                .and_then(|text| Visibility::parse(&text))
                .unwrap_or(fallback)
        };

        Ok(Self {
            user_id,
            last_seen_visibility: parse(
                row.get("last_seen_visibility")?,
                defaults.last_seen_visibility,
            ),
            profile_photo_visibility: parse(
                row.get("profile_photo_visibility")?,
                defaults.profile_photo_visibility,
            ),
            about_visibility: parse(row.get("about_visibility")?, defaults.about_visibility),
            status_visibility: parse(row.get("status_visibility")?, defaults.status_visibility),
            read_receipts: row.get::<_, i64>("read_receipts")? != 0,
            online_status_enabled: row.get::<_, i64>("online_status_enabled")? != 0,
        })
    }

    pub fn to_json(&self) -> Value {
        serde_json::json!({
            "user_id": self.user_id,
            "last_seen_visibility": self.last_seen_visibility.as_str(),
            "profile_photo_visibility": self.profile_photo_visibility.as_str(),
            "about_visibility": self.about_visibility.as_str(),
            "status_visibility": self.status_visibility.as_str(),
            "read_receipts": if self.read_receipts { 1 } else { 0 },
            "online_status_enabled": if self.online_status_enabled { 1 } else { 0 },
        })
    }
}

pub fn get_privacy_settings(
    conn: &Connection,
    user_id: &str,
) -> rusqlite::Result<PrivacySettings> {
    let row = conn
        .query_row(
            "SELECT * FROM privacy_settings WHERE user_id = ?",
            params![user_id],
            PrivacySettings::from_row,
        )
        .optional()?;
    Ok(row.unwrap_or_else(|| PrivacySettings::defaults(user_id)))
}

pub fn get_privacy_settings_map(
    conn: &Connection,
    user_ids: &[String],
) -> rusqlite::Result<HashMap<String, PrivacySettings>> {
    let mut map = HashMap::new();
    let mut unique: Vec<&String> = Vec::new();
    for id in user_ids {
        if !id.is_empty() && !unique.iter().any(|existing| *existing == id) {
            unique.push(id);
        }
    }
    if unique.is_empty() {
        return Ok(map);
    }

    let placeholders = vec!["?"; unique.len()].join(",");
    let sql = format!("SELECT * FROM privacy_settings WHERE user_id IN ({placeholders})");
    let mut statement = conn.prepare(&sql)?;
    let mut rows = statement.query(params_from_iter(unique.iter()))?;
    while let Some(row) = rows.next()? {
        let settings = PrivacySettings::from_row(row)?;
        map.insert(settings.user_id.clone(), settings);
    }
    for id in unique {
        map.entry(id.clone())
            .or_insert_with(|| PrivacySettings::defaults(id));
    }
    Ok(map)
}

/// `upsertPrivacySettings` — always writes the full row.
pub fn upsert_privacy_settings(
    conn: &Connection,
    next: &PrivacySettings,
) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO privacy_settings
           (user_id, last_seen_visibility, profile_photo_visibility, about_visibility,
            status_visibility, read_receipts, online_status_enabled)
         VALUES (?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(user_id) DO UPDATE SET
           last_seen_visibility = excluded.last_seen_visibility,
           profile_photo_visibility = excluded.profile_photo_visibility,
           about_visibility = excluded.about_visibility,
           status_visibility = excluded.status_visibility,
           read_receipts = excluded.read_receipts,
           online_status_enabled = excluded.online_status_enabled",
        params![
            next.user_id,
            next.last_seen_visibility.as_str(),
            next.profile_photo_visibility.as_str(),
            next.about_visibility.as_str(),
            next.status_visibility.as_str(),
            if next.read_receipts { 1 } else { 0 },
            if next.online_status_enabled { 1 } else { 0 },
        ],
    )?;
    Ok(())
}

/// `areFriends` — self always counts as a friend so self views never redact.
pub fn are_friends(conn: &Connection, a: &str, b: &str) -> rusqlite::Result<bool> {
    if a == b {
        return Ok(true);
    }
    let found: Option<i64> = conn
        .query_row(
            "SELECT 1 FROM friendships
             WHERE (user1_id = ? AND user2_id = ?) OR (user1_id = ? AND user2_id = ?)",
            params![a, b, b, a],
            |row| row.get(0),
        )
        .optional()?;
    Ok(found.is_some())
}

/// `getFriendIdSet` — which of `other_ids` are friends of `user_id`.
pub fn friend_id_set(
    conn: &Connection,
    user_id: &str,
    other_ids: &[String],
) -> rusqlite::Result<HashSet<String>> {
    let mut set = HashSet::new();
    let mut unique: Vec<&String> = Vec::new();
    for id in other_ids {
        if !id.is_empty() && id != user_id && !unique.iter().any(|existing| *existing == id) {
            unique.push(id);
        }
    }
    if unique.is_empty() {
        return Ok(set);
    }

    let placeholders = vec!["?"; unique.len()].join(",");
    let sql = format!(
        "SELECT user1_id, user2_id FROM friendships
         WHERE (user1_id = ? AND user2_id IN ({placeholders}))
            OR (user2_id = ? AND user1_id IN ({placeholders}))"
    );
    let mut statement = conn.prepare(&sql)?;
    let mut bound: Vec<&dyn rusqlite::ToSql> = Vec::new();
    bound.push(&user_id);
    for id in &unique {
        bound.push(*id);
    }
    bound.push(&user_id);
    for id in &unique {
        bound.push(*id);
    }

    let mut rows = statement.query(params_from_iter(bound))?;
    while let Some(row) = rows.next()? {
        let first: String = row.get(0)?;
        let second: String = row.get(1)?;
        if first == user_id {
            set.insert(second);
        } else {
            set.insert(first);
        }
    }
    Ok(set)
}

/// `applyPrivacy()` — mutate a profile-shaped object in place.
pub fn apply_privacy(
    profile: &mut Value,
    viewer_id: &str,
    settings: &PrivacySettings,
    is_friend: bool,
) {
    let Some(object) = profile.as_object_mut() else {
        return;
    };
    let target_id = object
        .get("id")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();
    let is_self = target_id == viewer_id;

    if !settings.profile_photo_visibility.allows(is_self, is_friend) {
        object.remove("avatar_url");
    }
    if !settings.about_visibility.allows(is_self, is_friend) {
        object.remove("bio");
    }
    if !settings.last_seen_visibility.allows(is_self, is_friend) {
        object.remove("last_seen");
    }
    if !is_self && !settings.online_status_enabled {
        object.insert("is_online".into(), Value::Bool(false));
    }
}

/// `applyPrivacyToList()` — one batch of settings lookups for a whole page.
pub fn apply_privacy_to_list(
    conn: &Connection,
    viewer_id: &str,
    profiles: &mut [Value],
) -> rusqlite::Result<()> {
    let ids: Vec<String> = profiles
        .iter()
        .filter_map(|profile| profile.get("id").and_then(Value::as_str))
        .filter(|id| *id != viewer_id)
        .map(str::to_string)
        .collect();
    if ids.is_empty() {
        return Ok(());
    }

    let settings_map = get_privacy_settings_map(conn, &ids)?;
    let friends = friend_id_set(conn, viewer_id, &ids)?;
    for profile in profiles.iter_mut() {
        let id = profile
            .get("id")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        if id == viewer_id {
            continue;
        }
        let settings = settings_map
            .get(&id)
            .cloned()
            .unwrap_or_else(|| PrivacySettings::defaults(&id));
        apply_privacy(profile, viewer_id, &settings, friends.contains(&id));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn visibility_tiers() {
        assert!(Visibility::Everyone.allows(false, false));
        assert!(!Visibility::Contacts.allows(false, false));
        assert!(Visibility::Contacts.allows(false, true));
        assert!(!Visibility::Nobody.allows(false, false));
        assert!(Visibility::Nobody.allows(true, false));
    }
}
