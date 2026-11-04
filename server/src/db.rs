//! SQLite connection pool.
//!
//! The reference worker talks to Cloudflare D1 (SQLite). We keep the exact same
//! schema and dialect, so every query in the route modules is a direct port of
//! the TypeScript one. A tiny round-robin pool keeps concurrent requests off a
//! single mutex; SQLite serialises writes itself, and WAL lets readers proceed
//! while a writer is active.

use rusqlite::Connection;
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, MutexGuard};

/// The merged final schema (migrations 0001..0005 folded together).
pub const SCHEMA: &str = include_str!("../schema.sql");

pub struct Db {
    pool: Vec<Mutex<Connection>>,
    next: AtomicUsize,
}

impl Db {
    pub fn open(path: impl AsRef<Path>, pool_size: usize) -> rusqlite::Result<Self> {
        let path = path.as_ref().to_path_buf();
        if let Some(parent) = path.parent() {
            if !parent.as_os_str().is_empty() {
                let _ = std::fs::create_dir_all(parent);
            }
        }

        // Bootstrap once, outside the pool.
        {
            let conn = Connection::open(&path)?;
            conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;")?;
            conn.execute_batch(SCHEMA)?;
        }

        let size = pool_size.max(1);
        let mut pool = Vec::with_capacity(size);
        for _ in 0..size {
            let conn = Connection::open(&path)?;
            conn.execute_batch("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;")?;
            pool.push(Mutex::new(conn));
        }
        Ok(Self {
            pool,
            next: AtomicUsize::new(0),
        })
    }

    /// Run a closure against a pooled connection.
    pub fn with<T>(
        &self,
        f: impl FnOnce(&mut Connection) -> rusqlite::Result<T>,
    ) -> rusqlite::Result<T> {
        let index = self.next.fetch_add(1, Ordering::Relaxed) % self.pool.len();
        let mut guard: MutexGuard<Connection> =
            self.pool[index].lock().unwrap_or_else(|err| err.into_inner());
        f(&mut guard)
    }
}

impl std::fmt::Debug for Db {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "Db(pool={})", self.pool.len())
    }
}
