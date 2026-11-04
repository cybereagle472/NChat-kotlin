package com.nchat.app.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nchat.app.data.model.Chat
import com.nchat.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class ChatFilter(val label: String) {
    All("All"),
    Unread("Unread"),
    Groups("Groups")
}

data class ChatsUiState(
    val query: String = "",
    val filter: ChatFilter = ChatFilter.All,
    val pinned: List<Chat> = emptyList(),
    val others: List<Chat> = emptyList(),
    val contacts: List<Chat> = emptyList(),
    val totalCount: Int = 0,
    val unreadCount: Int = 0,
    val isLoading: Boolean = true
) {
    val isEmpty: Boolean get() = pinned.isEmpty() && others.isEmpty()
}

class ChatsViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(ChatFilter.All)

    val uiState: StateFlow<ChatsUiState> =
        combine(repository.chats, query, filter) { chats, currentQuery, currentFilter ->
            val matching = chats
                .filter { it.matches(currentQuery, currentFilter) }
                .sortedByDescending { it.lastMessageAt }
            ChatsUiState(
                query = currentQuery,
                filter = currentFilter,
                pinned = matching.filter { it.isPinned },
                others = matching.filterNot { it.isPinned },
                contacts = chats.filterNot { it.isGroup }
                    .sortedBy { it.title }
                    .take(24),
                totalCount = chats.size,
                unreadCount = chats.sumOf { it.unreadCount },
                isLoading = false
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatsUiState()
        )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onFilterChange(value: ChatFilter) {
        filter.value = value
    }

    fun markRead(chatId: String) = repository.markRead(chatId)

    fun togglePin(chatId: String) = repository.togglePin(chatId)

    fun toggleMute(chatId: String) = repository.toggleMute(chatId)

    private fun Chat.matches(currentQuery: String, currentFilter: ChatFilter): Boolean {
        val queryMatches = currentQuery.isBlank() ||
            title.contains(currentQuery, ignoreCase = true) ||
            lastMessagePreview.contains(currentQuery, ignoreCase = true)
        val filterMatches = when (currentFilter) {
            ChatFilter.All -> true
            ChatFilter.Unread -> unreadCount > 0
            ChatFilter.Groups -> isGroup
        }
        return queryMatches && filterMatches
    }

    companion object {
        fun factory(repository: ChatRepository) = viewModelFactory {
            initializer { ChatsViewModel(repository) }
        }
    }
}
