package com.nchat.app.network

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Every endpoint of the Rust backend, one function each, returning the server's own types.
 *
 * Paths and bodies are copied from `server/src/routes/mod.rs`; nothing here renames a field,
 * because the app is supposed to speak the reference protocol byte-for-byte.
 */
class NChatApi(private val client: ApiClient) {

    val isConfigured: Boolean get() = client.isConfigured

    val socketRoot: String get() = client.socketRoot

    // ------------------------------------------------------------------ auth

    suspend fun signUp(email: String, password: String, fullName: String): AuthResponse =
        decode(
            client.post(
                "/auth/signup",
                body {
                    put("email", email)
                    put("password", password)
                    put("full_name", fullName)
                }
            )
        )

    suspend fun signIn(email: String, password: String): AuthResponse =
        decode(
            client.post(
                "/auth/login",
                body {
                    put("email", email)
                    put("password", password)
                }
            )
        )

    suspend fun logout(refreshToken: String?) {
        client.post(
            "/auth/logout",
            body { if (refreshToken != null) put("refresh_token", refreshToken) }
        )
    }

    suspend fun me(): UserEnvelope = decode(client.get("/auth/me"))

    // ----------------------------------------------------------------- users

    suspend fun myProfile(): ProfileEnvelope = decode(client.get("/users/me"))

    suspend fun updateMe(
        fullName: String? = null,
        username: String? = null,
        bio: String? = null,
        avatarUrl: String? = null,
    ): ProfileEnvelope = decode(
        client.patch(
            "/users/me",
            body {
                if (fullName != null) put("full_name", fullName)
                if (username != null) put("username", username)
                if (bio != null) put("bio", bio)
                if (avatarUrl != null) put("avatar_url", avatarUrl)
            }
        )
    )

    suspend fun usernameAvailable(username: String): AvailabilityEnvelope =
        decode(client.get("/users/username-available", mapOf("username" to username)))

    suspend fun searchUsers(query: String, limit: Int = 20): ProfilesEnvelope =
        decode(client.get("/users/search", mapOf("q" to query, "limit" to limit.toString())))

    suspend fun user(id: String): ProfileEnvelope = decode(client.get("/users/$id"))

    suspend fun allUsers(): ProfilesEnvelope = decode(client.get("/users"))

    suspend fun heartbeat() {
        client.post("/users/presence", body { })
    }

    // --------------------------------------------------------------- friends

    suspend fun friends(): FriendsEnvelope = decode(client.get("/friends"))

    suspend fun friendRequests(): RequestsEnvelope = decode(client.get("/friends/requests"))

    suspend fun sentRequests(): SentEnvelope = decode(client.get("/friends/sent"))

    suspend fun sendFriendRequest(receiverId: String) {
        client.post("/friends/request", body { put("receiver_id", receiverId) })
    }

    suspend fun respondToRequest(requestId: String, accept: Boolean) {
        client.post("/friends/$requestId/respond", body { put("accept", accept) })
    }

    suspend fun cancelRequest(requestId: String) {
        client.delete("/friends/request/$requestId")
    }

    suspend fun removeFriend(friendId: String) {
        client.delete("/friends/$friendId")
    }

    suspend fun blockUser(userId: String) {
        client.post("/friends/block", body { put("user_id", userId) })
    }

    suspend fun unblockUser(userId: String) {
        client.post("/friends/unblock", body { put("user_id", userId) })
    }

    suspend fun blockedUsers(): BlockedEnvelope = decode(client.get("/friends/blocked"))

    // -------------------------------------------------------------- messages

    suspend fun conversation(
        otherId: String,
        before: String? = null,
        limit: Int = 100,
    ): MessagesEnvelope = decode(
        client.get(
            "/messages/with/$otherId",
            buildMap {
                put("limit", limit.toString())
                if (before != null) put("before", before)
            }
        )
    )

    suspend fun chats(): ChatsEnvelope = decode(client.get("/messages/chats"))

    /** Hide every message in a direct thread for the current account only. */
    suspend fun clearConversation(otherId: String) {
        client.post("/messages/with/$otherId/delete-for-me")
    }

    suspend fun sendMessage(
        receiverId: String,
        content: String,
        type: String = "text",
        clientMessageId: String? = null,
        replyTo: String? = null,
        mediaUrl: String? = null,
        thumbnailUrl: String? = null,
        duration: String? = null,
        fileName: String? = null,
        fileSize: String? = null,
    ): MessageEnvelope = decode(
        client.post(
            "/messages",
            body {
                put("receiver_id", receiverId)
                put("content", content)
                put("type", type)
                if (clientMessageId != null) put("client_message_id", clientMessageId)
                if (replyTo != null) put("reply_to", replyTo)
                if (mediaUrl != null) put("media_url", mediaUrl)
                if (thumbnailUrl != null) put("thumbnail_url", thumbnailUrl)
                if (duration != null) put("duration", duration)
                if (fileName != null) put("fileName", fileName)
                if (fileSize != null) put("fileSize", fileSize)
            }
        )
    )

    suspend fun editMessage(id: String, content: String): MessageEnvelope =
        decode(client.patch("/messages/$id", body { put("content", content) }))

    suspend fun deleteMessage(id: String) {
        client.delete("/messages/$id")
    }

    suspend fun deleteMessageForMe(id: String) {
        client.post("/messages/$id/delete-for-me")
    }

    suspend fun reactToMessage(id: String, emoji: String): ReactionsEnvelope =
        decode(client.post("/messages/$id/reaction", body { put("emoji", emoji) }))

    suspend fun markConversationRead(senderId: String) {
        client.post("/messages/mark-read", body { put("sender_id", senderId) })
    }

    suspend fun sendTyping(receiverId: String, isTyping: Boolean) {
        client.post(
            "/messages/typing",
            body {
                put("receiver_id", receiverId)
                put("is_typing", isTyping)
            }
        )
    }

    suspend fun sendRecording(receiverId: String, isRecording: Boolean) {
        client.post(
            "/messages/recording",
            body {
                put("receiver_id", receiverId)
                put("is_recording", isRecording)
            }
        )
    }

    // ---------------------------------------------------------------- groups

    suspend fun groups(): GroupsEnvelope = decode(client.get("/groups"))

    suspend fun group(id: String): GroupEnvelope = decode(client.get("/groups/$id"))

    suspend fun createGroup(
        name: String,
        description: String? = null,
        memberIds: List<String> = emptyList(),
    ): GroupEnvelope = decode(
        client.post(
            "/groups",
            body {
                put("name", name)
                if (description != null) put("description", description)
                if (memberIds.isNotEmpty()) {
                    putJsonArray("memberIds") { memberIds.forEach { add(it) } }
                }
            }
        )
    )

    suspend fun updateGroup(
        id: String,
        name: String? = null,
        description: String? = null,
        avatarUrl: String? = null,
    ): GroupEnvelope = decode(
        client.patch(
            "/groups/$id",
            body {
                if (name != null) put("name", name)
                if (description != null) put("description", description)
                if (avatarUrl != null) put("avatar_url", avatarUrl)
            }
        )
    )

    suspend fun deleteGroup(id: String) {
        client.delete("/groups/$id")
    }

    suspend fun addGroupMembers(id: String, memberIds: List<String>): GroupEnvelope =
        decode(
            client.post(
                "/groups/$id/members",
                body { putJsonArray("memberIds") { memberIds.forEach { add(it) } } }
            )
        )

    suspend fun removeGroupMember(id: String, userId: String) {
        client.delete("/groups/$id/members/$userId")
    }

    suspend fun leaveGroup(id: String) {
        client.post("/groups/$id/leave")
    }

    suspend fun setMemberRole(id: String, userId: String, role: String) {
        client.patch("/groups/$id/members/$userId", body { put("role", role) })
    }

    suspend fun muteGroup(id: String, muted: Boolean) {
        client.patch("/groups/$id/mute", body { put("muted", muted) })
    }

    suspend fun groupMessages(
        id: String,
        before: String? = null,
        limit: Int = 100,
    ): MessagesEnvelope = decode(
        client.get(
            "/groups/$id/messages",
            buildMap {
                put("limit", limit.toString())
                if (before != null) put("before", before)
            }
        )
    )

    suspend fun sendGroupMessage(
        groupId: String,
        content: String,
        type: String = "text",
        clientMessageId: String? = null,
        replyTo: String? = null,
        mediaUrl: String? = null,
        thumbnailUrl: String? = null,
        duration: String? = null,
    ): MessageEnvelope = decode(
        client.post(
            "/groups/$groupId/messages",
            body {
                put("content", content)
                put("type", type)
                if (clientMessageId != null) put("client_message_id", clientMessageId)
                if (replyTo != null) put("reply_to", replyTo)
                if (mediaUrl != null) put("media_url", mediaUrl)
                if (thumbnailUrl != null) put("thumbnail_url", thumbnailUrl)
                if (duration != null) put("duration", duration)
            }
        )
    )

    suspend fun deleteGroupMessageForMe(messageId: String) {
        client.post("/groups/messages/$messageId/delete-for-me")
    }

    suspend fun deleteGroupMessageForEveryone(messageId: String) {
        client.post("/groups/messages/$messageId/delete-for-everyone")
    }

    suspend fun reactToGroupMessage(messageId: String, emoji: String): ReactionsEnvelope =
        decode(client.post("/groups/messages/$messageId/reaction", body { put("emoji", emoji) }))

    suspend fun sendGroupTyping(groupId: String, isTyping: Boolean) {
        client.post(
            "/groups/$groupId/typing",
            body { put("is_typing", isTyping) }
        )
    }

    suspend fun sendGroupRecording(groupId: String, isRecording: Boolean) {
        client.post(
            "/groups/$groupId/recording",
            body { put("is_recording", isRecording) }
        )
    }

    suspend fun markGroupRead(groupId: String) {
        client.post("/groups/$groupId/read")
    }

    // -------------------------------------------------------------- statuses

    suspend fun myStatuses(): StatusesEnvelope = decode(client.get("/status/mine"))

    suspend fun statusFeed(): StatusesEnvelope = decode(client.get("/status/feed"))

    suspend fun createStatus(
        textContent: String,
        contentType: String = "text",
        backgroundColor: String? = null,
        caption: String? = null,
        privacy: String = "contacts",
        contentUrl: String? = null,
        fontStyle: String? = null,
        durationMs: Long = 0L,
    ): StatusEnvelope = decode(
        client.post(
            "/status",
            body {
                put("content_type", contentType)
                put("text_content", textContent)
                put("privacy", privacy)
                if (backgroundColor != null) put("background_color", backgroundColor)
                if (caption != null) put("caption", caption)
                if (contentUrl != null) put("content_url", contentUrl)
                // Both columns exist on the row; sending them is what makes a text status keep
                // its colour and font for everybody who opens it, not just its author.
                if (fontStyle != null) put("font_style", fontStyle)
                if (durationMs > 0) put("duration", durationMs)
            }
        )
    )

    suspend fun viewStatus(id: String) {
        client.post("/status/$id/view")
    }

    suspend fun statusViews(id: String): ViewsEnvelope = decode(client.get("/status/$id/views"))

    suspend fun deleteStatus(id: String) {
        client.delete("/status/$id")
    }

    suspend fun muteStatus(mutedUserId: String) {
        client.post("/status/mute", body { put("muted_user_id", mutedUserId) })
    }

    suspend fun unmuteStatus(mutedUserId: String) {
        client.delete("/status/mute/$mutedUserId")
    }

    suspend fun mutedStatuses(): MutedEnvelope = decode(client.get("/status/muted"))

    // ----------------------------------------------------------------- calls

    suspend fun turnCredentials(): IceServersEnvelope = decode(client.get("/calls/turn-credentials"))

    suspend fun callHistory(limit: Int = 50): HistoryEnvelope =
        decode(client.get("/calls/history", mapOf("limit" to limit.toString())))

    suspend fun startCall(
        receiverId: String,
        callType: String,
        offerSdp: JsonElement? = null,
    ): CallEnvelope = decode(
        client.post(
            "/calls",
            body {
                put("receiver_id", receiverId)
                put("call_type", callType)
                if (offerSdp != null) put("offer_sdp", offerSdp)
            }
        )
    )

    suspend fun signalCall(id: String, payload: JsonObject) {
        client.post(
            "/calls/$id/signal",
            buildJsonObject { payload.forEach { (key, value) -> put(key, value) } }
        )
    }

    suspend fun answerCall(id: String, answerSdp: JsonElement?) {
        client.post("/calls/$id/answer", body { if (answerSdp != null) put("answer_sdp", answerSdp) })
    }

    suspend fun endCall(id: String, status: String = "ended", duration: Long? = null) {
        client.post(
            "/calls/$id/end",
            body {
                put("status", status)
                if (duration != null) put("duration", duration)
            }
        )
    }

    // ----------------------------------------------------------- group calls

    suspend fun startGroupCall(groupId: String, callType: String): GroupCallEnvelope =
        decode(
            client.post(
                "/group-calls",
                body {
                    put("group_id", groupId)
                    put("call_type", callType)
                }
            )
        )

    suspend fun joinGroupCall(id: String): GroupCallEnvelope =
        decode(client.post("/group-calls/$id/join"))

    suspend fun signalGroupCall(id: String, to: String, payload: JsonObject) {
        client.post(
            "/group-calls/$id/signal",
            buildJsonObject {
                put("to", to)
                payload.forEach { (key, value) -> put(key, value) }
            }
        )
    }

    suspend fun declineGroupCall(id: String) {
        client.post("/group-calls/$id/decline")
    }

    suspend fun leaveGroupCall(id: String) {
        client.post("/group-calls/$id/leave")
    }

    // ----------------------------------------------------------------- media

    suspend fun uploadMedia(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        onProgress: ((Float) -> Unit)? = null
    ): MediaDto =
        decode(client.upload("/media/upload", fileName, mimeType, bytes, onProgress))

    // --------------------------------------------------------------- privacy

    suspend fun privacySettings(): SettingsEnvelope = decode(client.get("/privacy"))

    suspend fun updatePrivacy(
        lastSeenVisibility: String? = null,
        profilePhotoVisibility: String? = null,
        aboutVisibility: String? = null,
        statusVisibility: String? = null,
        readReceipts: Boolean? = null,
        onlineStatusEnabled: Boolean? = null,
    ): SettingsEnvelope = decode(
        client.patch(
            "/privacy",
            body {
                if (lastSeenVisibility != null) put("last_seen_visibility", lastSeenVisibility)
                if (profilePhotoVisibility != null) {
                    put("profile_photo_visibility", profilePhotoVisibility)
                }
                if (aboutVisibility != null) put("about_visibility", aboutVisibility)
                if (statusVisibility != null) put("status_visibility", statusVisibility)
                if (readReceipts != null) put("read_receipts", readReceipts)
                if (onlineStatusEnabled != null) put("online_status_enabled", onlineStatusEnabled)
            }
        )
    )

    // ------------------------------------------------------------- internals

    private fun body(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    /**
     * JSON → DTO, with a parse failure written to [ApiLog].
     *
     * A `SerializationException` here never reaches the screen: every repository wraps its
     * call in `runCatching { … }.getOrDefault(emptyList())`, so a shape mismatch looks exactly
     * like an empty account. Recording it means Settings ▸ Sync check names the type that
     * failed instead of showing a healthy `200` for a request nothing could read.
     */
    private inline fun <reified T> decode(raw: String): T = try {
        NcJson.format.decodeFromString<T>(raw)
    } catch (error: Throwable) {
        ApiLog.record(
            method = "PARSE",
            path = T::class.simpleName ?: "payload",
            status = null,
            millis = 0L,
            error = error.message?.take(200) ?: error.javaClass.simpleName
        )
        throw error
    }
}
