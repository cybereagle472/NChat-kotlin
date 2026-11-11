//! Row types and the JSON shapes they serialise to.
//!
//! Key names are copied character-for-character from the TypeScript helpers
//! (`userPublic`, `profilePublic`, `messageJson`) — including the two odd
//! camelCase keys the reference emits for attachments (`fileName`/`fileSize`) —
//! because the frontend reads them literally.

use rusqlite::Row;
use serde_json::{json, Value};

use crate::presence::compute_is_online;

pub struct UserRow {
    pub id: String,
    pub email: String,
    pub password_hash: String,
    pub full_name: String,
    pub username: Option<String>,
    pub username_last_changed: Option<String>,
    pub avatar_url: Option<String>,
    pub bio: Option<String>,
    pub is_admin: bool,
    pub created_at: String,
}

impl UserRow {
    pub fn from_row(row: &Row<'_>) -> rusqlite::Result<Self> {
        Ok(Self {
            id: row.get("id")?,
            email: row.get("email")?,
            password_hash: row.get("password_hash")?,
            full_name: row.get("full_name")?,
            username: row.get("username")?,
            username_last_changed: row.get("username_last_changed")?,
            avatar_url: row.get("avatar_url")?,
            bio: row.get("bio")?,
            is_admin: row.get::<_, i64>("is_admin")? != 0,
            created_at: row.get("created_at")?,
        })
    }

    /// `userPublic()` in `routes/auth.ts`: an account as its owner sees it.
    ///
    /// The only projection with the e-mail address and the admin flag in it. It is for
    /// answers about the caller's own account (`/auth/*`, and through [`Self::own_profile`]
    /// `/users/me`); what other people get is [`Self::profile`] or [`Self::brief`].
    pub fn user_public(&self) -> Value {
        let mut value = self.shared_fields();
        let object = value.as_object_mut().expect("object");
        object.insert("email".into(), json!(self.email));
        object.insert("is_admin".into(), json!(self.is_admin));
        value
    }

    /// What every projection of a profile has in common.
    fn shared_fields(&self) -> Value {
        let mut value = json!({
            "id": self.id,
            "full_name": self.full_name,
            "created_at": self.created_at,
        });
        let object = value.as_object_mut().expect("object");
        if let Some(username) = &self.username {
            object.insert("username".into(), json!(username));
        }
        if let Some(avatar) = &self.avatar_url {
            object.insert("avatar_url".into(), json!(avatar));
        }
        if let Some(bio) = &self.bio {
            object.insert("bio".into(), json!(bio));
        }
        value
    }

    /// Presence and username history, added to either kind of profile.
    fn add_presence(&self, value: &mut Value, last_active: Option<i64>) {
        let object = value.as_object_mut().expect("object");
        if let Some(changed) = &self.username_last_changed {
            object.insert("username_last_changed".into(), json!(changed));
        }
        object.insert("is_online".into(), json!(compute_is_online(last_active)));
        if let Some(ms) = last_active {
            object.insert("last_seen".into(), json!(iso_from_millis(ms)));
        }
    }

    /// `profilePublic()` in `routes/users.ts` (presence + username history), as somebody
    /// else sees it.
    ///
    /// The reference put the e-mail address and the admin flag in here, so every signed-in
    /// account could list every other account's address. People find each other by name
    /// and username; the address somebody signed up with is theirs.
    pub fn profile(&self, last_active: Option<i64>) -> Value {
        let mut value = self.shared_fields();
        self.add_presence(&mut value, last_active);
        value
    }

    /// The caller's own profile: [`Self::profile`] plus the two fields that are theirs alone.
    /// The Android client stores its identity, e-mail address included, from this.
    pub fn own_profile(&self, last_active: Option<i64>) -> Value {
        let mut value = self.user_public();
        self.add_presence(&mut value, last_active);
        value
    }

    /// `profilePublic()` in `routes/friends.ts` — no admin flag, no created_at, and (unlike
    /// the reference) no e-mail address.
    pub fn brief(&self, last_active: Option<i64>) -> Value {
        let mut value = json!({
            "id": self.id,
            "full_name": self.full_name,
            "is_online": compute_is_online(last_active),
        });
        let object = value.as_object_mut().expect("object");
        if let Some(username) = &self.username {
            object.insert("username".into(), json!(username));
        }
        if let Some(avatar) = &self.avatar_url {
            object.insert("avatar_url".into(), json!(avatar));
        }
        if let Some(bio) = &self.bio {
            object.insert("bio".into(), json!(bio));
        }
        if let Some(ms) = last_active {
            object.insert("last_seen".into(), json!(iso_from_millis(ms)));
        }
        value
    }
}

fn iso_from_millis(ms: i64) -> String {
    chrono::DateTime::<chrono::Utc>::from_timestamp_millis(ms)
        .map(|dt| dt.to_rfc3339_opts(chrono::SecondsFormat::Millis, true))
        .unwrap_or_default()
}

/// A `messages` row plus the JSON projection the frontend expects.
pub struct MessageRow {
    pub id: String,
    pub sender_id: String,
    pub receiver_id: String,
    pub content: String,
    pub kind: String,
    pub media_url: Option<String>,
    pub thumbnail_url: Option<String>,
    pub file_name: Option<String>,
    pub file_size: Option<String>,
    pub duration: Option<String>,
    pub client_message_id: Option<String>,
    pub reply_to: Option<String>,
    pub is_read: bool,
    pub is_edited: bool,
    pub edited_at: Option<String>,
    pub is_deleted: bool,
    pub deleted_for: Option<String>,
    pub reactions: Option<String>,
    pub created_at: String,
}

fn json_column(raw: &Option<String>) -> Value {
    match raw {
        Some(text) => serde_json::from_str(text).unwrap_or(Value::Null),
        None => Value::Null,
    }
}

impl MessageRow {
    pub fn from_row(row: &Row<'_>) -> rusqlite::Result<Self> {
        Ok(Self {
            id: row.get("id")?,
            sender_id: row.get("sender_id")?,
            receiver_id: row.get("receiver_id")?,
            content: row.get("content")?,
            kind: row.get("type")?,
            media_url: row.get("media_url")?,
            thumbnail_url: row.get("thumbnail_url")?,
            file_name: row.get("file_name")?,
            file_size: row.get("file_size")?,
            duration: row.get("duration")?,
            client_message_id: row.get("client_message_id")?,
            reply_to: row.get("reply_to")?,
            is_read: row.get::<_, i64>("is_read")? != 0,
            is_edited: row.get::<_, i64>("is_edited")? != 0,
            edited_at: row.get("edited_at")?,
            is_deleted: row.get::<_, i64>("is_deleted")? != 0,
            deleted_for: row.get("deleted_for")?,
            reactions: row.get("reactions")?,
            created_at: row.get("created_at")?,
        })
    }

    /// `messageJson()` in `routes/messages.ts`.
    pub fn to_json(&self) -> Value {
        let mut value = json!({
            "id": self.id,
            "sender_id": self.sender_id,
            "receiver_id": self.receiver_id,
            "content": self.content,
            "type": self.kind,
            "is_read": self.is_read,
            "is_edited": self.is_edited,
            "is_deleted": self.is_deleted,
            "created_at": self.created_at,
        });
        let object = value.as_object_mut().expect("object");

        if let Some(url) = &self.media_url {
            object.insert("media_url".into(), json!(url));
        }
        if let Some(url) = &self.thumbnail_url {
            object.insert("thumbnail_url".into(), json!(url));
        }
        if let Some(name) = &self.file_name {
            object.insert("fileName".into(), json!(name));
        }
        if let Some(size) = &self.file_size {
            object.insert("fileSize".into(), json!(size));
        }
        if let Some(duration) = &self.duration {
            object.insert("duration".into(), json!(duration));
        }
        if let Some(client_id) = &self.client_message_id {
            object.insert("client_message_id".into(), json!(client_id));
        }
        if let Some(reply) = &self.reply_to {
            object.insert("reply_to".into(), json!(reply));
        }
        if let Some(edited_at) = &self.edited_at {
            object.insert("edited_at".into(), json!(edited_at));
        }
        if self.deleted_for.is_some() {
            object.insert("deleted_for".into(), json_column(&self.deleted_for));
        }
        if self.reactions.is_some() {
            object.insert("reactions".into(), json_column(&self.reactions));
        }
        value
    }

    pub fn deleted_for_ids(&self) -> Vec<String> {
        self.deleted_for
            .as_deref()
            .and_then(|raw| serde_json::from_str::<Vec<String>>(raw).ok())
            .unwrap_or_default()
    }
}
