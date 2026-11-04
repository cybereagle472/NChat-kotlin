package com.nchat.app.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
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

    val chat: StateFlow<Chat?> = repository.chats
        .map { chats -> chats.firstOrNull { it.id == chatId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _activeMessageId = MutableStateFlow<String?>(null)
    val activeMessageId: StateFlow<String?> = _activeMessageId.asStateFlow()

    /** The message the composer is currently quoting, if any. */
    private val _replyTo = MutableStateFlow<Message?>(null)
    val replyTo: StateFlow<Message?> = _replyTo.asStateFlow()

    init {
        repository.markRead(chatId)
    }

    fun onComposerChange(value: String) {
        _composerText.value = value
    }

    fun onSend() {
        val text = _composerText.value
        if (text.isBlank()) return
        val quoted = _replyTo.value
        repository.sendMessage(
            chatId = chatId,
            text = text,
            replyAuthor = quoted?.authorName,
            replyText = quoted?.text
        )
        _composerText.value = ""
        _replyTo.value = null
    }

    fun onReply() {
        _activeMessageId.value?.let { id ->
            _replyTo.value = messages.value.firstOrNull { it.id == id }
        }
        _activeMessageId.value = null
    }

    fun onCancelReply() {
        _replyTo.value = null
    }

    fun onSelectMessage(messageId: String?) {
        _activeMessageId.value = messageId
    }

    fun onDeleteMessage(messageId: String, forEveryone: Boolean) {
        repository.deleteMessage(chatId, messageId, forEveryone)
        if (_activeMessageId.value == messageId) _activeMessageId.value = null
    }

    fun onForward(messageId: String, targetChatIds: List<String>) {
        if (targetChatIds.isEmpty()) return
        val source = messages.value.firstOrNull { it.id == messageId } ?: return
        targetChatIds.forEach { target -> repository.forwardMessage(target, source) }
    }

    fun onRenameGroup(name: String) {
        if (name.isBlank()) return
        repository.renameGroup(chatId, name)
    }

    fun onAddMembers(count: Int) {
        if (count <= 0) return
        repository.addMembers(chatId, count)
    }

    fun onLeaveGroup() {
        repository.leaveGroup(chatId)
    }

    fun onSendVoice(durationLabel: String) {
        repository.sendVoice(chatId, durationLabel)
    }

    fun onSendAttachment(kind: MessageKind) {
        repository.sendAttachment(
            chatId = chatId,
            kind = kind,
            label = when (kind) {
                MessageKind.Photo -> "Photo"
                MessageKind.Video -> "Video"
                MessageKind.Document -> "document.pdf"
                MessageKind.Voice -> "Voice message"
                MessageKind.Location -> "Location"
                MessageKind.Text -> ""
            }
        )
    }

    /** React from the full-reactions sheet, where the target outlives the context menu. */
    fun reactTo(messageId: String, emoji: String) {
        repository.toggleReaction(chatId, messageId, emoji)
    }

    fun onReaction(emoji: String) {
        _activeMessageId.value?.let { repository.toggleReaction(chatId, it, emoji) }
        _activeMessageId.value = null
    }

    companion object {
        fun factory(chatId: String, chatTitle: String, repository: ChatRepository) =
            viewModelFactory {
                initializer { ConversationViewModel(chatId, chatTitle, repository) }
            }
    }
}
