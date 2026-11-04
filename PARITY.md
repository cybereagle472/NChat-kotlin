# PARITY.md — how much of the reference frontend actually lives in this app

Audit date: 2026-11-04 · Android side: `arena/24345161-nchat-kotlin` @ 1.0.14 ·
Reference: `cybereagle472/nchat-friends` v2.0.0 (`src/` 26,570 lines, `worker/` 2,911 lines)

Legend: **✅ 1:1** · **🟡 partial / simulated** · **❌ missing**

---

## 0. The short answer

**No — the UI is fully cloned, the product is not.**

- **Visual layer: complete.** Every screen, sheet, overlay, gradient, glass surface, motion
  curve and micro-interaction in the reference has a Compose counterpart. 19/19 screens
  exist, plus Android-only extras (bottom tab bar, update checker, image cropper).
- **Behaviour layer: partial.** Interactions are wired to local state, so most of the app
  *feels* right until you try a second device, close the app, or use the microphone.
- **Platform layer: absent.** No backend, no realtime, no WebRTC, no persistence, no
  notifications, no permissions, no media pipeline. The Kotlin code is 12,929 lines against
  the reference's ~29,500 (UI + services + Worker), and the 12,929 include none of the
  ~7,400 lines of services/Worker logic.

Feature points reviewed below: **120** → **67 ✅, 14 🟡, 39 ❌**. Progress: 1.0.15 closed the
conversation-surface gaps (edit, media viewer, resume marker, jump-to-latest, empty state) and
1.0.16 added the Privacy, Notifications and Blocked-users panes with persisted state. What is
left is concentrated in platform-bound features (real calls, media pipeline, notifications,
push) plus the status composer and group member administration.

| Area | Reference | Android | Verdict |
|---|---|---|---|
| Screens / navigation | 19 components + App shell | 46 Kotlin files, 19 screens | ✅ |
| Design system (palette, glass, motion) | `globals.css` 1,473 | `NcTheme` / `NcComponents` | ✅ |
| Chat list | `Chats.tsx` 688 | `ChatsScreen.kt` | 🟡 (no chat management) |
| 1:1 conversation | `ChatView.tsx` 3,033 | `ConversationScreen.kt` | 🟡 (~10 features short) |
| Group chat | `GroupChatView.tsx` 1,596 | same file, group branch | 🟡 (~5 short) |
| Message actions / reactions | 724 + 401 | `MessageActionsMenu.kt` | 🟡 (no edit) |
| Voice notes | real MediaRecorder + seek | gesture + simulated timer | 🟡 |
| 1:1 calls | `CallModal.tsx` 1,044 (WebRTC) | `CallOverlay.kt` (~380) | 🟡 (UI shell only) |
| Group calls | `GroupCallModal.tsx` 927 (WebRTC) | `GroupCallOverlay.kt` | 🟡 (no join/decline, no media) |
| Status | `Status.tsx` 1,018 | `StatusScreen.kt` + `StatusViewer.kt` | 🟡 (no creator, no viewers) |
| Discover / friends | `Discover.tsx` 725 | `DiscoverScreen.kt` | ✅ |
| Groups | `Groups` 356 · `CreateGroup` 374 · `GroupInfo` 616 | 3 Kotlin files | 🟡 (no member admin) |
| Profile & settings | `Settings.tsx` 1,716 · `ProfileView` 479 | 3 Kotlin files | 🟡 (1 of 5 panes) |
| Auth + onboarding | `Auth.tsx` 853 · `ProfileSetup` 353 | 2 Kotlin files | 🟡 (local only) |
| Data layer | 10 services + hooks, 4,000+ lines | 562 lines of in-memory repos | ❌ |
| Realtime | Durable Object WebSocket | none | ❌ |
| Media pipeline | R2 upload, compress, thumbnails | none | ❌ |
| Persistence / offline | IndexedDB cache + sync | none (only auth in SharedPreferences) | ❌ |
| Notifications / push | service worker + web-push | none | ❌ |
| Admin / system info | `routes/admin.ts` + Admin pane | none | ❌ |

---

## 1. Method

1. Cloned the reference at `v2.0.0` and enumerated every component, hook, service and Worker
   route (`src/components` 21 files, `src/services` 12, `src/hooks` 5, `src/utils` 10,
   `worker/src` 11 routes + 5 migrations).
2. Extracted the interactive surface of each reference file (state, handlers, literal UI
   strings, portal dialogs, gesture code).
3. Matched each against the Kotlin tree (`app/src/main/java/com/nchat/app`), file by file,
   searching for the equivalent composable, state, or repository call.
4. Marked **🟡** where the visual exists but is driven by mock/simulated state, **❌** where
   there is no counterpart at all.

---

## 2. Chat list — `Chats.tsx`

| Feature | Status | Note |
|---|---|---|
| Gradient header "Chats", search field, settings + create actions | ✅ | `NcTabHeader` + two header actions |
| Live search filtering with empty state | ✅ | `ChatsViewModel.onQueryChange` |
| Row: 56dp avatar, presence dot, title, snippet, time, unread badge | ✅ | matches reference row anatomy |
| Message-kind icon in snippet (photo/video/voice/doc/location) | ✅ | `KindIcon` |
| Delivery tick (sent / read) on own last message | ✅ | `DeliveryTick` |
| "typing" / "recording audio" preview line with dots | ✅ | `TypingLine`, green mic variant |
| Empty states (no chats / no results) | ✅ | `NcGlassEmptyState` |
| Long-press chat menu (pin, mute, archive, mark read, delete chat) | 🟡 | 1.0.17 — pin, mute, mark read, delete-with-confirm; archive still absent |
| Pinned / archived sections | 🟡 | 1.0.17 — pinned chats sort to the top with a pin marker; no archived section |
| Prefetch of message history for the first N chats | ❌ | reference `chatService.prefetchChatsMessages` |

## 3. 1:1 conversation — `ChatView.tsx` (3,033 lines)

| Feature | Status | Note |
|---|---|---|
| Gradient header, avatar, title, presence line, back/profile tap | ✅ | |
| Video / voice / more actions in header | 🟡 | buttons present, more-menu empty |
| Bubbles: text, photo, video, voice, document, location | ✅ | mock media |
| Tail shapes, own vs incoming, max-width 75 % | ✅ | `BubbleShapeMine/Theirs` |
| Timestamp, `edited`, read ticks | 🟡 | `edited` renders but nothing can edit |
| Reply quote inside bubble + reply strip above composer | ✅ | |
| "Forwarded" label | ✅ | |
| Day separators (Today / Yesterday / date) | ✅ | |
| Reactions: quick 6 + full picker + count chips | ✅ | |
| Long-press menu: reply, copy, forward, delete (me/everyone) | ✅ | |
| **Edit message** (own text, 15-minute window) | ✅ | 1.0.15 — sheet + `edited` tag + repo `editMessage` |
| **Save / bookmark** message | ❌ | reference `Save` action |
| **Full-screen media viewer** (tap photo/video, swipe, close) | 🟡 | 1.0.15 — opens, closes, shows author/type; no bitmap yet (mock media) |
| **Download / Share media** (FileProvider, share sheet) | ❌ | |
| **Load earlier messages / pagination** | ❌ | reference loads 50 at a time, group has a button |
| **Unread "resume here" divider + jump to first unread** | ✅ | 1.0.15 — marker captured before `markRead`, viewport parks there |
| **Scroll-to-bottom affordance** | ✅ | 1.0.15 — jump-to-latest pill + smart auto-scroll |
| **Block user from the chat menu** | ❌ | only reachable from `ProfileView` |
| **Empty conversation state ("Start the conversation")** | ✅ | 1.0.15 |
| **Marquee presence line** for long "last seen …" text | ❌ | reference slides it |
| Typing indicator (contact typing dots) | ✅ | |
| Composer: attach popup (Photos / Videos / Files), mic↔send morph | ✅ | popup sends a mock message instead of opening a picker |
| Voice record: hold, slide-to-cancel, lock, waveform, timer | 🟡 | gesture + UI exact; audio is simulated (random bars, no capture) |
| Voice playback with waveform progress | 🟡 | timer-driven fake progress, no audio |
| Keyboard insets / resize | ✅ | Compose `imePadding` + `adjustResize` |

## 4. Group chat — `GroupChatView.tsx`

| Feature | Status | Note |
|---|---|---|
| Group header (avatar, name, member count, tap → group info) | ✅ | |
| Sender name + 30dp avatar per sender-run | ✅ | added in 1.0.13 |
| Named typing / recording line ("Ayesha typing…" + dots) | ✅ | added in 1.0.13 |
| Group call entry from header | ✅ | `GroupCallOverlay` |
| Reply, forward, delete (with role rules), reactions | 🟡 | delete ignores admin role; no "deleted by admin" attribution |
| Attach + voice | 🟡 | same simulation as 1:1 |
| **Load earlier messages** ("Load earlier messages") | ❌ | |
| **Admin delete / delete-for-everyone window (1 h)** | 🟡 | window enforced visually, no role check |
| **Mentions / @-autocomplete** | n/a | reference does not implement them |

## 5. Message actions — `MessageActions.tsx`, `GroupMessageActions.tsx`

| Feature | Status | Note |
|---|---|---|
| Bottom sheet with quick reactions + "more" | ✅ | `MessageActionsMenu.kt` |
| Full reaction picker (all emoji, 8 per row) | ✅ | |
| Reply / Copy / Forward / Delete | ✅ | |
| Delete confirmation dialog (Everyone / Me / Cancel) | ✅ | |
| Forward picker with search + multi-select | 🟡 | no Contacts/Groups tabs (reference has both) |
| **Edit** | ❌ | |
| **Message info** (who read it) | ❌ | reference only for groups |

## 6. Calls — `CallModal.tsx`, `GroupCallModal.tsx`

| Feature | Status | Note |
|---|---|---|
| Full-screen gradient call UI, avatar pulse, timer | ✅ | `CallOverlay.kt` |
| Mute / speaker / end controls | ✅ | |
| **Incoming call state + "Slide to answer" / Decline** | ❌ | calls always start "calling…" |
| **Video surface (camera preview), flip camera, camera on/off** | ❌ | video is only a text label |
| **Minimise to a floating PiP card** | ❌ | |
| **Reconnect / call-ended / call-failed states** | ❌ | |
| **Write the call into History on end** | ❌ | history entries are static mocks |
| Group: "Calling everyone…" screen, 2-column tiles, mute/camera/hang-up | ✅ | `GroupCallOverlay.kt` |
| Group: **Join / Decline invite flow** | ❌ | reference `GroupCallModal` has Join and Decline |
| Group: participant count, per-tile mute badges | ✅ | |
| **Real audio/video (WebRTC + signalling)** | ❌ | `webrtcMedia.ts` / `callService.ts` / `groupCallService.ts` have no counterpart |
| Mic / camera permissions | ❌ | manifest has only INTERNET, ACCESS_NETWORK_STATE, REQUEST_INSTALL_PACKAGES |

## 7. Status — `Status.tsx`

| Feature | Status | Note |
|---|---|---|
| "My status" row with + badge, "Tap to add status update" | 🟡 | opens the viewer of an existing mock story, does not create |
| Recent updates list with seen/unseen gradient ring | ✅ | |
| Full-screen viewer, segmented progress, tap/hold, swipe down | ✅ | `StatusViewer.kt` |
| **Status composer**: text status with background + font pickers | ❌ | `STATUS_BACKGROUNDS`, `STATUS_FONTS` in `statusService.ts` |
| **Media status** (photo/video + caption + upload progress) | ❌ | |
| **Viewers panel** ("No views yet", per-viewer list, view counts) | ❌ | |
| **Delete my status / reply to a status** | ❌ | |
| Mute video in viewer, auto-pause while replying | ❌ | |
| Status privacy (who can see) | ❌ | `privacyService.ts` |

## 8. Discover / friends — `Discover.tsx`

| Feature | Status | Note |
|---|---|---|
| Header, 3 tabs (Friends / Requests / Find), counters and badges | ✅ | |
| Search field, "All Users" chip, empty states | ✅ | |
| Friend cards: avatar, name, handle, presence | ✅ | |
| Message / voice / video shortcuts per friend | ✅ | |
| Requests: accept / decline with busy state | ✅ | |
| Sent requests: cancel | ✅ | |
| Unfriend with confirmation dialog | ✅ | |
| Send friend request from Find | ✅ | |
| Server-side user search with debounce + loading | 🟡 | local filter over mock users (`isSearchingUsers` equivalent absent) |
| `Friends.tsx` standalone screen (129 lines) | n/a | not referenced by the reference app itself — dead component |

## 9. Groups — `Groups.tsx`, `CreateGroup.tsx`, `GroupInfo.tsx`

| Feature | Status | Note |
|---|---|---|
| Group list with member count, last activity, unread badge | ✅ | |
| Create group: name, description, member picker, validation | ✅ | |
| Group info: photo change, rename, mute notifications, leave | ✅ | |
| Add members sheet | ✅ | adds mock members |
| **Remove member / promote to admin / admin badges** | ❌ | reference `groupService.removeMember/promoteMember` |
| **"Make someone else an admin before you leave" guard** | ❌ | |
| **Invite link / join by code** | ❌ | |
| **Group media / docs / links tabs** | ❌ | reference keeps them inside group info |

## 10. Profile & settings — `Settings.tsx`, `ProfileView.tsx`

| Feature | Status | Note |
|---|---|---|
| Gradient header + profile card (avatar, name, about) | ✅ | |
| Profile pane: name, about, avatar, cropper | ✅ | |
| Menu sections with pastel icon tiles | ✅ | |
| **Chats / Appearance / Language / Help / About rows** | ✅ | *also inert in the reference* — 1:1, dead ends in both |
| **Privacy pane** (last seen, profile photo, read receipts, online status) | ✅ | 1.0.16 — persisted in SharedPreferences |
| **Notifications pane** (messages, calls, preview, sound, vibrate, ringtone) | 🟡 | 1.0.16 — five toggles persisted; ringtone row still missing |
| **Blocked users pane** (list, unblock, "No blocked users") | ✅ | 1.0.16 — shared block state between profile view and settings |
| **Admin pane** (System info: version, build, users, groups, messages, online) | ❌ | |
| Update check + install | ✅ | Android-only extra |
| Log out (clears session) | ✅ | |
| ProfileView: block/unblock, message, voice, video | ✅ | |
| ProfileView: **mute notifications, report, shared media, "profile unavailable"** | ❌ | |

## 11. Auth & onboarding — `Auth.tsx`, `ProfileSetup.tsx`

| Feature | Status | Note |
|---|---|---|
| Auth screen: gradient, waves, floating circles, card, logo | ✅ | |
| Email / password / confirm, show-hide, validation order | ✅ | |
| Email availability debounce (500 ms), taken state | ✅ | local list only |
| Error / success banners, mode switch | ✅ | |
| Profile setup: 6 gradient avatars, name, username check, bio | ✅ | |
| Session persistence across launches | ✅ | `SharedPreferences("nchat_auth")` |
| **Real sign-up / sign-in (JWT, `authService.ts` → Worker)** | ❌ | any valid-looking credentials succeed |
| **Password reset / email verification** | ❌ | |
| **Admin login detection** | ❌ | reference gates the Admin pane on a hard-coded email |

## 12. App shell & global behaviour — `App.tsx`

| Feature | Status | Note |
|---|---|---|
| View model: tabs + stack, back handling | ✅ | `NcNavigator` (reference uses a flat `AppView`) |
| Bottom navigation (reference uses a top-level view switch) | ✅ | Android-specific improvement |
| Launch stages Auth → ProfileSetup → Home | ✅ | |
| Toasts / snackbars for events and errors (`sonner`) | 🟡 | 1.0.16 — `NcToaster` host wired for copy/edit/delete/forward/block |
| **Offline / online banner, network retry states** | ❌ | |
| **Push notifications + permission** (service worker, `web-push`) | ❌ | |
| **Deep link from a notification into a chat** | ❌ | |
| Presence heartbeat + `presenceFormat` ("online", "last seen 5m ago") | 🟡 | hard-coded `isOnline` flags, formatter exists |
| Keyboard-aware layout (`useKeyboardInset`, `useVisualViewportHeight`) | ✅ | handled by Compose insets |
| PWA install prompt / update banner | n/a | replaced by the in-app updater |
| Landscape / tablet layouts | ❌ | phone portrait only |
| Error boundary / crash reporting | ❌ | `ErrorBoundary.tsx`, `errorTracking.ts` |

---

## 13. Data & platform layers (all ❌)

| Reference module | Lines | Android counterpart |
|---|---|---|
| `lib/apiClient.ts` (fetch wrapper, auth header, retry) | 352 | none |
| `lib/realtimeClient.ts` (WebSocket, backoff, resubscribe) | 274 | none |
| `lib/webrtcMedia.ts` | 308 | none |
| `services/chatService.ts` (messages, pagination, receipts, reactions, delete, search) | 1,012 | `ChatRepository` 340 (in-memory) |
| `services/groupService.ts` | 501 | `ChatRepository` group functions |
| `services/statusService.ts` (create/upload/view tracking) | 403 | `MockDataProvider.statuses()` |
| `services/callService.ts` / `groupCallService.ts` (signalling) | 677 | none |
| `services/mediaService.ts` (R2 upload, thumbnails) | 274 | none |
| `services/typingService.ts` (typing/recording pub-sub) | 373 | local `isTyping` flags |
| `services/privacyService.ts` | ~60 | none |
| `services/friendService.ts` / `profileService.ts` / `authService.ts` | 705 | `FriendRepository` 59, `ProfileRepository` 60, `AuthRepository` 103 |
| `utils/offlineStorage.ts` (IndexedDB cache + queue) | 593 | none |
| `utils/mediaUtils.ts` (compress, duration, thumbnails) | 308 | none |
| `utils/retry.ts`, `logger.ts`, `errorTracking.ts`, `validation.ts`, `search.ts` | 605 | partial inline validation only |
| `hooks/useAppData.ts` (aggregate store + realtime wiring) | 779 | `ServiceLocator` + repositories |
| Worker: `routes/*` (auth, users, friends, messages, groups, groupCalls, calls, status, media, privacy, admin) + D1 migrations + Durable Object | 2,911 | none |
| **Android permission model** (RECORD_AUDIO, CAMERA, POST_NOTIFICATIONS, media read, VIBRATE) | — | only INTERNET / NETWORK_STATE / INSTALL_PACKAGES |

**Consequence:** everything except the signed-in session resets on relaunch; there is one
device's worth of data; media never leaves memory; no notification can arrive.

---

## 14. What is needed — ordered plan

### Phase 1 — finish the UI surface (no backend, pure Compose) · ~10–14 dev-days
1. **Edit message** (own text, 15-min window) — `MessageActionsMenu`, `ConversationViewModel.editMessage`, `Message.edited` already renders.
2. **Full-screen media viewer** — dialog with zoom/pan for photos, player stub for video, close on swipe/tap.
3. **Unread "resume here" divider + scroll-to-bottom FAB** — `ConversationViewModel` tracks last-read id.
4. **Status composer** — text (6 backgrounds × 3 fonts), photo/video pick + caption, "My status" viewers panel, delete, reply.
5. **Settings panes**: Privacy (4 selectors/toggles), Notifications (5 toggles + ringtone row), Blocked users (list + unblock), About/System info. Same inert rows stay inert.
6. **Group member admin** — remove member, promote to admin, role badges, leave guard.
7. **Call UI completion** — incoming state with slide-to-answer, video surface placeholder, camera flip button, minimise card, ended state.
8. **Group call invite** — Join / Decline sheet before the overlay.
9. **Chat long-press menu** — pin, mute, mark read, delete (local only).
10. **Empty/loading/error states audit** + "Start the conversation".
11. **Toasts** — small `NcSnackbar` host for actions (message copied, member promoted, request sent).
12. **ProfileView extras** — mute, report, shared media.
13. **Forward dialog tabs** — Contacts / Groups.

### Phase 2 — real device capabilities · ~8–12 dev-days
14. Runtime permissions: `RECORD_AUDIO`, `CAMERA`, `POST_NOTIFICATIONS`, `READ_MEDIA_IMAGES/VIDEO`, `VIBRATE`.
15. **Real voice notes**: `MediaRecorder` (AAC in M4A) + amplitude sampling for the real waveform; `ExoPlayer`/`MediaPlayer` playback with drag-to-seek.
16. **Real media picker + preview**: `ActivityResultContracts.PickVisualMedia` / `OpenDocument` → decode, downscale, generate thumbnail, persist to `filesDir`.
17. **CameraX** preview for video calls; front/back flip.
18. **Local notifications**: message, call, status; notification channels; tap-through into the chat (deep link).
19. **Share / download / open-with** via `FileProvider` + `Intent.ACTION_SEND`.

### Phase 3 — persistence + real data · ~15–20 dev-days
20. **Room** schema mirroring the Worker D1 tables (users, friends, chats, messages, groups, members, statuses, calls, reactions, privacy) with Flow DAOs — the repositories already have the right shape.
21. **REST client** (Ktor or OkHttp + kotlinx-serialization) covering the 11 Worker route groups; JWT storage + refresh in `AuthRepository`.
22. **Realtime client** (OkHttp WebSocket + exponential backoff) for messages, receipts, presence, typing/recording, call signalling.
23. **Media upload** to R2 with progress, resumable retry (`WorkManager`), CDN URLs in messages.
24. **Sync engine**: local-first writes, outbox queue, prefetch of first 50 messages per chat, pagination.

### Phase 4 — calls, push, admin · ~15–25 dev-days
25. **WebRTC** (`stream-webrtc-android`): 1:1 audio/video with signalling over the WebSocket, ICE/STUN, mute, flip, PiP.
26. **Group calls**: SFU or mesh for ≤5 peers, join/decline, tiles, active speaker.
27. **Push (FCM)** + server keys, background message handler, call wake-up.
28. **Admin**: system info pane, developer controls, user totals.
29. **Offline-first polish**: offline banner, retry toasts, conflict handling.

**Total for full functional parity: ~48–71 dev-days.** Phase 1 alone takes the app from
"demo" to "feels complete on one device".

---

## 15. Android-only extras (not in the reference)

- Floating bottom tab bar with the glass chip (reference is a plain view switch).
- In-app updater: `version.json` manifest, APK download, `FileProvider` install, progress dialog.
- Image cropper with pan / pinch / rotate / aspect presets (reference crops in-browser).
- Nested navigation stack with back-press handling per tab.
- Static Canvas backdrop replacing the animated CSS blobs (performance rule, see `eagle-x.md` §9).

---

## 16. Accuracy notes / caveats

- Line counts are `wc -l` on source only (no assets, no `node_modules`).
- "1:1" means layout, copy, palette, motion and interaction match; it does **not** mean the
  data is real.
- Where the reference itself is inert (Chats / Appearance / Language / Help / About menu rows),
  the Android app mirrors that inertness deliberately — matching the reference is the rule.
- `Friends.tsx` (129 lines) exists in the reference but is not imported by it; the Android
  Discover "Friends" tab covers the same UI.
