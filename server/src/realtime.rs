//! Realtime — the Rust replacement for the `RealtimeRoom` Durable Object.
//!
//! The reference has one Durable Object per room and clients connect with
//! `GET /realtime/connect?room=<room>&token=<jwt>`, where a room is
//! `user:<id>`, `dm:<a>:<b>`, `group:<id>` or `call:<id>`. Everything the
//! Worker broadcasts goes to a user's personal room, so a single socket per
//! logged-in client receives every event.
//!
//! The Android client uses personal rooms only. Each socket has a bounded
//! queue; a slow client is disconnected and can recover through a fresh sync.

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

type Sockets = HashMap<Uuid, mpsc::Sender<String>>;

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
    pub fn join(&self, room: &str) -> (Uuid, mpsc::Receiver<String>) {
        let id = Uuid::new_v4();
        let (tx, rx) = mpsc::channel(256);
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
        let mut rooms = self.lock();
        if let Some(sockets) = rooms.get_mut(room) {
            // Remove a full or disconnected queue rather than letting a stalled
            // connection accumulate messages indefinitely. Dropping the sender
            // closes the socket after its already-queued events have drained.
            sockets.retain(|_, sender| sender.try_send(payload.to_string()).is_ok());
            if sockets.is_empty() {
                rooms.remove(room);
            }
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

    // The Android client receives all events in its own personal room. Other
    // room types have no membership enforcement here, so fail closed instead
    // of allowing arbitrary authenticated users to listen or inject frames.
    if room != format!("user:{user_id}") {
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
                    // Personal rooms are server-to-client only. Relaying client text
                    // would let one session forge events for another session.
                    Some(Ok(Message::Text(_))) => {},
                    Some(Ok(Message::Ping(payload))) => {
                        if sink.send(Message::Pong(payload)).await.is_err() {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slow_consumers_are_removed_instead_of_growing_without_bound() {
        let hub = Hub::new();
        let (_id, _rx) = hub.join("user:one");
        for _ in 0..257 {
            hub.publish("user:one", "event");
        }
        assert_eq!(hub.room_size("user:one"), 0);
    }
}
