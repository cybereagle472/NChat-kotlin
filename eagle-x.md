# eagle-x.md — NChat, the whole story

A from-scratch record of what NChat is, why every piece looks the way it does, and what
must not be broken next. If you are picking this project up cold, read this file top to
bottom before touching code. It is the only document that describes *intent*; the code
describes the result.

- Repository: `cybereagle472/NChat-kotlin`
- Current version: **1.0.52** (`versionCode 60`)
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

The Android app talks to a real backend: the Cloudflare worker from `nchat-friends`
(`nchat-friends.usa66486.workers.dev` — D1 + R2 + a Durable Object per user room), addressed
through `network/NChatApi.kt` and one WebSocket per account. The repository/view-model shape
is unchanged from the UI-lab days, which is what made swapping mocks for the real thing
possible without rewriting screens. `docs/BACKEND-WIRING.md` maps every worker route to the
app code that calls it, and every broadcast event to the state it updates — read it before
assuming a screen is connected to anything.

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
- **Nothing in git at all: that is the current setup.** `nchat.properties` ships *empty*; the
  address comes from the repository's Actions variable `NCHAT_API_BASE`, which
  `.github/workflows/build-apk.yaml` passes to Gradle — the way the Cloudflare dashboard
  supplies `VITE_API_URL`. The workflow refuses to publish a main-branch release when the
  variable is missing, so a serverless APK can never be shipped by accident.
- **No address at build time → the app asks for one.** `ServerSetupScreen` runs before the
  auth stage whenever neither the build nor Settings ▸ Server names a backend, so an
  unconfigured APK is a "connect your server" screen rather than a broken one.
- **The server side is symmetrical.** The Rust server builds absolute media URLs from
  `PUBLIC_API_URL` (env, default `http://localhost:$PORT`) — exactly the worker's variable —
  and allows cross-origin requests the way the worker's `cors({origin:'*'})` does.

### No in-app server switcher

The address is a property of the APK, exactly like `VITE_API_URL` is a property of the web
bundle: **Settings has no Server field** (removed in 1.0.23), and `ServerConfig` is gone —
`ApiClient` takes `BuildConfig.NCHAT_API_BASE` directly. To point an install at another
backend, build it with a different `NCHAT_API_BASE`.

A build with no address cannot sign in anywhere, so it opens `MissingServerScreen` and says
so, instead of failing every request. Debug builds allow cleartext HTTP
(`app/src/debug/AndroidManifest.xml`) so a local `cargo run` can be the server; release builds
stay HTTPS-only.

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
- **`CrashLog`** — `install()` runs first in `NChatApplication.onCreate`; it records the
  throwable, the app version and the device to `files/last-crash.txt` (plus a copy in
  `Android/data/com.nchat.app/files/` for a file manager), then lets the platform handler run.
  `MainActivity` shows the stored report before composing the app. `CrashLog.note(...)` is the
  same path for failures that are caught on purpose — silence hides bugs, so they are logged
  and shown but never fatal.
- **`MediaImages`** — the Coil loader registered by `NChatApplication` (a Coil
  `ImageLoaderFactory`, so every `AsyncImage` inherits it). It adds `Authorization: Bearer …`
  to `/media/` reads **only for our own origin**, renews the session once through
  `ApiClient.renewAccessToken()` on a `401`, and caps the on-disk image cache at 128 MB.
  Media is private on the server, so an unauthenticated loader would show broken tiles.
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
| 1.0.52 | 60 | *this build* | **Chat-list power and WhatsApp's small print.** (1) *Text formatting* (`ChatTextFormat.kt`): `*bold*`, `_italic_`, `~strike~`, `` `mono` `` parsed once per message and remembered by the bubble. The rules are WhatsApp's, not a naive regex: an opener must be followed by a non-space, a closer must be preceded by one, a doubled marker (`**`) is literal, and a span cannot cross a newline — which is why `2 * 3 * 4` survives untouched. Styles nest through recursive `withStyle`, so `*bold _and italic_*` works. (2) *Swipe on a chat row*: right reveals Archive, left reveals Mark read; the horizontal drag only claims the axis after slop, and the action fires on release past 72 dp, then the row springs back over 180 ms. (3) *Archive* (`ArchivedStore`): device-side, because `/messages/chats` has no archived column and archiving is a private filing decision. Archived chats drop out of the list into an "Archived (n)" entry that opens a sheet where each one can be opened or put back; nothing else about the chat changes. (4) *All / Unread* chips, with honest empty states per filter. (5) *Pin a message* (`PinnedMessageStore`, one id per chat): a banner under the header with the formatted body, tap to jump and ring the bubble, ✕ to unpin — pinning is personal, so it never reaches the server. (6) *Message info*: from, chat, type, exact timestamp, delivery state and the real flags (edited / forwarded / starred / pinned). The worker keeps no per-recipient read table, so the sheet shows the message's own state rather than inventing a "read by" list. |
| 1.0.51 | 59 | `nchat-build-242` | **System notifications — the gap that mattered most.** Until now NChat rang for nothing at all: it had no `NotificationManager` call anywhere, so a message that arrived while the screen was elsewhere was simply invisible. (1) `NcNotifications` posts a `MessagingStyle` notification per chat, keeping the last six lines in memory so a second message *extends* the card instead of replacing it. (2) **One channel per conversation** (`chat_<id>`, named after the chat) so Android's own settings can mute one person without touching the rest; a muted chat gets `IMPORTANCE_LOW` — silent and no head-up — and because Android cannot change a channel's importance in place, the channel is deleted and recreated **only** when the mute flag actually flips, which leaves every other user tweak alone. (3) **Inline reply**: a `RemoteInput` on a mutable `PendingIntent` into `NotificationReplyReceiver`, which calls the same `ChatRepository.sendMessage` as the composer — so an answer from the shade lands in the outbox, gets ticks, and still sends with no network. The receiver calls `ServiceLocator.init` first, because it can run in a cold process. (4) **Tap opens that conversation**, in both directions: `singleTop` + `onNewIntent` for a warm app, intent extras for a cold one, both writing `ServiceLocator.pendingOpen`, which `HomeRoot` consumes once and clears. (5) The open conversation sets `activeChatId` in a `DisposableEffect`, so it never notifies for messages already on screen, and entering a chat cancels whatever was in the shade. (6) Android 13's POST_NOTIFICATIONS is requested on launch and a refusal is respected silently. A summary notification collapses the group and gives the launcher its badge. |
| 1.0.50 | 58 | `nchat-build-240` | **The chat header grew WhatsApp's tools.** (1) *In-chat search* (`ChatSearchOverlay`): the magnifier on the header opens a full-screen search over the messages the conversation already holds, so it is instant and works offline. Hits are highlighted in brand purple and tapping one closes the overlay, scrolls the timeline to that bubble and rings it for 1,8 s (`highlightId`), because identical bubbles are impossible to tell apart otherwise. (2) *Multi-select*: long-press → **Select** ticks one message and puts the chat into selection mode — deliberately two steps, so a long press never surprise-ticks. The header becomes a count with Forward / Copy / Select-all / Delete; `DeleteMessagesDialog` offers for-me or for-everyone; the selection lives in the view model so an arriving message cannot drop it. (3) *Starred messages* (`StarredStore`): starring copies author/text/timestamp, so a star survives the sender deleting the original and survives the message scrolling out of the loaded page — a server reference would go blank in both cases. (4) *Drafts* (`DraftStore`): 350 ms debounce per keystroke, flushed in `onCleared` so backing out keeps the last thing typed, restored in the view model's `init` before anything renders, and shown as a red **Draft:** on the chat row — `ChatsScreen` collects the store directly instead of threading it through the repository, because a draft is not a message. (5) *Media viewer*: a `HorizontalPager` over the chat's photos and videos. Photos pinch to zoom and double-tap back to fit; `userScrollEnabled = !zoomed` stops the pager stealing horizontal drags while a photo is zoomed, and `LaunchedEffect(pagerState.currentPage)` un-zooms on every page change so the next photo always opens fitted. |
| 1.0.49 | 57 | `nchat-build-2xx` | **The gestures WhatsApp users reach for first.** (1) *Swipe to reply*: the bubble is now its own swipe surface — an `Animatable` offset driven by `draggable(Orientation.Horizontal)`, so only horizontal movement is claimed and the conversation still scrolls when the finger goes up or down. The reply arrow is a sibling of the bubble inside its own Box, offset to the bubble's leading edge, which is why it lines up for a right-aligned outgoing bubble and a left-aligned incoming one without measuring anything. Past 56 dp the release quotes the message (`ConversationViewModel.quote`), the bubble springs back over 160 ms, and the composer shows the quote — no menu involved. (2) *Double-tap quick reaction*: `combinedClickable(…, onDoubleClick)` toggles ❤️ through the same optimistic path as the reaction sheet, so it lands instantly and reverts if the server refuses. The single tap still opens the action menu and a long press still opens it too, which is why the quick reaction had to be a *double* click rather than another press handler. (3) *Voice playback speed*: notes of 20 s or more get the 1× → 1.5× → 2× → 1× chip at the end of the bar. It sits beside the timer, never on the waveform, so tapping it cannot be mistaken for a seek; the rate is applied in `OnPreparedListener` and after `start()` — never to a paused player, because assigning `playbackParams` to one starts it playing on several devices, which would have looked like a random press of play. The bubble widens by 26 dp for the chip and the cap moves 288 → 272 dp so it still fits inside the 300 dp bubble minus its padding. |
| 1.0.48 | 56 | `nchat-build-235` | **Seeking a voice note stopped the audio, because the whole row was a play button.** `VoiceContent`'s `Row` carried `clickable { toggle() }` and the waveform sat *inside* it, so a tap or a drag across the bars was delivered to the row as a click as well — the gesture handler never consumed the up event, and the parent's `combinedClickable` (which opens the message menu on tap) plus the row's toggle both saw the same gesture. Result: you could not seek without pausing, and a drag could open the menu. (0) *Sending loader.* `POST /media/upload` now reports real progress (`ProgressBody`, an Okio `ForwardingSink` around the file part); `Message.uploadProgress` (`@Transient`, so a cached 60 % can never outlive its send) carries it to the bubble, and photo, video and voice bubbles wear a dimmed frame with a determinate ring until the server has the file — a slow upload looks like it is happening instead of a message stuck on its clock. The photo bubble also switches to the uploaded URL the moment the file lands, so the sender sees the picture as the ring disappears rather than a grey gradient. Fixed by making one owner per gesture: (1) only the 36 dp circular button toggles playback; (2) the waveform consumes the down *and* the release, so nothing else can read the gesture; (3) a drag is seek-only, with the head following the finger and the sampler standing down while it is down (`scrubbing`), and a single `seekTo` on release instead of a dozen queued ones; (4) the player is never toggled by a seek — the old `if (!playing) start(positionMs)` after every scrub is gone; (5) a vertical drag that starts on the bars is handed back to the conversation list, so scrolling over a waveform still scrolls; (6) holding still for half a second opens the message menu, like the rest of the bubble. Polish: a thin head marker drawn at the scrub position (visible while paused, where a tint alone reads as no progress), the play button grew to 36 dp, and clips are now fetched when the bubble appears (`MediaFiles`) with a spinner in the button, so the first play starts instantly instead of downloading on the tap — every seek after that is a local file read. Photos show a spinner inside the frame until Coil has fully decoded them, so a slow load no longer looks like a broken image. |
| 1.0.47 | 55 | `nchat-build-228` | **A voice note never played, and a chat row showed two microphones.** (1) *Playback.* `VoiceContent` handed the `media_url` straight to `MediaPlayer.setDataSource(url)`. Two things are wrong with that and both are fatal here: media reads on this backend are private (`GET /media/file/…` answers only a signed-in caller — `server/src/routes/media.rs`), and a player cannot attach the bearer token, so the request went out anonymous and got a 401; and `setDataSource` performs its network I/O synchronously on whatever thread calls it — a Compose click handler, i.e. the UI thread. Every remote clip therefore failed with the app's catch-all 'Couldn't play this voice message'. The bytes are now fetched by `MediaFiles` through the authenticated client (the same token + one-shot renew policy as `MediaImages`), written to `cacheDir/voice/`, sanity-checked to actually be audio (MP4/WebM/Ogg/MP3/WAV/AMR/AAC magic numbers — an error page saved as `.m4a` is caught here instead of by the decoder), and played from disk. That also makes a seek a local read instead of a range request, keeps a played clip available offline, and moves the work off the frame: the play button shows a spinner while the clip is on its way, the tap is remembered (`pendingStartAt`), and playback starts the moment the file lands. Failures are now specific: 'the file is no longer on the server' (404), 'the file is not shared with you' (401/403), 'the server did not return audio', or 'this phone can't decode that voice message' (MEDIA_ERROR_UNSUPPORTED), each of them also written to `ApiLog` so Settings ▸ Sync check shows the request. A dead player bumps a generation counter, so the next tap builds a fresh `MediaPlayer` instead of reusing one stuck in the error state. The full-screen video player had the identical bug — `VideoView.setVideoURI(url)` opens the URL itself, so a private clip answered 401 and the viewer showed 'Couldn't play this video' — and gets the same cure: download through `MediaFiles`, play the local file, spinner while it arrives, and the reason on screen when it cannot be fetched. (2) *The doubled icon.* 1.0.45 built chat-list previews as `"🎤 Voice message (0:07)"`, `"📷 Photo"`, `"🎥 Video"`, `"📎 <file>"`, `"📍 Location"` — while the row already draws a type icon, so one voice note showed a vector mic *and* a microphone emoji. 1.0.46 dropped the emoji from the wording, but rows written by 1.0.45 to the on-disk chat cache still carried it and survived the upgrade. `String.withoutKindMarkers()` strips a leading type emoji from every preview — in `Message.snippet()` and on `ChatListCache.load()` — so the list shows one icon per message for text, photo, video, voice, document and location alike. |
| 1.0.46 | 54 | `nchat-build-225` | **The chat list described something other than the message it was quoting, and four message features were half-wired.** (1) *Previews.* `previewText()` printed the server's raw `content`, so a photo row read "https://…/media/file/…", a voice note said "Voice message" with no length, a document showed a URL, and a group never said who spoke. The line is now derived from the message through one function (`Message.snippet()`): kind-aware, caption-aware, file-name-aware, prefixed with `You: `/`<name>: ` in groups, collapsed to a single line, and refreshed whenever the newest message changes — edit it, delete it, or fail to send it, and the row follows. `Chat.lastMessageFailed` puts a red "not sent" mark in the list instead of a tick for a message that never left the phone. Editing the newest message re-derives the row (`editMessage` never touched the preview — the list kept quoting the old text), and a refresh no longer undoes any of it: a message still in the outbox, or one the server refused, is newer than anything `/messages/chats` can report, so those rows are re-derived from the conversation after every reload — including the recovery pass for conversations the friendship table forgot, which used to rebuild the list from the server's answer and drop them. `Chat.isRecording`/`isTyping` are cleared by the preview writers, so a stale "recording…" can no longer outlive the recording. (2) *Replies.* `reply_to` was sent as `null` on every message while the composer kept a local quote, so a reply arrived on the other phone as an ordinary message. The id is stored on `Message.replyToId`, sent for 1:1 and group messages (and kept in the outbox for a queued one), and resolved back into author + text by `linkQuotes()` — the single funnel every conversation mutation already passes through. Quotes of non-text messages read "Voice message (0:04)" / "Photo · caption" instead of an empty strip, and a quote whose target is outside the loaded page still says it is a reply. (3) *Deleting.* A live `message:delete`/`group-message:delete` used to *remove* the bubble while the next history fetch answered with a tombstone, so the two views disagreed for a second; both now tombstone. A delete the worker refuses — a group message older than its one-hour window (`groups.ts:513-521`) — used to leave a tombstone that a refresh silently undid: the message is put back and the reason is shown. Deleting a message that was still queued left its outbox entry behind, so the next flush delivered what the user had deleted; the entry (and, for a voice note, its recorded clip) is removed with the bubble. `deleted_for` is filtered client-side as well as server-side, because `GET /groups/:id/messages` does not filter it — a group message deleted "for me" came back on every refresh. (4) *Reacting and forwarding.* A refused reaction was left on screen; it is reverted and reported. Forwarding copied `source.text`, so a forwarded photo arrived as an empty bubble and a forwarded voice note as the words "Voice message"; it now re-sends the file — content, type, `media_url`, `thumbnail_url`, `file_name`, `duration` — exactly like the reference's `forwardToUsers` (`chatService.ts:697-737`), and a tombstone cannot be forwarded. (5) *Voice playback.* The waveform is a scrubber now (touch or drag to seek; ranges are already served by the worker), `MediaPlayer` reports the real duration over the label, the play/pause state is optimistic and rolled back on failure, and `playWhenReady` keeps a tap-then-pause during buffering paused instead of starting the clip when the buffer lands. (6) *Settings.* Privacy values are mirrored to SharedPreferences, so the pane no longer claims "Everyone / on" on an offline launch. `docs/BACKEND-WIRING.md` §16. |
| 1.0.45 | 53 | `nchat-build-220` | **A voice note was the one message the app could lose, and the one it drew with a lie.** Three separate faults, all in the same feature. (1) *It was drawn, not recorded.* The recording strip painted `Random.nextFloat()` bars and the bubble painted a fixed repeating pattern, so no voice note ever showed its own shape — and nothing was sent for the other client to draw either. `MediaRecorder.getMaxAmplitude()` is now sampled on the composer's existing 100 ms tick (`core/audio/VoiceRecorder.peakAmplitude`), bucketed to 27 bars, normalised to the clip's own loudest moment and gamma-lifted (`core/audio/VoiceWaveform.kt`), stored in `thumbnail_url` — the column the reference already uses for exactly this (`ChatView.tsx:37-60`) — so the web app draws an Android recording's real waveform and vice versa. The bubble resamples to its own width, fills the played portion from the player's real position, and counts the elapsed time while it plays. (2) *It could not be queued.* `sendVoice` uploaded before it queued anything, so a recording made in a tunnel was alive only in memory: the upload threw, the bubble went red, and the user had to speak again. The bytes now land in `filesDir/voice-out` (`data/local/VoiceClipStore.kt`) before the request, the outbox entry carries the path (`OutboxEntry.localFile` / `mimeType`, defaulted so old queues still decode), and `deliver()` performs the upload as part of delivery — which is also why a still-pending note plays from the phone (`file://`) for its own author. Failure policy is now one function (`onDeliveryFailure`): a dead radio never burns an attempt, a transient answer waits, a live refusal is reported immediately with the server's own words, and only `MAX_OUTBOX_ATTEMPTS` gives up. A retry of a clip that never uploaded re-queues the file instead of posting its local path as a `media_url` nobody else can open. (3) *It was sent as the wrong type.* `MessageKind.wireType()` said `audio`; the web client keys its entire voice bubble off `message.type === 'voice'` (`ChatView.tsx:130`, `:2207`), so every replayed/retried voice send arrived there as a text bubble printing the URL. It is `voice` now. Also fixed: a reply that was open when the mic was held is carried on the voice note instead of being dropped (the reference calls this bug out at `ChatView.tsx:1886-1892`), and a bubble whose clip swaps from `file://` to its uploaded URL no longer reuses the old player's `prepared` flag. Pause is a real pause at last: `MediaRecorder.pause()`/`resume()`, because the strip used to freeze its own timer while the microphone went on recording everything said in the meantime. `docs/BACKEND-WIRING.md` §15. |
| 1.0.44 | 52 | `nchat-build-218` | **The lists were cached once, not kept up to date.** 1.0.43 made every conversation durable, but `ChatListCache` was written in exactly one place — the end of a successful `reload()` — so a message arriving on the socket, a pin, a mute, a rename, a delete or a chat created by an incoming message lived in memory until the next reload, and killing the app rolled the list back. A chat that existed *only* because a message arrived (someone the server's friend view does not list) disappeared from the list entirely after a kill, even though its messages were on disk. Both lists are now mirrored from their state flows (debounced 700 ms, atomic write) and guarded by `chatsTrusted` / `groupsTrusted`: a half-failed refresh — chats without groups, groups without chats — is never written, which is exactly what the old "only persist a complete answer" comment was protecting. The Groups tab gets its own cache (`data/local/GroupListCache.kt`, with rosters, so group bubbles name their author offline) and opens with content instead of "no groups". Finally, the red "couldn't load your chats" banner is suppressed while the phone has no network — the list is complete from disk and the "Waiting for network" pill already explains the situation; the banner is kept for answers the server actually gave. `docs/BACKEND-WIRING.md` §14. |
| 1.0.43 | 51 | `nchat-build-216` | **Messages lived only in memory.** The chat list and (since 1.0.42) the friend graph were cached, but closing the app threw every conversation away: reopening a chat hit `/messages/with/:id`, so offline — or on a bad connection — the screen was empty. No messenger works like that. `data/local/MessageStore.kt` now keeps every conversation on disk, one file per chat at `cache/messages/<ownerId>/<chatId>.json`, newest 300, written atomically (tmp + rename) 400 ms after the conversation's state flow changes — the flow is the single funnel every mutation already goes through, so nothing can be missed — and painted into the screen *before* the request starts. It is per account, deleted with the chat, and cleared on sign-out. The in-memory page rose to 300 to match, so a network merge can no longer shrink history the user was just looking at, and group ids are seeded from the cached list so a group opened offline is read and queued as a group. Second half: **connectivity is now observed, not guessed.** `core/network/NetworkMonitor.kt` listens to `registerDefaultNetworkCallback` and calls the device online only when the network is both INTERNET and VALIDATED (a captive portal fails that test). Offline the socket loop parks instead of dialling a dead radio, the open chat stops its 3 s fallback poll, and the UI says "Waiting for network"; online it re-dials immediately (`reconnectNow`, because a socket that survives a wifi→cellular handover is usually a zombie), re-reports presence, flushes the outbox and resyncs. A send made while offline keeps its clock instead of turning red after three failed attempts — it is queued and delivered on the next `Connected`, which is what WhatsApp does. `docs/BACKEND-WIRING.md` §14. |
| 1.0.42 | 50 | `nchat-build-211` | **The Friends tab was the only list in the app with no local copy, and it lied about why it was empty.** `/friends` was the single source of the graph: memory-only, replaced wholesale on every reload, and rebuilt from an empty list whenever the request failed — so one 502, one 401 that needed a token refresh, or one cold start on a dead network put "No friends yet" on screen while Chats, painted from `ChatListCache`, was showing the very same people. Four changes, all in `data/repository/RemoteFriendRepository.kt`: (1) a failed `/friends` no longer deletes the friends it could not re-confirm; (2) a row the server lists is kept even when its nested `profile` is missing (the row id is the person, `worker/src/routes/friends.ts:177-197`) instead of being dropped silently; (3) the graph is reconciled with the chat list — `/messages/chats` runs the identical `friendships` JOIN and stamps every row `status: 'accepted'` (`worker/src/routes/messages.ts:83-128`), so anyone with a 1:1 thread is a friend, which is the invariant the reference web app gets for free because its chat list is *derived* from its friend list; (4) the graph is cached on disk (`data/local/FriendGraphCache.kt`, per-account, cleared on sign-out) the way the chat list always was. `reload()` is also guarded end to end now — an exception used to escape into `refresh()`'s naked `runCatching`, leaving the list empty with no error at all — and the empty state distinguishes three different answers: the request failed (`graphError`), the server answered zero friends (`graphNote`, naming the account), or the rows could not be read. `SyncCheck` gained a "Friends vs chats" row that prints any conversation missing from `/friends` by id. |
| 1.0.41 | 49 | `nchat-build-209` | **Calls had a line but no voice.** 1.0.40 made the protocol real — ringing, answering, records — but there was no media layer: no peer connection, no tracks, so "Connected" was a timer over silence. `data/calls/WebRtcEngine.kt` now ports the reference's WebRTC plumbing (`CallModal.tsx`, `callService.ts`) onto `org.webrtc` (`io.github.webrtc-sdk:android:150.7871.01`): the caller posts its offer inside `POST /calls`, the callee answers with an SDP answer, and ICE candidates trickle through `POST /calls/:id/signal` — queued when they arrive early (the callee is still ringing) instead of dropped, which is the bug the reference documents at `CallModal.tsx:276`. TURN comes from `GET /calls/turn-credentials`, cached 100 minutes like the web client, with public STUN as the direct-path fallback. The call screen renders two real surfaces: the far end full-screen and our own camera mirrored in the corner, with working mute, speaker and camera-flip. State is `Ringing → Connecting → Live → Ended`; the timer starts only when ICE/DTLS connects, and the reference's 45 s ring / 20 s connecting / 6 s disconnect guard rails are ported so a call that cannot connect says so instead of sitting on "Connecting…". Microphone and camera are requested before dialling *and* before answering. `docs/BACKEND-WIRING.md` §9 and §10.3 updated. |
| 1.0.40 | 48 | `nchat-build-204` | **The app looked wired and was not.** Calls were an animation: `CallOverlay` counted to 1.6 s and declared itself connected, nothing listened for `call:incoming`, so the other phone never rang, answering was impossible and no call record was ever written. Calls now run on the real protocol (`POST /calls` → `call:incoming` → `/answer` → `/end`, with `call:answered`/`call:ended` driving an app-level overlay with Answer/Decline and a timer anchored to the moment the server said answered). Three more silent gaps closed: `StatusRepository.markViewed` had no caller, so a friend's viewer count never moved; `setRecording` had no caller, so a peer never saw "recording audio…"; and group "is typing" guessed the name from whoever spoke last even though the worker sends `sender_id`. Group-chat calls opened an overlay that animated a roster nobody had signalled — removed, the button now says group calls are not available. The dead `groupMembers` wrapper is gone (`GET /groups/:id` already carries the roster). `docs/BACKEND-WIRING.md` is new: every worker route, the app code that reaches it, every realtime event, and the four things that are deliberately not wired (device-side blocking, group calls, call media/WebRTC, operator-only `/admin`). |
| 1.0.39 | 47 | `nchat-build-201` | **Realtime was fragile, so changes arrived seconds late.** The socket loop `return`ed when the access token was momentarily empty (never to restart), backed off 2 s→30 s, retried a `401` handshake with the same dead token, and nothing re-synced after a gap — a WebSocket is not a mailbox. Now: renews the token before re-handshaking, first retry is immediate (cap 8 s), `ensureConnected()` on `onResume` wakes it instantly, `resyncAfterReconnect()` re-reads the list + open conversations, typing indicators expire after 6 s, an open chat polls every 3 s **only while realtime is down**, and a `Connecting…/Reconnecting…` pill makes the state visible. Friend requests, accepts, new/renamed groups and new statuses are now applied from the event payload instead of a 1–3 call reload. Full audit + roadmap in `docs/AUDIT-1.0.39.md`. |
| 1.0.38 | 46 | *this build* | **Friends tab was silent about failure; media from the web was unreadable.** (1) The graph was fetched once in `init` and every failure became an empty list, and the reconnect handler bailed out whenever chats existed — one bad `/friends` call left the tab empty for the whole session. It now retries 3× with backoff, reloads on socket reconnect, is serialised by a mutex, and publishes `graphError`, which the Friends tab renders with a Retry button. (2) The reference stores the uploaded URL in `content`, not `media_url` (`ChatView.tsx:1449-1455` voice `:1911-1918`), and reads `media_url || content` (`:432`); we only read `media_url`, so every photo/video/voice note sent from the web arrived with no URL. `toModel` now falls back to `content`, and we write both fields. (3) Videos now play for real (`VideoView` with transport controls) instead of showing a still frame, sent clips get a poster frame from `MediaMetadataRetriever`, and voice notes pause each other. `duration` and `thumbnail_url` are decoded leniently (the reference sends seconds as a number and a waveform array in `thumbnail_url`), and parse failures are recorded in `ApiLog` instead of vanishing. |
| 1.0.37 | 45 | *this build* | **Messages were being swallowed as "duplicates".** The send id was `"local-${n}"` from an `AtomicLong` that restarted at 1 on every launch, and the worker uses `client_message_id` as an idempotency key — `worker/src/routes/messages.ts:147` does `SELECT * FROM messages WHERE sender_id = ? AND client_message_id = ?` and returns the *existing* row without storing or broadcasting anything. So from the second session on, every send matched an older message from the same account: nothing was stored, nothing was delivered, and the bubble was replaced by that old row. `newClientId()` now mints `c-<UUID>` exactly like the reference's `generateClientMessageId()` (`src/utils/offlineStorage.ts:118`); group sends hit this later only because their key also includes `group_id` (`worker/src/routes/groups.ts:404`). `isPending()` replaces the `"local-"` prefix check so edit/react/delete never fire against a client-only id, and `OutboxStore.read()` drops legacy `local-*` queue rows instead of replaying them. |
| 1.0.36 | 44 | *this build* | **Persistent outbox (reference parity).** `nchat-friends` writes every send to IndexedDB first (`queuePendingMessage`) and flushes it on reconnect (`processPendingQueue`, max 5 tries); the Kotlin client had no queue at all, so a send that hit a dead spot was lost. `data/local/OutboxStore.kt` + `OutboxEntry` now store `{clientId, chatId, isGroup, ownerId, text, type, mediaUrl, thumbnailUrl, duration, fileName, fileSize, replyTo, attempts}` in `filesDir/outbox.json`; `sendMessage`/`sendVoice`/`sendMedia` enqueue before the request, the socket echo or the HTTP answer removes the entry, `flushOutbox()` runs at startup and on every `ConnectionState.Connected`, `deliver()` reuses the same `client_message_id` (idempotent server-side) and gives up after 5 attempts with a visible failed bubble. `restorePending()` puts queued bubbles back into a conversation after a restart. Group chats seed `names` from `GroupDto.members` (bubbles no longer say "NChat user") and handle `group-message:read` to flip own ticks. |
| 1.0.35 | 43 | *this build* | **Sending is instant and never silent; voice notes work.** `loadConversation` now *merges* local and server history keyed by `client_message_id || id` (the reference's `mergeMessages`) instead of replacing the stream, so a message sent while the history was still loading is no longer wiped; `Message.clientMessageId` + `reconcileOwn()` use the worker's `message:new` echo to both participants to flip a pending bubble to sent even if the HTTP answer is lost; `sendWithRetry` makes three backed-off attempts and `markFailed` marks the bubble (`Message.failed`, red warning, tap to resend via `ChatRepository.retrySend`) with a toast instead of a clock that never clears. `ApiClient.upload` now refreshes on 401 like every other call — an expired access token used to kill voice/photo uploads silently; `VoiceRecorder.stop()` reads the file even when `MediaRecorder.stop()` throws; voice `duration` is sent as whole seconds (reference-compatible) and formatted back to `m:ss`. Removed the dead `sendAttachment` mock path. |
| 1.0.34 | 42 | *this build* | **Account isolation for the in-memory chat state.** `RemoteChatRepository` collected `TokenStore.signedIn`: on sign-out it now clears `_chats`, `_groups`, `names`, the conversation streams and the recovered-history peers, and the history probe is keyed to the account it ran for (`historyProbedFor`). Before this, the merge step that keeps threads `/messages/chats` does not list could carry the previous account's rows into the next account's list on the same process. |
| 1.0.33 | 41 | *this build* | **Conversations are recovered from the message history, not from the friendship table.** `GET /messages/chats` is friends-only (`SELECT … FROM friendships`), so a friendship row deleted server-side (an early build's block did exactly that, leaving the accepted `friend_requests` row behind) left a working history with no row in the list — the chat "disappeared" while the person still showed in Find behind an Add button that could only answer 409. `RemoteChatRepository.recoverFromHistory()` probes `GET /messages/with/:id?limit=1` for every account from `GET /users` the chat list does not cover (once per process, main list published first, results cached), and `HomeScreen` merges those threads into the friend graph as `Friend` — Friends tab lists them, Find offers Message. `sendRequest` now treats `409 Already friends` / `409 Request already pending` as state, not failure: the row is promoted to Message or keeps its Cancel, with no error toast. |
| 1.0.32 | 40 | *this build* | **No more silent empty app.** Two real defects behind "everything disappeared": (1) `TokenStore.saveTokens` now writes through `SecurePrefs.putStringSync` (`commit()`), because `apply()` can lose a *rotated* refresh token if the process dies first — and the server has already invalidated the old one, which pins the device to a dead credential and 401s every request; (2) `TokenStore.sessionLost` fires when the pair is dropped, `RemoteAuthRepository` then clears the session/profile cache so the app returns to the sign-in screen with a toast instead of staying on an empty home screen. `HomeScreen` also diagnoses a settled-but-empty account: it reads `ApiLog.lastFailure()` and prints the failing call + status, or says the server answered fine and holds no data, with Retry (refreshes chats/friends/statuses/calls) and Sign out; the same refresh runs automatically when the realtime socket reaches `Connected`. |
| 1.0.31 | 39 | *this build* | **Blocking is non-destructive.** `POST /friends/block` on the reference worker also runs `DELETE FROM friendships`, and `POST /friends/unblock` never restores it — so porting block verbatim would delete the chat. The block now lives on the device (`data/local/BlockedContactsStore.kt`, `filesDir/blocked.json`, `{id,name,avatarUrl,blockedAt}`) and drives `FriendRepository.blockedIds`/`blockedPeople`: `ConversationScreen` keeps every message and replaces the composer with an Unblock banner, `RemoteChatRepository.handleEvent("message:new")` drops live messages from a blocked sender, Discover filters them out of Friends/Requests/Find, and Settings ▸ Blocked Users shows the list with Unblock. The friendship row and both sides of the history are never touched, so unblocking resumes the same conversation at the same message (WhatsApp/Telegram/Messenger behaviour). Legacy server-side blocks are merged into the device list on `reload()` and cleared through `/friends/unblock`, which only removes the `blocked_users` row. |
| 1.0.30 | 38 | *this build* | **Voice messages actually carry audio.** `core/audio/VoiceRecorder.kt` records AAC/.m4a through `MediaRecorder` (with the `RECORD_AUDIO` permission), `ChatRepository.sendVoice` uploads the clip to `POST /media/upload` and sends `type: voice` with `media_url` + `duration` — before this the message had no file at all, so the receiver heard silence. `MessageBubble.VoiceContent` plays the URL through `MediaPlayer` with a real position and pause. Chats tab is 1:1 only (`ChatsViewModel` drops group rows, matching `Chats.tsx`); `reload()` keeps threads the server does not list and `bumpPreview()` creates a row for an incoming message from a non-friend; Discover resolves relation at render time (graph → pending request → existing conversation) so a friend never shows Add, and `FriendRepository.pendingSent`/`actionError` make a refused request revert and explain itself. |
| 1.0.28 | 36 | *this build* | **Status and Group Info wired to endpoints that existed but were never called.** `StatusRepository` gains `createPhoto` (`POST /media/upload` → `POST /status` with `content_type: image` and `content_url`), `viewers()` (`GET /status/:id/views`) and `mute`/`unmute` backed by `mutedAuthors` from `GET /status/muted`; `StatusUpdate` carries `authorId`, `contentType`, `contentUrl`, `viewCount`, and `StatusViewer` renders the picture plus an eye count that opens the viewer list. Group Info had been showing a *guessed* roster (`friends.take(memberCount - 1)`); `Group` now carries the real `members[]` from `/groups` with `createdBy`/`myRole`, so `removeMember`, `setMemberRole` and `deleteGroup` finally have somewhere to go. |
| 1.0.27 | 35 | *shipped as build 174* | **Discover was never receiving the data it renders.** `HomeScreen` handed `DiscoverScreen` a list pre-filtered to accepted friends, and that screen derives the Requests tab and the Find suggestions from it — so both were structurally always empty. It now gets the whole graph. `FriendRepository.search` adds the missing `GET /users/search` call (debounced, stale answers cancelled), so Find searches the server as you type; sent requests get their own section with Cancel. `UserProfile.avatarUrl` carries the account's own photo from `/users/me` (and the cached identity on a cold start) into `ProfileAvatar`, which finally loads it with Coil. `ChatListCache` only persists a fully successful refresh. Sync check gains an account audit: duplicate accounts for the same email, plus a per-contact message count for call-history people who are no longer friends. |
| 1.0.26 | 34 | | **Nothing fails silently any more.** `ApiClient` records every call in `ApiLog` (path, status, duration, error) and Settings ▸ **Sync check** probes ten endpoints, printing the server's own answer next to the signed-in account — `SyncCheck.kt`. `RemoteChatRepository.syncError` drives a red banner with Retry above the chat list, so "empty" can no longer mean "the request failed". Profile photos are wired through the whole UI: `Friend.avatarUrl` and `StatusUpdate.avatarUrl` reach `NcAvatar.imageUrl` in Discover (friends, requests, Find), the status ring and viewer, and `ProfileViewScreen`. |
| 1.0.25 | 33 | *shipped as build 169* | **Fixes the launch crash reported from build 32** (`ClassCastException: java.lang.Boolean cannot be cast to java.lang.String` at `SecurePrefs.getString` → `TokenStore.profileComplete` → `RemoteAuthRepository.<init>`). An older install had stored `profile_complete` as a real boolean; `SharedPreferences.getString` throws on that type, and it ran before the first frame. `SecurePrefs` now reads through `readRaw`, which keeps the value whatever its type and coerces it, so a legacy key can never again make the app unopenable. |
| 1.0.24 | 32 | *shipped as build 167* | **A failure now explains itself.** Any uncaught exception (plus every caught-but-important background failure) is written to `files/last-crash.txt` and to the app's external files dir, and the next launch opens `CrashReportScreen` — selectable text, one-tap *Copy report*, *Delete and start*. The image loader was rebuilt as a standalone OkHttp client fed by lambdas, so a picture that cannot be fetched degrades to a placeholder instead of a process death; `ServiceLocator.appScope` gained a `CoroutineExceptionHandler` for the same reason. |
| 1.0.23 | 31 | *shipped as build 165* | **Photos shrink, previews fly, the list opens instantly, media is private — and the server switcher is gone.** Picked photos are re-encoded to 1600 px / JPEG 85 with EXIF (GPS) stripped and a 320 px thumbnail uploaded alongside; the chat list paints from an on-device cache before the network answers (cleared on sign-out); `/media/file/*` now authorises per caller (uploader, conversation participant, group member, or status audience) and offers signed expiring links via `/media/link`, while the app loads every image with the session token through `MediaImages`. Settings ▸ Server was removed: the address is fixed at build time, bare hosts are normalised to `https://`. |
| 1.0.22 | 30 | *this build* | **Security & privacy pass** (see `SECURITY.md`). Tokens are AES-256-GCM encrypted under an Android Keystore key; the server throttles login/sign-up, caps upload size, confines media keys to the media directory (path-traversal fix), streams media instead of buffering it, and no longer ships a personal admin address in the source. |
| 1.0.21 | 29 | *this build* | **The backend address left the code.** Gradle resolves it once from `nchat.properties` / `-PNCHAT_API_BASE` / `$NCHAT_API_BASE` / `local.properties` (or a CI variable) into `BuildConfig.NCHAT_API_BASE`; `ApiClient` stays the single choke point for HTTP and WebSocket. A build with no address now opens a "Connect NChat" screen and saves what you enter. |
| 1.0.20 | 28 | *this build* | **Media renders.** Coil brings `media_url` into photo/video bubbles and the full-screen viewer, and `avatar_url` into chat-list/header avatars, so an upload is visible on both sides instead of a placeholder. |
| 1.0.19 | 27 | *this build* | **The deployed backend is built in and the demo data is gone.** The APK talks to the configured backend out of the box (Settings ▸ Server still overrides it), every `InMemory*` repository and its seed data was deleted, and the remaining unwired routes were finished: real attachment uploads, the status composer, server-side privacy tiers, avatar upload, and 1:1 call records that the peer is actually rung for. |
| 1.0.18 | 26 | | **The app can talk to the Rust backend.** OkHttp + kotlinx.serialization transport with refresh-token rotation, one realtime socket per user, remote repositories for auth/profile/chats/groups/messages/friends/statuses/call history, and Settings ▸ Server to point any install at a running server. Empty server = the same offline demo as before. |
| 1.0.17 | 25 | `nchat-build-112` | Composer state hoisted (typing no longer recomposes the conversation); chat long-press menu with pin/mute/mark-read/delete. |
| 1.0.16 | 24 | `nchat-build-107` | Settings panes that work: Privacy, Notifications, Blocked users (persisted), shared block state. |
| 1.0.15 | 23 | `nchat-build-105` | Conversation depth: resume marker, jump-to-latest, media viewer, message editing, toasts; per-row render layers removed. |
| 1.0.14 | 22 | `nchat-build-103` | Layout sweep: call-history rows rebuilt, single-line labels everywhere, shared 12dp gutters / 8dp gaps. |
| 1.0.13 | 21 | `nchat-build-101` | Named group typing indicators, group sender avatars, **tab-switch performance fix**, this document. |
| 1.0.12 | 20 | `nchat-build-98` | Auth screen, profile setup, local session, Log Out. |
| 1.0.17 backend | — | *server only* | `server/` lands: full Rust port of the reference worker (all routes, realtime, media, TURN, admin) with 13 end-to-end test flows. No APK change. |
| 1.0.11 | 94 | `nchat-build-94` | Settings profile pane finished; PR #13. |
| 1.0.10 | 90 | `nchat-build-90` | Group call wiring, profile repository, cropper polish, settings profile pane. |
| 1.0.9 | 88 | `nchat-build-88` | Forward dialog, status viewers polish, header actions, consistency sweep. |
| 1.0.8 | 84 | `nchat-build-84` | Update checker, version manifest, FileProvider install flow. |
| 1.0.7 | 82 | `nchat-build-82` | Profile screens + image cropper (pan/zoom, aspect presets), settings sections. |
| 1.0.6 | 78 | `nchat-build-78` | Call overlay (audio/video), pip-style minimise, in-call controls. |
| 1.0.5 | 75 | `nchat-build-75` | Message actions sheet (reply, forward, copy, delete, react), reactions row. |
| 1.0.4 | 72 | `nchat-build-72` | Voice recorder gesture (lock at −56dp, cancel at −96dp), waveform, playback. |
| 1.0.3 | 70 | `nchat-build-70` | Groups: list, create, group info, group chat; group call overlay. |
| 1.0.2 | 66 | `nchat-build-66` | Full Discover (friends/requests), status ring + viewer, history screen; **tab switching regressed here**. |
| 1.0.1 | 62 | `nchat-build-62` | Glass surfaces, empty states, avatar gradients, day separators, message state icons. |


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

### Done in 1.0.23

1. **Private media** — `/media/file/*` authorises per caller, `/media/link` mints signed
   expiring links, and every image request in the app carries the session token
   (`network/MediaImages.kt`).
2. **Photo compression + thumbnails** — 1600 px / JPEG 85 with EXIF stripped, plus a 320 px
   thumbnail that travels with the message (`core/util/MediaPreparer.kt`).
3. **Instant chat list** — the last list is painted from `filesDir/cache/chats.json` before
   the network answers, and cleared on sign-out (`data/local/ChatListCache.kt`).
4. **No in-app server switcher** — one address, fixed at build time.

### Next (not started — each needs a go-ahead)

5. **Pinned chats** surfacing (`PATCH /chats/:id` already stores the flag).
6. **Push notifications (FCM)** so a backgrounded app wakes for a message.
7. **E2EE design note** — a protocol change (per-conversation keys, no server-side search),
   written up before any code.

Older items still open: group admin tools (roles, invite links), search across chats and
messages, light/dark chrome switch, ArkTS/Swift mirrors (out of repo).

---

## 12. Working agreement

- Fidelity first; if the reference and "common Android sense" disagree, follow the
  reference.
- One version bump per batch of changes, never per file.
- Every release: bump `app/build.gradle` + `version.json` in the same commit, push, wait
  for a green run, then tag/verify the release asset size.
- Keep `eagle-x.md` current — when a rule in §8 or §9 is discovered the hard way, add it
  here in the same commit that fixes it.
