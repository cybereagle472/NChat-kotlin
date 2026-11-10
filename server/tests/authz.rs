//! Authorization regression suite (agent.md §33, Phase 1).
//!
//! Every test asks one question: can somebody who should *not* be able to do
//! this, do it? The cast is the same throughout:
//!
//! - **Ada** owns the thing under test (the group, the status, the call);
//! - **Grace** is legitimately involved (a friend, a member, the callee);
//! - **Mallory** is signed in but has no relationship with either of them.
//!
//! A refusal is only half of an authorization check. The other half is that the
//! refused request changed nothing and told nobody, so besides the status code
//! these tests read the state back through the API and listen on the realtime
//! hub for events that must not have been published.

use axum::body::Body;
use axum::http::{Request, StatusCode};
use axum::Router;
use http_body_util::BodyExt;
use nchat_server::db::Db;
use nchat_server::{build_app, jwt, AppState};
use serde_json::{json, Value};
use tokio::sync::mpsc;
use tower::ServiceExt;

const SECRET: &str = "test-secret";

struct TestApp {
    router: Router,
    /// Shares the hub and the database with `router`.
    state: AppState,
    _dir: tempfile::TempDir,
}

struct User {
    token: String,
    id: String,
}

/// One signed-in device: everything the server pushes to `user:<id>`.
struct Inbox {
    rx: mpsc::Receiver<String>,
}

impl Inbox {
    /// Every event queued so far. Handlers publish before they answer, so once a
    /// request has been awaited nothing is still in flight.
    fn drain(&mut self) -> Vec<Value> {
        let mut events = Vec::new();
        while let Ok(text) = self.rx.try_recv() {
            events.push(serde_json::from_str(&text).unwrap_or(Value::Null));
        }
        events
    }

    /// Just the `type` of each queued event, in arrival order.
    fn kinds(&mut self) -> Vec<String> {
        let events = self.drain();
        let mut kinds = Vec::new();
        for event in &events {
            kinds.push(event["type"].as_str().unwrap_or_default().to_string());
        }
        kinds
    }
}

fn app() -> TestApp {
    let dir = tempfile::tempdir().expect("temp dir");
    let db = Db::open(dir.path().join("nchat.sqlite"), 2).expect("db");
    let state = AppState::new(db, SECRET)
        .with_public_url("http://test.local")
        .with_media_dir(dir.path().join("media"));
    TestApp {
        router: build_app(state.clone()),
        state,
        _dir: dir,
    }
}

/// `true` when `key` appears anywhere inside `value`.
fn mentions_key(value: &Value, key: &str) -> bool {
    match value {
        Value::Object(map) => map
            .iter()
            .any(|(name, inner)| name.as_str() == key || mentions_key(inner, key)),
        Value::Array(items) => items.iter().any(|inner| mentions_key(inner, key)),
        _ => false,
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

    async fn signup(&self, email: &str, name: &str) -> User {
        let (status, body) = self
            .call(
                "POST",
                "/auth/signup",
                None,
                Some(json!({ "email": email, "password": "secret1", "full_name": name })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "signup {email}: {body}");
        User {
            token: body["access_token"].as_str().expect("token").to_string(),
            id: body["user"]["id"].as_str().expect("id").to_string(),
        }
    }

    async fn befriend(&self, sender: &User, receiver: &User) {
        let (status, body) = self
            .call(
                "POST",
                "/friends/request",
                Some(&sender.token),
                Some(json!({ "receiver_id": receiver.id })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "friend request: {body}");
        let request_id = body["id"].as_str().expect("request id").to_string();
        let (status, body) = self
            .call(
                "POST",
                &format!("/friends/{request_id}/respond"),
                Some(&receiver.token),
                Some(json!({ "accept": true })),
            )
            .await;
        assert_eq!(status, StatusCode::OK, "accept: {body}");
    }

    async fn block(&self, blocker: &User, target: &User) {
        let (status, body) = self
            .call(
                "POST",
                "/friends/block",
                Some(&blocker.token),
                Some(json!({ "user_id": target.id })),
            )
            .await;
        assert_eq!(status, StatusCode::OK, "block: {body}");
    }

    /// Subscribes to a user's personal realtime room, exactly like their device.
    fn listen(&self, user: &User) -> Inbox {
        let (_socket, rx) = self.state.hub.join(&format!("user:{}", user.id));
        Inbox { rx }
    }

    async fn create_group(&self, admin: &User, members: &[&User]) -> String {
        let mut ids: Vec<&str> = Vec::new();
        for member in members {
            ids.push(member.id.as_str());
        }
        let (status, body) = self
            .call(
                "POST",
                "/groups",
                Some(&admin.token),
                Some(json!({ "name": "Squad", "memberIds": ids })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "create group: {body}");
        body["group"]["id"].as_str().expect("group id").to_string()
    }

    async fn group_message(&self, sender: &User, group_id: &str, content: &str) -> String {
        let (status, body) = self
            .call(
                "POST",
                &format!("/groups/{group_id}/messages"),
                Some(&sender.token),
                Some(json!({ "content": content })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "group message: {body}");
        body["message"]["id"].as_str().expect("message id").to_string()
    }

    /// The only message of a group, as a member sees it.
    async fn only_group_message(&self, member: &User, group_id: &str) -> Value {
        let (status, body) = self
            .call(
                "GET",
                &format!("/groups/{group_id}/messages"),
                Some(&member.token),
                None,
            )
            .await;
        assert_eq!(status, StatusCode::OK, "group history: {body}");
        assert_eq!(
            body["messages"].as_array().map(Vec::len),
            Some(1),
            "expected exactly one message: {body}"
        );
        body["messages"][0].clone()
    }

    /// Posts a text status; `extra` overrides or adds request fields.
    async fn post_status(&self, author: &User, extra: Value) -> String {
        let mut request = json!({ "content_type": "text", "text_content": "hello" });
        if let (Some(target), Some(source)) = (request.as_object_mut(), extra.as_object()) {
            for (key, value) in source {
                target.insert(key.clone(), value.clone());
            }
        }
        let (status, body) = self
            .call("POST", "/status", Some(&author.token), Some(request))
            .await;
        assert_eq!(status, StatusCode::CREATED, "post status: {body}");
        body["status"]["id"].as_str().expect("status id").to_string()
    }

    /// Ids of the statuses in a user's feed.
    async fn feed(&self, viewer: &User) -> Vec<String> {
        let (status, body) = self
            .call("GET", "/status/feed", Some(&viewer.token), None)
            .await;
        assert_eq!(status, StatusCode::OK, "feed: {body}");
        let mut ids = Vec::new();
        for entry in body["statuses"].as_array().expect("statuses") {
            ids.push(entry["id"].as_str().unwrap_or_default().to_string());
        }
        ids
    }

    async fn view_status(&self, viewer: &User, status_id: &str) -> (StatusCode, Value) {
        self.call(
            "POST",
            &format!("/status/{status_id}/view"),
            Some(&viewer.token),
            None,
        )
        .await
    }

    /// Statuses live for 24 hours; this moves one's expiry into the past.
    fn expire_status(&self, status_id: &str) {
        self.state
            .db
            .with(|conn| {
                conn.execute(
                    "UPDATE statuses SET expires_at = '2000-01-01T00:00:00.000Z' WHERE id = ?",
                    [status_id],
                )
            })
            .expect("expire status");
    }

    async fn set_status_visibility(&self, user: &User, value: &str) {
        let (status, body) = self
            .call(
                "PATCH",
                "/privacy",
                Some(&user.token),
                Some(json!({ "status_visibility": value })),
            )
            .await;
        assert_eq!(status, StatusCode::OK, "privacy: {body}");
    }

    /// Uploads a picture and returns `(key, url)`.
    async fn upload(&self, owner: &User, file_name: &str) -> (String, String) {
        let boundary = "----nchat-authz-boundary";
        let mut body = Vec::new();
        body.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
        body.extend_from_slice(
            format!(
                "Content-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\n"
            )
            .as_bytes(),
        );
        body.extend_from_slice(b"Content-Type: image/jpeg\r\n\r\n");
        body.extend_from_slice(b"jpeg-bytes");
        body.extend_from_slice(format!("\r\n--{boundary}--\r\n").as_bytes());

        let request = Request::builder()
            .method("POST")
            .uri("/media/upload")
            .header("authorization", format!("Bearer {}", owner.token))
            .header(
                "content-type",
                format!("multipart/form-data; boundary={boundary}"),
            )
            .body(Body::from(body))
            .expect("request");
        let response = self.router.clone().oneshot(request).await.expect("response");
        let status = response.status();
        let bytes = response
            .into_body()
            .collect()
            .await
            .expect("body")
            .to_bytes();
        let value: Value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
        assert_eq!(status, StatusCode::CREATED, "upload: {value}");
        (
            value["key"].as_str().expect("key").to_string(),
            value["url"].as_str().expect("url").to_string(),
        )
    }

    /// Status code of `GET /media/file/<key>` for this caller.
    async fn open_media(&self, key: &str, viewer: Option<&User>) -> StatusCode {
        let mut builder = Request::builder().uri(format!("/media/file/{key}"));
        if let Some(viewer) = viewer {
            builder = builder.header("authorization", format!("Bearer {}", viewer.token));
        }
        let request = builder.body(Body::empty()).expect("request");
        let response = self.router.clone().oneshot(request).await.expect("response");
        response.status()
    }

    async fn start_call(&self, caller: &User, callee: &User) -> String {
        let (status, body) = self
            .call(
                "POST",
                "/calls",
                Some(&caller.token),
                Some(json!({ "receiver_id": callee.id, "call_type": "audio" })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "start call: {body}");
        body["call"]["id"].as_str().expect("call id").to_string()
    }

    async fn start_group_call(&self, starter: &User, group_id: &str) -> String {
        let (status, body) = self
            .call(
                "POST",
                "/group-calls",
                Some(&starter.token),
                Some(json!({ "group_id": group_id, "call_type": "audio" })),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "start group call: {body}");
        body["call"]["id"].as_str().expect("call id").to_string()
    }

    /// `POST /group-calls/<id>/<join | decline | leave>`.
    async fn group_call_action(
        &self,
        user: &User,
        call_id: &str,
        action: &str,
    ) -> (StatusCode, Value) {
        self.call(
            "POST",
            &format!("/group-calls/{call_id}/{action}"),
            Some(&user.token),
            None,
        )
        .await
    }
}

struct Cast {
    app: TestApp,
    ada: User,
    grace: User,
    mallory: User,
}

/// Ada and Grace are friends; Mallory knows neither of them.
async fn cast() -> Cast {
    let app = app();
    let ada = app.signup("ada@example.com", "Ada").await;
    let grace = app.signup("grace@example.com", "Grace").await;
    let mallory = app.signup("mallory@example.com", "Mallory").await;
    app.befriend(&ada, &grace).await;
    Cast {
        app,
        ada,
        grace,
        mallory,
    }
}

/// Ada's group with Grace in it, and a call that both of them have joined.
async fn group_call_in_progress(app: &TestApp, ada: &User, grace: &User) -> (String, String) {
    let group_id = app.create_group(ada, &[grace]).await;
    let call_id = app.start_group_call(ada, &group_id).await;
    let (status, body) = app.group_call_action(grace, &call_id, "join").await;
    assert_eq!(status, StatusCode::OK, "join group call: {body}");
    (group_id, call_id)
}

// ------------------------------------------------------------------ credentials

#[tokio::test]
async fn protected_routes_refuse_requests_without_a_valid_token() {
    let app = app();
    let routes: &[(&str, &str)] = &[
        ("GET", "/auth/me"),
        ("GET", "/users/me"),
        ("PATCH", "/users/me"),
        ("GET", "/users"),
        ("GET", "/users/search?q=a"),
        ("GET", "/users/username-available?username=a"),
        ("POST", "/users/presence"),
        ("GET", "/users/x"),
        ("GET", "/friends"),
        ("GET", "/friends/requests"),
        ("GET", "/friends/sent"),
        ("GET", "/friends/blocked"),
        ("POST", "/friends/request"),
        ("POST", "/friends/x/respond"),
        ("DELETE", "/friends/request/x"),
        ("DELETE", "/friends/x"),
        ("POST", "/friends/block"),
        ("POST", "/friends/unblock"),
        ("GET", "/messages/chats"),
        ("GET", "/messages/with/x"),
        ("POST", "/messages"),
        ("PATCH", "/messages/x"),
        ("DELETE", "/messages/x"),
        ("POST", "/messages/x/reaction"),
        ("POST", "/messages/x/delete-for-me"),
        ("POST", "/messages/with/x/delete-for-me"),
        ("POST", "/messages/mark-read"),
        ("POST", "/messages/typing"),
        ("POST", "/messages/recording"),
        ("GET", "/privacy"),
        ("PATCH", "/privacy"),
        ("GET", "/admin/stats"),
        ("GET", "/groups"),
        ("POST", "/groups"),
        ("GET", "/groups/x"),
        ("PATCH", "/groups/x"),
        ("DELETE", "/groups/x"),
        ("GET", "/groups/x/members"),
        ("POST", "/groups/x/members"),
        ("DELETE", "/groups/x/members/y"),
        ("PATCH", "/groups/x/members/y"),
        ("POST", "/groups/x/leave"),
        ("PATCH", "/groups/x/mute"),
        ("GET", "/groups/x/messages"),
        ("POST", "/groups/x/messages"),
        ("POST", "/groups/x/typing"),
        ("POST", "/groups/x/recording"),
        ("POST", "/groups/x/read"),
        ("POST", "/groups/messages/x/reaction"),
        ("POST", "/groups/messages/x/delete-for-me"),
        ("POST", "/groups/messages/x/delete-for-everyone"),
        ("GET", "/status/mine"),
        ("GET", "/status/feed"),
        ("GET", "/status/muted"),
        ("POST", "/status"),
        ("POST", "/status/mute"),
        ("DELETE", "/status/mute/x"),
        ("POST", "/status/x/view"),
        ("GET", "/status/x/views"),
        ("DELETE", "/status/x"),
        ("GET", "/calls/turn-credentials"),
        ("GET", "/calls/history"),
        ("POST", "/calls"),
        ("POST", "/calls/x/signal"),
        ("POST", "/calls/x/answer"),
        ("POST", "/calls/x/end"),
        ("POST", "/group-calls"),
        ("POST", "/group-calls/x/join"),
        ("POST", "/group-calls/x/signal"),
        ("POST", "/group-calls/x/decline"),
        ("POST", "/group-calls/x/leave"),
        ("GET", "/media/link?key=x/y.jpg"),
        ("GET", "/media/file/x/y.jpg"),
        ("DELETE", "/media/file/x/y.jpg"),
    ];
    for (method, path) in routes {
        let (status, _) = app.call(method, path, None, None).await;
        assert_eq!(
            status,
            StatusCode::UNAUTHORIZED,
            "{method} {path} without a token"
        );
        let (status, _) = app.call(method, path, Some("not.a.token"), None).await;
        assert_eq!(
            status,
            StatusCode::UNAUTHORIZED,
            "{method} {path} with a garbage token"
        );
    }

    // The upload route reads a multipart body, so it needs its own request shape.
    let request = Request::builder()
        .method("POST")
        .uri("/media/upload")
        .header("content-type", "multipart/form-data; boundary=x")
        .body(Body::from("--x--\r\n"))
        .expect("request");
    let response = app.router.clone().oneshot(request).await.expect("response");
    assert_eq!(
        response.status(),
        StatusCode::UNAUTHORIZED,
        "POST /media/upload without a token"
    );
}

#[tokio::test]
async fn forged_expired_and_refresh_tokens_are_not_api_credentials() {
    let app = app();
    let (status, body) = app
        .call(
            "POST",
            "/auth/signup",
            None,
            Some(json!({ "email": "ada@example.com", "password": "secret1", "full_name": "Ada" })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    let access = body["access_token"].as_str().expect("access token").to_string();
    let refresh = body["refresh_token"].as_str().expect("refresh token").to_string();
    let ada_id = body["user"]["id"].as_str().expect("id").to_string();

    let (status, _) = app.call("GET", "/groups", Some(&access), None).await;
    assert_eq!(status, StatusCode::OK, "the genuine access token must work");

    let wrong_secret = jwt::sign(&json!({ "sub": ada_id }), "another-secret", 600).expect("token");
    let expired = jwt::sign(&json!({ "sub": ada_id }), SECRET, -60).expect("token");
    let refresh_shaped =
        jwt::sign(&json!({ "sub": ada_id, "sid": "session" }), SECRET, 600).expect("token");
    let no_subject = jwt::sign(&json!({ "role": "admin" }), SECRET, 600).expect("token");

    let rejected: [(&str, &str); 5] = [
        ("signed with another secret", wrong_secret.as_str()),
        ("expired", expired.as_str()),
        ("shaped like a refresh token", refresh_shaped.as_str()),
        ("without a subject", no_subject.as_str()),
        ("the real refresh token", refresh.as_str()),
    ];
    for (label, token) in rejected {
        let (status, _) = app.call("GET", "/groups", Some(token), None).await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "a token {label} was accepted");
    }
}

#[tokio::test]
async fn admin_stats_are_denied_to_ordinary_accounts() {
    let app = app();
    let user = app.signup("not-the-admin@example.com", "Nobody").await;
    let (status, body) = app.call("GET", "/admin/stats", Some(&user.token), None).await;
    assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
    assert!(body.get("totalUsers").is_none(), "{body}");
}

// ----------------------------------------------------------------------- groups

#[tokio::test]
async fn outsider_is_refused_on_every_group_route() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let mut ada_inbox = app.listen(&ada);
    let mut grace_inbox = app.listen(&grace);

    let grace_membership = format!("/groups/{group_id}/members/{}", grace.id);
    let attempts: Vec<(&str, String, Value)> = vec![
        ("GET", format!("/groups/{group_id}"), json!({})),
        ("PATCH", format!("/groups/{group_id}"), json!({ "name": "Mine now" })),
        ("DELETE", format!("/groups/{group_id}"), json!({})),
        (
            "POST",
            format!("/groups/{group_id}/members"),
            json!({ "memberIds": [mallory.id] }),
        ),
        ("DELETE", grace_membership.clone(), json!({})),
        ("PATCH", grace_membership, json!({ "role": "admin" })),
        ("PATCH", format!("/groups/{group_id}/mute"), json!({ "muted": true })),
        ("GET", format!("/groups/{group_id}/messages"), json!({})),
        (
            "POST",
            format!("/groups/{group_id}/messages"),
            json!({ "content": "hello strangers" }),
        ),
        ("POST", format!("/groups/{group_id}/typing"), json!({ "is_typing": true })),
        (
            "POST",
            format!("/groups/{group_id}/recording"),
            json!({ "is_recording": true }),
        ),
        ("POST", format!("/groups/{group_id}/read"), json!({})),
    ];
    for (method, path, body) in attempts {
        let (status, answer) = app
            .call(method, &path, Some(&mallory.token), Some(body))
            .await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{method} {path}: {answer}");
    }

    let events = ada_inbox.drain();
    assert!(events.is_empty(), "the admin was sent {events:?}");
    let events = grace_inbox.drain();
    assert!(events.is_empty(), "a member was sent {events:?}");

    let (status, body) = app
        .call("GET", &format!("/groups/{group_id}"), Some(&ada.token), None)
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["group"]["name"], "Squad");
    assert_eq!(body["group"]["members"].as_array().map(Vec::len), Some(2));
}

#[tokio::test]
async fn ordinary_member_cannot_administer_the_group() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let group_id = app.create_group(&ada, &[&grace]).await;

    let ada_membership = format!("/groups/{group_id}/members/{}", ada.id);
    let attempts: Vec<(&str, String, Value)> = vec![
        ("PATCH", format!("/groups/{group_id}"), json!({ "name": "Coup" })),
        ("DELETE", format!("/groups/{group_id}"), json!({})),
        (
            "POST",
            format!("/groups/{group_id}/members"),
            json!({ "memberIds": [mallory.id] }),
        ),
        ("DELETE", ada_membership.clone(), json!({})),
        ("PATCH", ada_membership, json!({ "role": "member" })),
    ];
    for (method, path, body) in attempts {
        let (status, answer) = app
            .call(method, &path, Some(&grace.token), Some(body))
            .await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{method} {path}: {answer}");
    }

    let (_, body) = app
        .call("GET", &format!("/groups/{group_id}"), Some(&ada.token), None)
        .await;
    assert_eq!(body["group"]["name"], "Squad");
    assert_eq!(body["group"]["members"].as_array().map(Vec::len), Some(2));
}

#[tokio::test]
async fn removed_member_loses_access_to_the_group() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let (status, body) = app
        .call(
            "DELETE",
            &format!("/groups/{group_id}/members/{}", grace.id),
            Some(&ada.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");

    for (method, path) in [
        ("GET", format!("/groups/{group_id}")),
        ("GET", format!("/groups/{group_id}/messages")),
        ("POST", format!("/groups/{group_id}/messages")),
    ] {
        let (status, answer) = app
            .call(method, &path, Some(&grace.token), Some(json!({ "content": "still here?" })))
            .await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{method} {path}: {answer}");
    }
    let (_, body) = app.call("GET", "/groups", Some(&grace.token), None).await;
    assert_eq!(body["groups"].as_array().map(Vec::len), Some(0), "{body}");
}

#[tokio::test]
async fn group_member_list_is_for_members_only() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let path = format!("/groups/{group_id}/members");

    let (status, body) = app.call("GET", &path, Some(&mallory.token), None).await;
    assert_eq!(
        status,
        StatusCode::FORBIDDEN,
        "an outsider listed the members: {body}"
    );
    assert!(body.get("members").is_none(), "{body}");

    let (status, body) = app.call("GET", &path, Some(&grace.token), None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["members"].as_array().map(Vec::len), Some(2), "{body}");
}

#[tokio::test]
async fn group_projections_do_not_expose_member_emails() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let mut grace_inbox = app.listen(&grace);
    let group_id = app.create_group(&ada, &[&grace]).await;

    // What the invited member's device is pushed when the group appears.
    let pushed = grace_inbox.drain();
    assert!(
        pushed.iter().any(|event| event["type"] == "group:created"),
        "expected a group:created event, got {pushed:?}"
    );
    for event in &pushed {
        assert!(
            !mentions_key(event, "email"),
            "a realtime event carries an email: {event}"
        );
    }

    for path in [
        format!("/groups/{group_id}/members"),
        format!("/groups/{group_id}"),
        "/groups".to_string(),
    ] {
        let (status, body) = app.call("GET", &path, Some(&grace.token), None).await;
        assert_eq!(status, StatusCode::OK, "{path}: {body}");
        assert!(
            !mentions_key(&body, "email"),
            "GET {path} carries an email: {body}"
        );
    }
}

#[tokio::test]
async fn outsider_cannot_destroy_a_group_by_leaving_it() {
    let app = app();
    let ada = app.signup("ada@example.com", "Ada").await;
    let mallory = app.signup("mallory@example.com", "Mallory").await;
    // A group whose only member is its creator: "the last member left" deletes it.
    let group_id = app.create_group(&ada, &[]).await;
    app.group_message(&ada, &group_id, "notes to self").await;

    let (status, body) = app
        .call(
            "POST",
            &format!("/groups/{group_id}/leave"),
            Some(&mallory.token),
            None,
        )
        .await;
    assert_eq!(
        status,
        StatusCode::FORBIDDEN,
        "an outsider \"left\" a group they were never in: {body}"
    );

    let message = app.only_group_message(&ada, &group_id).await;
    assert_eq!(message["content"], "notes to self");
}

#[tokio::test]
async fn outsider_leaving_a_group_is_refused_and_not_announced() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let mut ada_inbox = app.listen(&ada);
    let mut grace_inbox = app.listen(&grace);
    let mut mallory_inbox = app.listen(&mallory);

    for target in [group_id.as_str(), "no-such-group"] {
        let (status, body) = app
            .call(
                "POST",
                &format!("/groups/{target}/leave"),
                Some(&mallory.token),
                None,
            )
            .await;
        assert_eq!(status, StatusCode::FORBIDDEN, "leave {target}: {body}");
    }

    let events = ada_inbox.drain();
    assert!(events.is_empty(), "the admin was sent {events:?}");
    let events = grace_inbox.drain();
    assert!(events.is_empty(), "a member was sent {events:?}");
    let events = mallory_inbox.drain();
    assert!(events.is_empty(), "the outsider was sent {events:?}");
}

#[tokio::test]
async fn outsider_cannot_react_to_a_group_message() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let message_id = app.group_message(&ada, &group_id, "members only").await;
    let mut ada_inbox = app.listen(&ada);

    let (status, body) = app
        .call(
            "POST",
            &format!("/groups/messages/{message_id}/reaction"),
            Some(&mallory.token),
            Some(json!({ "emoji": "👀" })),
        )
        .await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "an outsider reacted to a group message: {body}"
    );
    let events = ada_inbox.drain();
    assert!(events.is_empty(), "the reaction was broadcast: {events:?}");

    let message = app.only_group_message(&grace, &group_id).await;
    assert!(
        message.get("reactions").is_none(),
        "the reaction was stored: {message}"
    );
}

#[tokio::test]
async fn outsider_cannot_mark_a_group_message_deleted_for_themselves() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let message_id = app.group_message(&ada, &group_id, "members only").await;

    let (status, body) = app
        .call(
            "POST",
            &format!("/groups/messages/{message_id}/delete-for-me"),
            Some(&mallory.token),
            None,
        )
        .await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "an outsider wrote to a group message: {body}"
    );

    let message = app.only_group_message(&grace, &group_id).await;
    assert!(
        message.get("deleted_for").is_none(),
        "the outsider was recorded on the message: {message}"
    );
}

#[tokio::test]
async fn removed_member_cannot_delete_their_old_message_for_everyone() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let message_id = app
        .group_message(&grace, &group_id, "said while a member")
        .await;
    let (status, body) = app
        .call(
            "DELETE",
            &format!("/groups/{group_id}/members/{}", grace.id),
            Some(&ada.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let mut ada_inbox = app.listen(&ada);

    let (status, body) = app
        .call(
            "POST",
            &format!("/groups/messages/{message_id}/delete-for-everyone"),
            Some(&grace.token),
            None,
        )
        .await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "a removed member still changed the group's history: {body}"
    );
    let events = ada_inbox.drain();
    assert!(events.is_empty(), "the deletion was broadcast: {events:?}");

    let message = app.only_group_message(&ada, &group_id).await;
    assert_eq!(message["content"], "said while a member");
    assert_eq!(message["is_deleted"], false);
}

#[tokio::test]
async fn removing_someone_who_is_not_a_member_announces_nothing() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let group_id = app.create_group(&ada, &[&grace]).await;
    let mut grace_inbox = app.listen(&grace);
    let mut mallory_inbox = app.listen(&mallory);

    let (status, body) = app
        .call(
            "DELETE",
            &format!("/groups/{group_id}/members/{}", mallory.id),
            Some(&ada.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");

    let events = mallory_inbox.drain();
    assert!(
        events.is_empty(),
        "someone who was never a member was sent {events:?}"
    );
    let events = grace_inbox.drain();
    assert!(
        events.is_empty(),
        "members were told about a removal that did not happen: {events:?}"
    );
}

// ----------------------------------------------------------------------- status

#[tokio::test]
async fn friend_view_is_recorded_once_and_reported_to_the_author() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let status_id = app.post_status(&ada, json!({})).await;
    assert_eq!(app.feed(&grace).await, vec![status_id.clone()]);
    let mut ada_inbox = app.listen(&ada);

    for _ in 0..2 {
        let (status, body) = app.view_status(&grace, &status_id).await;
        assert_eq!(status, StatusCode::OK, "{body}");
    }

    let events = ada_inbox.drain();
    assert_eq!(events.len(), 1, "one view, one event: {events:?}");
    assert_eq!(events[0]["type"], "status:view");
    assert_eq!(events[0]["viewer_id"].as_str(), Some(grace.id.as_str()));
}

#[tokio::test]
async fn status_feed_respects_blocks_in_both_directions() {
    let app = app();
    let ada = app.signup("ada@example.com", "Ada").await;
    let grace = app.signup("grace@example.com", "Grace").await;
    let linus = app.signup("linus@example.com", "Linus").await;
    let heidi = app.signup("heidi@example.com", "Heidi").await;
    for friend in [&grace, &linus, &heidi] {
        app.befriend(&ada, friend).await;
    }
    // Blocking keeps the friendship row, so the feed has to check both tables.
    app.block(&ada, &grace).await;
    app.block(&linus, &ada).await;

    let status_id = app.post_status(&ada, json!({})).await;

    assert_eq!(
        app.feed(&heidi).await,
        vec![status_id.clone()],
        "an ordinary friend must still see it"
    );
    assert!(
        app.feed(&grace).await.is_empty(),
        "a friend the author blocked still sees the author's status"
    );
    assert!(
        app.feed(&linus).await.is_empty(),
        "someone who blocked the author is still shown the author's status"
    );
}

#[tokio::test]
async fn new_status_is_not_pushed_to_blocked_friends() {
    let app = app();
    let ada = app.signup("ada@example.com", "Ada").await;
    let grace = app.signup("grace@example.com", "Grace").await;
    let linus = app.signup("linus@example.com", "Linus").await;
    let heidi = app.signup("heidi@example.com", "Heidi").await;
    for friend in [&grace, &linus, &heidi] {
        app.befriend(&ada, friend).await;
    }
    app.block(&ada, &grace).await;
    app.block(&linus, &ada).await;
    let mut grace_inbox = app.listen(&grace);
    let mut linus_inbox = app.listen(&linus);
    let mut heidi_inbox = app.listen(&heidi);

    app.post_status(&ada, json!({})).await;

    assert_eq!(
        heidi_inbox.kinds(),
        vec!["status:new"],
        "an ordinary friend must still be told"
    );
    let events = grace_inbox.drain();
    assert!(
        events.is_empty(),
        "a friend the author blocked was pushed {events:?}"
    );
    let events = linus_inbox.drain();
    assert!(
        events.is_empty(),
        "someone who blocked the author was pushed {events:?}"
    );
}

#[tokio::test]
async fn status_for_nobody_stays_with_its_author() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let mut grace_inbox = app.listen(&grace);
    let status_id = app.post_status(&ada, json!({ "privacy": "nobody" })).await;

    let events = grace_inbox.drain();
    assert!(events.is_empty(), "a private status was pushed: {events:?}");
    assert!(
        app.feed(&grace).await.is_empty(),
        "a private status reached a friend's feed"
    );
    let (status, body) = app.view_status(&grace, &status_id).await;
    assert_eq!(status, StatusCode::NOT_FOUND, "{body}");

    let (_, body) = app.call("GET", "/status/mine", Some(&ada.token), None).await;
    assert_eq!(
        body["statuses"].as_array().map(Vec::len),
        Some(1),
        "the author keeps it: {body}"
    );
}

#[tokio::test]
async fn status_with_an_audience_the_server_cannot_evaluate_fails_closed() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let mut grace_inbox = app.listen(&grace);
    // The web types name these two, but no list of people is stored for them.
    for privacy in ["only_share_with", "contacts_except", "something-else"] {
        app.post_status(&ada, json!({ "privacy": privacy })).await;
    }

    let events = grace_inbox.drain();
    assert!(
        events.is_empty(),
        "statuses with an unknown audience were pushed: {events:?}"
    );
    let feed = app.feed(&grace).await;
    assert!(
        feed.is_empty(),
        "statuses with an unknown audience reached the feed: {feed:?}"
    );
}

#[tokio::test]
async fn account_level_status_visibility_is_enforced() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    app.set_status_visibility(&ada, "nobody").await;
    let mut grace_inbox = app.listen(&grace);
    let status_id = app.post_status(&ada, json!({})).await;

    let events = grace_inbox.drain();
    assert!(
        events.is_empty(),
        "\"status visibility: nobody\" was ignored by the broadcast: {events:?}"
    );
    assert!(
        app.feed(&grace).await.is_empty(),
        "\"status visibility: nobody\" was ignored by the feed"
    );
    let (status, body) = app.view_status(&grace, &status_id).await;
    assert_eq!(status, StatusCode::NOT_FOUND, "{body}");

    // Opening it up again restores the ordinary audience.
    app.set_status_visibility(&ada, "contacts").await;
    assert_eq!(app.feed(&grace).await, vec![status_id]);
}

#[tokio::test]
async fn stranger_cannot_record_a_status_view() {
    let Cast {
        app, ada, mallory, ..
    } = cast().await;
    let status_id = app.post_status(&ada, json!({})).await;
    let mut ada_inbox = app.listen(&ada);

    let (status, body) = app.view_status(&mallory, &status_id).await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "a stranger registered as a viewer: {body}"
    );
    let events = ada_inbox.drain();
    assert!(
        events.is_empty(),
        "the author was told a stranger viewed it: {events:?}"
    );

    let (status, body) = app
        .call(
            "GET",
            &format!("/status/{status_id}/views"),
            Some(&ada.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["views"].as_array().map(Vec::len), Some(0), "{body}");
}

#[tokio::test]
async fn blocked_friend_cannot_record_a_status_view() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let status_id = app.post_status(&ada, json!({})).await;
    app.block(&ada, &grace).await;
    let mut ada_inbox = app.listen(&ada);

    let (status, body) = app.view_status(&grace, &status_id).await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "a blocked friend registered as a viewer: {body}"
    );
    let events = ada_inbox.drain();
    assert!(events.is_empty(), "the author was sent {events:?}");
}

#[tokio::test]
async fn expired_status_cannot_be_viewed() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let status_id = app.post_status(&ada, json!({})).await;
    app.expire_status(&status_id);
    let mut ada_inbox = app.listen(&ada);

    let (status, body) = app.view_status(&grace, &status_id).await;
    assert_eq!(
        status,
        StatusCode::NOT_FOUND,
        "a view was recorded on an expired status: {body}"
    );
    let events = ada_inbox.drain();
    assert!(events.is_empty(), "the author was sent {events:?}");
}

#[tokio::test]
async fn only_the_author_can_list_who_viewed_a_status() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let status_id = app.post_status(&ada, json!({})).await;
    let (status, body) = app.view_status(&grace, &status_id).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let path = format!("/status/{status_id}/views");

    for (who, user) in [("a friend who viewed it", &grace), ("a stranger", &mallory)] {
        let (status, body) = app.call("GET", &path, Some(&user.token), None).await;
        assert_eq!(
            status,
            StatusCode::NOT_FOUND,
            "{who} read the viewer list: {body}"
        );
        assert!(body.get("views").is_none(), "{body}");
    }

    let (status, body) = app.call("GET", &path, Some(&ada.token), None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["views"].as_array().map(Vec::len), Some(1), "{body}");
    assert_eq!(body["views"][0]["viewer_id"].as_str(), Some(grace.id.as_str()));
}

#[tokio::test]
async fn only_the_author_can_delete_a_status() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let status_id = app.post_status(&ada, json!({})).await;
    let path = format!("/status/{status_id}");

    // The route answers 200 to anyone; what matters is that nothing is deleted.
    for user in [&grace, &mallory] {
        app.call("DELETE", &path, Some(&user.token), None).await;
    }
    assert_eq!(
        app.feed(&grace).await,
        vec![status_id.clone()],
        "someone other than the author deleted the status"
    );

    let (status, body) = app.call("DELETE", &path, Some(&ada.token), None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert!(
        app.feed(&grace).await.is_empty(),
        "the author could not delete their own status"
    );
}

#[tokio::test]
async fn status_media_is_readable_by_friends_and_not_by_strangers() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let (key, url) = app.upload(&ada, "story.jpg").await;
    app.post_status(&ada, json!({ "content_type": "image", "content_url": url }))
        .await;

    assert_eq!(app.open_media(&key, Some(&grace)).await, StatusCode::OK);
    assert_eq!(
        app.open_media(&key, Some(&mallory)).await,
        StatusCode::NOT_FOUND
    );
    assert_eq!(app.open_media(&key, None).await, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn blocked_friend_cannot_open_status_media() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let (key, url) = app.upload(&ada, "story.jpg").await;
    app.post_status(&ada, json!({ "content_type": "image", "content_url": url }))
        .await;
    assert_eq!(app.open_media(&key, Some(&grace)).await, StatusCode::OK);

    app.block(&ada, &grace).await;
    assert_eq!(
        app.open_media(&key, Some(&grace)).await,
        StatusCode::NOT_FOUND,
        "a blocked friend still opens the picture behind the author's status"
    );
}

#[tokio::test]
async fn status_media_follows_the_audience_of_its_status() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;

    let (key, url) = app.upload(&ada, "unknown-audience.jpg").await;
    app.post_status(
        &ada,
        json!({ "content_type": "image", "content_url": url, "privacy": "only_share_with" }),
    )
    .await;
    assert_eq!(
        app.open_media(&key, Some(&grace)).await,
        StatusCode::NOT_FOUND,
        "media of a status with an unknown audience was served to a friend"
    );

    app.set_status_visibility(&ada, "nobody").await;
    let (key, url) = app.upload(&ada, "hidden-account.jpg").await;
    app.post_status(&ada, json!({ "content_type": "image", "content_url": url }))
        .await;
    assert_eq!(
        app.open_media(&key, Some(&grace)).await,
        StatusCode::NOT_FOUND,
        "\"status visibility: nobody\" was ignored for the status's media"
    );
    assert_eq!(
        app.open_media(&key, Some(&ada)).await,
        StatusCode::OK,
        "the author always opens their own upload"
    );
}

// ------------------------------------------------------------------- 1:1 calls

#[tokio::test]
async fn outsider_cannot_touch_someone_elses_call() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let call_id = app.start_call(&ada, &grace).await;
    let mut ada_inbox = app.listen(&ada);
    let mut grace_inbox = app.listen(&grace);

    let answer_sdp = json!({ "answer_sdp": { "type": "answer", "sdp": "v=0" } });
    for (action, body) in [
        ("signal", json!({ "candidate": "x" })),
        ("answer", answer_sdp.clone()),
        ("end", json!({ "status": "ended" })),
    ] {
        let (status, answer) = app
            .call(
                "POST",
                &format!("/calls/{call_id}/{action}"),
                Some(&mallory.token),
                Some(body),
            )
            .await;
        assert_eq!(status, StatusCode::FORBIDDEN, "{action}: {answer}");
    }
    let events = ada_inbox.drain();
    assert!(events.is_empty(), "the caller was sent {events:?}");
    let events = grace_inbox.drain();
    assert!(events.is_empty(), "the callee was sent {events:?}");

    // The call is still ringing for the people who are actually in it.
    let (status, body) = app
        .call(
            "POST",
            &format!("/calls/{call_id}/answer"),
            Some(&grace.token),
            Some(answer_sdp),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
}

#[tokio::test]
async fn blocked_user_cannot_ring() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    app.block(&grace, &ada).await;
    let mut grace_inbox = app.listen(&grace);

    let (status, body) = app
        .call(
            "POST",
            "/calls",
            Some(&ada.token),
            Some(json!({ "receiver_id": grace.id, "call_type": "audio" })),
        )
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
    let events = grace_inbox.drain();
    assert!(events.is_empty(), "the blocker's phone rang: {events:?}");
}

#[tokio::test]
async fn call_signal_cannot_impersonate_another_event() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let call_id = app.start_call(&ada, &grace).await;
    let mut grace_inbox = app.listen(&grace);

    // A signalling body is opaque WebRTC data, but it must not be able to
    // replace the envelope the server puts around it.
    let (status, body) = app
        .call(
            "POST",
            &format!("/calls/{call_id}/signal"),
            Some(&ada.token),
            Some(json!({
                "type": "message:new",
                "callId": "some-other-call",
                "call_id": "some-other-call",
                "groupCallId": "some-other-call",
                "group_call_id": "some-other-call",
                "from": grace.id,
                "from_id": grace.id,
                "senderId": grace.id,
                "sender_id": grace.id,
                "user_id": grace.id,
                "to": grace.id,
                "candidate": { "candidate": "candidate:1", "sdpMid": "0", "sdpMLineIndex": 0 },
            })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");

    let events = grace_inbox.drain();
    assert_eq!(events.len(), 1, "{events:?}");
    let event = &events[0];
    assert_eq!(
        event["type"], "call:signal",
        "the sender chose the event type: {event}"
    );
    assert_eq!(
        event["callId"].as_str(),
        Some(call_id.as_str()),
        "the sender chose the call id: {event}"
    );
    assert_eq!(
        event["from"].as_str(),
        Some(ada.id.as_str()),
        "the sender chose who the event is from: {event}"
    );
    assert_eq!(
        event["candidate"]["candidate"], "candidate:1",
        "the signalling data itself must pass through: {event}"
    );
    // Clients read "which call" and "from whom" under several spellings (the
    // Android app tries each of these), so none of them may come from the sender.
    for alias in [
        "call_id",
        "groupCallId",
        "group_call_id",
        "from_id",
        "senderId",
        "sender_id",
        "user_id",
        "to",
    ] {
        assert!(
            event.get(alias).is_none(),
            "the sender set `{alias}` on the delivered event: {event}"
        );
    }
}

#[tokio::test]
async fn finished_call_relays_no_more_signals() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let call_id = app.start_call(&ada, &grace).await;
    let (status, body) = app
        .call(
            "POST",
            &format!("/calls/{call_id}/end"),
            Some(&grace.token),
            Some(json!({ "status": "declined" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let mut grace_inbox = app.listen(&grace);

    let (status, body) = app
        .call(
            "POST",
            &format!("/calls/{call_id}/signal"),
            Some(&ada.token),
            Some(json!({ "candidate": "late" })),
        )
        .await;
    assert_eq!(
        status,
        StatusCode::OK,
        "a late candidate is not an error for the client: {body}"
    );
    let events = grace_inbox.drain();
    assert!(
        events.is_empty(),
        "a finished call still delivers events: {events:?}"
    );
}

// ----------------------------------------------------------------- group calls

#[tokio::test]
async fn outsider_cannot_start_join_or_signal_a_group_call() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let (group_id, call_id) = group_call_in_progress(&app, &ada, &grace).await;
    let mut ada_inbox = app.listen(&ada);
    let mut grace_inbox = app.listen(&grace);

    let attempts: Vec<(String, Value)> = vec![
        (
            "/group-calls".to_string(),
            json!({ "group_id": group_id, "call_type": "audio" }),
        ),
        (format!("/group-calls/{call_id}/join"), json!({})),
        (
            format!("/group-calls/{call_id}/signal"),
            json!({ "to": grace.id, "candidate": "x" }),
        ),
    ];
    for (path, body) in attempts {
        let (status, answer) = app
            .call("POST", &path, Some(&mallory.token), Some(body))
            .await;
        assert_eq!(status, StatusCode::FORBIDDEN, "POST {path}: {answer}");
    }
    let events = ada_inbox.drain();
    assert!(events.is_empty(), "a participant was sent {events:?}");
    let events = grace_inbox.drain();
    assert!(events.is_empty(), "a participant was sent {events:?}");
}

#[tokio::test]
async fn outsider_cannot_decline_a_group_call() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let (_group_id, call_id) = group_call_in_progress(&app, &ada, &grace).await;
    let mut ada_inbox = app.listen(&ada);

    let (status, body) = app.group_call_action(&mallory, &call_id, "decline").await;
    assert_eq!(
        status,
        StatusCode::FORBIDDEN,
        "an outsider declined a call they were never invited to: {body}"
    );
    let events = ada_inbox.drain();
    assert!(events.is_empty(), "the caller was sent {events:?}");
}

#[tokio::test]
async fn outsider_cannot_leave_a_group_call() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let (_group_id, call_id) = group_call_in_progress(&app, &ada, &grace).await;
    let mut ada_inbox = app.listen(&ada);
    let mut grace_inbox = app.listen(&grace);

    let (status, body) = app.group_call_action(&mallory, &call_id, "leave").await;
    assert_eq!(
        status,
        StatusCode::FORBIDDEN,
        "an outsider \"left\" a call they were never in: {body}"
    );
    let events = ada_inbox.drain();
    assert!(events.is_empty(), "a participant was sent {events:?}");
    let events = grace_inbox.drain();
    assert!(events.is_empty(), "a participant was sent {events:?}");
}

#[tokio::test]
async fn removed_member_can_still_hang_up() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let (group_id, call_id) = group_call_in_progress(&app, &ada, &grace).await;
    let (status, body) = app
        .call(
            "DELETE",
            &format!("/groups/{group_id}/members/{}", grace.id),
            Some(&ada.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let mut ada_inbox = app.listen(&ada);

    // Grace is no longer in the group, but she is still on the call: she has to
    // be able to leave it, and the others have to see her go.
    let (status, body) = app.group_call_action(&grace, &call_id, "leave").await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(ada_inbox.kinds(), vec!["group-call:participant-left"]);
}

#[tokio::test]
async fn group_call_signal_cannot_impersonate_another_event() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let (_group_id, call_id) = group_call_in_progress(&app, &ada, &grace).await;
    let mut grace_inbox = app.listen(&grace);

    let (status, body) = app
        .call(
            "POST",
            &format!("/group-calls/{call_id}/signal"),
            Some(&ada.token),
            Some(json!({
                "to": grace.id,
                "type": "group:deleted",
                "call_id": "some-other-call",
                "callId": "some-other-call",
                "groupCallId": "some-other-call",
                "group_call_id": "some-other-call",
                "from": grace.id,
                "from_id": grace.id,
                "senderId": grace.id,
                "sender_id": grace.id,
                "user_id": grace.id,
                "offer_sdp": { "type": "offer", "sdp": "v=0" },
            })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");

    let events = grace_inbox.drain();
    assert_eq!(events.len(), 1, "{events:?}");
    let event = &events[0];
    assert_eq!(
        event["type"], "group-call:signal",
        "the sender chose the event type: {event}"
    );
    assert_eq!(
        event["call_id"].as_str(),
        Some(call_id.as_str()),
        "the sender chose the call id: {event}"
    );
    assert_eq!(
        event["from"].as_str(),
        Some(ada.id.as_str()),
        "the sender chose who the event is from: {event}"
    );
    assert_eq!(
        event["offer_sdp"]["type"], "offer",
        "the SDP itself must pass through untouched: {event}"
    );
    for alias in [
        "to",
        "callId",
        "groupCallId",
        "group_call_id",
        "from_id",
        "senderId",
        "sender_id",
        "user_id",
    ] {
        assert!(
            event.get(alias).is_none(),
            "the sender set `{alias}` on the delivered event: {event}"
        );
    }
}

/// The attack the envelope rules exist for. The Android client starts an audio
/// connection to whoever a signal for its current group call is from, and it
/// reads the call id from `callId` before `call_id`. If Mallory could send Ada
/// a signal from her own call that names Ada's private call, Ada's phone would
/// start connecting to Mallory as though she were on a call she was never
/// invited to. Call ids are random, so Mallory has to have seen this one — as
/// someone who was removed from the group during the call would have.
#[tokio::test]
async fn signal_from_one_group_call_cannot_pose_as_another() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let (_group_id, private_call) = group_call_in_progress(&app, &ada, &grace).await;
    // Nothing stops Mallory from putting Ada into a group of her own and
    // starting a call there, so she can signal Ada perfectly legitimately.
    let mallory_group = app.create_group(&mallory, &[&ada]).await;
    let mallory_call = app.start_group_call(&mallory, &mallory_group).await;
    let mut ada_inbox = app.listen(&ada);

    let (status, body) = app
        .call(
            "POST",
            &format!("/group-calls/{mallory_call}/signal"),
            Some(&mallory.token),
            Some(json!({
                "to": ada.id,
                "callId": private_call,
                "call_id": private_call,
                "groupCallId": private_call,
                "group_call_id": private_call,
                "sdp": { "type": "offer", "sdp": "v=0" },
            })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");

    let events = ada_inbox.drain();
    assert_eq!(events.len(), 1, "{events:?}");
    let event = &events[0];
    assert_eq!(event["call_id"].as_str(), Some(mallory_call.as_str()), "{event}");
    assert_eq!(event["from"].as_str(), Some(mallory.id.as_str()), "{event}");
    assert!(
        !event.to_string().contains(&private_call),
        "a signal names a call its sender is not part of: {event}"
    );

    // And the way back is closed too: Ada's side of the private call cannot be
    // made to signal someone who is not in that call's group.
    let mut mallory_inbox = app.listen(&mallory);
    let (status, body) = app
        .call(
            "POST",
            &format!("/group-calls/{private_call}/signal"),
            Some(&ada.token),
            Some(json!({ "to": mallory.id, "sdp": { "type": "answer", "sdp": "v=0" } })),
        )
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN, "{body}");
    let events = mallory_inbox.drain();
    assert!(events.is_empty(), "the outsider was sent {events:?}");
}

#[tokio::test]
async fn member_removed_from_the_group_gets_no_more_call_signals() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let (group_id, call_id) = group_call_in_progress(&app, &ada, &grace).await;
    let (status, body) = app
        .call(
            "DELETE",
            &format!("/groups/{group_id}/members/{}", grace.id),
            Some(&ada.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let mut grace_inbox = app.listen(&grace);

    // Grace keeps her participant row, which is what lets her hang up, but she
    // is no longer in the group: the call's signalling has to stop reaching her.
    let (status, body) = app
        .call(
            "POST",
            &format!("/group-calls/{call_id}/signal"),
            Some(&ada.token),
            Some(json!({ "to": grace.id, "candidate": "x" })),
        )
        .await;
    assert_eq!(
        status,
        StatusCode::FORBIDDEN,
        "signalling still reaches someone who was removed from the group: {body}"
    );
    let events = grace_inbox.drain();
    assert!(events.is_empty(), "the removed member was sent {events:?}");
}

#[tokio::test]
async fn group_call_signal_cannot_be_aimed_outside_the_call() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let (_group_id, call_id) = group_call_in_progress(&app, &ada, &grace).await;
    let mut mallory_inbox = app.listen(&mallory);

    let (status, body) = app
        .call(
            "POST",
            &format!("/group-calls/{call_id}/signal"),
            Some(&ada.token),
            Some(json!({ "to": mallory.id, "candidate": "x" })),
        )
        .await;
    assert_eq!(
        status,
        StatusCode::FORBIDDEN,
        "a member pushed a call event at someone outside the group: {body}"
    );
    let events = mallory_inbox.drain();
    assert!(events.is_empty(), "the outsider was sent {events:?}");
}

#[tokio::test]
async fn ended_group_call_relays_no_more_signals() {
    let Cast {
        app, ada, grace, ..
    } = cast().await;
    let (_group_id, call_id) = group_call_in_progress(&app, &ada, &grace).await;
    for user in [&grace, &ada] {
        let (status, body) = app.group_call_action(user, &call_id, "leave").await;
        assert_eq!(status, StatusCode::OK, "{body}");
    }
    let mut grace_inbox = app.listen(&grace);

    let (status, body) = app
        .call(
            "POST",
            &format!("/group-calls/{call_id}/signal"),
            Some(&ada.token),
            Some(json!({ "to": grace.id, "candidate": "late" })),
        )
        .await;
    assert_eq!(
        status,
        StatusCode::OK,
        "a late candidate is not an error for the client: {body}"
    );
    let events = grace_inbox.drain();
    assert!(
        events.is_empty(),
        "an ended call still delivers events: {events:?}"
    );
}

// ------------------------------------------------- direct messages and friends

#[tokio::test]
async fn strangers_cannot_reach_each_other_through_direct_message_routes() {
    let Cast {
        app, ada, mallory, ..
    } = cast().await;
    let mut ada_inbox = app.listen(&ada);

    let attempts: Vec<(&str, Value)> = vec![
        ("/messages", json!({ "receiver_id": ada.id, "content": "hi" })),
        (
            "/messages/typing",
            json!({ "receiver_id": ada.id, "is_typing": true }),
        ),
        (
            "/messages/recording",
            json!({ "receiver_id": ada.id, "is_recording": true }),
        ),
        ("/messages/mark-read", json!({ "sender_id": ada.id })),
    ];
    for (path, body) in attempts {
        let (status, answer) = app
            .call("POST", path, Some(&mallory.token), Some(body))
            .await;
        assert_eq!(status, StatusCode::FORBIDDEN, "POST {path}: {answer}");
    }
    let events = ada_inbox.drain();
    assert!(events.is_empty(), "a stranger pushed {events:?}");
}

#[tokio::test]
async fn third_party_cannot_act_on_someone_elses_direct_message() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let (status, body) = app
        .call(
            "POST",
            "/messages",
            Some(&ada.token),
            Some(json!({ "receiver_id": grace.id, "content": "between us" })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    let message_id = body["message"]["id"].as_str().expect("message id").to_string();
    let mut ada_inbox = app.listen(&ada);
    let mut grace_inbox = app.listen(&grace);

    let attempts: Vec<(&str, String, Value)> = vec![
        ("PATCH", format!("/messages/{message_id}"), json!({ "content": "edited" })),
        ("DELETE", format!("/messages/{message_id}"), json!({})),
        (
            "POST",
            format!("/messages/{message_id}/reaction"),
            json!({ "emoji": "👀" }),
        ),
        ("POST", format!("/messages/{message_id}/delete-for-me"), json!({})),
    ];
    for (method, path, body) in attempts {
        let (status, answer) = app
            .call(method, &path, Some(&mallory.token), Some(body))
            .await;
        assert_eq!(status, StatusCode::NOT_FOUND, "{method} {path}: {answer}");
    }
    let events = ada_inbox.drain();
    assert!(events.is_empty(), "the sender was sent {events:?}");
    let events = grace_inbox.drain();
    assert!(events.is_empty(), "the receiver was sent {events:?}");

    let (_, body) = app
        .call(
            "GET",
            &format!("/messages/with/{}", grace.id),
            Some(&ada.token),
            None,
        )
        .await;
    assert_eq!(body["messages"][0]["content"], "between us", "{body}");
    assert!(body["messages"][0].get("reactions").is_none(), "{body}");

    // And asking for "my conversation with Ada" shows Mallory nothing.
    let (status, body) = app
        .call(
            "GET",
            &format!("/messages/with/{}", ada.id),
            Some(&mallory.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["messages"].as_array().map(Vec::len), Some(0), "{body}");
}

#[tokio::test]
async fn friend_request_is_answered_only_by_its_receiver() {
    let app = app();
    let ada = app.signup("ada@example.com", "Ada").await;
    let grace = app.signup("grace@example.com", "Grace").await;
    let mallory = app.signup("mallory@example.com", "Mallory").await;
    let (status, body) = app
        .call(
            "POST",
            "/friends/request",
            Some(&ada.token),
            Some(json!({ "receiver_id": grace.id })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    let request_id = body["id"].as_str().expect("request id").to_string();

    for (who, user) in [("a third party", &mallory), ("the sender", &ada)] {
        let (status, body) = app
            .call(
                "POST",
                &format!("/friends/{request_id}/respond"),
                Some(&user.token),
                Some(json!({ "accept": true })),
            )
            .await;
        assert_eq!(
            status,
            StatusCode::NOT_FOUND,
            "{who} answered the request: {body}"
        );
    }

    // A third party cannot withdraw it either.
    app.call(
        "DELETE",
        &format!("/friends/request/{request_id}"),
        Some(&mallory.token),
        None,
    )
    .await;
    let (_, body) = app
        .call("GET", "/friends/requests", Some(&grace.token), None)
        .await;
    assert_eq!(body["requests"].as_array().map(Vec::len), Some(1), "{body}");
    let (_, body) = app.call("GET", "/friends", Some(&ada.token), None).await;
    assert_eq!(body["friends"].as_array().map(Vec::len), Some(0), "{body}");
}

#[tokio::test]
async fn unfriending_a_stranger_sends_them_nothing() {
    let Cast {
        app,
        ada,
        grace,
        mallory,
    } = cast().await;
    let mut ada_inbox = app.listen(&ada);

    let (status, body) = app
        .call(
            "DELETE",
            &format!("/friends/{}", ada.id),
            Some(&mallory.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let events = ada_inbox.drain();
    assert!(
        events.is_empty(),
        "someone who was never a friend pushed {events:?}"
    );
    let (_, body) = app.call("GET", "/friends", Some(&ada.token), None).await;
    assert_eq!(
        body["friends"].as_array().map(Vec::len),
        Some(1),
        "the real friendship must be untouched: {body}"
    );

    // Control: a real unfriend is still announced to the other side.
    let (status, body) = app
        .call(
            "DELETE",
            &format!("/friends/{}", ada.id),
            Some(&grace.token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(ada_inbox.kinds(), vec!["friend:removed"]);
}
