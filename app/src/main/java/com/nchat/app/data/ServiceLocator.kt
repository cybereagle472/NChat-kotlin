package com.nchat.app.data

import android.content.Context
import com.nchat.app.BuildConfig
import com.nchat.app.data.local.ChatListCache
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
import com.nchat.app.network.NChatApi
import com.nchat.app.network.NChatSocket
import com.nchat.app.network.TokenStore
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
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    /** The backend this build talks to; empty only for a misconfigured build. */
    val serverUrl: String get() = BuildConfig.NCHAT_API_BASE.trim().trimEnd('/')

    /** False only when the APK was built without a backend address. */
    val isServerConfigured: Boolean get() = serverUrl.isNotEmpty()

    val connection: StateFlow<ConnectionState> get() = socket.state

    fun connectRealtime() {
        socket.connect()
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


    val authRepository: AuthRepository by lazy {
        RemoteAuthRepository(api, tokens, appScope, chatCache)
    }
    val profileRepository: ProfileRepository by lazy {
        RemoteProfileRepository(api, tokens, appScope)
    }
    val chatRepository: ChatRepository by lazy {
        RemoteChatRepository(api, tokens, socket, appScope, chatCache)
    }
    val friendRepository: FriendRepository by lazy {
        RemoteFriendRepository(api, tokens, socket, appScope)
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
