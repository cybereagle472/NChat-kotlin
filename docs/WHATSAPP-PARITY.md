# NChat vs WhatsApp — the parity audit

Written after the 1.0.48 review, and kept current as the gaps close. Every row is a feature a
WhatsApp user will look for in the first ten minutes; the third column says where NChat stands
today, and the fourth where it is worked on (or why it cannot be a UI-only change).

Legend: **✅ parity (or better)** · **🟡 partial** · **❌ missing** · **⛔ needs backend work**

## Messaging, 1:1

| Feature | WhatsApp | NChat | Where |
| --- | --- | --- | --- |
| Text messages, ticks, edited marks | ✅ | ✅ single/double tick, `edited` | `MessageBubble` |
| Reply / quote | ✅ | ✅ `reply_to`, quote bar in composer, swipe to reply | `ConversationScreen`, `ConversationViewModel` |
| Reactions | ✅ | ✅ quick strip + full 32-emoji palette + double-tap ❤️ | `MessageActionsMenu` |
| Forward | ✅ | ✅ multi-target, copies the real file | `ForwardDialog` |
| Delete for me / for everyone | ✅ | ✅ tombstones; "everyone" window is 1 h in groups (server) | `RemoteChatRepository` |
| **In-chat search** | ✅ | ✅ 1.0.50 — magnifier in the header, hits highlighted, tap jumps + rings the bubble | `ChatSearchOverlay` |
| **Multi-select** | ✅ | ✅ 1.0.50 — Select from the action menu → count header, forward / copy / select all / delete | `SelectionHeader` |
| **Starred messages** | ✅ | ✅ 1.0.50 — star badge on the bubble, sheet under ⋮, text copied so it outlives the original | `StarredStore` |
| **Per-chat drafts** | ✅ | ✅ 1.0.50 — restored into the composer, red "Draft:" on the chat row | `DraftStore` |
| **Photo viewer paging + zoom** | ✅ | ✅ 1.0.50 — swipe a chat's photos, pinch zoom, double tap to fit | `MediaViewerDialog` |
| **Voice playback speed** | ✅ | ✅ 1.0.49 — 1× / 1.5× / 2× chip on notes ≥ 20 s | `VoiceContent` |
| Voice notes: waveform, scrub, slide-to-cancel, lock | ✅ | ✅ **better** — real waveform, drag head marker, slide-to-lock/cancel, offline queue, per-byte upload ring | `VoiceRecorder`, `VoiceContent` |
| Typing indicator | ✅ | ✅ 1:1 + per member in groups | `ConversationHeader` |
| Recording indicator | ✅ | ✅ mic row on the chat and in the header | `ChatsScreen` |
| Text formatting (bold/italic/strike/mono) | ✅ | ❌ planned 1.0.51 | — |
| Pin a message in the chat | ✅ | ❌ planned 1.0.51 | — |
| Per-message info ("read by") | ✅ | ❌ planned 1.0.51 | — |
| In-app camera capture | ✅ | ❌ planned 1.0.51 (only pickers today) | — |
| Send a location | ✅ | ✅ 1.0.53 — `Locator` (cached fix, else one fresh fix, 9 s budget), `type=location` + `lat,lng`, queued offline, tap opens Maps | `Locator` |
| View-once media | ✅ | ❌ later | — |
| Media index per chat ("Media, links and docs") | ✅ | ✅ 1.0.55 — grid / docs / links, tap opens the pager | `ChatMediaSheet` |
| Disappearing messages | ✅ | ⛔ server has no TTL column | — |

## Groups

| Feature | WhatsApp | NChat | Where |
| --- | --- | --- | --- |
| Create / rename / description / avatar | ✅ | ✅ | `GroupInfoScreen` |
| Add / remove members, promote / demote, leave, delete | ✅ | ✅ | `GroupInfoScreen` |
| Typing per member, sender avatars, member count | ✅ | ✅ | `ConversationHeader` |
| **Group voice call** | ✅ | ✅ 1.0.56/1.0.57 — mesh: one `PeerConnection` per participant, perfect-negotiation glare handling, roster overlay, incoming-call card (`group-call:incoming`) | `GroupCallSession`, `GroupCallInviteDialog` |
| Group **video** call | ✅ | ❌ next — one camera feeding several encoders is its own problem | — |
| Invite link / QR | ✅ | ❌ needs a backend endpoint (only "add existing friends") | — |
| @mentions / @all | ✅ | 🟡 1.0.54 + 1.0.64 — picker, `@Name` highlighting from the roster, and a notification that says "you were mentioned" (the wire stays plain text; `@all` needs a server role) | `ChatTextFormat`, `NcNotifications` |
| Reply privately / keep in chat | ✅ | 🟡 1.0.54 — forwards the message into the author's DM (a group screen cannot pre-fill another chat's composer) | `ConversationScreen` |
| Admin-restricted messaging + approval queue | ✅ | ❌ needs server roles beyond `admin` | — |

## Chat list and notifications

| Feature | WhatsApp | NChat | Where |
| --- | --- | --- | --- |
| Type-aware previews, pinned chats, mute, unread badge | ✅ | ✅ | `ChatsScreen` |
| Draft preview | ✅ | ✅ 1.0.50 | `ChatsScreen` |
| **System notifications** | ✅ | ✅ 1.0.51–1.0.62 — per-chat channels honouring mute, `MessagingStyle` with inline reply, group summary + launcher badge, sender photo, tap lands on *that message*, and alerts for friend requests, accepted requests, group adds, incoming and missed calls | `NcNotifications`, `ServiceLocator` |
| Archive | ✅ | ✅ 1.0.52 — swipe, archive sheet, swipe back out | `ChatsScreen` |
| Swipe actions on rows | ✅ | ✅ 1.0.52 — swipe to archive (left) / mark read (right), with the hint revealed behind the row | `SwipeableChatRow` |
| Unread / Groups filters | ✅ | ✅ 1.0.52, restyled 1.0.60 (own row under the header, gradient pill, hairline idle state) | `ChatFilterRow` |
| Background calls (foreground service, ringtone, lock screen) | ✅ | 🟡 1.0.63 — a call now rings from the shade with a **Decline** action and the call screen opens on tap, even with the app in the background; there is still no full-screen lock-screen takeover (that needs `USE_FULL_SCREEN_INTENT` plus an Activity that can answer without the app being resumed) | `NcNotifications.showIncomingCall`, `CallActionReceiver` |

## Platform and trust

| Feature | WhatsApp | NChat | Note |
| --- | --- | --- | --- |
| End-to-end encryption | ✅ | ❌ | The worker stores plaintext. This is a protocol project (key exchange, per-device sessions, group sender keys), not a screen — tracked separately. |
| Multi-device / linked devices / Web | ✅ | ❌ | Needs server sessions per device. |
| Backup & restore | ✅ | ❌ | Local caches only. |
| Media auto-download rules, storage manager | ✅ | 🟡 1.0.55 — storage manager with real per-directory sizes and confirms; auto-download rules still missing | `StorageReport` |

## Where the waves stand

- **1.0.49 — shipped.** Swipe to reply, double-tap ❤️, voice speed 1×/1.5×/2×.
- **1.0.50 — shipped.** In-chat search, multi-select, starred messages, per-chat drafts,
  photo viewer paging + zoom.
- **1.0.51 — shipped.** System notifications: per-chat channels honouring mute,
  `MessagingStyle` with inline reply, silent summary + launcher badge, tap opens that chat.
- **1.0.52 — shipped.** Text formatting, swipe-to-archive / mark-read, archive sheet,
  All/Unread filters, pinned message banner, message info.
- **1.0.58 — shipped.** Background delivery without Firebase: `BackgroundSyncService`
  holds the socket, per-chat pref, battery-exemption row.
- **1.0.59 — shipped.** Self-view black fixed, calls no longer end themselves, 30 s
  reconnect grace with a visible "Reconnecting…".
- **1.0.60 — shipped.** Notification photos, alerts for every event kind, chats-tab
  filter row restyled, conversation-header actions no longer pushed off screen, startup
  update sheet with scrollable notes, battery permission asked on first launch.
- **1.0.61 — shipped.** Chat rows on one grid (name + clock, then preview + pin/mute/badge),
  group sender names drawn separately, unread styling.
- **1.0.62 — shipped.** Notification taps land on the message (scroll + ring) and on the
  call (`openFromNotification`); unread badges no longer come back after a back gesture.
- **1.0.63 — shipped.** Calls ring from the shade with a Decline action and a lock-screen
  visible notification; "missed" is decided once, from the call state.
- **1.0.64 — shipped.** Update page (full screen, version step, scrollable notes, both
  choices, live percentage), mention-aware group notifications.
- **1.0.53 — shipped.** Camera capture (`TakePicture`), share location with a Maps hand-off.
- **1.0.54 — shipped.** @mentions (composer picker + roster-aware highlighting), reply privately.
- **1.0.55 — shipped.** Storage and data screen; per-chat "Media, links and docs".
- **1.0.56/1.0.57 — shipped.** Group **voice** calls (mesh, perfect negotiation, roster overlay), aligned to the worker's real `group-call:*` shapes, plus the incoming-call card.
- **Next (1.0.57).** Group **video** calls (shared camera into several encoders + tile grid), then
  media auto-download rules.
- **Then.** Media auto-download rules + storage manager; and the rows marked ⛔, which need the
  worker to grow columns or endpoints first (invite links, mention delivery, admin-restricted
  groups, disappearing messages). E2EE and multi-device stay separate protocol projects.
