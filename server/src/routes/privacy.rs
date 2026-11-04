//! `/privacy` — port of `worker/src/routes/privacy.ts`.

use axum::body::Bytes;
use axum::extract::State;
use axum::http::HeaderMap;
use axum::Json;
use serde_json::{json, Value};

use crate::error::{ApiError, ApiResult};
use crate::privacy::{
    get_privacy_settings, upsert_privacy_settings, Visibility,
};
use crate::routes::{bool_field, parse_body, text_field};
use crate::AppState;

pub async fn get_settings(
    State(state): State<AppState>,
    headers: HeaderMap,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let settings = state
        .db
        .with(|conn| get_privacy_settings(conn, &user_id))?;
    Ok(Json(json!({ "settings": settings.to_json() })))
}

pub async fn patch_settings(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<Json<Value>> {
    let user_id = state.current_user(&headers)?;
    let body = parse_body(&body);

    let mut settings = state
        .db
        .with(|conn| get_privacy_settings(conn, &user_id))?;
    let mut changed = false;

    let mut apply_visibility = |key: &str, slot: &mut Visibility| {
        if let Some(raw) = text_field(&body, key) {
            if let Some(parsed) = Visibility::parse(&raw) {
                *slot = parsed;
                changed = true;
            }
        }
    };

    apply_visibility(
        "last_seen_visibility",
        &mut settings.last_seen_visibility,
    );
    apply_visibility(
        "profile_photo_visibility",
        &mut settings.profile_photo_visibility,
    );
    apply_visibility("about_visibility", &mut settings.about_visibility);
    apply_visibility("status_visibility", &mut settings.status_visibility);

    if let Some(value) = bool_field(&body, "read_receipts") {
        settings.read_receipts = value;
        changed = true;
    }
    if let Some(value) = bool_field(&body, "online_status_enabled") {
        settings.online_status_enabled = value;
        changed = true;
    }

    if !changed {
        return Err(ApiError::bad_request("No valid fields to update"));
    }

    state
        .db
        .with(|conn| upsert_privacy_settings(conn, &settings))?;
    Ok(Json(json!({ "settings": settings.to_json() })))
}
