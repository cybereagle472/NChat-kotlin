package com.nchat.app.data

import android.content.Context
import com.nchat.app.BuildConfig
import com.nchat.app.data.model.CallRecord
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.data.model.UserProfile
import com.nchat.app.data.repository.AuthRepository
import com.nchat.app.data.repository.ChatRepository
import com.nchat.app.data.repository.FriendRepository
import com.nchat.app.data.repository.InMemoryChatRepository
import com.nchat.app.data.repository.InMemoryFriendRepository
import com.nchat.app.data.repository.InMemoryProfileRepository
import com.nchat.app.data.repository.LocalAuthRepository
import com.nchat.app.data.repository.LocalPreferencesRepository
import com.nchat.app.data.repository.PreferencesRepository
import com.nchat.app.data.repository.ProfileRepository
import com.nchat.app.data.repository.RemoteAuthRepository
import com.nchat.app.data.repository.RemoteChatRepository
import com.nchat.app.data.repository.RemoteProfileRepository
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
 * Composition root — the single seam between the UI and where its data comes from.
 *
 * Two modes, chosen at build time by `BuildConfig.NCHAT_API_BASE`:
 *
 * - **empty** → the in-memory mock repositories (what the released demo APK ships, so it
 *   works with no server at all),
 * - **set** → the repositories that talk to the Rust backend in `server/`.
 *
 * Screens never check which mode is active; they only see the interfaces.
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

    /** True when this build was pointed at a backend. */
    val isRemote: Boolean get() = apiClient.isConfigured

    val connection: StateFlow<ConnectionState> get() = socket.state

    /** Opens the realtime socket (no-op on mock builds). */
    fun connectRealtime() {
        if (isRemote) socket.connect()
    }

    fun disconnectRealtime() {
        if (isRemote) socket.disconnect()
    }

    /** Writes the heartbeat that keeps this account "online" for everyone else. */
    fun reportPresence() {
        if (!isRemote) return
        appScope.launch { runCatching { api.heartbeat() } }
    }

    // --------------------------------------------------------- repositories

    val authRepository: AuthRepository get() = if (isRemote) remoteAuth else localAuth
    val profileRepository: ProfileRepository get() = if (isRemote) remoteProfile else mockProfile
    val chatRepository: ChatRepository get() = if (isRemote) remoteChat else mockChat
    val friendRepository: FriendRepository get() = mockFriends
    val preferencesRepository: PreferencesRepository get() = preferencesRepositoryImpl

    val statuses: List<StatusUpdate> get() = mockChat.statuses
    val friends: List<Friend> get() = mockChat.friends
    val calls: List<CallRecord> get() = mockChat.calls
    val profile: UserProfile get() = profileRepository.profile.value

    private val localAuth: AuthRepository by lazy { LocalAuthRepository(requireContext()) }
    private val remoteAuth: AuthRepository by lazy {
        RemoteAuthRepository(api, tokens, appScope)
    }
    private val remoteProfile: ProfileRepository by lazy {
        RemoteProfileRepository(api, tokens, appScope)
    }
    private val remoteChat: RemoteChatRepository by lazy {
        RemoteChatRepository(api, tokens, socket, appScope)
    }
    private val mockProfile: InMemoryProfileRepository by lazy { InMemoryProfileRepository() }
    private val mockChat: InMemoryChatRepository by lazy {
        InMemoryChatRepository(profileProvider = { profileRepository.profile.value })
    }
    private val mockFriends: InMemoryFriendRepository by lazy { InMemoryFriendRepository() }
    private val preferencesRepositoryImpl: PreferencesRepository by lazy {
        LocalPreferencesRepository(requireContext())
    }
}
