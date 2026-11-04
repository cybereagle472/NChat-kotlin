//! End-to-end tests that drive the router exactly like the frontend does.
//!
//! Every assertion mirrors a behaviour the reference worker exposes, so a green
//! run means the port is wire-compatible for that flow.

use axum::body::Body;
use axum::http::{Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use nchat_server::db::Db;
use nchat_server::{build_app, AppState};
use serde_json::{json, Value};
use tower::ServiceExt;

struct TestApp {
    router: Router,
    _dir: tempfile::TempDir,
}

fn app() -> TestApp {
    let dir = tempfile::tempdir().expect("temp dir");
    let db = Db::open(dir.path().join("nchat.sqlite"), 2).expect("db");
    let state = AppState::new(db, "test-secret").with_public_url("http://test.local");
    TestApp {
        router: build_app(state),
        _dir: dir,
    }
}

impl TestApp {
    async fn call(
        &self,
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

        let response = self.router.clone().oneshot(request).await.expect("response");
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

    async fn signup(&self, email: &str, name: &str) -> (String, String) {
        let (status, body) = self
            .call(
                "POST",
                "/auth/signup",
                None,
                Some(json!({ "email": email, "password": "secret1", "full_name": name })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "signup {email}: {body}");
        (
            body["access_token"].as_str().expect("token").to_string(),
            body["user"]["id"].as_str().expect("id").to_string(),
        )
    }

    async fn befriend(&self, sender: &str, receiver_id: &str, receiver: &str) {
        let (status, body) = self
            .call(
                "POST",
                "/friends/request",
                Some(sender),
                Some(json!({ "receiver_id": receiver_id })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "request: {body}");
        let request_id = body["id"].as_str().expect("request id").to_string();

        let (status, body) = self
            .call(
                "POST",
                &format!("/friends/{request_id}/respond"),
                Some(receiver),
                Some(json!({ "accept": true })),
            )
            .await;
        assert_eq!(status, StatusCode::OK, "respond: {body}");
    }
}

#[tokio::test]
async fn health_and_root() {
    let app = app();
    let (status, body) = app.call("GET", "/health", None, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["ok"], true);

    let (status, body) = app.call("GET", "/", None, None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["service"], "nchat-api");
}

#[tokio::test]
async fn signup_login_refresh_logout() {
    let app = app();

    let (token, _) = app.signup("ada@example.com", "Ada Lovelace").await;

    let (status, body) = app.call("GET", "/auth/me", Some(&token), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["user"]["full_name"], "Ada Lovelace");
    assert_eq!(body["user"]["is_admin"], false);

    // Duplicate email is a 409.
    let (status, _) = app
        .call(
            "POST",
            "/auth/signup",
            None,
            Some(json!({ "email": "ADA@example.com", "password": "secret1" })),
        )
        .await;
    assert_eq!(status, StatusCode::CONFLICT);

    // Short password and bad email are 400s.
    let (status, _) = app
        .call(
            "POST",
            "/auth/signup",
            None,
            Some(json!({ "email": "b@example.com", "password": "123" })),
        )
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    let (status, body) = app
        .call(
            "GET",
            "/auth/check-email?email=ada@example.com",
            None,
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["exists"], true);

    // Login, then rotate the refresh token.
    let (status, body) = app
        .call(
            "POST",
            "/auth/login",
            None,
            Some(json!({ "email": "ada@example.com", "password": "secret1" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let refresh = body["refresh_token"].as_str().expect("refresh").to_string();

    let (status, rotated) = app
        .call(
            "POST",
            "/auth/refresh",
            None,
            Some(json!({ "refresh_token": refresh })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert!(rotated["access_token"].is_string());

    // The old refresh token is single-use.
    let (status, _) = app
        .call(
            "POST",
            "/auth/refresh",
            None,
            Some(json!({ "refresh_token": refresh })),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    // Wrong password.
    let (status, _) = app
        .call(
            "POST",
            "/auth/login",
            None,
            Some(json!({ "email": "ada@example.com", "password": "nope" })),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn auth_is_enforced() {
    let app = app();
    for path in ["/auth/me", "/users/me", "/friends", "/messages/chats", "/privacy"] {
        let (status, _) = app.call("GET", path, None, None).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "{path} should be protected");
    }
    let (status, _) = app.call("GET", "/users/me", Some("bogus.token"), None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn profiles_and_usernames() {
    let app = app();
    let (ada_token, ada_id) = app.signup("ada@example.com", "Ada Lovelace").await;

    let (status, body) = app
        .call(
            "PATCH",
            "/users/me",
            Some(&ada_token),
            Some(json!({ "username": "ada", "bio": "computing" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["profile"]["username"], "ada");
    assert_eq!(body["profile"]["bio"], "computing");

    // The reference excludes the caller, so your *own* username reads as free…
    let (status, body) = app
        .call("GET", "/users/username-available?username=ada", Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["available"], true);

    // …while someone else's does not.
    let (grace_token, _) = app.signup("grace@example.com", "Grace Hopper").await;
    let (status, _) = app
        .call(
            "PATCH",
            "/users/me",
            Some(&grace_token),
            Some(json!({ "username": "grace" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    let (status, body) = app
        .call("GET", "/users/username-available?username=grace", Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["available"], false);

    let (status, body) = app
        .call("GET", "/users/username-available?username=nobody", Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["available"], true);

    // Presence heartbeat makes the profile online.
    let (status, _) = app
        .call("POST", "/users/presence", Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);

    let (status, body) = app
        .call("GET", &format!("/users/{ada_id}"), Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["profile"]["is_online"], true);
}

#[tokio::test]
async fn friend_request_lifecycle() {
    let app = app();
    let (ada_token, ada_id) = app.signup("ada@example.com", "Ada").await;
    let (grace_token, grace_id) = app.signup("grace@example.com", "Grace").await;

    let (status, body) = app
        .call(
            "POST",
            "/friends/request",
            Some(&ada_token),
            Some(json!({ "receiver_id": grace_id })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    assert!(body["id"].is_string());

    // Cannot stack a second pending request.
    let (status, _) = app
        .call(
            "POST",
            "/friends/request",
            Some(&ada_token),
            Some(json!({ "receiver_id": grace_id })),
        )
        .await;
    assert_eq!(status, StatusCode::CONFLICT);

    let (status, body) = app
        .call("GET", "/friends/requests", Some(&grace_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["requests"][0]["sender_id"].as_str(), Some(ada_id.as_str()));
    assert_eq!(body["requests"][0]["sender_profile"]["full_name"], "Ada");

    let (status, body) = app
        .call("GET", "/friends/sent", Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["receiverIds"][0].as_str(), Some(grace_id.as_str()));

    let request_id = body["requests"][0]["id"].as_str().unwrap_or_default().to_string();
    let request_id = if request_id.is_empty() {
        let (_, list) = app.call("GET", "/friends/requests", Some(&grace_token), None).await;
        list["requests"][0]["id"].as_str().unwrap_or_default().to_string()
    } else {
        request_id
    };

    let (status, _) = app
        .call(
            "POST",
            &format!("/friends/{request_id}/respond"),
            Some(&grace_token),
            Some(json!({ "accept": true })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    let (status, body) = app.call("GET", "/friends", Some(&ada_token), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["friends"][0]["profile"]["full_name"], "Grace");

    // Blocking removes the friendship and hides both users from each other.
    let (status, _) = app
        .call(
            "POST",
            "/friends/block",
            Some(&ada_token),
            Some(json!({ "user_id": grace_id })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    let (_, body) = app.call("GET", "/friends", Some(&ada_token), None).await;
    assert_eq!(body["friends"].as_array().map(Vec::len), Some(0));
    let (_, body) = app.call("GET", "/friends/blocked", Some(&ada_token), None).await;
    assert_eq!(body["blocked"][0]["full_name"], "Grace");

    let (status, _) = app
        .call(
            "POST",
            "/friends/unblock",
            Some(&ada_token),
            Some(json!({ "user_id": grace_id })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    let (_, body) = app.call("GET", "/friends/blocked", Some(&ada_token), None).await;
    assert_eq!(body["blocked"].as_array().map(Vec::len), Some(0));
}

#[tokio::test]
async fn messaging_flow() {
    let app = app();
    let (ada_token, ada_id) = app.signup("ada@example.com", "Ada").await;
    let (grace_token, grace_id) = app.signup("grace@example.com", "Grace").await;
    app.befriend(&ada_token, &grace_id, &grace_token).await;

    let (status, body) = app
        .call(
            "POST",
            "/messages",
            Some(&ada_token),
            Some(json!({
                "receiver_id": grace_id,
                "content": "hello grace",
                "client_message_id": "client-1"
            })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    let message_id = body["message"]["id"].as_str().expect("id").to_string();
    assert_eq!(body["message"]["content"], "hello grace");
    assert_eq!(body["message"]["is_read"], false);

    // Idempotent retry: same client id returns the same row, not a duplicate.
    let (status, again) = app
        .call(
            "POST",
            "/messages",
            Some(&ada_token),
            Some(json!({
                "receiver_id": grace_id,
                "content": "hello grace",
                "client_message_id": "client-1"
            })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(again["message"]["id"].as_str(), Some(message_id.as_str()));

    // Grace's chat list shows one unread conversation.
    let (status, body) = app.call("GET", "/messages/chats", Some(&grace_token), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["chats"][0]["unreadCount"], 1);
    assert_eq!(body["chats"][0]["lastMessage"]["content"], "hello grace");

    // History is oldest-first and readable by both sides.
    let (status, body) = app
        .call(
            "GET",
            &format!("/messages/with/{ada_id}"),
            Some(&grace_token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["messages"][0]["content"], "hello grace");

    // Marking read clears the badge.
    let (status, _) = app
        .call(
            "POST",
            "/messages/mark-read",
            Some(&grace_token),
            Some(json!({ "sender_id": ada_id })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    let (_, body) = app.call("GET", "/messages/chats", Some(&grace_token), None).await;
    assert_eq!(body["chats"][0]["unreadCount"], 0);

    // Reactions toggle.
    let (status, body) = app
        .call(
            "POST",
            &format!("/messages/{message_id}/reaction"),
            Some(&grace_token),
            Some(json!({ "emoji": "❤️" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["reactions"]["❤️"][0].as_str(), Some(grace_id.as_str()));

    // Edit is author-only and text-only.
    let (status, _) = app
        .call(
            "PATCH",
            &format!("/messages/{message_id}"),
            Some(&grace_token),
            Some(json!({ "content": "hijack" })),
        )
        .await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    let (status, body) = app
        .call(
            "PATCH",
            &format!("/messages/{message_id}"),
            Some(&ada_token),
            Some(json!({ "content": "hello again" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["message"]["content"], "hello again");
    assert_eq!(body["message"]["is_edited"], true);

    // Delete for everyone blanks the content.
    let (status, _) = app
        .call(
            "DELETE",
            &format!("/messages/{message_id}"),
            Some(&ada_token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    let (_, body) = app
        .call(
            "GET",
            &format!("/messages/with/{ada_id}"),
            Some(&grace_token),
            None,
        )
        .await;
    assert_eq!(body["messages"][0]["is_deleted"], true);
    assert_eq!(body["messages"][0]["content"], "");
}

#[tokio::test]
async fn privacy_redaction_is_applied_to_other_viewers() {
    let app = app();
    let (ada_token, ada_id) = app.signup("ada@example.com", "Ada").await;
    let (grace_token, grace_id) = app.signup("grace@example.com", "Grace").await;

    // Ada hides last seen, photo and bio from everyone.
    let (status, body) = app
        .call(
            "PATCH",
            "/privacy",
            Some(&ada_token),
            Some(json!({
                "last_seen_visibility": "nobody",
                "profile_photo_visibility": "nobody",
                "about_visibility": "nobody",
                "read_receipts": false,
                "online_status_enabled": false
            })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["settings"]["last_seen_visibility"], "nobody");

    let (status, body) = app
        .call("GET", &format!("/users/{ada_id}"), Some(&grace_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["profile"].get("bio").is_none());
    assert!(body["profile"].get("last_seen").is_none());
    assert_eq!(body["profile"]["is_online"], false);

    // Her own view keeps everything.
    let (status, body) = app
        .call("GET", &format!("/users/{ada_id}"), Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["profile"]["email"], "ada@example.com");

    // Invalid option is rejected as "no valid fields".
    let (status, _) = app
        .call(
            "PATCH",
            "/privacy",
            Some(&ada_token),
            Some(json!({ "last_seen_visibility": "sometimes" })),
        )
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    let _ = grace_id;
}

#[tokio::test]
async fn user_search_finds_people_and_hides_blocked() {
    let app = app();
    let (ada_token, _) = app.signup("ada@example.com", "Ada Lovelace").await;
    let (_, grace_id) = app.signup("grace@example.com", "Grace Hopper").await;

    let (status, body) = app
        .call("GET", "/users/search?q=grace", Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["profiles"][0]["full_name"], "Grace Hopper");

    let (status, body) = app
        .call("GET", "/users/search?q=@grace", Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["profiles"].as_array().map(Vec::len), Some(1));

    let (status, body) = app
        .call("GET", "/users", Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["profiles"].as_array().map(Vec::len), Some(1));

    app.call(
        "POST",
        "/friends/block",
        Some(&ada_token),
        Some(json!({ "user_id": grace_id })),
    )
    .await;

    let (status, body) = app
        .call("GET", "/users/search?q=grace", Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["profiles"].as_array().map(Vec::len), Some(0));
}
