//! Presence — port of `worker/src/lib/presence.ts`.
//!
//! A user counts as online while their last heartbeat is younger than two
//! minutes; the client posts `/users/presence` on a timer while the app is
//! foregrounded.

use rusqlite::{params_from_iter, Connection};
use std::collections::HashMap;

use crate::util::now_millis;

/// `ONLINE_THRESHOLD_MS`.
pub const ONLINE_THRESHOLD_MS: i64 = 120_000;

pub fn compute_is_online(last_active: Option<i64>) -> bool {
    match last_active {
        Some(ms) => now_millis() - ms < ONLINE_THRESHOLD_MS,
        None => false,
    }
}

pub fn get_presence(conn: &Connection, user_id: &str) -> rusqlite::Result<Option<i64>> {
    let mut statement = conn.prepare("SELECT last_active_at FROM presence WHERE user_id = ?")?;
    let mut rows = statement.query([user_id])?;
    match rows.next()? {
        Some(row) => Ok(Some(row.get::<_, i64>(0)?)),
        None => Ok(None),
    }
}

pub fn heartbeat(conn: &Connection, user_id: &str) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO presence (user_id, last_active_at) VALUES (?, ?)
         ON CONFLICT(user_id) DO UPDATE SET last_active_at = excluded.last_active_at",
        rusqlite::params![user_id, now_millis()],
    )?;
    Ok(())
}

/// Batch fetch, mirroring `getPresenceMap()`.
pub fn get_presence_map(
    conn: &Connection,
    user_ids: &[String],
) -> rusqlite::Result<HashMap<String, i64>> {
    let mut map = HashMap::new();
    let mut unique: Vec<&String> = Vec::new();
    for id in user_ids {
        if !id.is_empty() && !unique.contains(&id) {
            unique.push(id);
        }
    }
    if unique.is_empty() {
        return Ok(map);
    }

    let placeholders = vec!["?"; unique.len()].join(",");
    let sql = format!("SELECT user_id, last_active_at FROM presence WHERE user_id IN ({placeholders})");
    let mut statement = conn.prepare(&sql)?;
    let mut rows = statement.query(params_from_iter(unique.iter()))?;
    while let Some(row) = rows.next()? {
        map.insert(row.get::<_, String>(0)?, row.get::<_, i64>(1)?);
    }
    Ok(map)
}
