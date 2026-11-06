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
| Disappearing messages | ✅ | ⛔ server has no TTL column | — |

## Groups

| Feature | WhatsApp | NChat | Where |
| --- | --- | --- | --- |
| Create / rename / description / avatar | ✅ | ✅ | `GroupInfoScreen` |
| Add / remove members, promote / demote, leave, delete | ✅ | ✅ | `GroupInfoScreen` |
| Typing per member, sender avatars, member count | ✅ | ✅ | `ConversationHeader` |
| **Group voice / video call** | ✅ | ❌ stubbed toast; `NChatApi` already has the endpoints — planned 1.0.53 (mesh + N-party `CallOverlay`) | `CallOverlay` |
| Invite link / QR | ✅ | ❌ needs a backend endpoint (only "add existing friends") | — |
| @mentions / @all | ✅ | ❌ needs mention delivery on the wire | — |
| Reply privately / keep in chat | ✅ | ❌ planned with mentions | — |
| Admin-restricted messaging + approval queue | ✅ | ❌ needs server roles beyond `admin` | — |

## Chat list and notifications

| Feature | WhatsApp | NChat | Where |
| --- | --- | --- | --- |
| Type-aware previews, pinned chats, mute, unread badge | ✅ | ✅ | `ChatsScreen` |
| Draft preview | ✅ | ✅ 1.0.50 | `ChatsScreen` |
| **System notifications** | ✅ | ❌ none at all — planned 1.0.51: per-chat channels honouring mute, `MessagingStyle` with inline reply, group summaries, unread badge | — |
| Archive | ✅ | ❌ planned 1.0.51 | — |
| Swipe actions on rows | ✅ | ❌ planned 1.0.51 | — |
| Unread / Groups filters | ✅ | ❌ planned 1.0.51 | — |
| Background calls (foreground service, ringtone, lock screen) | ✅ | 🟡 call works only while the app is in the foreground — planned 1.0.51 | `CallOverlay` |

## Platform and trust

| Feature | WhatsApp | NChat | Note |
| --- | --- | --- | --- |
| End-to-end encryption | ✅ | ❌ | The worker stores plaintext. This is a protocol project (key exchange, per-device sessions, group sender keys), not a screen — tracked separately. |
| Multi-device / linked devices / Web | ✅ | ❌ | Needs server sessions per device. |
| Backup & restore | ✅ | ❌ | Local caches only. |
| Media auto-download rules, storage manager | ✅ | ❌ | planned after notifications. |

## Where the waves stand

- **1.0.49 — shipped.** Swipe to reply, double-tap ❤️, voice speed 1×/1.5×/2×.
- **1.0.50 — this build.** In-chat search, multi-select, starred messages, per-chat drafts,
  photo viewer paging + zoom.
- **Next (1.0.51).** Notifications (channels, `MessagingStyle`, inline reply, unread badge),
  archive, swipe actions, Unread/Groups filters, text formatting, pin message, message info.
- **Then (1.0.52+).** In-app camera, send location, group calls, and the backend-dependent rows
  (invite links, mentions, admin-restricted groups, disappearing messages).
