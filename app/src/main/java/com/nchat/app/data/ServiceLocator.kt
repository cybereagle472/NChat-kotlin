package com.nchat.app.data

import com.nchat.app.data.model.CallEntry
import com.nchat.app.data.model.Channel
import com.nchat.app.data.model.Community
import com.nchat.app.data.model.Story
import com.nchat.app.data.model.UserProfile
import com.nchat.app.data.repository.ChatRepository
import com.nchat.app.data.repository.InMemoryChatRepository

/**
 * Minimal service locator. Keeps the composition root in one place without pulling in
 * a dependency injection framework for the current scope.
 */
object ServiceLocator {

    val chatRepository: ChatRepository by lazy { repository }

    val calls: List<CallEntry> get() = repository.calls
    val stories: List<Story> get() = repository.stories
    val channels: List<Channel> get() = repository.channels
    val communities: List<Community> get() = repository.communities
    val profile: UserProfile get() = repository.profile

    private val repository: InMemoryChatRepository by lazy { InMemoryChatRepository() }
}
