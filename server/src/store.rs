//! In-memory store with atomic snapshot persistence.
//!
//! Every mutation takes the write lock, updates the tree and writes `db.json`
//! through a temp file + rename so a crash can never leave half a file behind.
//! Reads are lock-free-ish (shared `RwLock`), which keeps message listing at
//! tens of microseconds — the client's perceived latency is then dominated by
//! the network, not the backend.

use crate::auth;
use crate::error::ApiError;
use crate::model::*;
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::RwLock;

const MAX_TEXT: usize = 8_000;
const EDIT_WINDOW_MS: i64 = 15 * 60 * 1_000;
const DEFAULT_PAGE: usize = 50;
const MAX_PAGE: usize = 200;

pub struct Store {
    db: RwLock<Db>,
    path: Option<PathBuf>,
    online: RwLock<HashSet<Id>>,
    last_seen: RwLock<HashMap<Id, i64>>,
}

fn receipt_key(chat_id: &str, user_id: &str) -> String {
    format!("{chat_id}:{user_id}")
}

fn new_id() -> Id {
    uuid::Uuid::new_v4().to_string()
}

impl Store {
    pub fn open(path: Option<PathBuf>) -> Self {
        let db = path
            .as_ref()
            .and_then(|p| std::fs::read_to_string(p).ok())
            .and_then(|raw| serde_json::from_str::<Db>(&raw).ok())
            .unwrap_or_default();
        Self {
            db: RwLock::new(db),
            path,
            online: RwLock::new(HashSet::new()),
            last_seen: RwLock::new(HashMap::new()),
        }
    }

    pub fn counts(&self) -> (usize, usize) {
        let db = self.db.read().expect("db lock");
        (db.users.len(), db.chats.len())
    }

    fn persist(&self, db: &Db) -> Result<(), ApiError> {
        if let Some(path) = &self.path {
            let raw =
                serde_json::to_vec(db).map_err(|e| ApiError::Internal(format!("encode: {e}")))?;
            let tmp = path.with_extension("json.tmp");
            if let Some(parent) = path.parent() {
                std::fs::create_dir_all(parent)?;
            }
            std::fs::write(&tmp, raw)?;
            std::fs::rename(&tmp, path)?;
        }
        Ok(())
    }

    // ------------------------------------------------------------- accounts ---

    fn unique_handle(db: &Db, name: &str, except: Option<&str>) -> String {
        let base = auth::slugify(name);
        let taken = |candidate: &str| {
            db.users
                .iter()
                .any(|u| u.handle == candidate && Some(u.id.as_str()) != except)
        };
        if !taken(&base) {
            return base;
        }
        for n in 2..10_000 {
            let candidate = format!("{base}-{n}");
            if !taken(&candidate) {
                return candidate;
            }
        }
        format!("{base}-{}", now_ms())
    }

    pub fn create_user(
        &self,
        name: &str,
        password: &str,
        about: &str,
        avatar_emoji: Option<String>,
    ) -> Result<User, ApiError> {
        let name = name.trim();
        let chars = name.chars().count();
        if chars < 2 {
            return Err(ApiError::BadRequest(
                "name must be at least 2 characters".into(),
            ));
        }
        if chars > 40 {
            return Err(ApiError::BadRequest("name is too long".into()));
        }
        if password.len() < 4 {
            return Err(ApiError::BadRequest(
                "password must be at least 4 characters".into(),
            ));
        }

        let mut db = self.db.write().expect("db lock");
        if db.users.iter().any(|u| u.name.eq_ignore_ascii_case(name)) {
            return Err(ApiError::Conflict("that name is already taken".into()));
        }

        let salt = auth::new_salt();
        let user = User {
            id: new_id(),
            name: name.to_string(),
            handle: Self::unique_handle(&db, name, None),
            about: about.trim().to_string(),
            avatar_emoji,
            created_at: now_ms(),
        };
        db.credentials.insert(
            user.id.clone(),
            Credential {
                hash: auth::hash_password(password, &salt),
                salt,
            },
        );
        db.users.push(user.clone());
        self.persist(&db)?;
        Ok(user)
    }

    pub fn authenticate(&self, name: &str, password: &str) -> Result<User, ApiError> {
        let db = self.db.read().expect("db lock");
        let user = db
            .users
            .iter()
            .find(|u| u.name.eq_ignore_ascii_case(name.trim()))
            .ok_or(ApiError::Unauthorized)?;
        let credential = db.credentials.get(&user.id).ok_or(ApiError::Unauthorized)?;
        if !auth::verify_password(password, &credential.salt, &credential.hash) {
            return Err(ApiError::Unauthorized);
        }
        Ok(user.clone())
    }

    pub fn user(&self, id: &str) -> Option<User> {
        self.db
            .read()
            .expect("db lock")
            .users
            .iter()
            .find(|u| u.id == id)
            .cloned()
    }

    pub fn users_except(&self, me: &str, query: &str) -> Vec<User> {
        let needle = query.trim().to_ascii_lowercase();
        let db = self.db.read().expect("db lock");
        db.users
            .iter()
            .filter(|u| u.id != me)
            .filter(|u| {
                needle.is_empty()
                    || u.name.to_ascii_lowercase().contains(&needle)
                    || u.handle.contains(&needle)
            })
            .cloned()
            .collect()
    }

    pub fn update_user(
        &self,
        id: &str,
        name: Option<String>,
        about: Option<String>,
        avatar_emoji: Option<String>,
    ) -> Result<User, ApiError> {
        let mut db = self.db.write().expect("db lock");
        let index = db
            .users
            .iter()
            .position(|u| u.id == id)
            .ok_or_else(|| ApiError::NotFound("user not found".into()))?;

        if let Some(raw) = name {
            let trimmed = raw.trim().to_string();
            if trimmed.chars().count() < 2 {
                return Err(ApiError::BadRequest("name is too short".into()));
            }
            let clash = db
                .users
                .iter()
                .any(|u| u.id != id && u.name.eq_ignore_ascii_case(&trimmed));
            if clash {
                return Err(ApiError::Conflict("that name is already taken".into()));
            }
            db.users[index].handle = Self::unique_handle(&db, &trimmed, Some(id));
            db.users[index].name = trimmed;
        }
        if let Some(value) = about {
            db.users[index].about = value.trim().chars().take(200).collect();
        }
        if let Some(value) = avatar_emoji {
            db.users[index].avatar_emoji = if value.trim().is_empty() {
                None
            } else {
                Some(value.trim().to_string())
            };
        }
        let user = db.users[index].clone();
        self.persist(&db)?;
        Ok(user)
    }

    // ---------------------------------------------------------------- chats ---

    pub fn create_chat(
        &self,
        creator: &str,
        title: &str,
        member_ids: Vec<Id>,
        is_group: bool,
    ) -> Result<Chat, ApiError> {
        let mut db = self.db.write().expect("db lock");

        let mut members: Vec<Id> = Vec::with_capacity(member_ids.len() + 1);
        members.push(creator.to_string());
        for id in member_ids {
            if !members.contains(&id) {
                members.push(id);
            }
        }
        for id in &members {
            if !db.users.iter().any(|u| &u.id == id) {
                return Err(ApiError::BadRequest(format!("unknown member {id}")));
            }
        }
        if members.len() < 2 {
            return Err(ApiError::BadRequest(
                "a chat needs at least two people".into(),
            ));
        }

        if !is_group {
            // Reuse the existing 1:1 conversation instead of spawning duplicates.
            let existing = db
                .chats
                .iter()
                .find(|c| {
                    !c.is_group
                        && c.member_ids.len() == members.len()
                        && members.iter().all(|m| c.member_ids.contains(m))
                })
                .cloned();
            if let Some(mut chat) = existing {
                chat.hidden_for.retain(|u| u != creator);
                if let Some(stored) = db.chats.iter_mut().find(|c| c.id == chat.id) {
                    stored.hidden_for.retain(|u| u != creator);
                }
                self.persist(&db)?;
                return Ok(chat);
            }
        }

        let title = if !title.trim().is_empty() {
            title.trim().to_string()
        } else {
            let names: Vec<String> = members
                .iter()
                .filter(|id| id.as_str() != creator)
                .filter_map(|id| db.users.iter().find(|u| &u.id == id).map(|u| u.name.clone()))
                .collect();
            if is_group {
                names.join(", ")
            } else {
                names.first().cloned().unwrap_or_else(|| "Chat".to_string())
            }
        };

        let chat = Chat {
            id: new_id(),
            title,
            is_group: is_group || members.len() > 2,
            avatar_emoji: None,
            member_ids: members,
            created_at: now_ms(),
            last_seq: 0,
            pinned_by: Vec::new(),
            muted_by: Vec::new(),
            hidden_for: Vec::new(),
        };
        db.chats.push(chat.clone());
        db.messages.insert(chat.id.clone(), Vec::new());
        self.persist(&db)?;
        Ok(chat)
    }

    fn member_names(db: &Db, chat: &Chat, except: &str) -> Vec<String> {
        chat.member_ids
            .iter()
            .filter(|id| id.as_str() != except)
            .filter_map(|id| db.users.iter().find(|u| &u.id == id).map(|u| u.name.clone()))
            .collect()
    }

    pub fn chats_for(&self, user: &str) -> Vec<ChatSummary> {
        let db = self.db.read().expect("db lock");
        let mut rows: Vec<ChatSummary> = db
            .chats
            .iter()
            .filter(|c| c.member_ids.iter().any(|m| m == user))
            .filter(|c| !c.hidden_for.iter().any(|m| m == user))
            .map(|chat| {
                let messages = db.messages.get(&chat.id);
                let read_seq = db
                    .receipts
                    .get(&receipt_key(&chat.id, user))
                    .copied()
                    .unwrap_or(0);
                let unread = messages
                    .map(|list| {
                        list.iter()
                            .filter(|m| m.seq > read_seq && m.sender_id != user && !m.deleted)
                            .count() as u32
                    })
                    .unwrap_or(0);
                let last = messages
                    .and_then(|list| list.iter().rev().find(|m| !m.deleted).cloned());
                let updated_at = last
                    .as_ref()
                    .map(|m| m.created_at)
                    .unwrap_or(chat.created_at);
                ChatSummary {
                    id: chat.id.clone(),
                    title: chat.title.clone(),
                    is_group: chat.is_group,
                    avatar_emoji: chat.avatar_emoji.clone(),
                    member_ids: chat.member_ids.clone(),
                    member_names: Self::member_names(&db, chat, user),
                    last_message: last,
                    unread_count: unread,
                    pinned: chat.pinned_by.iter().any(|m| m == user),
                    muted: chat.muted_by.iter().any(|m| m == user),
                    updated_at,
                }
            })
            .collect();

        rows.sort_by(|a, b| {
            b.pinned
                .cmp(&a.pinned)
                .then_with(|| b.updated_at.cmp(&a.updated_at))
        });
        rows
    }

    pub fn chat_for(&self, user: &str, chat_id: &str) -> Result<Chat, ApiError> {
        let db = self.db.read().expect("db lock");
        let chat = db
            .chats
            .iter()
            .find(|c| c.id == chat_id)
            .ok_or_else(|| ApiError::NotFound("chat not found".into()))?;
        if !chat.member_ids.iter().any(|m| m == user) {
            return Err(ApiError::Forbidden);
        }
        Ok(chat.clone())
    }

    pub fn chat_members(&self, chat_id: &str) -> Vec<Id> {
        self.db
            .read()
            .expect("db lock")
            .chats
            .iter()
            .find(|c| c.id == chat_id)
            .map(|c| c.member_ids.clone())
            .unwrap_or_default()
    }

    /// Everyone who shares at least one chat with `user` — the presence audience.
    pub fn peers_of(&self, user: &str) -> Vec<Id> {
        let db = self.db.read().expect("db lock");
        let mut peers: HashSet<Id> = HashSet::new();
        for chat in db.chats.iter().filter(|c| c.member_ids.iter().any(|m| m == user)) {
            for member in &chat.member_ids {
                if member != user {
                    peers.insert(member.clone());
                }
            }
        }
        peers.into_iter().collect()
    }

    pub fn set_chat_flags(
        &self,
        user: &str,
        chat_id: &str,
        pinned: Option<bool>,
        muted: Option<bool>,
    ) -> Result<Chat, ApiError> {
        let mut db = self.db.write().expect("db lock");
        let chat = db
            .chats
            .iter_mut()
            .find(|c| c.id == chat_id)
            .ok_or_else(|| ApiError::NotFound("chat not found".into()))?;
        if !chat.member_ids.iter().any(|m| m == user) {
            return Err(ApiError::Forbidden);
        }
        if let Some(value) = pinned {
            chat.pinned_by.retain(|m| m != user);
            if value {
                chat.pinned_by.push(user.to_string());
            }
        }
        if let Some(value) = muted {
            chat.muted_by.retain(|m| m != user);
            if value {
                chat.muted_by.push(user.to_string());
            }
        }
        let snapshot = chat.clone();
        self.persist(&db)?;
        Ok(snapshot)
    }

    pub fn hide_chat(&self, user: &str, chat_id: &str) -> Result<Vec<Id>, ApiError> {
        let mut db = self.db.write().expect("db lock");
        let chat = db
            .chats
            .iter_mut()
            .find(|c| c.id == chat_id)
            .ok_or_else(|| ApiError::NotFound("chat not found".into()))?;
        if !chat.member_ids.iter().any(|m| m == user) {
            return Err(ApiError::Forbidden);
        }
        if !chat.hidden_for.iter().any(|m| m == user) {
            chat.hidden_for.push(user.to_string());
        }
        let members = chat.member_ids.clone();
        self.persist(&db)?;
        Ok(members)
    }

    // ------------------------------------------------------------- messages ---

    pub fn messages_for(
        &self,
        user: &str,
        chat_id: &str,
        before: Option<i64>,
        limit: Option<usize>,
    ) -> Result<MessagePage, ApiError> {
        self.chat_for(user, chat_id)?;
        let db = self.db.read().expect("db lock");
        let all = db.messages.get(chat_id).cloned().unwrap_or_default();
        let limit = limit.unwrap_or(DEFAULT_PAGE).clamp(1, MAX_PAGE);

        let cutoff = before.unwrap_or(i64::MAX);
        let mut window: Vec<Message> = all
            .into_iter()
            .filter(|m| m.seq < cutoff)
            .collect();
        let start = window.len().saturating_sub(limit);
        let next_before = if start > 0 {
            window.get(start).map(|m| m.seq)
        } else {
            None
        };
        let page = window.split_off(start);
        Ok(MessagePage {
            messages: page,
            next_before,
        })
    }

    pub fn send_message(
        &self,
        user: &str,
        chat_id: &str,
        kind: MessageKind,
        text: &str,
    ) -> Result<Message, ApiError> {
        let text = text.trim().to_string();
        if kind == MessageKind::Text && text.is_empty() {
            return Err(ApiError::BadRequest("message is empty".into()));
        }
        if text.chars().count() > MAX_TEXT {
            return Err(ApiError::BadRequest("message is too long".into()));
        }

        let mut db = self.db.write().expect("db lock");
        let chat = db
            .chats
            .iter_mut()
            .find(|c| c.id == chat_id)
            .ok_or_else(|| ApiError::NotFound("chat not found".into()))?;
        if !chat.member_ids.iter().any(|m| m == user) {
            return Err(ApiError::Forbidden);
        }

        chat.last_seq += 1;
        let message = Message {
            id: new_id(),
            chat_id: chat_id.to_string(),
            seq: chat.last_seq,
            sender_id: user.to_string(),
            kind,
            text,
            created_at: now_ms(),
            edited_at: None,
            deleted: false,
        };
        db.messages
            .entry(chat_id.to_string())
            .or_default()
            .push(message.clone());
        // A sender has implicitly read their own message.
        db.receipts
            .entry(receipt_key(chat_id, user))
            .and_modify(|seq| *seq = (*seq).max(message.seq))
            .or_insert(message.seq);
        self.persist(&db)?;
        Ok(message)
    }

    pub fn find_message(&self, message_id: &str) -> Option<(Chat, Message)> {
        let db = self.db.read().expect("db lock");
        for (chat_id, list) in db.messages.iter() {
            if let Some(message) = list.iter().find(|m| m.id == message_id) {
                if let Some(chat) = db.chats.iter().find(|c| &c.id == chat_id) {
                    return Some((chat.clone(), message.clone()));
                }
            }
        }
        None
    }

    pub fn edit_message(
        &self,
        user: &str,
        message_id: &str,
        text: &str,
    ) -> Result<(Chat, Message), ApiError> {
        let text = text.trim().to_string();
        if text.is_empty() {
            return Err(ApiError::BadRequest("message is empty".into()));
        }
        if text.chars().count() > MAX_TEXT {
            return Err(ApiError::BadRequest("message is too long".into()));
        }

        let mut db = self.db.write().expect("db lock");
        let chat_id = db
            .messages
            .iter()
            .find(|(_, list)| list.iter().any(|m| m.id == message_id))
            .map(|(id, _)| id.clone())
            .ok_or_else(|| ApiError::NotFound("message not found".into()))?;

        let chat = db
            .chats
            .iter()
            .find(|c| c.id == chat_id)
            .cloned()
            .ok_or_else(|| ApiError::NotFound("chat not found".into()))?;
        if !chat.member_ids.iter().any(|m| m == user) {
            return Err(ApiError::Forbidden);
        }

        let list = db.messages.get_mut(&chat_id).expect("chat messages");
        let message = list
            .iter_mut()
            .find(|m| m.id == message_id)
            .ok_or_else(|| ApiError::NotFound("message not found".into()))?;
        if message.sender_id != user {
            return Err(ApiError::Forbidden);
        }
        if message.deleted {
            return Err(ApiError::BadRequest("message was deleted".into()));
        }
        if message.kind != MessageKind::Text {
            return Err(ApiError::BadRequest("only text messages can be edited".into()));
        }
        if now_ms() - message.created_at > EDIT_WINDOW_MS {
            return Err(ApiError::BadRequest("the edit window has closed".into()));
        }

        message.text = text;
        message.edited_at = Some(now_ms());
        let updated = message.clone();
        self.persist(&db)?;
        Ok((chat, updated))
    }

    pub fn delete_message(
        &self,
        user: &str,
        message_id: &str,
    ) -> Result<(Chat, Message), ApiError> {
        let mut db = self.db.write().expect("db lock");
        let chat_id = db
            .messages
            .iter()
            .find(|(_, list)| list.iter().any(|m| m.id == message_id))
            .map(|(id, _)| id.clone())
            .ok_or_else(|| ApiError::NotFound("message not found".into()))?;

        let chat = db
            .chats
            .iter()
            .find(|c| c.id == chat_id)
            .cloned()
            .ok_or_else(|| ApiError::NotFound("chat not found".into()))?;
        if !chat.member_ids.iter().any(|m| m == user) {
            return Err(ApiError::Forbidden);
        }

        let list = db.messages.get_mut(&chat_id).expect("chat messages");
        let message = list
            .iter_mut()
            .find(|m| m.id == message_id)
            .ok_or_else(|| ApiError::NotFound("message not found".into()))?;
        if message.sender_id != user {
            return Err(ApiError::Forbidden);
        }
        message.deleted = true;
        message.text.clear();
        let updated = message.clone();
        self.persist(&db)?;
        Ok((chat, updated))
    }

    pub fn mark_read(
        &self,
        user: &str,
        chat_id: &str,
        message_id: &str,
    ) -> Result<i64, ApiError> {
        let mut db = self.db.write().expect("db lock");
        let chat = db
            .chats
            .iter()
            .find(|c| c.id == chat_id)
            .ok_or_else(|| ApiError::NotFound("chat not found".into()))?;
        if !chat.member_ids.iter().any(|m| m == user) {
            return Err(ApiError::Forbidden);
        }
        let seq = db
            .messages
            .get(chat_id)
            .and_then(|list| list.iter().find(|m| m.id == message_id))
            .map(|m| m.seq)
            .ok_or_else(|| ApiError::NotFound("message not found".into()))?;

        db.receipts
            .entry(receipt_key(chat_id, user))
            .and_modify(|current| *current = (*current).max(seq))
            .or_insert(seq);
        self.persist(&db)?;
        Ok(seq)
    }

    pub fn read_seq(&self, chat_id: &str, user: &str) -> i64 {
        self.db
            .read()
            .expect("db lock")
            .receipts
            .get(&receipt_key(chat_id, user))
            .copied()
            .unwrap_or(0)
    }

    // ------------------------------------------------------------- presence ---

    pub fn set_presence(&self, user: &str, online: bool) -> Vec<Id> {
        {
            let mut online_set = self.online.write().expect("presence lock");
            if online {
                online_set.insert(user.to_string());
            } else {
                online_set.remove(user);
            }
        }
        if !online {
            self.last_seen
                .write()
                .expect("presence lock")
                .insert(user.to_string(), now_ms());
        }
        self.peers_of(user)
    }

    pub fn is_online(&self, user: &str) -> bool {
        self.online.read().expect("presence lock").contains(user)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store() -> Store {
        Store::open(None)
    }

    fn two_users() -> (Store, User, User) {
        let store = store();
        let ada = store.create_user("Ada", "pass", "hi", None).expect("ada");
        let grace = store
            .create_user("Grace", "pass", "ho", None)
            .expect("grace");
        (store, ada, grace)
    }

    #[test]
    fn unread_and_receipts_track_the_cursor() {
        let (store, ada, grace) = two_users();
        let chat = store
            .create_chat(&ada.id, "", vec![grace.id.clone()], false)
            .expect("chat");

        store
            .send_message(&ada.id, &chat.id, MessageKind::Text, "one")
            .expect("first");
        let second = store
            .send_message(&ada.id, &chat.id, MessageKind::Text, "two")
            .expect("second");

        let summary = store.chats_for(&grace.id);
        assert_eq!(summary.len(), 1);
        assert_eq!(summary[0].unread_count, 2);
        assert_eq!(summary[0].title, "Ada");

        store
            .mark_read(&grace.id, &chat.id, &second.id)
            .expect("read");
        let summary = store.chats_for(&grace.id);
        assert_eq!(summary[0].unread_count, 0);
    }

    #[test]
    fn direct_chats_are_reused_not_duplicated() {
        let (store, ada, grace) = two_users();
        let first = store
            .create_chat(&ada.id, "", vec![grace.id.clone()], false)
            .expect("first");
        let second = store
            .create_chat(&grace.id, "", vec![ada.id.clone()], false)
            .expect("second");
        assert_eq!(first.id, second.id);
        assert_eq!(store.counts().1, 1);
    }

    #[test]
    fn messages_page_backwards() {
        let (store, ada, grace) = two_users();
        let chat = store
            .create_chat(&ada.id, "", vec![grace.id.clone()], false)
            .expect("chat");
        for i in 0..5 {
            store
                .send_message(&ada.id, &chat.id, MessageKind::Text, &format!("m{i}"))
                .expect("send");
        }
        let page = store
            .messages_for(&ada.id, &chat.id, None, Some(2))
            .expect("page");
        assert_eq!(page.messages.len(), 2);
        assert_eq!(page.messages[1].text, "m4");
        assert!(page.next_before.is_some());

        let older = store
            .messages_for(&ada.id, &chat.id, page.next_before, Some(2))
            .expect("older");
        assert_eq!(older.messages[0].text, "m2");
    }

    #[test]
    fn outsiders_cannot_read_a_chat() {
        let (store, ada, grace) = two_users();
        let mallory = store.create_user("Mallory", "pass", "", None).expect("m");
        let chat = store
            .create_chat(&ada.id, "", vec![grace.id.clone()], false)
            .expect("chat");
        assert!(matches!(
            store.messages_for(&mallory.id, &chat.id, None, None),
            Err(ApiError::Forbidden)
        ));
    }

    #[test]
    fn edits_respect_ownership_and_text_only() {
        let (store, ada, grace) = two_users();
        let chat = store
            .create_chat(&ada.id, "", vec![grace.id.clone()], false)
            .expect("chat");
        let message = store
            .send_message(&ada.id, &chat.id, MessageKind::Text, "hello")
            .expect("send");

        assert!(store.edit_message(&grace.id, &message.id, "hijack").is_err());
        let (_, edited) = store
            .edit_message(&ada.id, &message.id, "hello there")
            .expect("edit");
        assert_eq!(edited.text, "hello there");
        assert!(edited.edited_at.is_some());
        assert!(edited.created_at > 0);
    }
}
