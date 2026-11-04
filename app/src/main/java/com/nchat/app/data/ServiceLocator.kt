package com.nchat.app.data

import com.nchat.app.data.model.CallRecord
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.data.model.UserProfile
import com.nchat.app.data.repository.ChatRepository
import com.nchat.app.data.repository.FriendRepository
import com.nchat.app.data.repository.InMemoryChatRepository
import com.nchat.app.data.repository.InMemoryFriendRepository
import com.nchat.app.data.repository.InMemoryProfileRepository
import com.nchat.app.data.repository.ProfileRepository

/**
 * Composition root. One place to swap the in-memory repository for the Rust backend
 * once its API is ready.
 */
object ServiceLocator {

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
