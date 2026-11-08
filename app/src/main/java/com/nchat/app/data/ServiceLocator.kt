package com.nchat.app.data

import android.content.Context
import coil.ImageLoader
import com.nchat.app.BuildConfig
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import androidx.core.graphics.drawable.toBitmap
import coil.request.SuccessResult
import coil.request.ImageRequest
import android.graphics.Bitmap
import com.nchat.app.data.local.ArchivedStore
import com.nchat.app.data.local.BlockedContactsStore
import com.nchat.app.data.local.DraftStore
import com.nchat.app.data.local.WallpaperStore
import com.nchat.app.core.network.NetworkMonitor
import com.nchat.app.data.local.ChatListCache
import com.nchat.app.data.local.FriendGraphCache
import com.nchat.app.data.local.GroupListCache
import com.nchat.app.data.local.MessageStore
import com.nchat.app.data.local.OutboxStore
import com.nchat.app.data.local.PinnedMessageStore
import com.nchat.app.data.local.StarredStore
import com.nchat.app.data.local.VoiceClipStore
import com.nchat.app.data.notifications.NcNotifications
import com.nchat.app.data.push.BackgroundSyncService
import com.nchat.app.data.push.BatteryExemption
import com.nchat.app.data.model.UserProfile
import com.nchat.app.data.repository.AuthRepository
import com.nchat.app.data.repository.CallRepository
import com.nchat.app.data.repository.ChatRepository
import com.nchat.app.data.repository.FriendRepository
import com.nchat.app.data.repository.PreferencesRepository
import com.nchat.app.data.repository.ProfileRepository
import com.nchat.app.data.repository.RemoteAuthRepository
import com.nchat.app.data.repository.RemoteCallRepository
import com.nchat.app.data.repository.RemoteChatRepository
import com.nchat.app.data.repository.RemoteFriendRepository
import com.nchat.app.data.repository.RemotePreferencesRepository
import com.nchat.app.data.repository.RemoteProfileRepository
import com.nchat.app.data.repository.RemoteStatusRepository
import com.nchat.app.data.repository.StatusRepository
import com.nchat.app.network.ApiClient
import com.nchat.app.network.RealtimeEvent
import com.nchat.app.network.ConnectionState
import com.nchat.app.network.MediaFiles
import com.nchat.app.network.MediaImages
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.TokenStore
import com.nchat.app.core.util.CrashLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Composition root.
 *
 * NChat is a thin client: every repository below reads from the backend, so the app has one
 * source of truth and no demo data to drift out of sync with it.
 *
 * The backend address is fixed at build time (`BuildConfig.NCHAT_API_BASE`, filled by Gradle
 * from `nchat.properties` / `NCHAT_API_BASE`) and never editable at runtime — the same
 * "one address, injected by the build" rule the reference web app follows with
 * `VITE_API_URL`. There is no in-app server switcher.
 */
object ServiceLocator {

    private var appContext: Context? = null

    /**
     * Background work that must never take the process down: a socket that fails to open, a
     * heartbeat, a cache write. Without this handler one throwing coroutine is an
     * "NChat keeps stopping" with no trace.
     */
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            CrashLog.note("background coroutine failed", error)
        }
    )

    /** Called once from [com.nchat.app.MainActivity] before the UI reads anything. */
    fun init(context: Context) {
        appContext = context.applicationContext
        /*
         * Voice playback downloads its clip first (see `MediaFiles`): a player cannot send the
         * session token that a private `GET /media/file/…` requires, so the bytes are fetched
         * here and played from disk. Wired next to the image loader, for the same reason and
         * with the same failure policy — a broken token store must not stop the app from
         * starting.
         */
        runCatching {
            MediaFiles.install(
                context = requireContext(),
                rootUrl = serverUrl,
                accessToken = { tokens.accessToken },
                renewSession = { apiClient.renewAccessToken() }
            )
        }
        startNetworkWatch()
        // Channels must exist before the first message can arrive, not on the first notification.
        runCatching { notifications.ensureChannels() }
        // Requests, group invites and calls get a notification too — not just messages.
        startSystemNotifications()
        // First launch after installing an update: ask Android to stop freezing us, once.
        runCatching { maybeAskBatteryOnce() }
    }

    private fun requireContext(): Context =
        appContext ?: error("ServiceLocator.init(context) must run before the UI")

    // ------------------------------------------------------------ transport

    val tokens: TokenStore by lazy { TokenStore(requireContext()) }
    private val apiClient: ApiClient by lazy { ApiClient(BuildConfig.NCHAT_API_BASE, tokens) }
    val api: NChatApi by lazy { NChatApi(apiClient) }
    val socket: NChatSocket by lazy { NChatSocket(apiClient, tokens, appScope) }

    /**
     * Coil loader used by every `AsyncImage` in the app. It is registered as the singleton
     * in [com.nchat.app.NChatApplication], so photos carry the session token the backend
     * now requires (see [MediaImages]).
     */
    val images: ImageLoader by lazy {
        MediaImages.loader(
            context = requireContext(),
            rootUrl = serverUrl,
            accessToken = { tokens.accessToken },
            renewSession = { apiClient.renewAccessToken() }
        )
    }

    /** The backend this build talks to; empty only for a misconfigured build. */
    val serverUrl: String get() = BuildConfig.NCHAT_API_BASE.trim().trimEnd('/')

    /** False only when the APK was built without a backend address. */
    val isServerConfigured: Boolean get() = serverUrl.isNotEmpty()

    val connection: StateFlow<ConnectionState> get() = socket.state

    /** The device's own internet state, straight from the platform — see [NetworkMonitor]. */
    private val networkMonitor by lazy { NetworkMonitor(requireContext()) }
    val network: StateFlow<Boolean> get() = networkMonitor.online
    val networkTransport: StateFlow<String?> get() = networkMonitor.transport

    private var networkWatchStarted = false

    /**
     * Follows the device's connectivity instead of inferring it from the socket.
     *
     * Two things happen the moment the network comes back: the socket is torn down and dialled
     * again (`reconnectNow`) — because a socket that "survived" a wifi → cellular handover is
     * usually a zombie — and presence is re-reported so the account is not shown offline to
     * everybody else. Everything else follows from `ConnectionState.Connected`, which is the
     * one sync path this app has always used: `flushOutbox()` for queued sends,
     * `resyncAfterReconnect()` for the list and the open conversations, and the home screen's
     * own refresh.
     *
     * While offline the socket parks (no handshake against a dead radio), the open chat stops
     * polling, and sends stay in the outbox. Nothing is lost; it waits.
     */
    private fun startNetworkWatch() {
        if (networkWatchStarted) return
        networkWatchStarted = true
        networkMonitor.start()
        appScope.launch {
            // The value at start-up is applied immediately: an app opened in airplane mode
            // must not spend its first seconds dialling.
            runCatching { socket.setNetworkAvailable(networkMonitor.online.value) }
            networkMonitor.online.drop(1).collect { online ->
                runCatching { socket.setNetworkAvailable(online) }
                if (!online) return@collect
                if (tokens.signedIn.value) reportPresence()
            }
        }
    }

    fun connectRealtime() {
        socket.connect()
    }

    /**
     * "Make sure realtime is up" — called when the app comes back to the foreground.
     *
     * A socket that died while the phone was in a pocket (wifi handover, doze, a killed
     * Durable Object) is otherwise only noticed the next time the loop's backoff expires: the
     * user opens the app, nothing arrives, and messages turn up seconds later. This wakes it
     * immediately.
     */
    fun ensureRealtime() {
        socket.ensureConnected()
    }

    /**
     * Starts or stops the background connection to match the settings and the session.
     *
     * Called on launch, when the notification switch changes, and when the session ends. The
     * decision lives here rather than in the UI so the answer cannot differ between the settings
     * screen, a cold start and a sign-out.
     */
    fun applyBackgroundSync() {
        val enabled = runCatching { preferencesRepository.notifications.value.background }
            .getOrDefault(true)
        if (enabled && tokens.signedIn.value) {
            BackgroundSyncService.start(requireContext())
        } else {
            BackgroundSyncService.stop(requireContext())
        }
    }

    fun disconnectRealtime() {
        socket.disconnect()
    }

    /** Writes the heartbeat that keeps this account "online" for everyone else. */
    fun reportPresence() {
        appScope.launch { runCatching { api.heartbeat() } }
    }

    // --------------------------------------------------------- repositories

    /** Disk cache of the chat list; wiped on sign-out. */
    private val chatCache by lazy { ChatListCache(requireContext()) }

    /** Disk cache of the friend graph, so the Friends tab opens with content like Chats does. */
    private val friendCache by lazy { FriendGraphCache(requireContext()) }

    /** Device-side block list; blocking never touches the server's friendship rows. */
    private val blockedStore by lazy { BlockedContactsStore(requireContext()) }

    /** Messages that still have to reach the server; survives restarts and reconnects. */
    private val outboxStore by lazy { OutboxStore(requireContext()) }

    /** Every conversation, per account, on disk — see [MessageStore]. */
    private val messageStore by lazy { MessageStore(requireContext()) }

    /** Last known group list and rosters, so the Groups tab opens offline — see [GroupListCache]. */
    private val groupCache by lazy { GroupListCache(requireContext()) }

    /**
     * Voice clips waiting to be uploaded, so a recording made without a network is queued with
     * its audio instead of being lost — see [VoiceClipStore].
     */
    private val voiceClips by lazy { VoiceClipStore(requireContext()) }

    /** Unfinished composer text per conversation, restored when the chat is reopened. */
    val drafts: DraftStore by lazy { DraftStore(requireContext()) }

    /** The chat wallpaper choice (Settings ▸ Chats). Device-local, no account data. */
    val wallpapers: WallpaperStore by lazy { WallpaperStore(requireContext()) }

    /** Private, per-device starred messages behind "Starred messages". */
    val stars: StarredStore by lazy { StarredStore(requireContext()) }

    /** Archived conversations — a private filing decision, so it never leaves the device. */
    val archived: ArchivedStore by lazy { ArchivedStore(requireContext()) }

    /** One pinned message per conversation, shown as the banner under the header. */
    val pinned: PinnedMessageStore by lazy { PinnedMessageStore(requireContext()) }

    /** System notifications: per-chat channels, MessagingStyle, inline reply. */
    val notifications: NcNotifications by lazy {
        NcNotifications(requireContext(), appScope) { url -> avatarBitmap(url) }
    }

    /**
     * A sender's picture for a notification, through the same authenticated image loader the UI
     * uses (the backend requires the session token, so a bare URL would 401).
     */
    private suspend fun avatarBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(requireContext())
                .data(url)
                .size(128)
                .allowHardware(false)
                .build()
            (images.execute(request) as? SuccessResult)?.drawable?.toBitmap(128, 128)
        }.getOrNull()
    }

    /**
     * A conversation a notification tap asked for, consumed by `HomeRoot`.
     *
     * The tap may arrive while the app is cold (it starts, then this is set) or already running
     * (`onNewIntent`), so both paths write here and the navigation layer reacts once.
     */
    val pendingOpen = MutableStateFlow<PendingOpen?>(null)

    /**
     * A call notification was tapped.
     *
     * The call may have stopped ringing while the shade was open, so this is only a *request*:
     * `CallRepository.openFromNotification` checks the server and does nothing if it is over.
     */
    val pendingCall = MutableStateFlow<String?>(null)


    // ------------------------------------------------------- system notifications

    @Volatile
    private var systemNotificationsStarted = false

    /** Calls that rang while this device was not on the call screen, so it can report a miss. */
    private val ringingCalls = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Calls this device actually answered — those must never be reported as missed. */
    private val answeredCalls = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /**
     * Requests, group changes and calls, as notifications.
     *
     * Messages were the only thing that notified, which meant a friend request, being added to a
     * group, or a call while the app was closed simply never showed. One collector on the socket
     * covers all of them, so there is a single place that decides what is worth interrupting
     * somebody for, and the repositories stay free of Android notification code.
     */
    private fun startSystemNotifications() {
        if (systemNotificationsStarted) return
        systemNotificationsStarted = true
        appScope.launch {
            socket.events.collect { event ->
                runCatching { handleSystemEvent(event) }
            }
        }
        /*
         * Answering (or dismissing) a call in the app must stop the ringing notification, even if
         * the caller's `call:answered` frame is late. One observer on the call state covers both.
         */
        appScope.launch {
            callRepository.active.collect { call ->
                when {
                    call == null -> Unit
                    call.state == com.nchat.app.data.model.CallState.Live ||
                        call.state == com.nchat.app.data.model.CallState.Connecting -> {
                        answeredCalls.add(call.id)
                        ringingCalls.remove(call.id)
                        notifications.cancelIncomingCall(call.id)
                    }
                    /*
                     * Over. This is the only place a "missed call" is decided, because it is the
                     * only place that knows both halves: that it rang (`ringingCalls`) and that
                     * nobody took it (not answered here, and not declined by the user either —
                     * a declined call is a decision, not a miss).
                     */
                    call.state == com.nchat.app.data.model.CallState.Ended -> {
                        notifications.cancelIncomingCall(call.id)
                        val rang = ringingCalls.remove(call.id) != null
                        val handled = answeredCalls.remove(call.id) == true ||
                            call.note.equals("Declined", ignoreCase = true)
                        if (rang && !handled) {
                            notifications.showEvent(
                                key = "missed-${call.id}",
                                title = "Missed call",
                                body = "You missed a call from ${call.peerName}",
                                avatarUrl = call.peerAvatarUrl,
                                highPriority = false
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * True when a group message names this user (`@Nasir`, `@nasir12`).
     *
     * Mentions exist only as text on the wire, so this is the one place that can tell a mention
     * from an ordinary message — and therefore the only place a notification can say "you were
     * mentioned" instead of just showing the line.
     */
    private fun mentionsMe(chat: com.nchat.app.data.model.Chat, message: com.nchat.app.data.model.Message): Boolean {
        if (!chat.isGroup || message.isMine) return false
        val text = message.text
        if (text.isBlank() || !text.contains('@')) return false
        val me = runCatching { profile }.getOrNull()
        val handle = me?.handle?.removePrefix("@")?.takeIf { it.isNotBlank() }
        val name = me?.fullName?.takeIf { it.isNotBlank() }
        val needles = listOfNotNull(handle?.let { "@$it" }, name?.let { "@$it" })
        return needles.any { needle -> text.contains(needle, ignoreCase = true) }
    }

    /**
     * The user dealt with this call (declined it from the shade, or answered it elsewhere), so it
     * must not later be reported as missed.
     */
    fun markCallHandled(callId: String) {
        answeredCalls.add(callId)
        ringingCalls.remove(callId)
    }

    private fun handleSystemEvent(event: RealtimeEvent) {
        when (event.type) {
            "friend:request" -> {
                val request = event.objectOrNull("request") ?: return
                val profile = request.objectOrNull("sender_profile") ?: request
                val name = profile.string("full_name") ?: profile.string("username") ?: "Somebody"
                notifications.showEvent(
                    key = "friend-request",
                    title = "Friend request",
                    body = "$name wants to connect with you",
                    avatarUrl = profile.string("avatar_url")
                )
            }

            "friend:accepted" -> {
                val friend = event.objectOrNull("friend") ?: return
                val name = friend.string("full_name") ?: friend.string("username") ?: "Your contact"
                notifications.showEvent(
                    key = "friend-accepted",
                    title = "Request accepted",
                    body = "$name accepted your request",
                    avatarUrl = friend.string("avatar_url"),
                    highPriority = false
                )
            }

            "group:created" -> {
                val group = event.objectOrNull("group") ?: return
                val name = group.string("name") ?: "A group"
                val id = group.string("id")
                notifications.showEvent(
                    key = "group-$id",
                    title = "Added to $name",
                    body = "You are now a member of this group",
                    chatId = id,
                    chatTitle = name,
                    avatarUrl = group.string("avatar_url")
                )
            }

            /*
             * A call, always — not only when the call screen is already up.
             *
             * `CallRepository` sets `active` from this same event whether or not the Activity is
             * on screen, so skipping the notification whenever a call existed meant a call that
             * arrived while the app was in the background produced *no* sound at all: the overlay
             * was drawn in a window nobody could see. The ringing notification is therefore
             * unconditional, and it is the thing that makes the phone ring and shows over the
             * lock screen.
             */
            "call:incoming" -> {
                val call = event.objectOrNull("call") ?: return
                val id = call.string("id") ?: return
                val profile = call.objectOrNull("caller_profile")
                val name = profile?.string("full_name") ?: profile?.string("username") ?: "Someone"
                val video = call.string("call_type") == "video"
                ringingCalls[id] = name
                answeredCalls.remove(id)
                notifications.showIncomingCall(
                    callId = id,
                    callerName = name,
                    video = video,
                    avatarUrl = profile?.string("avatar_url")
                )
            }

            "call:answered" -> {
                val id = event.string("callId") ?: event.string("call_id") ?: return
                answeredCalls.add(id)
                ringingCalls.remove(id)
                notifications.cancelIncomingCall(id)
            }

            // The ring stops here; whether it becomes a "missed call" is decided once, from the
            // call state (see the `active` observer in `startSystemNotifications`).
            "call:ended" -> {
                val id = event.string("callId") ?: event.string("call_id") ?: return
                notifications.cancelIncomingCall(id)
                notifications.cancelEvent("call-$id")
            }
        }
    }

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.objectOrNull(key: String): JsonObject? = this?.get(key) as? JsonObject

    /**
     * Asks once, on the first run after an update, for the battery exemption.
     *
     * It used to live only behind a row in Settings, which is not where a messenger asks: without
     * it Android freezes the connection and messages stop arriving until the app is opened. Asked
     * once per version code, so a refusal is respected and never nagged about again.
     */
    private fun maybeAskBatteryOnce() {
        val prefs = requireContext().getSharedPreferences("nchat_prefs", Context.MODE_PRIVATE)
        val asked = runCatching { prefs.getInt("battery_asked_version", 0) }.getOrDefault(0)
        if (asked >= BuildConfig.VERSION_CODE) return
        prefs.edit().putInt("battery_asked_version", BuildConfig.VERSION_CODE).apply()
        if (BatteryExemption.granted(requireContext())) return
        appScope.launch {
            delay(2_500)
            runCatching { BatteryExemption.request(requireContext()) }
        }
    }

    val authRepository: AuthRepository by lazy {
        RemoteAuthRepository(
            api = api,
            tokens = tokens,
            scope = appScope,
            cache = chatCache,
            clearLocalState = {
                drafts.clearAll()
                stars.clearAll()
                archived.clearAll()
                pinned.clearAll()
                notifications.cancelAll()
            }
        )
    }
    val profileRepository: ProfileRepository by lazy {
        RemoteProfileRepository(api, tokens, appScope)
    }
    val chatRepository: ChatRepository by lazy {
        RemoteChatRepository(
            api = api,
            tokens = tokens,
            socket = socket,
            scope = appScope,
            cache = chatCache,
            blockedIds = { friendRepository.blockedIds.value },
            outbox = outboxStore,
            messages = messageStore,
            online = { networkMonitor.online.value },
            groupCache = groupCache,
            voiceClips = voiceClips,
            // A message from somebody else becomes a system notification, unless that chat is the
            // one currently on screen (`NcNotifications.activeChatId`).
            onIncoming = { chat, message ->
                notifications.show(chat, message, mentioned = mentionsMe(chat, message))
            }
        )
    }
    val friendRepository: FriendRepository by lazy {
        RemoteFriendRepository(
            api = api,
            tokens = tokens,
            socket = socket,
            scope = appScope,
            blockedStore = blockedStore,
            cache = friendCache,
        )
    }
    val statusRepository: StatusRepository by lazy {
        RemoteStatusRepository(api, tokens, socket, appScope)
    }
    val callRepository: CallRepository by lazy {
        // The context is only for the media engine (audio routing, camera enumeration) — the
        // signalling half is exactly what it was in 1.0.40.
        RemoteCallRepository(api, tokens, socket, appScope, requireContext())
    }
    val preferencesRepository: PreferencesRepository by lazy {
        RemotePreferencesRepository(requireContext(), api, appScope)
    }

    val profile: UserProfile get() = profileRepository.profile.value
}

/**
 * What a notification tap asked for: which conversation, and which message inside it.
 *
 * [messageId] is null when the tap came from something without a message (a friend request, a
 * group invite, an older notification built before ids were carried).
 */
data class PendingOpen(
    val chatId: String,
    val title: String,
    val messageId: String? = null
)
