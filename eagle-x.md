# eagle-x.md — NChat, the whole story

A from-scratch record of what NChat is, why every piece looks the way it does, and what
must not be broken next. If you are picking this project up cold, read this file top to
bottom before touching code. It is the only document that describes *intent*; the code
describes the result.

- Repository: `cybereagle472/NChat-kotlin`
- Current version: **1.0.22** (`versionCode 30`)
- Package: `com.nchat.app`
- Stack: Kotlin, Jetpack Compose (BOM 2024.09), Material 3 1.2.0, single-module `:app`,
  AGP 8.1, Gradle 8.2, JDK 17, minSdk 24, targetSdk 34 — plus a Rust backend in `server/`
  (axum + tokio + SQLite, see §3.1)
- Distribution: signed release APK per push, published as a GitHub Release

---

## 1. What NChat is

NChat is a pixel-faithful Kotlin/Compose rebuild of a React + Tailwind + Framer Motion
messaging UI (`nchat-friends`, v2.0.0, MIT). The point of the project is **fidelity**: the
web app is the design source of truth, and every screen in Android should be
indistinguishable from it — same gradients, same glass, same motion curves, same copy.

The Android app is a *UI laboratory*, not a production messenger. All data is local and
mocked behind repositories; there is no network backend. That is deliberate: it lets the
UI evolve quickly without server work, while keeping the shape of a real app (repositories,
view models, flows) so real data can be dropped in later.

The APK is a demo/installable artefact: `nchat-ui-lab.apk`, published on every green
build, and self-updates through a version manifest.

---

## 2. Non-goals

- No push notifications.
- The Rust backend (§3.1) exists and is wire-compatible with the reference worker, but the
  Android client still runs on mock repositories; wiring the client to it is the next big
  seam, and until then the APK keeps working with no server at all.
- No offline database; mock data is regenerated from code on each launch.
- No tablet/foldable-specific layouts yet.
- No unit/UI test suite yet — the build gate is compilation plus manual install.

---

## 3. Architecture

Single Gradle module, package-by-feature, with a thin "core" and a "data" layer.

```
app/src/main/java/com/nchat/app/
├── MainActivity.kt                 // edge-to-edge host, single composable root
├── ui/
│   ├── NChatApp.kt                 // launch stages: Auth → ProfileSetup → Home
│   ├── HomeScreen.kt               // tab host + nested nav stack (NcNavigator)
│   └── components/NcBottomBar.kt   // glass floating tab bar
├── core/
│   ├── designsystem/               // NcTheme (palette), NcComponents, BubbleShapes
│   ├── navigation/                 // NcNavigator, NcScreen (stack + tab state)
│   ├── update/                     // update checker against version.json
│   └── util/                       // TimeFormat, avatar initials, misc helpers
├── data/
│   ├── Models.kt / model/*.kt      // Chat, Message, Friend, Group, Status, Reaction…
│   ├── mock/                       // seeded fake conversations, groups, stories
│   ├── repository/                 // Chat, Friend, Profile, Auth repositories (StateFlow)
│   └── ServiceLocator.kt           // hand-rolled DI singleton for the demo
└── feature/
    ├── auth/          AuthScreen, ProfileSetupScreen
    ├── chats/         ChatsScreen, ConversationScreen, MessageBubble, MessageActionsMenu,
    │                  ComposerBar, VoiceRecorder, CallOverlay, GroupCallOverlay, ForwardDialog
    ├── groups/        GroupsScreen, CreateGroupScreen, GroupInfoScreen
    ├── discover/      DiscoverScreen (friends, requests, add/cancel/accept/decline)
    ├── status/        StatusScreen, StatusViewer (stories + progress)
    ├── history/       HistoryScreen (calls/activity log)
    ├── profile/       ProfileScreen (self), ProfileViewScreen (other), ImageCropperScreen
    └── settings/      SettingsScreen, SettingsViewModel
```

**State.** Repositories expose `StateFlow`s; screens collect them with
`collectAsStateWithLifecycle()`. View models are created through
`viewModel(key = …, factory = …)` so each conversation keeps its own instance. The
`ServiceLocator` object is the seam where real repositories would be swapped in.

**Navigation.** `NcNavigator` holds a `tab: HomeTab` plus a back stack of `NcScreen`
values. Tab presses replace the tab; pushes stack screens (conversation, group info,
profile view, cropper) inside the tab. Back pops the stack, then leaves the app. There is
no Navigation-Compose dependency — the stack is a plain `SnapshotStateList` so that tab
switching stays a single composition swap (see §9).

---

## 3.1 The Rust backend (`server/`) — a line-for-line port of `worker/`

The backend is **Rust**, not Node. That is a standing decision: the reference worker is
already production code, so the job is translation, never redesign. Every route, field,
status code, event name and JWT/PBKDF2 format in `server/` was read off
`cybereagle472/nchat-friends/worker/src/**` and reproduced; where the two could differ,
the TypeScript wins.

### Runtime mapping (nothing else changed)

| Reference (Cloudflare) | Here (self-hosted) |
|---|---|
| Hono router on Workers | axum 0.7 on tokio |
| D1 (SQLite over HTTP) | `rusqlite` bundled SQLite, `Db::with()` closure per unit of work |
| D1 migrations `0001`–`0005` | the same SQL folded into `schema.sql` (`include_str!`) + `migrations/` copy |
| R2 bucket `MEDIA` | `NCHAT_DATA_DIR/media/<user>/<id>.<ext>` on disk |
| Durable Object per room | `realtime::Hub` (rooms `dm:<a>:<b>` sorted, `group:<id>`, `call:<id>`, `user:<id>`) |
| `crypto.subtle` PBKDF2 | `pbkdf2` + `sha2` crate, same `"100000:salthex:hashhex"` string |
| JWT via `jose` | `jwt.rs`, HMAC-SHA256, access 15 min / refresh 30 days + single-use `sid` rotation |
| `CF_TURN_*` → rtc.live.cloudflare.com | identical upstream call via `reqwest`, `{"iceServers": null}` on any failure |

### Files and what each one is responsible for

| Path | Responsibility (all ported from a named reference file) |
|---|---|
| `src/db.rs` | connection, pragmas (WAL, foreign keys, busy timeout), `schema.sql` bootstrap, `with()` helper |
| `src/jwt.rs` / `src/password.rs` | the exact token and PBKDF2 formats shared with the reference |
| `src/models.rs` | `profilePublic`, message/group JSON builders, profile masking |
| `src/privacy.rs` | `getPrivacySettings`, `applyPrivacy`, `applyPrivacyToList`, `are_friends` |
| `src/presence.rs` | `last_active_at` reads/writes and the online/offline window |
| `src/realtime.rs` | WebSocket upgrade (`/realtime/connect?room=&token=`), room registry, `publish_to_user` |
| `src/routes/auth.rs` | check-email, signup, login, refresh (rotation), logout, me |
| `src/routes/users.rs` | me/patch, username-available, search, one, list, presence |
| `src/routes/friends.rs` | request/respond/cancel, requests, sent, list, remove, block/unblock/blocked |
| `src/routes/messages.rs` | with/:otherId (paging), chats, send (idempotent `client_message_id`), edit, delete, delete-for-me, reaction, mark-read, typing, recording |
| `src/routes/groups.rs` | create/list/get/members/add/remove/leave/role/mute/update/delete + messages, delete-for-me/everyone, reactions, typing, recording, read state |
| `src/routes/status.rs` | create (24 h TTL), mine, feed, view, views, delete, mute/unmute/muted |
| `src/routes/calls.rs` | turn-credentials, create, signal, answer, end, history (masked profiles) |
| `src/routes/group_calls.rs` | start-or-join, join, signal, decline, leave (mesh signalling relay) |
| `src/routes/media.rs` | upload (streamed to disk, R2 key shape), public read with `Range`→206, uploader-only delete |
| `src/routes/admin.rs` | `/admin/stats` gated on the admin email (`NCHAT_ADMIN_EMAIL`) |

### Behaviour that must not drift

- snake_case JSON everywhere, same wrappers (`{messages:[]}`, `{chats:[]}`, `{groups:[]}`), same 201/400/403/404/409 choices.
- Read receipts are **masked per viewer**: another user's `is_read` is forced to `false` when *their* `read_receipts` is off, and other members' `last_read_at` is hidden in groups.
- Idempotency: `client_message_id` on DM send, `INSERT OR IGNORE` for status views and read-state upserts.
- Realtime fan-out is per-user (`publish_to_user`) even for group events, exactly like `broadcastToUser` in the reference — the client never joins one room per contact.
- Media: `<userId>/<newId>.<ext>` key, `accept-ranges: bytes`, `cache-control: public, max-age=31536000, immutable`, 206 + `content-range` on a byte range, foreign delete → 404.

### Deliberate deviations (the only three)

1. **Timestamps are strictly increasing** (`util::now_iso`). Unread counts compare
   `created_at > last_read_at`, and a fresh group member is compared against `joined_at`, so
   two writes inside the same millisecond made a just-sent message look already read — a real
   flake in the worker as well. Same string format, same wall-clock order, +1 ms when needed.
2. **Realtime rooms live in process memory** (`Hub`), not in Durable Objects. One instance is
   wire-identical; running several instances would need a shared bus (Redis/NATS) in front of
   `publish_to_user`.
3. **Media objects are files** under `NCHAT_DATA_DIR/media`, not R2 objects. Same key shape,
   same headers, same 206 semantics; a real deployment can mount a bucket or object-store
   gateway at that path.

### Verification

`cargo test` runs end-to-end tests through the real router (no mocks): `tests/api.rs`
(8 flows), `tests/groups_status_calls.rs` (4 flows: groups + read state, statuses/views,
1:1 call signalling, group calls), `tests/media.rs` (upload → range read → owner-only
delete). `.github/workflows/backend.yml` is the only gate — fmt, build, clippy, `cargo test`
with a **zero-test guard**, and on failure it commits `.ci/last-failure.log` so the log can
be read with `git show`. There is no local `cargo` in the dev sandbox, so a green run is the
only proof of compilation.

### Configuration

| Env var | Meaning |
|---|---|
| `NCHAT_DATA_DIR` | data root (`data/` by default); SQLite file + `media/` live here |
| `NCHAT_ADMIN_EMAIL` | overrides the `/admin/stats` gate (defaults to the owner's address) |
| `CF_TURN_KEY_ID`, `CF_TURN_KEY_API_TOKEN` | Cloudflare Realtime TURN; absent → `{"iceServers": null}` |
| `PUBLIC_API_URL` | base for absolute media URLs (empty → relative keys) |

### Remaining on this strand

1. Wire the Kotlin client (`ServiceLocator`) to the Rust API — the app is still mock-only.
2. Deployment packaging (binary + systemd/Docker recipe, migrations on boot already handled).
3. D1-specific corners that only show up under concurrency (single-writer vs. D1's queueing) — verified per test flow so far, nothing known broken.

---

## 3.2 How the client reaches it

There is one data source: the deployed backend. The in-memory demo layer and its seed data
were deleted in 1.0.19, so nothing in the app can fall back to fabricated chats, friends or
statuses — an install with no network shows the API's own error, not invented content.

```
UI (screens, view models) ──► repository interfaces
                                  └── Remote* (the only implementation)
                                          └── ApiClient (OkHttp) · NChatSocket (WebSocket)
```

### The backend address is configuration, never code

This mirrors the reference exactly. There, `src/lib/apiClient.ts` reads `VITE_API_URL` once
into `API_BASE`, and every HTTP and WebSocket call funnels through it; the production address
is injected by the build, not committed in a component. The APK does the same thing:

```
nchat.properties / -PNCHAT_API_BASE / $NCHAT_API_BASE / local.properties
        │  (Gradle, build time — one value)
        ▼
BuildConfig.NCHAT_API_BASE
        ▼
ServerConfig.effective()   ← Settings ▸ Server (per-install override, a plain preference)
        ▼
ApiClient (root + socketRoot) ──► every repository ──► every screen
```

- **No Kotlin file contains the address.** `grep` for the host in `app/src/main/java` finds
  nothing; `ApiClient` receives it as a constructor argument and derives `wss://` from the
  same string.
- **One committed default:** `nchat.properties` (`apiBase=`), the counterpart of the worker's
  `PUBLIC_API_URL` in `wrangler.toml`. Build-time precedence is
  `-PNCHAT_API_BASE` → `$NCHAT_API_BASE` → `local.properties` (`nchat.apiBase`) → that file.
- **Nothing in git at all is also supported:** blank the value and set `NCHAT_API_BASE` as a
  repository Actions variable/secret — `.github/workflows/build-apk.yaml` passes it to Gradle,
  the way the Cloudflare dashboard supplies `VITE_API_URL`.
- **No address at build time → the app asks for one.** `ServerSetupScreen` runs before the
  auth stage whenever neither the build nor Settings ▸ Server names a backend, so an
  unconfigured APK is a "connect your server" screen rather than a broken one.
- **The server side is symmetrical.** The Rust server builds absolute media URLs from
  `PUBLIC_API_URL` (env, default `http://localhost:$PORT`) — exactly the worker's variable —
  and allows cross-origin requests the way the worker's `cors({origin:'*'})` does.

### Runtime override

`ServerConfig.effective()` reads **Settings ▸ Server** first (stored in
`SharedPreferences("nchat_server")`), then the baked constant. Clearing the field returns to
the build's address — never to offline mode. The value is read once at start-up (singletons
own the sockets), so the dialog says "reopen NChat after saving". Debug builds also allow
cleartext HTTP (`app/src/debug/AndroidManifest.xml`) so a local `cargo run` can be the
server; release builds stay HTTPS-only.

### Transport rules (`network/`)

- **`ApiClient`** — one OkHttp client, JSON in and out, `ApiException(status, serverMessage)`
  for every non-2xx (the server's own `error` string is what the UI shows). A `401` triggers
  one refresh through a mutex (the backend rotates refresh tokens single-use, so parallel
  refreshes would revoke the session) and then one retry; a failed refresh signs the user out.
- **`TokenStore`** — access + refresh tokens, user id, name, username, avatar, and the
  "profile set up" flag, all in `SharedPreferences`. Restoring a session needs no network.
- **`Dtos.kt`** — snake_case wire types copied from `server/src/models.rs`, **including the
  three legacy camelCase keys** the reference emits (`fileName`, `fileSize`, `lastMessage`,
  `unreadCount`, `receiverIds`). Every field is nullable/defaulted: the backend omits nulls,
  and a new server field must never crash an old APK.
- **`NChatApi`** — one function per route (paths taken from `server/src/routes/mod.rs`).
- **`NChatSocket`** — `GET /realtime/connect?room=user:<id>&token=<jwt>`, exponential
  backoff 1 s → 30 s, `StateFlow<ConnectionState>`, and a `SharedFlow<RealtimeEvent>` of
  `{"type": …}` frames. One socket carries everything: messages, group traffic, typing,
  receipts, calls, statuses — because the server fans every event to each member's personal
  room.

### What each screen reads now

| Screen | Remote source |
|---|---|
| Auth | `/auth/check-email`, `/auth/signup`, `/auth/login`, `/auth/logout` |
| Profile setup / Settings profile | `GET`/`PATCH /users/me` |
| Chats | `GET /messages/chats` + `GET /groups`, merged and sorted by last activity |
| Conversation | `/messages/with/:id` or `/groups/:id/messages`; sends, edits, deletes, reactions |
| Groups | `/groups` with members, roles, mute, leave, add members (real member ids) |
| Discover | `/friends`, `/friends/requests`, `/friends/sent`, `/users`, `/friends/blocked` merged into one relation-tagged list |
| Status | `/status/mine` + `/status/feed`, views persisted through `/status/:id/view` |
| History | `/calls/history` |
| Settings ▸ Blocked | `/friends/blocked` |

### Behaviour details worth remembering

- **Optimistic sends.** The bubble appears immediately with `DeliveryState.Sending`; the
  server's row then replaces it (real id, timestamp, `Sent`), and `message:read` flips it to
  `Read`. A failed send keeps the bubble rather than silently dropping the text.
- **Unread counts belong to the server.** The client only zeroes the badge it was given;
  it never recomputes counts locally (which is also why `util::now_iso` had to be monotonic,
  §3.1).
- **Typing** is streamed (`/messages/typing`, `/groups/:id/typing`); incoming indicators set
  `isTyping`/`isRecording` on the chat row. The mock build keeps its local simulation.
- **Pins and direct-chat mutes stay local** — the reference has no columns for them. Group
  mute is real (`PATCH /groups/:id/mute`).
- **Pins/mutes and group member counts** update optimistically, then reconcile from the
  server reply.

### Backend features wired in 1.0.19

- **Attachments are real files.** Photo / video / document picks go through
  `ActivityResultContracts` → `readPickedFile` (24 MB cap) → `POST /media/upload` →
  `POST /messages` (or `/groups/:id/messages`) with `media_url`, `type`, and file name/size.
  The optimistic bubble is replaced by the server row, exactly like a text send.
- **Status composer.** The ＋ badge on "My status" (and the header actions) opens a text
  composer that calls `POST /status`; contacts receive `status:new` and the 24-hour expiry is
  the server's.
- **Privacy tiers are the server's.** `GET`/`PATCH /privacy` back Settings ▸ Privacy
  (`everyone` / `contacts` / `nobody`, read receipts, online status). Notification switches
  stay device-local — the reference has no server-side notification preferences either.
- **Avatars upload.** A cropped avatar is re-encoded to PNG, uploaded to `/media/upload`, and
  stored on the account with `PATCH /users/me {avatar_url}`.
- **Calls create records.** Opening a 1:1 call places `POST /calls` (the other side gets
  `call:incoming`); hanging up closes it with `POST /calls/:id/end {status, duration}`, so
  History and the peer agree on what happened.
- **Live username availability.** Sign-up asks `GET /users/username-available` instead of
  comparing against a hardcoded list.

### Remote media rendering (1.0.20)

Media and avatars are fetched, not simulated. Coil (`coil-compose`) loads:

- **Photo bubbles** — `media_url` fills the 210×140 frame (`ContentScale.Crop`); the brand
  gradient is only the loading/fallback state.
- **Video bubbles** — the poster frame is `thumbnail_url ?: media_url` with the play badge on
  top.
- **The media viewer** — tapping a photo/clip opens the file full-screen.
- **Avatars** — `avatar_url` renders inside `NcAvatar` wherever the model carries one (chat
  list rows, conversation header); the gradient initial stays as the fallback.
- `/media/file/:key` is **public** in the reference worker (no auth header needed), so Coil
  fetches directly; the returned URL is absolute and already points at the server that
  stored the file.

### Still missing on this strand (deliberate, tracked)

1. **Calls have no audio/video transport.** Signalling endpoints, TURN credentials and call
   records are all real; the overlay is still a ringing UI with no WebRTC stack behind it.
3. **No offline queue** — a send that fails while the socket is down is not retried
   automatically (ARCHITECTURE step 5).
4. **Incoming calls are not surfaced** — `call:incoming` refreshes History but does not yet
   raise a full-screen ringer.

---

## 4. Design system

### 4.1 Palette (exact — do not approximate)

| Token | Value |
|---|---|
| Brand gradient (headers, FAB, logo) | `#8B5CF6 → #A855F7 → #D946EF` |
| Body gradient (every screen background) | `#F5F3FF → #FAF5FF → #FDF4FF` |
| Own bubble | violet → purple, white text |
| Composer | `#A855F7 → #8B5CF6 → #7C3AED` |
| Incoming bubble | white, subtle slate text |
| Text | slate-900 / 700 / 500 / 400 |
| Typing indicator | violet-500 (`NcTheme.extra.typing`) |
| Read receipts | sky-500 |
| Online dot | `#22C55E` |
| Glass | `rgba(255,255,255,.22)` + `blur(18px)` + `saturate(150%)` + border `.55` |
| Error banner | bg `#FEF2F2`, border `#FECACA`, text `#DC2626` |
| Success banner | bg `#F0FDF4`, border `#BBF7D0`, text `#16A34A` |
| Group call background | `#0F172A → #2E1065 → #0F172A` |

### 4.2 Shape & spacing

- Chat-list avatar 56dp, radius 16dp. Header avatar 40dp circle.
- Bubbles radius 16dp with a 6dp tail corner. Meta row 10sp. Composer radius 28dp.
- Group sender avatar 30dp circle, gradient, initial fallback.
- Screen padding = `NcSpace.md` (16dp). List items are separated by 2dp, not 8dp, so runs
  read as one block.

### 4.3 Motion

- Signature curve: `cubic-bezier(0.16, 1, 0.3, 1)` — fast out, long settle.
- Tab bar chip: tint 120 ms, scale 140 ms, press feedback 90 ms. Nothing springy.
- Overlays (calls, viewers, sheets) slide/fade in 200–260 ms.
- Typing dots: 3 dots, 1400 ms cycle, 150 ms stagger, reverse repeat.
- **Lists do not animate on entry, tabs do not cross-fade.** See §9.

---

## 5. Version history

Each row is one shipped release; versionCode is what the updater compares.

| Version | versionCode | Release tag | What landed |
|---|---|---|---|
| 1.0.0 | 60 | `nchat-build-60` | Foundation: theme, bottom bar, chat list, conversation, composer, bubbles, mock data. |
| 1.0.1 | 62 | `nchat-build-62` | Glass surfaces, empty states, avatar gradients, day separators, message state icons. |
| 1.0.2 | 66 | `nchat-build-66` | Full Discover (friends/requests), status ring + viewer, history screen; **tab switching regressed here**. |
| 1.0.3 | 70 | `nchat-build-70` | Groups: list, create, group info, group chat; group call overlay. |
| 1.0.4 | 72 | `nchat-build-72` | Voice recorder gesture (lock at −56dp, cancel at −96dp), waveform, playback. |
| 1.0.5 | 75 | `nchat-build-75` | Message actions sheet (reply, forward, copy, delete, react), reactions row. |
| 1.0.6 | 78 | `nchat-build-78` | Call overlay (audio/video), pip-style minimise, in-call controls. |
| 1.0.7 | 82 | `nchat-build-82` | Profile screens + image cropper (pan/zoom, aspect presets), settings sections. |
| 1.0.8 | 84 | `nchat-build-84` | Update checker, version manifest, FileProvider install flow. |
| 1.0.9 | 88 | `nchat-build-88` | Forward dialog, status viewers polish, header actions, consistency sweep. |
| 1.0.10 | 90 | `nchat-build-90` | Group call wiring, profile repository, cropper polish, settings profile pane. |
| 1.0.11 | 94 | `nchat-build-94` | Settings profile pane finished; PR #13. |
| 1.0.12 | 20 | `nchat-build-98` | Auth screen, profile setup, local session, Log Out. |
| 1.0.13 | 21 | `nchat-build-101` | Named group typing indicators, group sender avatars, **tab-switch performance fix**, this document. |
| 1.0.14 | 22 | `nchat-build-103` | Layout sweep: call-history rows rebuilt, single-line labels everywhere, shared 12dp gutters / 8dp gaps. |
| 1.0.15 | 23 | `nchat-build-105` | Conversation depth: resume marker, jump-to-latest, media viewer, message editing, toasts; per-row render layers removed. |
| 1.0.16 | 24 | `nchat-build-107` | Settings panes that work: Privacy, Notifications, Blocked users (persisted), shared block state. |
| 1.0.17 | 25 | `nchat-build-112` | Composer state hoisted (typing no longer recomposes the conversation); chat long-press menu with pin/mute/mark-read/delete. |
| 1.0.17 backend | — | *server only* | `server/` lands: full Rust port of the reference worker (all routes, realtime, media, TURN, admin) with 13 end-to-end test flows. No APK change. |
| 1.0.18 | 26 | | **The app can talk to the Rust backend.** OkHttp + kotlinx.serialization transport with refresh-token rotation, one realtime socket per user, remote repositories for auth/profile/chats/groups/messages/friends/statuses/call history, and Settings ▸ Server to point any install at a running server. Empty server = the same offline demo as before. |
| 1.0.19 | 27 | *this build* | **The deployed backend is built in and the demo data is gone.** The APK talks to the configured backend out of the box (Settings ▸ Server still overrides it), every `InMemory*` repository and its seed data was deleted, and the remaining unwired routes were finished: real attachment uploads, the status composer, server-side privacy tiers, avatar upload, and 1:1 call records that the peer is actually rung for. |
| 1.0.20 | 28 | *this build* | **Media renders.** Coil brings `media_url` into photo/video bubbles and the full-screen viewer, and `avatar_url` into chat-list/header avatars, so an upload is visible on both sides instead of a placeholder. |
| 1.0.21 | 29 | *this build* | **The backend address left the code.** Gradle resolves it once from `nchat.properties` / `-PNCHAT_API_BASE` / `$NCHAT_API_BASE` / `local.properties` (or a CI variable) into `BuildConfig.NCHAT_API_BASE`; `ApiClient` stays the single choke point for HTTP and WebSocket. A build with no address now opens a "Connect NChat" screen and saves what you enter. |
| 1.0.22 | 30 | *this build* | **Security & privacy pass** (see `SECURITY.md`). Tokens are AES-256-GCM encrypted under an Android Keystore key; the server throttles login/sign-up, caps upload size, confines media keys to the media directory (path-traversal fix), streams media instead of buffering it, and no longer ships a personal admin address in the source. |


---

## 6. What each feature cost (and why it looks the way it does)

### 6.0 Row anatomy (read this before writing any list row)

Rows broke in 1.0.13 exactly where a flexible `Row` had one more child than it had space:
the call-history duration was measured with ~15dp left, so `12:08` wrapped one character per
line and every card grew around it. The invariants that prevent this class of bug:

- **One hierarchy per card, three lines max:** identity, meta, then time. The same order in
  every list, so cards are the same height on every screen.
- **Every text in a row is single-line**: `maxLines = 1` plus `softWrap = false` for tokens
  (times, durations, pills) and `overflow = TextOverflow.Ellipsis` for names.
- **Exactly one child may shrink:** the flexible label gets `Modifier.weight(1f, fill = false)`.
  Fixed-size siblings (pills, badges, ticks, action icons) must never be the child that gets
  squeezed, otherwise they wrap.
- **Fixed leading and trailing columns:** avatar 44–56dp, trailing actions 28–32dp each; the
  middle column alone takes `weight(1f)`.
- **`heightIn(min = …)`** on the card so one- and two-line bodies still align.
- **Shared gutters:** `contentPadding = PaddingValues(NcSpace.md)` (12dp) and
  `Arrangement.spacedBy(NcSpace.sm)` (8dp) for every tab list.

### 6.1 Chat list
Glass row: 56dp gradient avatar, name, snippet, time, unread count. Rows are dense (2dp
gaps) because the reference reads as a single translucent sheet, not cards. Online dot is
an 11dp `#22C55E` circle with a 2dp white ring, bottom-right of the avatar.

### 6.2 Conversation
- Header is a brand-gradient row with back, avatar, title + status, video, voice, more.
  It sits under the status bar (`statusBarsPadding`) rather than behind it — the reference
  header is opaque, not immersive.
- Bubbles: own = gradient with white text and light meta; incoming = white with a 1dp
  hairline. `widthIn(max = 300.dp)` (75% on the reference) keeps long messages readable.
- Meta row: clock, `edited`, delivery tick (single = sent, double sky = read).
- Long-press opens the message actions sheet; the bubble that received the gesture must
  stay mounted while the sheet is up, otherwise the gesture dies mid-animation.
- Reply shows a quote strip inside the composer and a quote block in the bubble.
- Photos/video/voice/document/location bubbles each have a bespoke content composable;
  voice duration is parsed from the label.

### 6.3 Composer
Gradient pill (radius 28dp), reply banner above, attach, camera, text field that grows to
~5 lines, mic that morphs into send when text is non-empty. Voice recording is
press-and-hold: drag up ≥56dp to lock, drag left ≥96dp to cancel; releasing with
`cancelProgress ≥ 0.9` or after <1s discards.

### 6.4 Status
Ring gradient (violet→fuchsia) on unseen stories, slate ring on seen. Viewer is full
bleed with segmented progress bars, tap left/right to step, hold to pause, swipe down to
close. Progress uses `LinearProgressIndicator(progress = { … })` — the lambda overload is
mandatory in Material 3 1.2.0.

### 6.5 Groups
Create-group flow (name, description, member picker with pending invites), group info
(eight features, member list, exit), group chat variant of the conversation, and the
group call overlay: dark `#0F172A → #2E1065` background, 140dp min tiles at 12dp radius,
hang-up 64dp while calling / 56dp in call, toggles `0x26FFFFFF` when active.

### 6.6 Profile & cropping
`ProfileScreen` (self, editable) and `ProfileViewScreen` (others) share the gradient
header + glass card language. The cropper supports pan, pinch zoom (1×–4×), 90° rotation
and 1:1 / 4:5 / 16:9 aspect presets, exporting a bitmap with `withTransform` inside the
`DrawScope` (not `Modifier.drawBehind` — `withTransform` is a `DrawScope` extension).

### 6.7 Auth + onboarding (1.0.12)
Local-only accounts, faithfully ported from `Auth.tsx` / `ProfileSetup.tsx`:

- Three-stop violet gradient with Canvas waves and four floating circles (6 s loops).
- White card r24; 64dp logo r16 gradient `#A855F7 → #7C3AED`; "Welcome Back" /
  "Create Account" copy.
- Validation order: email regex → passwords match (sign-up) → length ≥ 6 → email taken.
- Email availability check debounces 500 ms and only runs in sign-up mode; submit is
  disabled while loading or when the email is taken.
- Switching mode clears password, confirm and banners.
- Profile setup: six gradient avatar styles, display name, sanitised username with
  spinner/✓/"Taken", 150-char bio, "Start chatting".
- Session persists in `SharedPreferences("nchat_auth")`; `NChatApp` crossfades
  Auth → ProfileSetup → Home; Settings exposes Log Out.

---

## 7. Build, sign and release contract

`.github/workflows/build-apk.yaml` runs on every push to `main` and `arena/**`:

1. JDK 17 + Gradle 8.2 + `platforms;android-34`, `build-tools;34.0.0`.
2. Restores the persistent demo keystore from `.github/nchat-demo-keystore.b64`
   (passwords `NChatDemo2026!`, alias `nchat-demo`) — the key never changes, so updates
   install over older builds without uninstalling.
3. `gradle --no-daemon --stacktrace :app:assembleRelease`, log teed to
   `/tmp/gradle-build.log`.
4. On failure, the last 500 log lines are committed to `.ci/last-failure.log` with
   `[skip ci]` and pushed. **This file is stale even on green runs — trust the run's exit
   code, and `git rm` the file if it appears.**
5. Copies the APK to `nchat-ui-lab.apk`, uploads the workflow artifact, and publishes the
   GitHub release `nchat-build-<versionCode>` with that asset.

Version bumps are always one commit touching both `app/build.gradle`
(`versionCode` + `versionName`) and `version.json`. The in-app updater reads
`raw.githubusercontent.com/cybereagle472/NChat-kotlin/main/version.json`, compares
`versionCode`, and installs through `FileProvider`
(`${packageName}.fileprovider`) from `files/updates/nchat-${versionCode}.apk`.

Download link (stable):
`https://github.com/cybereagle472/NChat-kotlin/releases/latest/download/nchat-ui-lab.apk`

Release sizes: 1.0.9 → 10,292,897 B · 1.0.10 → 10,309,569 B ·
1.0.11 → 10,338,889 B · 1.0.12 → 10,373,701 B.

---

## 8. Hard-won lessons (do not repeat these)

**Compose / Material 3 1.2.0**

- `LinearProgressIndicator` must be called with `progress = { … }`.
- No `DropdownMenu(containerColor = …)`, no colour params on `Slider` (use
  `SliderDefaults.colors`); avoid `animateDpAsState` on `Dp?`.
- `withTransform` is `DrawScope`-only. `rotate`/`scale` take `Offset` pivots.
- A white stripe or "mixed colour" bubble comes from chaining `clip()` + translucent
  `background()` + `border()` + `shadow()` in one modifier chain — split it.
- Never unmount the node that received `touchstart` (voice gesture, long-press sheets).

**Material icons actually available in `material-icons-extended`**

`Sparkles` → use `AutoAwesome`; `AtSign` → `Email`; `UserCheck` → `HowToReg`;
`UserMinus` → `PersonRemove`; `Users` → `Groups`; `Schedule` → `History`.
Known-good: `OpenWith`, `RotateRight`, `ZoomIn`, `ZoomOut`, `Close`, `Check`,
`ChatBubble`, `Visibility`, `VisibilityOff`, `Info`, `CameraAlt`, `Logout`, `Mic`,
`Call`, `Videocam`, `MoreVert`, `ArrowBack`.

**Kotlin**

- Don't name a private enum/state holder the same as a composable (`SettingsSection` →
  `SettingsPane`).
- Adding a parameter to a composable without updating every call site is the most common
  CI failure. `grep` the signature's name after changing it.
- Unused imports are *not* errors in Kotlin, but the sweep script that removes them must
  keep `getValue`/`setValue` (delegate operators) and never touch `as` aliases.
- Regex/`str.index` patch scripts can duplicate a block when the anchor is short — after
  each patch, grep `fun ` and check brace balance.

**Verification**

- There is no reliable local Gradle build in this environment. **Never claim a change
  compiles without a green workflow run.**
- Tree-sitter (Python `tree-sitter` + `tree-sitter-kotlin`) catches syntax errors and
  unbalanced braces before pushing; it does *not* catch unresolved imports.
- `gh run watch` exit code is the source of truth; `.ci/last-failure.log` is not.
- A pushed CI bot commit (`[skip ci]`) can make the next `git push` non-fast-forward —
  `git pull --rebase` first.

---

## 9. Performance & layout rules (1.0.13 / 1.0.14)

User report: *"switching between Chats / Groups / Status / History / Discover has been slow
since 1.0.2 — it must be instant."* Root causes were self-inflicted decoration, not data:

1. **`NcBackdrop` was animating forever.** It used `rememberInfiniteTransition()` plus
   three composable blobs whose radial gradients were re-rasterised every frame — on
   *every* screen, because every screen mounts a backdrop. It is now a single static
   `Canvas` drawing three radial-gradient circles. Visually identical, ~0% idle cost.
2. **Tab switching cross-faded.** `AnimatedContent(targetState = navigator.tab)` kept both
   tab trees composed and measured during the transition. Replaced with a plain
   `Box { when (navigator.tab) { … } }` — one composition swap, no overlap.
3. **Lists replayed entrances on every visit.** `DiscoverScreen` and `HistoryScreen`
   wrapped each row in a staggered `SlideUp(index)` `graphicsLayer`, which re-ran on every
   tab switch. Rows are now plain `items` calls; keys and hoisted `LazyListState`s are
   kept so scroll position survives.
4. **The tab chip was springy.** `spring(dampingRatio = …)` on nav tint/scale/press was
   replaced with `tween(120/140/90)`.

Standing rules:

- Backgrounds are static. Nothing that fills the screen may animate continuously.
- Tab switching is a composition swap — no cross-fade, no slide, no shared-element work.
- No per-row entrance animations in lists, ever. Hover/press feedback only.
- Nav affordances animate in ≤150 ms.
- Keep `key`s on list items and hoist `LazyListState` to the tab host so returning to a
  tab restores its scroll offset instead of rebuilding.
- Rows follow §6.0: single-line text, one weighted flexible child, fixed leading/trailing
  columns, `heightIn(min = …)`.
- **No per-row `shadow()`.** Each shadowed bubble is its own render layer; a hairline border
  looks identical at 1dp and costs nothing. Shadows stay on one-off chrome (sheets, pills).
- **No snapshot-backed maps written from layout.** Bubble bounds live in a plain `HashMap`
  and are read only when a menu opens; writing them into `mutableStateMapOf` invalidated
  readers on every layout pass while scrolling.
- **Scrolling must never re-filter.** Derived lists (`calls.filter`, `friends.filter`, …) are
  wrapped in `remember(data, key)`. Whenever a new label is added to a row, ask "what happens at
  1.3× font scale on a 360dp screen?" — if the answer is "it wraps", weight the label and
  ellipsize it, never the token beside it.

---

## 10. Reference component status

Web source (`nchat-friends` v2.0.0) → Android port.

| Reference file | Lines | Ported to | Status |
|---|---|---|---|
| `ChatView.tsx` | 3033 | `ConversationScreen.kt`, `MessageBubble.kt`, `VoiceRecorder.kt` | Done |
| `Settings.tsx` | 1716 | `SettingsScreen.kt` | Done (incl. Log Out) |
| `GroupChatView.tsx` | 1596 | `ConversationScreen.kt` group branch | Done 1.0.13 (named typing, sender avatars) |
| `CallModal.tsx` | 1044 | `CallOverlay.kt` | Done |
| `Status.tsx` | 1018 | `StatusScreen.kt`, `StatusViewer.kt` | Done |
| `GroupCallModal.tsx` | 927 | `GroupCallOverlay.kt` | Done |
| `Auth.tsx` | 853 | `AuthScreen.kt` | Done 1.0.12 |
| `Discover.tsx` | 725 | `DiscoverScreen.kt` | Done |
| `MessageActions.tsx` | 724 | `MessageActionsMenu.kt` | Done |
| `Chats.tsx` | 688 | `ChatsScreen.kt` | Done |
| `GroupInfo.tsx` | 616 | `GroupInfoScreen.kt` | Done |
| `ImageCropper.tsx` | 500 | `ImageCropperScreen.kt` | Done |
| `ProfileView.tsx` | 479 | `ProfileViewScreen.kt` | Done |
| `GroupMessageActions.tsx` | 401 | `MessageActionsMenu.kt` (shared) | Done |
| `CreateGroup.tsx` | 374 | `CreateGroupScreen.kt` | Done |
| `Groups.tsx` | 356 | `GroupsScreen.kt` | Done |
| `ProfileSetup.tsx` | 353 | `ProfileSetupScreen.kt` | Done 1.0.12 |
| `History.tsx` | 220 | `HistoryScreen.kt` | Done |
| `Friends.tsx` | 129 | `DiscoverScreen.kt` (requests tab) | Done |

### Group chat specifics (1.0.13)

Ported from `GroupChatView.tsx`:

- `describeActivity(ids)` — 1 name → `"Name"`; 2 → `"A and B"`; n → `"A and N others"`
  (N = count − 1). The recording set always takes priority over the typing set, and
  recorders are excluded from the typing set.
- Header second line: while recording → green-300 (`#86EFAC`) `Mic` 12dp +
  `"… recording audio"`; while typing → green-300 `"… typing"` + three 4dp dots
  (0/150/300 ms); otherwise `"N members · tap for info"`. Android names the member:
  `"Ayesha typing…"`.
- Incoming group bubbles carry a 30dp circle (gradient, initial fallback) **only on the
  first message of a sender run**; continuation bubbles get an equivalent 38dp spacer so
  the column stays aligned. Sender name sits above the bubble at 10sp violet-600.
- Android derives the typing member from a local simulation in `ConversationViewModel`
  (`chat.isTyping`), reusing the 1:1 reasoning: first signal is instant, "stopped" debounces
  for 2 s.

---

## 11. Roadmap

Ordered by what the reference still exposes and what users have asked for:

1. Real persistence (Room) so mock data survives restarts.
2. Attachment picker parity: gallery multi-select, document browser, location picker.
3. Status composer (text/photo stories with gradient backgrounds, viewers list).
4. Group admin tools (add/remove members, roles, invite links).
5. Search across chats and messages.
6. Light/dark theme switch (dark palette already exists for calls — extend to chrome).
7. ArkTS/Swift mirrors of the same design system (out of repo for now).

---

## 12. Working agreement

- Fidelity first; if the reference and "common Android sense" disagree, follow the
  reference.
- One version bump per batch of changes, never per file.
- Every release: bump `app/build.gradle` + `version.json` in the same commit, push, wait
  for a green run, then tag/verify the release asset size.
- Keep `eagle-x.md` current — when a rule in §8 or §9 is discovered the hard way, add it
  here in the same commit that fixes it.
