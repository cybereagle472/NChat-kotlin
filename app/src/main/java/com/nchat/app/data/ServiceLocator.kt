package com.nchat.app.data

import com.nchat.app.data.model.CallRecord
import com.nchat.app.data.model.Friend
import com.nchat.app.data.model.StatusUpdate
import com.nchat.app.data.model.UserProfile
import com.nchat.app.data.repository.ChatRepository
import com.nchat.app.data.repository.InMemoryChatRepository

/**
 * Composition root. One place to swap the in-memory repository for the Rust backend
 * once its API is ready.
 */
object ServiceLocator {

    val chatRepository: ChatRepository get() = repository
    val statuses: List<StatusUpdate> get() = repository.statuses
    val friends: List<Friend> get() = repository.friends
    val calls: List<CallRecord> get() = repository.calls
    val profile: UserProfile get() = repository.profile

    private val repository: InMemoryChatRepository by lazy { InMemoryChatRepository() }
}
