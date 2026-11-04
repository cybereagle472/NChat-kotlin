//! HTTP surface. Every handler is synchronous inside (the store is in memory),
//! so a request costs a lock, a clone and a JSON encode.

use axum::extract::{Path, Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, patch, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::json;

use crate::auth;
use crate::error::ApiError;
use crate::model::*;
use crate::AppState;

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/api/v1/health", get(health))
        .route("/api/v1/auth/register", post(register))
        .route("/api/v1/auth/login", post(login))
        .route("/api/v1/me", get(me).patch(update_me))
        .route("/api/v1/users", get(list_users))
        .route("/api/v1/chats", get(list_chats).post(create_chat))
        .route("/api/v1/chats/:id", patch(patch_chat).delete(delete_chat))
        .route("/api/v1/chats/:id/messages", get(list_messages).post(send_message))
        .route("/api/v1/chats/:id/members", get(chat_members))
        .route("/api/v1/chats/:id/read", post(mark_read))
        .route("/api/v1/chats/:id/typing", post(typing))
        .route("/api/v1/messages/:id", patch(edit_message).delete(delete_message))
        .route("/api/v1/ws", get(crate::ws::handler))
        .with_state(state)
}

#[derive(Debug, Deserialize)]
struct SearchQuery {
    #[serde(default)]
    query: String,
}

#[derive(Debug, Deserialize)]
struct PageQuery {
    before: Option<i64>,
    limit: Option<usize>,
}

fn broadcast_chat(state: &AppState, chat: &Chat, payload: serde_json::Value, except: Option<&str>) {
    let recipients: Vec<Id> = chat
        .member_ids
        .iter()
        .filter(|id| Some(id.as_str()) != except)
        .cloned()
        .collect();
    state.hub.broadcast(recipients, payload);
}

// ------------------------------------------------------------------- health ---

async fn health(State(state): State<AppState>) -> Json<Health> {
    let (users, chats) = state.store.counts();
    Json(Health {
        status: "ok",
        version: env!("CARGO_PKG_VERSION"),
        users,
        chats,
    })
}

// --------------------------------------------------------------------- auth ---

async fn register(
    State(state): State<AppState>,
    Json(req): Json<RegisterRequest>,
) -> Result<Json<AuthResponse>, ApiError> {
    let user = state.store.create_user(
        &req.name,
        &req.password,
        &req.about,
        req.avatar_emoji,
    )?;
    let token = state
        .issue_token(&user.id)
        .ok_or_else(|| ApiError::Internal("could not mint a token".into()))?;
    Ok(Json(AuthResponse { token, user }))
}

async fn login(
    State(state): State<AppState>,
    Json(req): Json<LoginRequest>,
) -> Result<Json<AuthResponse>, ApiError> {
    let user = state.store.authenticate(&req.name, &req.password)?;
    let token = state
        .issue_token(&user.id)
        .ok_or_else(|| ApiError::Internal("could not mint a token".into()))?;
    Ok(Json(AuthResponse { token, user }))
}

async fn me(State(state): State<AppState>, headers: HeaderMap) -> Result<Json<User>, ApiError> {
    let user_id = state.require_user(&headers)?;
    state
        .store
        .user(&user_id)
        .map(Json)
        .ok_or_else(|| ApiError::NotFound("user not found".into()))
}

async fn update_me(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<UpdateMeRequest>,
) -> Result<Json<User>, ApiError> {
    let user_id = state.require_user(&headers)?;
    let user = state
        .store
        .update_user(&user_id, req.name, req.about, req.avatar_emoji)?;
    Ok(Json(user))
}

async fn list_users(
    State(state): State<AppState>,
    headers: HeaderMap,
    Query(query): Query<SearchQuery>,
) -> Result<Json<Vec<User>>, ApiError> {
    let user_id = state.require_user(&headers)?;
    Ok(Json(state.store.users_except(&user_id, &query.query)))
}

// -------------------------------------------------------------------- chats ---

async fn list_chats(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> Result<Json<Vec<ChatSummary>>, ApiError> {
    let user_id = state.require_user(&headers)?;
    Ok(Json(state.store.chats_for(&user_id)))
}

async fn create_chat(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(req): Json<CreateChatRequest>,
) -> Result<Json<ChatSummary>, ApiError> {
    let user_id = state.require_user(&headers)?;
    let chat = state
        .store
        .create_chat(&user_id, &req.title, req.member_ids, req.is_group)?;

    let summary = state
        .store
        .chats_for(&user_id)
        .into_iter()
        .find(|c| c.id == chat.id);

    broadcast_chat(
        &state,
        &chat,
        json!({ "type": "chat.new", "chatId": chat.id, "title": chat.title }),
        Some(&user_id),
    );

    summary
        .map(Json)
        .ok_or_else(|| ApiError::Internal("chat vanished".into()))
}

async fn patch_chat(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(chat_id): Path<String>,
    Json(req): Json<PatchChatRequest>,
) -> Result<Json<Chat>, ApiError> {
    let user_id = state.require_user(&headers)?;
    let chat = state
        .store
        .set_chat_flags(&user_id, &chat_id, req.pinned, req.muted)?;
    Ok(Json(chat))
}

async fn delete_chat(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(chat_id): Path<String>,
) -> Result<StatusCode, ApiError> {
    let user_id = state.require_user(&headers)?;
    let members = state.store.hide_chat(&user_id, &chat_id)?;
    let others: Vec<Id> = members.into_iter().filter(|m| m != &user_id).collect();
    state
        .hub
        .broadcast(others, json!({ "type": "chat.deleted", "chatId": chat_id }));
    Ok(StatusCode::NO_CONTENT)
}

async fn chat_members(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(chat_id): Path<String>,
) -> Result<Json<Vec<User>>, ApiError> {
    let user_id = state.require_user(&headers)?;
    state.store.chat_for(&user_id, &chat_id)?;
    let users = state
        .store
        .chat_members(&chat_id)
        .into_iter()
        .filter_map(|id| state.store.user(&id))
        .collect::<Vec<User>>();
    Ok(Json(users))
}

// ----------------------------------------------------------------- messages ---

async fn list_messages(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(chat_id): Path<String>,
    Query(page): Query<PageQuery>,
) -> Result<Json<MessagePage>, ApiError> {
    let user_id = state.require_user(&headers)?;
    let page = state
        .store
        .messages_for(&user_id, &chat_id, page.before, page.limit)?;
    Ok(Json(page))
}

async fn send_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(chat_id): Path<String>,
    Json(req): Json<SendMessageRequest>,
) -> Result<Json<Message>, ApiError> {
    let user_id = state.require_user(&headers)?;
    let message = state
        .store
        .send_message(&user_id, &chat_id, req.kind, &req.text)?;
    let chat = state.store.chat_for(&user_id, &chat_id)?;
    broadcast_chat(
        &state,
        &chat,
        json!({ "type": "message.new", "chatId": chat_id, "message": message }),
        Some(&user_id),
    );
    Ok(Json(message))
}

async fn edit_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(message_id): Path<String>,
    Json(req): Json<EditMessageRequest>,
) -> Result<Json<Message>, ApiError> {
    let user_id = state.require_user(&headers)?;
    let (chat, message) = state.store.edit_message(&user_id, &message_id, &req.text)?;
    broadcast_chat(
        &state,
        &chat,
        json!({ "type": "message.edit", "chatId": chat.id, "message": message }),
        None,
    );
    Ok(Json(message))
}

async fn delete_message(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(message_id): Path<String>,
) -> Result<Json<Message>, ApiError> {
    let user_id = state.require_user(&headers)?;
    let (chat, message) = state.store.delete_message(&user_id, &message_id)?;
    broadcast_chat(
        &state,
        &chat,
        json!({ "type": "message.delete", "chatId": chat.id, "message": message }),
        None,
    );
    Ok(Json(message))
}

async fn mark_read(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(chat_id): Path<String>,
    Json(req): Json<ReadRequest>,
) -> Result<Json<serde_json::Value>, ApiError> {
    let user_id = state.require_user(&headers)?;
    let seq = state.store.mark_read(&user_id, &chat_id, &req.message_id)?;
    let chat = state.store.chat_for(&user_id, &chat_id)?;
    broadcast_chat(
        &state,
        &chat,
        json!({ "type": "receipt", "chatId": chat_id, "userId": user_id, "seq": seq }),
        Some(&user_id),
    );
    Ok(Json(json!({ "seq": seq })))
}

async fn typing(
    State(state): State<AppState>,
    headers: HeaderMap,
    Path(chat_id): Path<String>,
    Json(req): Json<TypingRequest>,
) -> Result<StatusCode, ApiError> {
    let user_id = state.require_user(&headers)?;
    let chat = state.store.chat_for(&user_id, &chat_id)?;
    broadcast_chat(
        &state,
        &chat,
        json!({
            "type": "typing",
            "chatId": chat_id,
            "userId": user_id,
            "typing": req.typing,
        }),
        Some(&user_id),
    );
    Ok(StatusCode::NO_CONTENT)
}

/// Exposed for the WebSocket handshake and for tests.
pub fn verify_token(state: &AppState, token: &str) -> Option<Id> {
    auth::verify_token(&state.secret, token, now_ms()).filter(|id| state.store.user(id).is_some())
}
