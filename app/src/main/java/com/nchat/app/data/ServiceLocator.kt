package com.nchat.app.data

import android.content.Context
import coil.ImageLoader
import com.nchat.app.BuildConfig
import com.nchat.app.data.local.BlockedContactsStore
import com.nchat.app.data.local.ChatListCache
import com.nchat.app.data.local.OutboxStore
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
import com.nchat.app.network.MediaImages
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.TokenStore
import com.nchat.app.core.util.CrashLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
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

    /** Device-side block list; blocking never touches the server's friendship rows. */
    private val blockedStore by lazy { BlockedContactsStore(requireContext()) }

    /** Messages that still have to reach the server; survives restarts and reconnects. */
    private val outboxStore by lazy { OutboxStore(requireContext()) }


    val authRepository: AuthRepository by lazy {
        RemoteAuthRepository(api, tokens, appScope, chatCache)
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
            outbox = outboxStore
        )
    }
    val friendRepository: FriendRepository by lazy {
        RemoteFriendRepository(api, tokens, socket, appScope, blockedStore)
    }
    val statusRepository: StatusRepository by lazy {
        RemoteStatusRepository(api, tokens, socket, appScope)
    }
    val callRepository: CallRepository by lazy {
        RemoteCallRepository(api, tokens, socket, appScope)
    }
    val preferencesRepository: PreferencesRepository by lazy {
        RemotePreferencesRepository(requireContext(), api, appScope)
    }

    val profile: UserProfile get() = profileRepository.profile.value
}
