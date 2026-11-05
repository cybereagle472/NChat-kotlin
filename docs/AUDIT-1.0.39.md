# NChat Android — full app audit & realtime to-do

Written for 1.0.39. Everything below was read out of this repository (and the reference
`cybereagle472/nchat-friends` where behaviour had to be matched); nothing is assumed.

The goal of this pass is the one thing the web app cannot do and the user asked for
explicitly: **every surface must update the instant it changes, like WhatsApp** — not
"after a few seconds", and never "only after a restart".

---

## 1. The app as it stands (inventory)

### Shell
| Surface | File | Notes |
|---|---|---|
| Launch / auth gate | `ui/NChatApp.kt`, `feature/auth/AuthScreen.kt` | Crossfade between Auth → Profile setup → Main |
| Bottom tabs | `core/navigation/NcNavigator.kt` | **Chats · Status · Groups · Discover · History** |
| Conversation screen | `feature/chats/ConversationScreen.kt` | header: back, avatar, call, video call, ⋮ menu |
| Group info | `feature/groups/GroupInfoScreen.kt` | members, add/remove, leave |
| Create group | `feature/groups/CreateGroupScreen.kt` | pick friends, name, description |
| Profile / view profile | `feature/profile/ProfileScreen.kt`, `ProfileViewScreen.kt` | avatar crop |
| Settings | `feature/settings/SettingsScreen.kt`, `SettingsPanes.kt` | Account · Privacy · Notifications · Blocked Users · About · **Sync check** |
| Crash report | `ui/CrashReportScreen.kt` | last-launch crash |
| Toasts | `core/designsystem/NcToast.kt` | one host for the app |

### Chats tab (`feature/chats/ChatsScreen.kt`)
Search field · conversation rows (avatar, name, preview, time, unread badge, pin, mute,
typing line) · long-press row menu (pin / mute / delete / mark read) · empty + error state
with Retry · compose FAB.

### Conversation (`feature/chats/ConversationScreen.kt`, `MessageBubble.kt`)
Composer (text, attach: photo / video / document, mic: hold-to-record, slide-to-cancel,
slide-up-to-lock, pause, send) · reply quote · forward dialog · message actions menu
(react, reply, copy, edit, delete, forward, info) · reactions sheet · bubbles for text,
photo, video, voice, document, location · tick states (clock / single / double blue) ·
failed-send tap to retry · unread divider · media viewer (photo, **real video playback**) ·
edit sheet · delete confirm · in-chat search? (no) · block banner with Unblock.

### Groups tab (`feature/groups/GroupsScreen.kt`)
Group rows (name, member count, last message, unread) · create group · open group info ·
leave / delete.

### Discover (`feature/discover/DiscoverScreen.kt`)
Tabs **Friends · Requests · Find** · friend rows (message / call / video call / unfriend) ·
requests with Accept / Decline · sent requests with Cancel · Find search (server-side,
debounced 250 ms) with Add / Message / Cancel · profile view · graph error card + Retry.

### Status (`feature/status/StatusScreen.kt`)
My status + friends' statuses · text status · photo status (upload) · viewers sheet ·
mute author · 24 h expiry rendering.

### History (`feature/history/HistoryScreen.kt`)
Call history rows (incoming/outgoing/missed, voice/video, duration).

### Settings panes
`Account` (name, username, bio, email, avatar) · `Privacy` (last seen, profile photo, read
receipts, online status) · `Notifications` (messages, preview, calls, sound, vibrate) ·
`Blocked Users` (local, non-destructive) · `About` (version) · `Sync check` (live endpoint
probes + the REST log + parse failures).

### Calls
`feature/chats/CallOverlay.kt`, `GroupCallOverlay.kt` — UI exists; **signalling is not
wired** (`answerCall`, `signalCall`, `turnCredentials`, group-call routes are unimplemented
in `NChatApi`), and nothing listens for `call:incoming`, so a call never rings. Out of
scope for the speed pass, tracked below.

---

## 2. Realtime: how each surface learns about a change

| Surface | Mechanism | Instant? |
|---|---|---|
| Open conversation, new message | `message:new` / `group-message:new` → `ingest()` into a `MutableStateFlow` | **yes** |
| Own message acknowledgment | HTTP answer **and** socket echo (`reconcileOwn`) | **yes** |
| Chat list preview / unread / order | `bumpPreview()` on the same event | **yes** |
| Read ticks (1:1) | `message:read` | **yes** |
| Read ticks (group) | `group-message:read` | **yes** |
| Reactions / edits / deletes | `message:reaction` / `message:edit` / `message:delete` (+ group variants) | **yes** |
| Typing / recording | `typing`, `recording`, `group-typing`, `group-recording` | yes, but never expired (fixed in 1.0.39) |
| Friends / requests | `friend:*` → full refresh | 1–3 round trips |
| Groups list / membership | `group:*`, `group-member:*` → full refresh | 1–3 round trips |
| Status feed | `status:new`, `status:view` → full refresh (3 GETs) | 1–3 round trips |
| Call history | `call:*` → full refresh | 1 round trip |
| **Anything missed while the socket was down** | *nothing* | **no — fixed in 1.0.39** |
| **Socket recovery speed** | backoff 2 s → 30 s, loop could exit permanently | **no — fixed in 1.0.39** |

## 3. What 1.0.39 changes (this pass)

1. **`NChatSocket` can no longer give up or stall.**
   - A missing/empty token no longer *ends* the reconnect loop (it parked forever); it
     waits and retries, and renews an expired access token before re-handshaking
     (`401` on the WS upgrade is now detected and triggers a refresh instead of an endless
     retry with a dead token).
   - Backoff starts at **0 ms** and caps at **8 s** (was 2 s → 30 s).
   - `ensureConnected()` + a CONFLATED nudge channel: the app foreground (`onResume`) wakes
     a sleeping socket *immediately* instead of waiting out the delay.
2. **Reconnect ⇒ catch up.** `resyncAfterReconnect()` re-reads the chat list and every
   conversation this process has opened, so messages broadcast during an outage appear at
   once. (A WebSocket is not a mailbox; this is the standard fix.)
3. **Typing indicators expire** after 6 s of silence, 1:1 and groups — no more permanently
   stuck "typing…".
4. **Liveness fallback.** While the realtime socket is *not* connected, an open conversation
   re-reads itself every 3 s (`ChatRepository.refreshConversation`). With the socket up this
   does nothing at all; with it down the screen is never stale.
5. **Visible state.** A small "Connecting… / Reconnecting…" pill appears (after 1.2 s, so a
   normal instant reconnect never flashes) — the user can see the truth instead of guessing.

## 4. To-do, in priority order

### P0 — realtime correctness (this release)
- [x] Socket never exits, fast reconnect, token-aware handshake
- [x] Foreground wake (`onResume`)
- [x] Catch-up resync after a gap
- [x] Typing expiry
- [x] Offline liveness poll for the open chat
- [x] Connection indicator

### P1 — incremental event application (done in 1.0.39)
- [x] `friend:request` / `friend:accepted` / `friend:declined` / `friend:removed` applied from
      the payload (`{request}` / `{friend}` / `{userId}`) — request and accept are now instant.
- [x] `group:created` / `group:updated` applied from `{group}`; `group:deleted` drops the row;
      `group-member:*` re-reads just that one group (one GET instead of a full reload).
- [x] `status:new` applied from `{status}`; `status:view` refreshes only my own rows.
- [ ] `call:*` still triggers a history reload (cheap, 1 GET) — fold into the call-signalling
      work below.
- [ ] Presence: emit `presence` events so online dots change live (server work).

### P2 — feel / smoothness
- [ ] Chats list: keep scroll position and animate row moves on new messages (WhatsApp
      slides the row to the top rather than snapping).
- [ ] Message list: stable keys already exist; add `animateItemPlacement` for inserts.
- [ ] Image bubbles: paint from the disk cache instantly, then the network copy
      (`AsyncImage` already caches; add a placeholder sized to the message).
- [ ] Skeleton/placeholder rows instead of empty flashes on first launch.
- [ ] Haptics on send / receive, like WhatsApp.

### P3 — missing features
- [ ] **Incoming calls never ring** — no `call:incoming` handler in the UI; `answerCall`,
      `signalCall`, `turnCredentials` and the group-call routes are unimplemented in
      `NChatApi`. This is the largest remaining functional gap.
- [ ] In-chat search, message info sheet, chat wallpaper/themes.
- [ ] Message backup/export.
- [ ] Notification channel wiring for background messages (needs FCM; the socket only lives
      while the process does).

### P4 — housekeeping
- [ ] Split `RemoteChatRepository` (~1500 lines) into transport / mapping / state.
- [ ] Unit tests for the event reducer (`handleEvent`) — it is the heart of "instant".
- [ ] `ApiLog` size/latency summary in Sync check (p50/p95 per endpoint).
