package com.nchat.app.data.repository

import com.nchat.app.data.model.Chat
import com.nchat.app.data.model.Group
import com.nchat.app.data.model.Message
import com.nchat.app.data.model.MessageKind
import kotlinx.coroutines.flow.StateFlow

/**
 * What every screen uses to reach chats, groups and messages. The only implementation is
 * [RemoteChatRepository] (the app has no demo data); the UI never touches HTTP itself.
 */
interface ChatRepository {
    val chats: StateFlow<List<Chat>>
    val groups: StateFlow<List<Group>>

    /**
     * Why the last refresh failed, or null when it worked.
     *
     * A failed request and an empty account both produce an empty list; this is what lets the
     * chat list say "couldn't load chats" instead of quietly showing nothing.
     */
    val syncError: StateFlow<String?>

    /** Reloads chats + groups now (used by the retry button). */
    fun refresh()
    fun conversation(chatId: String, chatTitle: String): StateFlow<List<Message>>

    /**
     * Re-reads one conversation from the server.
     *
     * Real-time normally means the socket pushes the change; this is the safety net for the
     * moments it cannot (the connection is down, or a frame was lost). An open chat calls it
     * while realtime is offline, so the screen falls back to "a couple of seconds" instead of
     * "whenever something else happens to refresh".
     */
    fun refreshConversation(chatId: String)
    /** Loads the preceding page when the user scrolls to the top of a conversation. */
    fun loadOlder(chatId: String)
    fun sendMessage(
        chatId: String,
        text: String,
        replyAuthor: String? = null,
        replyText: String? = null,
        /** The id of the message being quoted — sent as `reply_to`, so the other side sees it. */
        replyToId: String? = null
    )

    /**
     * Uploads [bytes] to `POST /media/upload` and sends the resulting message — the real
     * attachment path the reference uses for photos, clips and documents.
     */
    fun sendMedia(
        chatId: String,
        kind: MessageKind,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        caption: String? = null,
        thumbnail: ByteArray? = null
    )
    /**
     * Shares a position as a `location` message.
     *
     * The worker has no column for coordinates: a location is `type = "location"` with the
     * position in `content`, which is exactly how the reference web app writes it and reads it
     * back. Six decimals is ~11 cm — more than a phone's GPS can justify, and it keeps the
     * message readable as `lat,lng` on every client.
     */
    fun sendLocation(chatId: String, latitude: Double, longitude: Double)

    /**
     * Sends a recorded voice message: the clip is uploaded and the message carries its
     * `media_url`, which is what makes the other side actually hear something.
     *
     * The clip is kept on disk until the server has the message, so a recording made without a
     * network is queued (see `VoiceClipStore`) instead of being lost.
     *
     * @param waveform the recorded amplitude as a JSON array (see `VoiceWaveform`), stored in
     *   `thumbnail_url` — the same column the reference puts its waveform in, so both clients
     *   draw the real shape of the clip.
     */
    fun sendVoice(
        chatId: String,
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        durationLabel: String,
        replyAuthor: String? = null,
        replyText: String? = null,
        waveform: String? = null,
        replyToId: String? = null
    )
    /**
     * Resend a bubble whose send failed for good (the warning icon on the message).
     *
     * Voice/photo/document messages that already uploaded reuse their `media_url`, so pressing
     * the warning is the same request again — no second upload, no duplicate file.
     */
    fun retrySend(chatId: String, messageId: String)

    /** Rewrites the body of my own text message and flags it as edited. */
    fun editMessage(chatId: String, messageId: String, text: String)
    fun toggleReaction(chatId: String, messageId: String, emoji: String)
    fun deleteMessage(
        chatId: String, messageId: String, forEveryone: Boolean = false, reportSuccess: Boolean = true
    )
    fun createGroup(name: String, description: String, memberIds: List<String>): Group
    fun addMembers(groupId: String, memberIds: List<String>)
    fun renameGroup(groupId: String, name: String)
    fun leaveGroup(groupId: String)

    /** Admin actions — `DELETE /groups/:id/members/:userId`, `PATCH .../members/:userId`, `DELETE /groups/:id`. */
    fun removeMember(groupId: String, userId: String)
    fun setMemberRole(groupId: String, userId: String, role: String)
    fun deleteGroup(groupId: String)
    fun setPinned(chatId: String, pinned: Boolean)
    fun setMuted(chatId: String, muted: Boolean)
    fun deleteChat(chatId: String)
    fun forwardMessage(targetChatId: String, source: Message)
    fun markRead(chatId: String)

    /** Existing chat for this person, or a fresh one seeded with a greeting. */
    fun openChat(personId: String, title: String, isOnline: Boolean): Chat

    /**
     * Streams the typing indicator to the other side over the socket.
     */
    fun setTyping(chatId: String, typing: Boolean) = Unit
    fun setRecording(chatId: String, recording: Boolean) = Unit
}
