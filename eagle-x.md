# eagle-x.md — NChat, the whole story

A from-scratch record of what NChat is, why every piece looks the way it does, and what
must not be broken next. If you are picking this project up cold, read this file top to
bottom before touching code. It is the only document that describes *intent*; the code
describes the result.

- Repository: `cybereagle472/NChat-kotlin`
- Current version: **1.0.12** (`versionCode 20`) — next planned: 1.0.13 (`versionCode 21`)
- Package: `com.nchat.app`
- Stack: Kotlin, Jetpack Compose (BOM 2024.09), Material 3 1.2.0, single-module `:app`,
  AGP 8.1, Gradle 8.2, JDK 17, minSdk 24, targetSdk 34
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

- No real backend, no auth server, no push notifications (auth is local-only, see §7).
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
| 1.0.15 | 23 | *pending* | Conversation depth: resume marker, jump-to-latest, media viewer, message editing, toasts; per-row render layers removed. |

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
