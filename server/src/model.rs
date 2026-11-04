//! Wire model for the NChat backend.
//!
//! These types are serialised as JSON with `camelCase` keys so they map
//! one-to-one onto the Kotlin `data/model` classes in the Android client.
//! Anything persisted (the `Db` tree) also round-trips through these types, so
//! every new field must carry `#[serde(default)]` to stay compatible with an
//! existing `db.json` on disk.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

pub type Id = String;

/// Milliseconds since the Unix epoch — the single time unit on the wire.
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct User {
    pub id: Id,
    pub name: String,
    pub handle: String,
    #[serde(default)]
    pub about: String,
    #[serde(default)]
    pub avatar_emoji: Option<String>,
    pub created_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Credential {
    pub salt: String,
    pub hash: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum MessageKind {
    Text,
    Photo,
    Video,
    Voice,
    Document,
    Location,
}

impl Default for MessageKind {
    fn default() -> Self {
        MessageKind::Text
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub id: Id,
    pub chat_id: Id,
    /// Monotonic per-chat sequence number; also the read-receipt cursor.
    pub seq: i64,
    pub sender_id: Id,
    #[serde(default)]
    pub kind: MessageKind,
    #[serde(default)]
    pub text: String,
    pub created_at: i64,
    #[serde(default)]
    pub edited_at: Option<i64>,
    #[serde(default)]
    pub deleted: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Chat {
    pub id: Id,
    pub title: String,
    pub is_group: bool,
    #[serde(default)]
    pub avatar_emoji: Option<String>,
    pub member_ids: Vec<Id>,
    pub created_at: i64,
    #[serde(default)]
    pub last_seq: i64,
    #[serde(default)]
    pub pinned_by: Vec<Id>,
    #[serde(default)]
    pub muted_by: Vec<Id>,
    /// Per-user "delete chat": hidden for these members, kept for everyone else.
    #[serde(default)]
    pub hidden_for: Vec<Id>,
}

/// Chat list row: a chat folded together with its unread count and last message.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ChatSummary {
    pub id: Id,
    pub title: String,
    pub is_group: bool,
    pub avatar_emoji: Option<String>,
    pub member_ids: Vec<Id>,
    pub member_names: Vec<String>,
    pub last_message: Option<Message>,
    pub unread_count: u32,
    pub pinned: bool,
    pub muted: bool,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub id: Id,
    pub user_id: Id,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub emoji: Option<String>,
    pub created_at: i64,
    pub expires_at: i64,
}

/// The whole persisted state. Small enough to snapshot atomically; large enough
/// that the next step is an append-only journal (see `server/README.md`).
#[derive(Debug, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Db {
    #[serde(default)]
    pub users: Vec<User>,
    #[serde(default)]
    pub credentials: HashMap<Id, Credential>,
    #[serde(default)]
    pub chats: Vec<Chat>,
    #[serde(default)]
    pub messages: HashMap<Id, Vec<Message>>,
    /// `"{chatId}:{userId}" -> highest read message seq`.
    #[serde(default)]
    pub receipts: HashMap<String, i64>,
    #[serde(default)]
    pub statuses: Vec<Status>,
}

// ---------------------------------------------------------------- requests ---

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RegisterRequest {
    pub name: String,
    #[serde(default)]
    pub password: String,
    #[serde(default)]
    pub about: String,
    #[serde(default)]
    pub avatar_emoji: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LoginRequest {
    pub name: String,
    #[serde(default)]
    pub password: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateMeRequest {
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub about: Option<String>,
    #[serde(default)]
    pub avatar_emoji: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CreateChatRequest {
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub member_ids: Vec<Id>,
    #[serde(default)]
    pub is_group: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SendMessageRequest {
    #[serde(default)]
    pub kind: MessageKind,
    #[serde(default)]
    pub text: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EditMessageRequest {
    pub text: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReadRequest {
    pub message_id: Id,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PatchChatRequest {
    #[serde(default)]
    pub pinned: Option<bool>,
    #[serde(default)]
    pub muted: Option<bool>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TypingRequest {
    #[serde(default = "default_true")]
    pub typing: bool,
}

fn default_true() -> bool {
    true
}

// --------------------------------------------------------------- responses ---

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthResponse {
    pub token: String,
    pub user: User,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessagePage {
    pub messages: Vec<Message>,
    /// Cursor for the next page back in history; `null` when history is exhausted.
    pub next_before: Option<i64>,
}

#[derive(Debug, Serialize)]
pub struct Health {
    pub status: &'static str,
    pub version: &'static str,
    pub users: usize,
    pub chats: usize,
}
