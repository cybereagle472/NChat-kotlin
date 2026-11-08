package com.nchat.app.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import com.nchat.app.core.util.PickedFile
import com.nchat.app.data.local.DraftStore
import com.nchat.app.data.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn

class ConversationViewModel(
    val chatId: String,
    val chatTitle: String,
    private val repository: ChatRepository,
    /** Restores the half-written message a previous visit left in this chat. */
    private val drafts: DraftStore? = null
) : ViewModel() {

    val messages: StateFlow<List<Message>> = repository.conversation(chatId, chatTitle)

    val chat: StateFlow<Chat?> = repository.chats
        .map { chats -> chats.firstOrNull { it.id == chatId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _activeMessageId = MutableStateFlow<String?>(null)
    val activeMessageId: StateFlow<String?> = _activeMessageId.asStateFlow()

    /**
     * First message that was still unread when this conversation opened, captured before
     * [ChatRepository.markRead] clears the badge. Drives the "resume here" divider.
     */
    private val _firstUnreadId = MutableStateFlow<String?>(null)
    val firstUnreadId: StateFlow<String?> = _firstUnreadId.asStateFlow()

    /** The message the composer is currently quoting, if any. */
    private val _replyTo = MutableStateFlow<Message?>(null)
    val replyTo: StateFlow<Message?> = _replyTo.asStateFlow()

    /**
     * Messages ticked for a bulk action (forward / delete / copy).
     *
     * Long-pressing a bubble opens the action menu, and **Select** from that menu puts the
     * conversation into selection mode — the same two-step WhatsApp uses, so a long press never
     * surprise-ticks anything.
     */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    private var draftJob: Job? = null

    init {
        // The draft is restored *before* anything can render, so the composer opens on the
        // paragraph the user walked away from.
        drafts?.get(chatId)?.takeIf { it.isNotBlank() }?.let { _composerText.value = it }
        val unread = repository.chats.value.firstOrNull { it.id == chatId }?.unreadCount ?: 0
        val history = messages.value
        if (unread > 0 && history.isNotEmpty()) {
            val index = (history.size - unread).coerceIn(0, history.lastIndex)
            _firstUnreadId.value = history[index].id
        }
        repository.markRead(chatId)
        /*
         * Reading is not a single moment.
         *
         * Marking read once on open left anything that arrived afterwards counted as unread, so
         * leaving the chat showed a badge for messages the user was looking at. Every time the
         * newest message changes while this screen is alive, the chat is cleared again — with a
         * short debounce, because a burst of messages should mean one request, not ten.
         */
        viewModelScope.launch {
            messages
                .map { it.lastOrNull()?.id }
                .distinctUntilChanged()
                .collectLatest { newest ->
                    if (newest == null) return@collectLatest
                    delay(250)
                    repository.markRead(chatId)
                }
        }
    }

    fun onComposerChange(value: String) {
        _composerText.value = value
        // Stream "typing" to the other side.
        repository.setTyping(chatId, value.isNotEmpty())
        /*
         * Saved 350 ms after the last keystroke: every character would mean a file write per
         * character, and `onCleared` flushes whatever the timer has not reached yet, so leaving
         * the chat keeps the last thing typed either way.
         */
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
            delay(350)
            drafts?.set(chatId, value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        draftJob?.cancel()
        drafts?.set(chatId, _composerText.value)
    }

    fun onSend() {
        val text = _composerText.value
        if (text.isBlank()) return
        val quoted = _replyTo.value
        repository.sendMessage(
            chatId = chatId,
            text = text,
            replyAuthor = quoted?.authorName,
            replyText = quoted?.snippet(),
            replyToId = quoted?.id
        )
        _composerText.value = ""
        _replyTo.value = null
        repository.setTyping(chatId, false)
        draftJob?.cancel()
        drafts?.clear(chatId)
    }

    /** Quotes [messageId] straight from a swipe, without going through the menu. */
    fun quote(messageId: String) {
        _replyTo.value = messages.value.firstOrNull { it.id == messageId }
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

    fun onEditMessage(messageId: String, text: String) {
        repository.editMessage(chatId, messageId, text)
    }

    /** Tapping the warning on a bubble that never reached the server. */
    fun onRetrySend(messageId: String) {
        repository.retrySend(chatId, messageId)
    }

    fun onDeleteMessage(messageId: String, forEveryone: Boolean) {
        repository.deleteMessage(chatId, messageId, forEveryone)
        if (_activeMessageId.value == messageId) _activeMessageId.value = null
    }

    // ---------------------------------------------------------------- selection

    fun startSelection(messageId: String) {
        _selection.value = setOf(messageId)
        _activeMessageId.value = null
    }

    fun toggleSelection(messageId: String) {
        _selection.update { if (messageId in it) it - messageId else it + messageId }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectAllMessages() {
        _selection.value = messages.value.filterNot { it.deleted }.map { it.id }.toSet()
    }

    /** Deletes every ticked message; `forEveryone` also removes them from the other side. */
    fun deleteSelectedMessages(forEveryone: Boolean) {
        val ids = _selection.value
        _selection.value = emptySet()
        ids.forEach { id -> repository.deleteMessage(chatId, id, forEveryone, reportSuccess = false) }
    }

    /** Forwards every ticked message to [targetChatIds], one copy per message and target. */
    fun forwardSelectedMessages(targetChatIds: List<String>) {
        if (targetChatIds.isEmpty()) return
        val selected = messages.value.filter { it.id in _selection.value }
        _selection.value = emptySet()
        selected.forEach { source ->
            targetChatIds.forEach { target -> repository.forwardMessage(target, source) }
        }
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

    fun onAddMembers(memberIds: List<String>) {
        if (memberIds.isEmpty()) return
        repository.addMembers(chatId, memberIds)
    }

    fun onLeaveGroup() {
        repository.leaveGroup(chatId)
    }

    /** Admin only: drop somebody from the group (`DELETE /groups/:id/members/:userId`). */
    fun onRemoveMember(userId: String) {
        if (userId.isBlank()) return
        repository.removeMember(chatId, userId)
    }

    /** Admin only: promote (`admin`) or demote (`member`). */
    fun onSetMemberRole(userId: String, role: String) {
        if (userId.isBlank()) return
        repository.setMemberRole(chatId, userId, role)
    }

    fun onDeleteGroup() {
        repository.deleteGroup(chatId)
    }

    /**
     * Sends a recorded voice note.
     *
     * The reply stays attached while recording, exactly like it does for text — a quote the user
     * picked before holding the mic used to be dropped on the floor the moment they spoke.
     */
    fun onSendVoice(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        durationLabel: String,
        waveform: String? = null
    ) {
        if (bytes.isEmpty()) return
        val quoted = _replyTo.value
        repository.sendVoice(
            chatId = chatId,
            bytes = bytes,
            mimeType = mimeType,
            fileName = fileName,
            durationLabel = durationLabel,
            replyAuthor = quoted?.authorName,
            replyText = quoted?.snippet(),
            waveform = waveform,
            replyToId = quoted?.id
        )
        _replyTo.value = null
    }

    /** Shares a position the composer resolved (`Locator`). */
    fun onSendLocation(latitude: Double, longitude: Double) {
        repository.sendLocation(chatId, latitude, longitude)
    }

    /** A real attachment: the bytes are uploaded, then the message points at them. */
    fun onSendMedia(kind: MessageKind, file: PickedFile) {
        repository.sendMedia(
            chatId = chatId,
            kind = kind,
            fileName = file.name,
            mimeType = file.mimeType,
            bytes = file.bytes,
            thumbnail = file.thumbnail
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
        fun factory(
            chatId: String,
            chatTitle: String,
            repository: ChatRepository,
            drafts: DraftStore? = null
        ) = viewModelFactory {
            initializer { ConversationViewModel(chatId, chatTitle, repository, drafts) }
        }
    }
}
