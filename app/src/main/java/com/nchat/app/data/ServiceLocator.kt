package com.nchat.app.data

import android.content.Context
import coil.ImageLoader
import com.nchat.app.BuildConfig
import com.nchat.app.data.local.ArchivedStore
import com.nchat.app.data.local.BlockedContactsStore
import com.nchat.app.data.local.DraftStore
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
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.FriendRelation
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
import com.nchat.app.network.ConnectionState
import com.nchat.app.network.MediaFiles
import com.nchat.app.network.MediaImages
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.TokenStore
import com.nchat.app.core.util.CrashLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    /** Private, per-device starred messages behind "Starred messages". */
    val stars: StarredStore by lazy { StarredStore(requireContext()) }

    /** Archived conversations — a private filing decision, so it never leaves the device. */
    val archived: ArchivedStore by lazy { ArchivedStore(requireContext()) }

    /** One pinned message per conversation, shown as the banner under the header. */
    val pinned: PinnedMessageStore by lazy { PinnedMessageStore(requireContext()) }

    /** System notifications: per-chat channels, MessagingStyle, inline reply. */
    val notifications: NcNotifications by lazy { NcNotifications(requireContext()) }

    /**
     * A conversation a notification tap asked for, consumed by `HomeRoot`.
     *
     * The tap may arrive while the app is cold (it starts, then this is set) or already running
     * (`onNewIntent`), so both paths write here and the navigation layer reacts once.
     */
    val pendingOpen = MutableStateFlow<Pair<String, String>?>(null)


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
            onIncoming = { chat, message -> notifications.show(chat, message) }
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
            /*
             * `/messages/chats` is the same `friendships` JOIN as `/friends`, so every 1:1
             * thread the chat list holds is somebody the server already calls a friend. Passing
             * them in keeps the Friends tab from ever being emptier than the Chats tab; the
             * lambda is only read at reload time, so the two repositories can refer to each
             * other without a construction cycle.
             */
            chatPeers = {
                chatRepository.chats.value
                    .filterNot { it.isGroup }
                    .map { chat ->
                        Friend(
                            id = chat.id,
                            name = chat.title,
                            handle = "",
                            isOnline = chat.isOnline,
                            avatarUrl = chat.avatarUrl,
                            relation = FriendRelation.Friend
                        )
                    }
            }
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
