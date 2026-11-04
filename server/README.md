# NChat backend (Rust)

The realtime service behind the Kotlin app. `axum` + `tokio` on top of an
in-memory state tree that snapshots atomically to `data/db.json`.

```
server/
├── Cargo.toml
├── src/
│   ├── main.rs      # env config, bind, graceful shutdown
│   ├── lib.rs       # AppState, token issuing, router assembly
│   ├── api.rs       # REST handlers (auth, users, chats, messages, receipts, typing)
│   ├── ws.rs        # WebSocket handshake + broadcast fan-out
│   ├── store.rs     # in-memory state, pagination, receipts, persistence
│   ├── auth.rs      # salted SHA-256 passwords, HMAC-SHA256 bearer tokens
│   ├── model.rs     # wire model (camelCase, mirrors the Kotlin data classes)
│   └── error.rs     # ApiError → JSON { "error": ... }
└── tests/api.rs     # end-to-end tests that drive the router like the app does
```

## Run it

```bash
cd server
NCHAT_SECRET="change-me" PORT=8080 cargo run --release
```

Environment:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | listen port (binds `0.0.0.0`) |
| `NCHAT_DATA_DIR` | `data` | directory for `db.json` |
| `NCHAT_SECRET` | random per boot | HMAC key for bearer tokens — **set this in production** |
| `NCHAT_TOKEN_TTL` | `2592000` | token lifetime in seconds (30 days) |
| `NCHAT_LOG` | `info` | `tracing` filter, e.g. `debug` |

Health check: `GET /api/v1/health` → `{"status":"ok","version":"0.1.0","users":0,"chats":0}`.

## REST surface

All routes are prefixed with `/api/v1`. Authenticated routes take
`Authorization: Bearer <token>` and return `401` without it.

| Method | Path | Body / query | Notes |
|---|---|---|---|
| `POST` | `/auth/register` | `{name, password, about?, avatarEmoji?}` | returns `{token, user}` |
| `POST` | `/auth/login` | `{name, password}` | returns `{token, user}` |
| `GET` | `/me` | — | current user |
| `PATCH` | `/me` | `{name?, about?, avatarEmoji?}` | rename re-slugs the handle |
| `GET` | `/users?query=` | — | everyone except me, name/handle filtered |
| `GET` | `/chats` | — | `ChatSummary[]`, pinned first then newest |
| `POST` | `/chats` | `{title?, memberIds[], isGroup?}` | 1:1 chats are reused, never duplicated |
| `PATCH` | `/chats/:id` | `{pinned?, muted?}` | per-user flags |
| `DELETE` | `/chats/:id` | — | hides the chat for the caller only |
| `GET` | `/chats/:id/members` | — | `User[]` |
| `GET` | `/chats/:id/messages?before=<seq>&limit=50` | — | `{messages, nextBefore}` newest page first |
| `POST` | `/chats/:id/messages` | `{kind?, text}` | `kind`: `text\|photo\|video\|voice\|document\|location` |
| `PATCH` | `/messages/:id` | `{text}` | author only, text only, 15-minute window |
| `DELETE` | `/messages/:id` | — | author only, soft delete |
| `POST` | `/chats/:id/read` | `{messageId}` | advances the receipt cursor, returns `{seq}` |
| `POST` | `/chats/:id/typing` | `{typing?}` | fan-out only, nothing stored |
| `GET` | `/ws` | — | WebSocket, see below |

## WebSocket

Connect to `/api/v1/ws` and send the handshake as the **first** frame:

```json
{ "type": "auth", "token": "<bearer>" }
```

The server answers `{"type":"ready","userId":...}` then
`{"type":"presence.bulk","online":[...]}`. After that you receive:

```json
{ "type": "message.new",    "chatId": "...", "message": { ... } }
{ "type": "message.edit",   "chatId": "...", "message": { ... } }
{ "type": "message.delete", "chatId": "...", "message": { ... } }
{ "type": "receipt",        "chatId": "...", "userId": "...", "seq": 12 }
{ "type": "typing",         "chatId": "...", "userId": "...", "typing": true }
{ "type": "presence",       "userId": "...", "online": true }
{ "type": "chat.new",       "chatId": "...", "title": "..." }
{ "type": "chat.deleted",   "chatId": "..." }
```

Client → server frames after the handshake: `{"type":"typing","chatId":"...","typing":true}`.
Ping frames are answered with a pong.

## Why this shape is fast

* **One lock, no I/O on reads.** Chat lists, message pages and receipts are
  served straight from memory; a page costs a `Vec` clone plus a JSON encode.
* **Snapshots, not queries.** Writes go to the in-memory tree and then to
  `db.json` through `write → rename`, so there is no query planner or fsync
  storm between the client and the ack.
* **One broadcast channel.** Every realtime event is a single
  `serde_json::Value` shared by all recipients; each session filters by id.
* **Sequence numbers as cursors.** Unread counts and pagination are integer
  comparisons — no `COUNT(*)`, no offsets.
* **Release profile** is `opt-level = 3`, thin LTO, single codegen unit.

Next steps on this list (in order): an append-only journal instead of full
snapshots, `MsgPack`/`bincode` frames for the WebSocket, then Argon2id
passwords.

## Tests

```bash
cd server && cargo test
```

The suite covers password/token round-trips, unread accounting, backwards
pagination, 1:1 chat reuse, edit ownership and window, and full HTTP flows
including the 401/403 paths. CI runs it in `.github/workflows/backend.yml`.
