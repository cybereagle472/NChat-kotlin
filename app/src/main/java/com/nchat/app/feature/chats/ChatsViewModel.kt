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
    val totalUnread: Int = 0
) {
    val isEmpty: Boolean get() = chats.isEmpty()
}

class ChatsViewModel(
    private val repository: ChatRepository
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<ChatsUiState> =
        combine(repository.chats, query) { chats, currentQuery ->
            val visible = chats
                .filter { chat ->
                    currentQuery.isBlank() ||
                        chat.title.contains(currentQuery, ignoreCase = true) ||
                        chat.lastMessage.contains(currentQuery, ignoreCase = true)
                }
                .sortedByDescending { it.lastMessageAt }
            ChatsUiState(
                query = currentQuery,
                chats = visible,
                totalUnread = chats.sumOf { it.unreadCount }
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatsUiState()
        )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun markRead(chatId: String) = repository.markRead(chatId)

    companion object {
        fun factory(repository: ChatRepository) = viewModelFactory {
            initializer { ChatsViewModel(repository) }
        }
    }
}
