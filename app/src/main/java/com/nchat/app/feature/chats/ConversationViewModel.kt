package com.nchat.app.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Message
import com.nchat.app.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ConversationViewModel(
    val chatId: String,
    val chatTitle: String,
    private val repository: ChatRepository
) : ViewModel() {

    val messages: StateFlow<List<Message>> = repository.conversation(chatId, chatTitle)

    /** Live chat metadata (presence, typing, group size) for the conversation header. */
    val chat: StateFlow<Chat?> = repository.chats
        .map { chats -> chats.firstOrNull { it.id == chatId } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _activeMessageId = MutableStateFlow<String?>(null)
    val activeMessageId: StateFlow<String?> = _activeMessageId.asStateFlow()

    init {
        repository.markRead(chatId)
    }

    fun onComposerChange(value: String) {
        _composerText.value = value
    }

    fun onSend() {
        val text = _composerText.value
        if (text.isBlank()) return
        repository.sendMessage(chatId, text)
        _composerText.value = ""
    }

    fun onSelectMessage(messageId: String?) {
        _activeMessageId.value = messageId
    }

    fun onReaction(emoji: String) {
        val messageId = _activeMessageId.value ?: return
        repository.toggleReaction(chatId, messageId, emoji)
        _activeMessageId.value = null
    }

    fun refreshReadState() {
        repository.markRead(chatId)
    }

    override fun onCleared() {
        repository.saveDraft(chatId, _composerText.value)
        super.onCleared()
    }

    companion object {
        fun factory(chatId: String, chatTitle: String, repository: ChatRepository) =
            viewModelFactory {
                initializer { ConversationViewModel(chatId, chatTitle, repository) }
            }
    }
}
