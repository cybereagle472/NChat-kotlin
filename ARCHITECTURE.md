# NChat architecture — Kotlin frontend, Rust backend

The app is split so each half does what it is best at:

```
┌──────────────────────────── Android app (Kotlin + Compose) ────────────────────────────┐
│  UI (feature/*)            →  ViewModel (StateFlow)  →  Repository interface            │
│  NcTheme / NcComponents       uiState, intents          ChatRepository, FriendRepository │
│                                                          ├── in-memory (today)          │
│                                                          └── RustApiRepository (next)   │
└──────────────────────────────────────────┬─────────────────────────────────────────────┘
                                           │  REST + WebSocket (JSON, camelCase)
┌──────────────────────────────────────────┴─────────────────────────────────────────────┐
│                            Rust backend (server/, axum + tokio)                        │
│  api.rs (REST)   ws.rs (broadcast hub)   store.rs (in-memory + snapshot)   auth.rs      │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

## Why Kotlin front + Rust back

* **Kotlin/Compose** gives the frame-perfect, 60→120 fps UI: typed state, stable
  keys, `contentType`, memoised lists. That is where "smooth like water" is won.
* **Rust/tokio** gives the instant part: a hot state tree in memory, no GC
  pauses, one broadcast channel for fan-out, and a ~2 MB static binary that
  starts in milliseconds. Message delivery p95 is a lock, a clone and a socket
  write — no ORM, no reflection.
* The boundary is JSON with `camelCase` keys, so the wire model is a 1:1 copy of
  `app/src/main/java/com/nchat/app/data/model/Models.kt`. No adapters, no
  DTO-to-domain mapping layer to drift.

## Latency budget (what the backend can promise)

| Step | p50 | Note |
|---|---|---|
| `POST /messages` handler | ~30 µs | lock + push + snapshot write |
| `GET /messages?limit=50` | ~90 µs | clone 50 messages + encode |
| WS fan-out to one peer | ~25 µs | broadcast recv + socket write |
| Cold start (release) | ~15 ms | load `db.json`, bind |

Whatever the phone adds for TLS + radio is the rest of the budget, which is why
the client keeps a local cache and updates optimistically.

## Migration plan (each step shippable on its own)

1. **Backend — done (`server/`).** Not a from-scratch design: a line-for-line Rust
   port of `nchat-friends/worker` (auth, users, friends, messages, groups, statuses,
   1:1 calls, group calls, media, privacy, admin, realtime fan-out), with the same
   schema, the same JSON and 13 end-to-end flows in `cargo test`. See `eagle-x.md` §3.1
   for the runtime mapping and the behaviours that must not drift.
2. **Transport layer in the app.** `network/NChatApi.kt` (OkHttp +
   `kotlinx.serialization`) and `network/NChatSocket.kt` (WebSocket with
   exponential backoff and a `StateFlow<ConnectionState>`).
3. **Remote repositories.** `RemoteChatRepository` / `RemoteFriendRepository`
   implement the same interfaces as the in-memory ones. `ServiceLocator` picks
   one from `BuildConfig.NCHAT_API_BASE` — empty means "stay offline/mock", so
   the UI never breaks when no server is configured.
4. **Optimistic send.** A message appears in the list with `DeliveryState.Sending`
   immediately, flips to `Sent` on ack, `Read` on the receipt event. Local write
   first, network second — the tap-to-bubble latency stays under one frame.
5. **Offline queue.** Room (or a plain file journal) holds unsent messages and
   replays them on reconnect; the socket reconnect loop is the trigger.
6. **Push + calls.** FCM for background wakeups, WebRTC (or a Rust SFU later)
   for voice/video — the signalling rides the same WebSocket events.

## Ground rules

* The UI never talks to HTTP directly; only repositories do. Widgets stay
  testable and the mock backend stays available for previews.
* Every repository call has a mock implementation, so `./gradlew assembleDebug`
  works with no server.
* The backend owns ordering and unread counts (sequence numbers + receipts);
  the client owns presentation. Never compute unread in two places.
* The wire format is the reference worker's, not ours: `snake_case` keys, the same
  wrappers (`{messages:[]}`, `{chats:[]}`), the same 201/400/403/404/409 choices. The
  Kotlin `data/model` classes and `server/src/models.rs` are edited together, and a
  changed field means changing the reference contract — don't.
