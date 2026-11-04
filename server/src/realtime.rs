//! Realtime — the Rust replacement for the `RealtimeRoom` Durable Object.
//!
//! The reference has one Durable Object per room and clients connect with
//! `GET /realtime/connect?room=<room>&token=<jwt>`, where a room is
//! `user:<id>`, `dm:<a>:<b>`, `group:<id>` or `call:<id>`. Everything the
//! Worker broadcasts goes to a user's personal room, so a single socket per
//! logged-in client receives every event.
//!
//! Here the same contract is served from one process: a room → socket map, an
//! unbounded channel per socket, and JSON text frames passed through untouched.

use crate::error::ApiError;
use crate::jwt;
use crate::AppState;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Query, State};
use axum::response::{IntoResponse, Response};
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;
use uuid::Uuid;

type Sockets = HashMap<Uuid, mpsc::UnboundedSender<String>>;

#[derive(Clone, Default)]
pub struct Hub {
    rooms: Arc<Mutex<HashMap<String, Sockets>>>,
}

impl Hub {
    pub fn new() -> Self {
        Self::default()
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HashMap<String, Sockets>> {
        self.rooms.lock().unwrap_or_else(|err| err.into_inner())
    }

    /// Register a socket in a room.
    pub fn join(&self, room: &str) -> (Uuid, mpsc::UnboundedReceiver<String>) {
        let id = Uuid::new_v4();
        let (tx, rx) = mpsc::unbounded_channel();
        self.lock().entry(room.to_string()).or_default().insert(id, tx);
        (id, rx)
    }

    pub fn leave(&self, room: &str, id: Uuid) {
        let mut rooms = self.lock();
        if let Some(sockets) = rooms.get_mut(room) {
            sockets.remove(&id);
            if sockets.is_empty() {
                rooms.remove(room);
            }
        }
    }

    /// `broadcastToRoom()` — deliver to every socket in the room.
    pub fn publish(&self, room: &str, payload: &str) {
        let targets: Vec<mpsc::UnboundedSender<String>> = {
            let rooms = self.lock();
            rooms
                .get(room)
                .map(|sockets| sockets.values().cloned().collect())
                .unwrap_or_default()
        };
        for sender in targets {
            let _ = sender.send(payload.to_string());
        }
    }

    /// `broadcastToUser()` — sugar over a user's personal room.
    pub fn publish_to_user(&self, user_id: &str, payload: &str) {
        self.publish(&format!("user:{user_id}"), payload);
    }

    pub fn publish_to_users<'a>(&self, user_ids: impl IntoIterator<Item = &'a str>, payload: &str) {
        for user_id in user_ids {
            self.publish_to_user(user_id, payload);
        }
    }

    /// Ephemeral relay: same room, everyone but the sender.
    fn relay(&self, room: &str, from: Uuid, payload: &str) {
        let targets: Vec<mpsc::UnboundedSender<String>> = {
            let rooms = self.lock();
            rooms
                .get(room)
                .map(|sockets| {
                    sockets
                        .iter()
                        .filter(|(id, _)| **id != from)
                        .map(|(_, sender)| sender.clone())
                        .collect()
                })
                .unwrap_or_default()
        };
        for sender in targets {
            let _ = sender.send(payload.to_string());
        }
    }

    pub fn room_size(&self, room: &str) -> usize {
        self.lock().get(room).map(Sockets::len).unwrap_or(0)
    }
}

#[derive(Debug, Deserialize)]
pub struct ConnectQuery {
    room: Option<String>,
    token: Option<String>,
}

/// `GET /realtime/connect?room=&token=` — token in the query because browsers
/// cannot attach an `Authorization` header to a WebSocket upgrade.
pub async fn connect(
    ws: WebSocketUpgrade,
    State(state): State<AppState>,
    Query(query): Query<ConnectQuery>,
) -> Response {
    let (Some(room), Some(token)) = (query.room.clone(), query.token.clone()) else {
        return ApiError::bad_request("room and token query params are required").into_response();
    };

    let Some(user_id) = jwt::subject(&token, &state.secret) else {
        return ApiError::unauthorized("Invalid or expired token").into_response();
    };

    // A personal room carries private events, so only its owner may join it.
    if room.starts_with("user:") && room != format!("user:{user_id}") {
        return ApiError::forbidden("Forbidden").into_response();
    }

    ws.on_upgrade(move |socket| session(socket, state, room))
}

async fn session(socket: WebSocket, state: AppState, room: String) {
    let (mut sink, mut stream) = socket.split();
    let (id, mut rx) = state.hub.join(&room);

    loop {
        tokio::select! {
            incoming = stream.next() => {
                match incoming {
                    Some(Ok(Message::Text(text))) => state.hub.relay(&room, id, &text),
                    Some(Ok(Message::Ping(_))) => {
                        if sink.send(Message::Pong(Default::default())).await.is_err() {
                            break;
                        }
                    }
                    Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                    _ => {}
                }
            }
            outbound = rx.recv() => {
                match outbound {
                    Some(payload) => {
                        if sink.send(Message::Text(payload)).await.is_err() {
                            break;
                        }
                    }
                    None => break,
                }
            }
        }
    }

    state.hub.leave(&room, id);
}
