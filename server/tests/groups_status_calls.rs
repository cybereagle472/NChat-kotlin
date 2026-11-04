//! Coverage for the second half of the port: groups, statuses and calls.

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
        assert_eq!(status, StatusCode::CREATED, "{body}");
        let request_id = body["id"].as_str().expect("request id").to_string();
        let (status, _) = self
            .call(
                "POST",
                &format!("/friends/{request_id}/respond"),
                Some(receiver),
                Some(json!({ "accept": true })),
            )
            .await;
        assert_eq!(status, StatusCode::OK);
    }
}

#[tokio::test]
async fn group_lifecycle_messages_and_read_state() {
    let app = app();
    let (ada_token, ada_id) = app.signup("ada@example.com", "Ada").await;
    let (grace_token, grace_id) = app.signup("grace@example.com", "Grace").await;
    let (linus_token, _linus_id) = app.signup("linus@example.com", "Linus").await;

    let (status, body) = app
        .call(
            "POST",
            "/groups",
            Some(&ada_token),
            Some(json!({ "name": "Squad", "memberIds": [grace_id] })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    let group_id = body["group"]["id"].as_str().expect("group id").to_string();
    assert_eq!(body["group"]["name"], "Squad");
    assert_eq!(
        body["group"]["members"].as_array().map(Vec::len),
        Some(2),
        "creator + invited member"
    );
    let roles: Vec<&str> = body["group"]["members"]
        .as_array()
        .expect("members")
        .iter()
        .map(|member| member["role"].as_str().unwrap_or_default())
        .collect();
    assert!(roles.contains(&"admin"));

    // A member sends a message; the other side sees one unread.
    let (status, message) = app
        .call(
            "POST",
            &format!("/groups/{group_id}/messages"),
            Some(&ada_token),
            Some(json!({ "content": "hello squad", "client_message_id": "c1" })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "{message}");
    let message_id = message["message"]["id"].as_str().expect("message id").to_string();

    // Retrying the same client id must not duplicate.
    let (_, again) = app
        .call(
            "POST",
            &format!("/groups/{group_id}/messages"),
            Some(&ada_token),
            Some(json!({ "content": "hello squad", "client_message_id": "c1" })),
        )
        .await;
    assert_eq!(again["message"]["id"].as_str(), Some(message_id.as_str()));

    let (status, body) = app
        .call("GET", &format!("/groups/{group_id}/messages"), Some(&grace_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["messages"].as_array().map(Vec::len), Some(1));

    let (_, body) = app.call("GET", "/groups", Some(&grace_token), None).await;
    assert_eq!(body["groups"][0]["unreadCount"], 1);

    let (status, _) = app
        .call("POST", &format!("/groups/{group_id}/read"), Some(&grace_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    let (_, body) = app.call("GET", "/groups", Some(&grace_token), None).await;
    assert_eq!(body["groups"][0]["unreadCount"], 0);

    // Reactions and per-user deletes.
    let (status, body) = app
        .call(
            "POST",
            &format!("/groups/messages/{message_id}/reaction"),
            Some(&grace_token),
            Some(json!({ "emoji": "🔥" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["reactions"]["🔥"][0].as_str(), Some(grace_id.as_str()));

    let (status, _) = app
        .call(
            "POST",
            &format!("/groups/messages/{message_id}/delete-for-me"),
            Some(&grace_token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    // Only the author can delete for everyone.
    let (status, _) = app
        .call(
            "POST",
            &format!("/groups/messages/{message_id}/delete-for-everyone"),
            Some(&grace_token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    let (status, _) = app
        .call(
            "POST",
            &format!("/groups/messages/{message_id}/delete-for-everyone"),
            Some(&ada_token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    // Outsiders are locked out of the group.
    let (status, _) = app
        .call(
            "GET",
            &format!("/groups/{group_id}/messages"),
            Some(&linus_token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    // Admins can promote, mute and delete; non-admins cannot.
    let (status, _) = app
        .call(
            "PATCH",
            &format!("/groups/{group_id}/members/{grace_id}"),
            Some(&grace_token),
            Some(json!({ "role": "admin" })),
        )
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    let (status, _) = app
        .call(
            "PATCH",
            &format!("/groups/{group_id}/members/{grace_id}"),
            Some(&ada_token),
            Some(json!({ "role": "admin" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    let (status, _) = app
        .call(
            "PATCH",
            &format!("/groups/{group_id}/mute"),
            Some(&grace_token),
            Some(json!({ "muted": true })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    // The only admin cannot walk out while others remain — but now there are two.
    let (status, _) = app
        .call("POST", &format!("/groups/{group_id}/leave"), Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);

    let (status, _) = app
        .call(
            "DELETE",
            &format!("/groups/{group_id}"),
            Some(&grace_token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    let (_, body) = app.call("GET", "/groups", Some(&grace_token), None).await;
    assert_eq!(body["groups"].as_array().map(Vec::len), Some(0));
    let _ = ada_id;
}

#[tokio::test]
async fn statuses_reach_friends_only_and_track_views() {
    let app = app();
    let (ada_token, _ada_id) = app.signup("ada@example.com", "Ada").await;
    let (grace_token, grace_id) = app.signup("grace@example.com", "Grace").await;
    let (linus_token, _) = app.signup("linus@example.com", "Linus").await;

    app.befriend(&ada_token, &grace_id, &grace_token).await;

    let (status, body) = app
        .call(
            "POST",
            "/status",
            Some(&ada_token),
            Some(json!({
                "content_type": "text",
                "text_content": "shipping rust today",
                "background_color": "#8B5CF6"
            })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    let status_id = body["status"]["id"].as_str().expect("status id").to_string();

    // Mine returns it, with a view count.
    let (status, body) = app.call("GET", "/status/mine", Some(&ada_token), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["statuses"].as_array().map(Vec::len), Some(1));
    assert_eq!(body["statuses"][0]["view_count"], 0);

    // A friend sees it in the feed; a stranger does not.
    let (status, body) = app.call("GET", "/status/feed", Some(&grace_token), None).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["statuses"][0]["text_content"], "shipping rust today");
    assert_eq!(body["statuses"][0]["viewed_by_me"], false);

    let (_, body) = app.call("GET", "/status/feed", Some(&linus_token), None).await;
    assert_eq!(body["statuses"].as_array().map(Vec::len), Some(0));

    // Viewing records once, and my own view is not a view.
    let (status, _) = app
        .call("POST", &format!("/status/{status_id}/view"), Some(&grace_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    app.call("POST", &format!("/status/{status_id}/view"), Some(&grace_token), None)
        .await;
    let (_, body) = app.call("GET", "/status/mine", Some(&ada_token), None).await;
    assert_eq!(body["statuses"][0]["view_count"], 1);

    let (status, body) = app
        .call("GET", &format!("/status/{status_id}/views"), Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["views"][0]["viewer_id"].as_str(), Some(grace_id.as_str()));

    // Muting a contact hides their statuses from the feed.
    let (status, _) = app
        .call(
            "POST",
            "/status/mute",
            Some(&grace_token),
            Some(json!({ "muted_user_id": body["views"][0]["viewer_id"] })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    // Deleting removes it for good.
    let (status, _) = app
        .call("DELETE", &format!("/status/{status_id}"), Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    let (_, body) = app.call("GET", "/status/mine", Some(&ada_token), None).await;
    assert_eq!(body["statuses"].as_array().map(Vec::len), Some(0));
}

#[tokio::test]
async fn call_signalling_lifecycle() {
    let app = app();
    let (ada_token, _ada_id) = app.signup("ada@example.com", "Ada").await;
    let (grace_token, grace_id) = app.signup("grace@example.com", "Grace").await;
    let (linus_token, _) = app.signup("linus@example.com", "Linus").await;

    let (status, body) = app
        .call(
            "POST",
            "/calls",
            Some(&ada_token),
            Some(json!({ "receiver_id": grace_id, "call_type": "video" })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    let call_id = body["call"]["id"].as_str().expect("call id").to_string();
    assert_eq!(body["call"]["status"], "ringing");

    // Bad call types and self-calls are rejected.
    let (status, _) = app
        .call(
            "POST",
            "/calls",
            Some(&ada_token),
            Some(json!({ "receiver_id": grace_id, "call_type": "hologram" })),
        )
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);

    // Only the callee can answer.
    let (status, _) = app
        .call(
            "POST",
            &format!("/calls/{call_id}/answer"),
            Some(&ada_token),
            Some(json!({ "answer_sdp": { "type": "answer", "sdp": "v=0" } })),
        )
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    let (status, _) = app
        .call(
            "POST",
            &format!("/calls/{call_id}/answer"),
            Some(&grace_token),
            Some(json!({ "answer_sdp": { "type": "answer", "sdp": "v=0" } })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    // Outsiders cannot signal into the call.
    let (status, _) = app
        .call(
            "POST",
            &format!("/calls/{call_id}/signal"),
            Some(&linus_token),
            Some(json!({ "candidate": "x" })),
        )
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    let (status, _) = app
        .call(
            "POST",
            &format!("/calls/{call_id}/signal"),
            Some(&ada_token),
            Some(json!({ "candidate": "x" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    let (status, _) = app
        .call(
            "POST",
            &format!("/calls/{call_id}/end"),
            Some(&grace_token),
            Some(json!({ "status": "ended", "duration": 42 })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    let (status, body) = app.call("GET", "/calls/history", Some(&ada_token), None).await;
    assert_eq!(status, StatusCode::OK, "{body}");
    assert_eq!(body["history"][0]["status"], "ended");
    assert_eq!(body["history"][0]["duration"], 42);
    assert_eq!(body["history"][0]["other_profile"]["full_name"], "Grace");

    // TURN credentials keep the "not configured" contract.
    let (status, body) = app
        .call("GET", "/calls/turn-credentials", Some(&ada_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert!(body["iceServers"].is_null());
}

#[tokio::test]
async fn group_calls_start_join_and_end() {
    let app = app();
    let (ada_token, _ada_id) = app.signup("ada@example.com", "Ada").await;
    let (grace_token, grace_id) = app.signup("grace@example.com", "Grace").await;
    let (linus_token, _) = app.signup("linus@example.com", "Linus").await;

    let (_, group) = app
        .call(
            "POST",
            "/groups",
            Some(&ada_token),
            Some(json!({ "name": "Callers", "memberIds": [grace_id] })),
        )
        .await;
    let group_id = group["group"]["id"].as_str().expect("group id").to_string();

    let (status, body) = app
        .call(
            "POST",
            "/group-calls",
            Some(&ada_token),
            Some(json!({ "group_id": group_id, "call_type": "audio" })),
        )
        .await;
    assert_eq!(status, StatusCode::CREATED, "{body}");
    let call_id = body["call"]["id"].as_str().expect("call id").to_string();
    assert_eq!(body["call"]["status"], "active");

    // A second member joining gets the participant list back.
    let (status, body) = app
        .call("POST", &format!("/group-calls/{call_id}/join"), Some(&grace_token), None)
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["participants"][0]["user_id"].as_str(), Some(_ada_id.as_str()));

    // Non-members cannot join.
    let (status, _) = app
        .call("POST", &format!("/group-calls/{call_id}/join"), Some(&linus_token), None)
        .await;
    assert_eq!(status, StatusCode::FORBIDDEN);

    // Directed signalling.
    let (status, _) = app
        .call(
            "POST",
            &format!("/group-calls/{call_id}/signal"),
            Some(&ada_token),
            Some(json!({ "to": grace_id, "sdp": "v=0" })),
        )
        .await;
    assert_eq!(status, StatusCode::OK);

    // Everyone leaves → the call ends.
    for token in [&ada_token, &grace_token] {
        let (status, _) = app
            .call("POST", &format!("/group-calls/{call_id}/leave"), Some(token), None)
            .await;
        assert_eq!(status, StatusCode::OK);
    }
    let (status, _) = app
        .call(
            "POST",
            &format!("/group-calls/{call_id}/join"),
            Some(&grace_token),
            None,
        )
        .await;
    assert_eq!(status, StatusCode::CONFLICT, "call should be over");
}
