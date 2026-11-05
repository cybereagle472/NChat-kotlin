# Backend wiring — every worker route, and what in the app answers it

This is the contract between the Cloudflare worker (`cybereagle472/nchat-friends`,
`worker/src/routes/*.ts`) and the Android app. It exists so that "is this feature real?"
can be answered by reading one page instead of guessing from a screen.

Rules this document follows:

- A route counts as **wired** only when something in the app can *reach* it: a repository
  call made from a real user action, or a socket event handled into state the UI renders.
  An `NChatApi` method with no caller is not wiring.
- A screen counts as **real** only when what it shows comes from the server. An animation
  that simulates a server outcome is a lie and is listed as such.
- Anything that is *not* wired says why, and what it would take to wire it.

Backend: `https://nchat-friends.usa66486.workers.dev` (GitHub Actions variable
`NCHAT_API_BASE`, fixed at build time — no in-app switcher).
Transport: HTTPS + one WebSocket per user (`/realtime/connect?room=user:<id>&token=…`,
`worker/src/index.ts:46`). The socket establishes identity once, at handshake; every room
broadcast is `{ type, ... }` JSON and is the only way live changes arrive.

Regenerate the "dead method" list at any time:

```bash
cd app/src/main/java/com/nchat/app
python3 - <<'PY'
import re, glob
api = open('network/NChatApi.kt').read()
names = re.findall(r'suspend fun (\w+)\(', api)
code = '\n'.join(
    line for f in glob.glob('**/*.kt', recursive=True) if f != 'network/NChatApi.kt'
    for line in open(f) if not line.strip().startswith(('//', '*', '/*'))
)
print([n for n in names if not re.search(r'\b' + n + r'\b', code)])
PY
```

---

## 1. Auth — `routes/auth.ts`

| Route | App caller | Surface | State |
|---|---|---|---|
| `POST /auth/signup` | `NChatApi.signup` → `RemoteAuthRepository` | Auth screen | ✅ |
| `POST /auth/login` | `NChatApi.login` → `RemoteAuthRepository` | Auth screen | ✅ |
| `GET /auth/check-email` | `NChatApi.checkEmail` | Auth screen (live validation) | ✅ |
| `GET /auth/me` | `NChatApi.me` | startup session restore | ✅ |
| `POST /auth/refresh` | `ApiClient` (:164) | transparent 401 retry on every call **and** before a socket handshake | ✅ |
| `POST /auth/logout` | `NChatApi.logout` | Settings → Log out | ✅ |

## 2. Users — `routes/users.ts`

| Route | App caller | Surface | State |
|---|---|---|---|
| `GET /users/me` | `NChatApi.me` | profile, avatar, handle | ✅ |
| `PATCH /users/me` | `NChatApi.updateMe` | Account pane (name, username, bio) | ✅ |
| `GET /users/username-available` | `NChatApi.usernameAvailable` | Account pane | ✅ |
| `GET /users/search` | `NChatApi.searchUsers` | Find people | ✅ |
| `GET /users/:id` | `NChatApi.user` | profile view, group member sheet | ✅ |
| `GET /users` | `NChatApi.allUsers` | diagnostics (`SyncCheck`, "N people") | ✅ |
| `POST /users/presence` | `NChatApi.heartbeat` (ServiceLocator, app start) | online dots, "last seen" | ✅ |

## 3. Friends — `routes/friends.ts`

| Route | App caller | Surface | State |
|---|---|---|---|
| `GET /friends` | `RemoteFriendRepository` | Friends tab, Discover buttons | ✅ |
| `POST /friends/request` | `sendRequest` | Discover → Add | ✅ |
| `GET /friends/requests` | `loadRequests` | Requests list | ✅ |
| `GET /friends/sent` | `loadSent` | outgoing requests | ✅ |
| `POST /friends/:id/respond` | `respond` | accept / decline | ✅ |
| `DELETE /friends/request/:id` | `cancelRequest` | cancel outgoing | ✅ |
| `DELETE /friends/:friendId` | `removeFriend` | Remove friend | ✅ |
| `POST /friends/block` | — | — | 🚫 deliberate, see §10.1 |
| `POST /friends/unblock` | — | — | 🚫 deliberate, see §10.1 |
| `GET /friends/blocked` | `loadBlocked` (legacy rows only) | Unblock list | ⚠️ see §10.1 |

## 4. Messages — `routes/messages.ts`

| Route | App caller | Surface | State |
|---|---|---|---|
| `GET /messages/chats` | `RemoteChatRepository.loadChats` | Chats tab | ✅ |
| `GET /messages/with/:otherId` | `loadConversation` | open chat | ✅ |
| `POST /messages` | `sendMessage` / outbox `deliver` | composer | ✅ |
| `PATCH /messages/:id` | `editMessage` | edit bubble | ✅ |
| `DELETE /messages/:id` | `deleteForEveryone` | delete-for-everyone | ✅ |
| `POST /messages/:id/delete-for-me` | `deleteForMe` | delete-for-me | ✅ |
| `POST /messages/:id/reaction` | `react` | long-press reactions | ✅ |
| `POST /messages/mark-read` | `markRead` | read ticks | ✅ |
| `POST /messages/typing` | `setTyping` | "typing…" | ✅ (sent); see §11 |
| `POST /messages/recording` | `setRecording` | "recording audio…" | ✅ **since 1.0.40** |

## 5. Groups — `routes/groups.ts`

| Route | App caller | Surface | State |
|---|---|---|---|
| `GET /groups` | `RemoteChatRepository.loadGroups` | Groups tab | ✅ |
| `GET /groups/:id` | `loadGroup` (single source of the roster) | group info, members | ✅ |
| `POST /groups` | `createGroup` | Create group | ✅ |
| `PATCH /groups/:id` | `renameGroup` / `updateGroup` | rename, description | ✅ |
| `DELETE /groups/:id` | `deleteGroup` | delete group | ✅ |
| `POST /groups/:id/members` | `addGroupMembers` | add members | ✅ |
| `DELETE /groups/:id/members/:userId` | `removeGroupMember` | remove member | ✅ |
| `PATCH /groups/:id/members/:userId` | `setMemberRole` | promote/demote | ✅ |
| `PATCH /groups/:id/mute` | `muteGroup` | mute toggle | ✅ |
| `POST /groups/:id/leave` | `leaveGroup` | Leave group | ✅ |
| `GET /groups/:id/messages` | `groupMessages` (paging) | open group chat | ✅ |
| `POST /groups/:id/messages` | `sendGroupMessage` | group composer | ✅ |
| `POST /groups/messages/:id/delete-for-everyone` | `deleteGroupForEveryone` | delete bubble | ✅ |
| `POST /groups/messages/:id/delete-for-me` | `deleteGroupForMe` | delete for me | ✅ |
| `POST /groups/messages/:id/reaction` | `reactGroup` | reactions | ✅ |
| `POST /groups/:id/read` | `markGroupRead` | unread counters | ✅ |
| `POST /groups/:id/typing` | `sendGroupTyping` | "Nasir is typing…" | ✅ **fixed in 1.0.40** (was guessed — see §11) |
| `POST /groups/:id/recording` | `setRecording` | "recording audio…" | ✅ **since 1.0.40** |
| `GET /groups/:id/members` | — | — | 🚫 no app caller by design: `GET /groups/:id` already returns `members` (`groups.ts:195` → `groupWithMembers`), and keeping one source avoids the roster drifting between two fetches. The duplicate `groupMembers` wrapper was removed in 1.0.40. |

## 6. Status (stories) — `routes/status.ts`

| Route | App caller | Surface | State |
|---|---|---|---|
| `POST /status` | `StatusRepository.create` | status composer (text and photo) | ✅ |
| `GET /status/feed` | `loadFeed` | Status tab | ✅ |
| `GET /status/mine` | `loadMine` | My status ring | ✅ |
| `POST /status/:id/view` | `markViewed` | **viewer counts** | ✅ **since 1.0.40** (existed, never called) |
| `GET /status/:id/views` | `viewers` | "who watched" list | ✅ |
| `DELETE /status/:id` | `delete` | delete my status | ✅ |
| `POST /status/mute` | `mute` | mute a person's status | ✅ |
| `DELETE /status/mute/:id` | `unmute` | unmute | ✅ |
| `GET /status/muted` | `loadMuted` | muted list | ✅ |

## 7. Privacy — `routes/privacy.ts`

| Route | App caller | Surface | State |
|---|---|---|---|
| `GET /privacy` | `PreferencesRepository` | Privacy pane, initial values | ✅ |
| `PATCH /privacy` | `updatePrivacy` | every privacy switch | ✅ |

These settings are enforced **server-side** (profile masking, read-receipt suppression,
caller identity masking in `call:incoming`). The app must never decide them locally.

## 8. Media — `routes/media.ts`

| Route | App caller | Surface | State |
|---|---|---|---|
| `POST /media/upload` | `ApiClient.upload` | photos, video, voice notes, avatars | ✅ |
| `GET /media/file/:key` | `MediaImages` (signed request per gallery image) + `VideoView` | rendering images and video | ✅ |
| `DELETE /media/file/:key` | — | — | 🚫 **no product surface, in either client.** `deleteMedia` exists in the reference service (`src/services/mediaService.ts:189`) and has zero callers in `src/`; it is an ops helper, not a feature. |

## 9. Calls — `routes/calls.ts`

| Route | App caller | Surface | State |
|---|---|---|---|
| `POST /calls` | `CallRepository.dial` → `placeCall` | call button, call-back from History | ✅ **since 1.0.40** |
| `POST /calls/:id/answer` | `CallRepository.answer` | Answer button | ✅ **since 1.0.40** |
| `POST /calls/:id/end` | `decline` / `hangUp` | Decline, End, Cancel | ✅ **since 1.0.40** |
| `GET /calls/history` | `refresh` | History tab | ✅ |
| `POST /calls/:id/signal` | `CallRepository` (ICE) → `signalCall` | `call:signal` both ways | ✅ **since 1.0.41** |
| `GET /calls/turn-credentials` | `CallRepository.iceServers()` | Cloudflare TURN, cached 100 min | ✅ **since 1.0.41** |

Realtime: `call:incoming` → ring; `call:answered` → connected; `call:ended` → outcome;
`call:signal` → ICE/offer passthrough (`calls.ts:117`, `:137`, `:153`, `:124`).

Before 1.0.40 only `POST /calls` and `GET /calls/history` were used, and the call screen
was an animation: `LaunchedEffect { delay(1_600); connected = true }`. The other phone never
rang, answering was impossible, and the "connected" timer meant nothing.

## 10. Deliberately not wired

### 10.1 Blocking stays on the device

`POST /friends/block` does two things (`worker/src/routes/friends.ts:219`): it inserts into
`blocked_users` **and deletes the friendship row**, then broadcasts `friend:removed` — the
other person loses the contact too. Blocking must be non-destructive (the blocked
conversation stays, and unblocking resumes exactly where it was), which that endpoint
cannot express.

So the app keeps the block itself (`data/local/BlockedContactsStore.kt`): blocked ids are
hidden from Friends/Discover, their incoming messages are dropped in
`RemoteChatRepository.handleEvent` (:1106), the chat shows an Unblock banner instead of the
composer, and nothing is deleted. The cost is that the *server* is not told, so a blocked
person's messages are still stored and their calls still ring through to the worker.

To close this properly the worker needs a block that is separate from friendship deletion
(e.g. `POST /friends/block` with `keep_friendship: true`, or a dedicated
`/users/:id/block`). That is a change in `nchat-friends`, not in this repo.

### 10.2 Group calls

`routes/groupCalls.ts` (`POST /group-calls`, `/:id/join|signal|decline|leave`) is not wired.
The reference web client does not implement group calls either — there is no working
behaviour to port, and the user's own statement of the reference is "fully working except
group calls". The group-chat call button therefore says so instead of opening the old
`GroupCallOverlay`, which animated a roster that had never been told about the call. That
overlay has been deleted in 1.0.40 (git history keeps it). Wiring this needs the same media
layer as §10.3, timeboxed as its own release.

### 10.3 Call media — now implemented

1.0.41 adds the media layer (`data/calls/WebRtcEngine.kt`), ported from the reference's
`CallModal.tsx` + `callService.ts`:

- the caller builds the peer connection, creates an offer and posts it *with* the call
  (`POST /calls { offer_sdp }`), so the callee receives it inside `call:incoming`;
- the callee applies that offer, creates the answer and posts it (`/calls/:id/answer`) — the
  caller receives it as `call:answered { answer_sdp }`;
- ICE candidates trickle through `POST /calls/:id/signal { candidate }` and arrive as
  `call:signal { callId, from, candidate }`. Candidates that arrive before this side has a
  peer connection (the callee is still ringing) are queued and flushed, never dropped;
- `GET /calls/turn-credentials` supplies Cloudflare TURN servers, cached for 100 minutes like
  the reference (`callService.ts:45`), with public STUN as the direct-path fallback.

The state machine is explicit: `Ringing → Connecting → Live → Ended`. "Connecting…" is SDP
exchanged with ICE still working; the timer only starts when ICE/DTLS actually connects. The
reference's own guard rails are ported: a 45 s ring timeout (recorded as missed), a 20 s
connecting timeout and a 6 s disconnect grace (`CallModal.tsx:18`, `:26`, `:33`) — a call that
cannot connect says so and closes instead of sitting on "Connecting…" forever.

Media permissions (`RECORD_AUDIO`, `CAMERA`) are requested *before* dialling and *before*
answering: the peer connection opens the microphone while it builds the offer, so asking later
just produces a silent call.

Known limit, stated plainly: calls run while the app is in the foreground. A background
`microphone` foreground service (to keep the line alive with the screen off) is not part of
this release.

### 10.4 `GET /admin/stats`, `GET /admin/users`

Worker-operator endpoints (counts, user list). No in-app surface, and none in the reference
frontend. Not a product feature; the app never calls them.

## 11. Fixed in 1.0.40

| Problem | Why it was invisible | Fix |
|---|---|---|
| Calls were an animation | `CallOverlay` flipped to "connected" after a hard-coded 1.6 s; nothing listened for `call:incoming` | full signalling state machine in `CallRepository` + app-level overlay in `HomeRoot` |
| The callee's phone never rang | no `call:incoming` handler anywhere | ring with Answer/Decline, declined-when-busy, outcome written back |
| Status views never counted | `StatusRepository.markViewed` had no caller — the viewer only updated local state | `POST /status/:id/view` on open |
| A peer never saw "recording audio…" | `setRecording` had no caller; only the *incoming* event was rendered | sent on recorder start/stop/pause, and cleared on dispose |
| Group "is typing" named the wrong person | the worker sends `sender_id` (`groups.ts:575`) but only `is_typing` was stored, so the UI credited whoever spoke last | `Chat.typingUserId` carries the real id, resolved against the roster |
| A "connected" call was silent | no media layer at all: no peer connection, no tracks, no ICE | WebRTC engine, offer/answer via the worker, TURN credentials, real video renders |
| A call could sit on "Connecting…" forever | nothing watched ICE state | `Connecting → Live` only on ICE/DTLS connect, with 20 s / 6 s timeouts ported from the reference |
| Microphone/camera asked for too late | no permission gate | `rememberCallPermissionGate` before dialling and before answering |
| Group-chat calls opened a fake screen | `GroupCallOverlay` animated a roster that was never signalled | says group calls are not available; overlay deleted |

## 12. Realtime event coverage

Every `type` the worker broadcasts, and where it lands:

| Event | Handled by | Effect |
|---|---|---|
| `message:new` | `RemoteChatRepository` | bubble, list preview, unread, ordering, own-echo reconciliation |
| `message:updated` / `message:deleted` | `RemoteChatRepository` | edits, deletes |
| `message:reaction` | `RemoteChatRepository` | reaction rows |
| `typing` / `recording` | `RemoteChatRepository` | "typing…" / mic indicator, with expiry |
| `group-typing` / `group-recording` | `RemoteChatRepository` | same, attributed to `sender_id` |
| `group-message:*`, `group:created`, `group:updated`, `group:deleted`, `group-member:joined` | `RemoteChatRepository` | group list, roster, chat state |
| `friend:request`, `friend:accepted`, `friend:declined`, `friend:removed` | `RemoteFriendRepository` | graph, requests, buttons |
| `status:new`, `status:view` | `StatusRepository` | feed, viewer counts |
| `call:incoming`, `call:answered`, `call:ended` | `CallRepository` | ringing / connected / outcome |
| `call:signal` | `CallRepository` (no-op) | reserved for the media layer |

No event is ignored silently: every `type` in that list has a handler branch, and the
branches that intentionally do nothing say so in code.

## 13. Verification checklist

1. `python3` scan in the header → the only uncalled methods are `blockUser` and the five
   group-call ones — each explained above. `signalCall` and `turnCredentials` are used from
   1.0.41 on.
2. Two devices, same account? No: two accounts, both online.
   - Send a message: appears on the other device instantly, ticks update both ways.
   - Type: the other side shows "typing…" and clears after ~6 s.
   - Hold the mic: the other side shows "recording audio…".
   - Post a status, open it on the other device: the author's viewer count increments.
   - Call: the other phone rings with Answer/Decline; declining shows "Declined" on the
     caller; answering shows "Connecting…" and then a running timer, and **you can hear each
     other**; a video call shows the other camera full-screen with your own in the corner;
     mute silences your side, speaker moves the audio to the loudspeaker, flip switches
     cameras; ending writes a History row on both.
   - Call on mobile data with the other phone on Wi-Fi: ICE should relay through the worker's
     TURN credentials rather than failing.
3. Airplane mode on one device, send, re-enable: the message flushes from the outbox once.
