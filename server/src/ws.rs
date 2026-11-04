//! Realtime fan-out.
//!
//! One `broadcast` channel carries every event; each connection keeps its own
//! receiver and drops the events that are not addressed to it. That keeps the
//! send path allocation-light (one `serde_json::Value` per event for all
//! recipients) and makes "instant" updates a single channel hop away.

use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::State;
use axum::response::Response;
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::sync::broadcast;

use crate::model::{now_ms, Id};
use crate::AppState;

#[derive(Clone, Debug)]
pub struct Event {
    pub recipients: Vec<Id>,
    pub payload: Value,
}

#[derive(Clone)]
pub struct Hub {
    tx: broadcast::Sender<Event>,
}

impl Hub {
    pub fn new() -> Self {
        let (tx, _rx) = broadcast::channel(1024);
        Self { tx }
    }

    pub fn subscribe(&self) -> broadcast::Receiver<Event> {
        self.tx.subscribe()
    }

    pub fn broadcast(&self, recipients: Vec<Id>, payload: Value) {
        if recipients.is_empty() {
            return;
        }
        let _ = self.tx.send(Event {
            recipients,
            payload,
        });
    }
}

impl Default for Hub {
    fn default() -> Self {
        Self::new()
    }
}

#[derive(Debug, Deserialize)]
struct ClientFrame {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    token: Option<String>,
    #[serde(default, rename = "chatId")]
    chat_id: Option<String>,
    #[serde(default)]
    typing: Option<bool>,
}

pub async fn handler(ws: WebSocketUpgrade, State(state): State<AppState>) -> Response {
    ws.on_upgrade(move |socket| session(socket, state))
}

async fn session(socket: WebSocket, state: AppState) {
    let (mut sink, mut stream) = socket.split();

    // The first frame must be `{"type":"auth","token":"..."}`.
    let user_id = match stream.next().await {
        Some(Ok(Message::Text(text))) => match serde_json::from_str::<ClientFrame>(&text) {
            Ok(frame) if frame.kind == "auth" => frame
                .token
                .and_then(|token| crate::auth::verify_token(&state.secret, &token, now_ms()))
                .filter(|id| state.store.user(id).is_some()),
            _ => None,
        },
        _ => None,
    };

    let Some(user_id) = user_id else {
        let denial = json!({ "type": "error", "error": "unauthorized" }).to_string();
        let _ = sink.send(Message::Text(denial)).await;
        let _ = sink.close().await;
        return;
    };

    let mut events = state.hub.subscribe();

    let peers = state.store.set_presence(&user_id, true);
    state.hub.broadcast(
        peers.clone(),
        json!({ "type": "presence", "userId": user_id, "online": true }),
    );

    if sink
        .send(Message::Text(
            json!({ "type": "ready", "userId": user_id }).to_string(),
        ))
        .await
        .is_err()
    {
        return;
    }

    let online: Vec<Id> = peers
        .iter()
        .filter(|peer| state.store.is_online(peer))
        .cloned()
        .collect();
    let _ = sink
        .send(Message::Text(
            json!({ "type": "presence.bulk", "online": online }).to_string(),
        ))
        .await;

    loop {
        tokio::select! {
            incoming = stream.next() => {
                match incoming {
                    Some(Ok(Message::Text(text))) => {
                        if let Ok(frame) = serde_json::from_str::<ClientFrame>(&text) {
                            if frame.kind == "typing" {
                                if let Some(chat_id) = frame.chat_id {
                                    if state.store.chat_for(&user_id, &chat_id).is_ok() {
                                        let recipients: Vec<Id> = state
                                            .store
                                            .chat_members(&chat_id)
                                            .into_iter()
                                            .filter(|member| member != &user_id)
                                            .collect();
                                        state.hub.broadcast(
                                            recipients,
                                            json!({
                                                "type": "typing",
                                                "chatId": chat_id,
                                                "userId": user_id,
                                                "typing": frame.typing.unwrap_or(true),
                                            }),
                                        );
                                    }
                                }
                            }
                        }
                    }
                    Some(Ok(Message::Ping(_))) => {
                        if sink.send(Message::Pong(Default::default())).await.is_err() {
                            break;
                        }
                    }
                    Some(Ok(Message::Close(_))) | None => break,
                    Some(Err(_)) => break,
                    _ => {}
                }
            }
            event = events.recv() => {
                match event {
                    Ok(event) => {
                        if event.recipients.iter().any(|id| id == &user_id)
                            && sink.send(Message::Text(event.payload.to_string())).await.is_err()
                        {
                            break;
                        }
                    }
                    Err(broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                }
            }
        }
    }

    let peers = state.store.set_presence(&user_id, false);
    state.hub.broadcast(
        peers,
        json!({ "type": "presence", "userId": user_id, "online": false }),
    );
}
