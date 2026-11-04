package com.nchat.app.data

import com.nchat.app.data.model.CallRecord
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.data.model.UserProfile
import android.content.Context
import com.nchat.app.data.repository.AuthRepository
import com.nchat.app.data.repository.ChatRepository
import com.nchat.app.data.repository.FriendRepository
import com.nchat.app.data.repository.InMemoryChatRepository
import com.nchat.app.data.repository.InMemoryFriendRepository
import com.nchat.app.data.repository.InMemoryProfileRepository
import com.nchat.app.data.repository.LocalAuthRepository
import com.nchat.app.data.repository.ProfileRepository

/**
 * Composition root. One place to swap the in-memory repository for the Rust backend
 * once its API is ready.
 */
object ServiceLocator {

    private var appContext: Context? = null

    /** Called once from [com.nchat.app.MainActivity] before the UI reads anything. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val authRepository: AuthRepository get() = authRepositoryImpl

    private val authRepositoryImpl: AuthRepository by lazy {
        LocalAuthRepository(
            appContext ?: error("ServiceLocator.init(context) must run before the UI")
        )
    }

    val chatRepository: ChatRepository get() = repository
    val friendRepository: FriendRepository get() = friendsRepository
    val profileRepository: ProfileRepository get() = profileRepositoryImpl
    val statuses: List<StatusUpdate> get() = repository.statuses
    val friends: List<Friend> get() = repository.friends
    val calls: List<CallRecord> get() = repository.calls
    val profile: UserProfile get() = profileRepositoryImpl.profile.value

    private val profileRepositoryImpl: InMemoryProfileRepository by lazy { InMemoryProfileRepository() }
    private val repository: InMemoryChatRepository by lazy {
        InMemoryChatRepository(profileProvider = { profileRepositoryImpl.profile.value })
    }
    private val friendsRepository: InMemoryFriendRepository by lazy { InMemoryFriendRepository() }
}
