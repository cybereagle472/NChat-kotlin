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

data class ChatsUiState(
    val query: String = "",
    val chats: List<Chat> = emptyList(),
    val totalUnread: Int = 0,
    /** Set when the last refresh failed; the list then explains itself instead of looking empty. */
    val error: String? = null
) {
    val isEmpty: Boolean get() = chats.isEmpty()
}

class ChatsViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<ChatsUiState> =
        combine(repository.chats, query, repository.syncError) { chats, currentQuery, error ->
            val visible = chats
                .filter { chat ->
                    currentQuery.isBlank() ||
                        chat.title.contains(currentQuery, ignoreCase = true) ||
                        chat.lastMessage.contains(currentQuery, ignoreCase = true)
                }
                // Pinned conversations float to the top, then newest first.
                .sortedWith(compareByDescending<Chat> { it.pinned }.thenByDescending { it.lastMessageAt })
            ChatsUiState(
                query = currentQuery,
                chats = visible,
                totalUnread = chats.sumOf { it.unreadCount },
                error = error
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatsUiState()
        )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun retry() = repository.refresh()

    fun markRead(chatId: String) = repository.markRead(chatId)

    fun togglePin(chatId: String, pinned: Boolean) = repository.setPinned(chatId, pinned)

    fun toggleMute(chatId: String, muted: Boolean) = repository.setMuted(chatId, muted)

    fun deleteChat(chatId: String) = repository.deleteChat(chatId)

    companion object {
        fun factory(repository: ChatRepository) = viewModelFactory {
            initializer { ChatsViewModel(repository) }
        }
    }
}
