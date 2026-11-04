//! End-to-end tests that drive the router exactly like the Android client does.

use axum::body::Body;
use axum::http::{Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use nchat_server::store::Store;
use nchat_server::{build_app, AppState};
use serde_json::{json, Value};
use tower::ServiceExt;

fn app() -> Router {
    build_app(AppState::new(
        Store::open(None),
        b"test-secret".to_vec(),
        3_600,
    ))
}

async fn call(
    app: &Router,
    method: &str,
    uri: &str,
    token: Option<&str>,
    body: Option<Value>,
) -> (StatusCode, Value) {
    let mut builder = Request::builder().method(method).uri(uri);
    if let Some(token) = token {
        builder = builder.header("authorization", format!("Bearer {token}"));
    }
    let request = match body {
        Some(value) => builder
            .header("content-type", "application/json")
            .body(Body::from(value.to_string()))
            .expect("request"),
        None => builder.body(Body::empty()).expect("request"),
    };

    let response = app.clone().oneshot(request).await.expect("response");
    let status = response.status();
    let bytes = response
        .into_body()
        .collect()
        .await
        .expect("body")
        .to_bytes();
    let value = if bytes.is_empty() {
        Value::Null
    } else {
        serde_json::from_slice(&bytes).unwrap_or(Value::Null)
    };
    (status, value)
}

async fn register(app: &Router, name: &str) -> (String, String) {
    let (status, body) = call(
        app,
        "POST",
        "/api/v1/auth/register",
        None,
        Some(json!({ "name": name, "password": "secret", "about": "hi" })),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "register {name}: {body}");
    (
        body["token"].as_str().expect("token").to_string(),
        body["user"]["id"].as_str().expect("id").to_string(),
    )
}

#[tokio::test]
async fn health_is_up() {
    let app = app();
    let (status, body) = call(&app, "GET", "/api/v1/health", None, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["status"], "ok");
}

#[tokio::test]
async fn register_and_login() {
    let app = app();
    let (token, _) = register(&app, "Ada").await;

    let (status, body) = call(&app, "GET", "/api/v1/me", Some(&token), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["name"], "Ada");
    assert_eq!(body["handle"], "ada");

    let (status, body) = call(
        &app,
        "POST",
        "/api/v1/auth/login",
        None,
        Some(json!({ "name": "Ada", "password": "secret" })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["token"].is_string());

    let (status, _) = call(
        &app,
        "POST",
        "/api/v1/auth/login",
        None,
        Some(json!({ "name": "Ada", "password": "nope" })),
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    let (status, _) = call(
        &app,
        "POST",
        "/api/v1/auth/register",
        None,
        Some(json!({ "name": "ada", "password": "secret" })),
    )
    .await;
    assert_eq!(status, StatusCode::CONFLICT);
}

#[tokio::test]
async fn conversation_flow() {
    let app = app();
    let (ada_token, ada_id) = register(&app, "Ada").await;
    let (grace_token, grace_id) = register(&app, "Grace").await;

    let (status, chat) = call(
        &app,
        "POST",
        "/api/v1/chats",
        Some(&ada_token),
        Some(json!({ "memberIds": [grace_id], "isGroup": false })),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{chat}");
    let chat_id = chat["id"].as_str().expect("chat id").to_string();
    assert_eq!(chat["title"], "Grace");

    let (status, message) = call(
        &app,
        "POST",
        &format!("/api/v1/chats/{chat_id}/messages"),
        Some(&ada_token),
        Some(json!({ "text": "hello grace" })),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{message}");
    let message_id = message["id"].as_str().expect("message id").to_string();
    assert_eq!(message["seq"], 1);

    // Grace sees one unread.
    let (status, chats) = call(&app, "GET", "/api/v1/chats", Some(&grace_token), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(chats[0]["unreadCount"], 1);
    assert_eq!(chats[0]["lastMessage"]["text"], "hello grace");

    // Reading clears it.
    let (status, _) = call(
        &app,
        "POST",
        &format!("/api/v1/chats/{chat_id}/read"),
        Some(&grace_token),
        Some(json!({ "messageId": message_id })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (_, chats) = call(&app, "GET", "/api/v1/chats", Some(&grace_token), None).await;
    assert_eq!(chats[0]["unreadCount"], 0);

    // History paginates.
    let (status, page) = call(
        &app,
        "GET",
        &format!("/api/v1/chats/{chat_id}/messages?limit=10"),
        Some(&grace_token),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(page["messages"][0]["text"], "hello grace");
    assert!(page["nextBefore"].is_null());

    // Only the author can edit, and the edit is broadcast-ready.
    let (status, _) = call(
        &app,
        "PATCH",
        &format!("/api/v1/messages/{message_id}"),
        Some(&grace_token),
        Some(json!({ "text": "hijack" })),
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    let (status, edited) = call(
        &app,
        "PATCH",
        &format!("/api/v1/messages/{message_id}"),
        Some(&ada_token),
        Some(json!({ "text": "hello again" })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(edited["text"], "hello again");
    assert!(edited["editedAt"].is_i64());

    // Pin / mute round-trip on the chat summary.
    let (status, _) = call(
        &app,
        "PATCH",
        &format!("/api/v1/chats/{chat_id}"),
        Some(&ada_token),
        Some(json!({ "pinned": true })),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let (_, chats) = call(&app, "GET", "/api/v1/chats", Some(&ada_token), None).await;
    assert_eq!(chats[0]["pinned"], true);

    // Deleting hides the chat for one side only.
    let (status, _) = call(
        &app,
        "DELETE",
        &format!("/api/v1/chats/{chat_id}"),
        Some(&grace_token),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::NO_CONTENT);
    let (_, chats) = call(&app, "GET", "/api/v1/chats", Some(&grace_token), None).await;
    assert_eq!(chats.as_array().expect("array").len(), 0);
    let (_, chats) = call(&app, "GET", "/api/v1/chats", Some(&ada_token), None).await;
    assert_eq!(chats.as_array().expect("array").len(), 1);

    let _ = ada_id;
}

#[tokio::test]
async fn authentication_is_enforced() {
    let app = app();
    let (status, _) = call(&app, "GET", "/api/v1/chats", None, None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    let (status, _) = call(&app, "GET", "/api/v1/chats", Some("bogus.token"), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn outsiders_get_forbidden() {
    let app = app();
    let (ada_token, _) = register(&app, "Ada").await;
    let (_, grace_id) = register(&app, "Grace").await;
    let (mallory_token, _) = register(&app, "Mallory").await;

    let (_, chat) = call(
        &app,
        "POST",
        "/api/v1/chats",
        Some(&ada_token),
        Some(json!({ "memberIds": [grace_id] })),
    )
    .await;
    let chat_id = chat["id"].as_str().expect("chat id");

    let (status, _) = call(
        &app,
        "GET",
        &format!("/api/v1/chats/{chat_id}/messages"),
        Some(&mallory_token),
        None,
    )
    .await;
    assert_eq!(status, StatusCode::FORBIDDEN);
}
