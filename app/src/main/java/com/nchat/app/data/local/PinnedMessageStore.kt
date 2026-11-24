package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.network.LocalSeal
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One pinned message per conversation.
 *
 * The server has no concept of a pinned message (`messages` has `reply_to` and nothing else), and
 * a pin is a *personal* bookmark anyway — in a group, pinning for yourself must not pin for
 * everybody. One id per chat is enough: WhatsApp shows a single banner and replaces it when you
 * pin another message, which is exactly what a `Map<chatId, messageId>` says.
 *
 * The id is resolved against the messages already loaded, so a pin whose message has been deleted
 * or paged out simply shows nothing rather than a blank banner.
 *
 * It names the conversations of the account, so the file is sealed for the account that is
 * signed in (see [SealedState]).
 */
class PinnedMessageStore internal constructor(file: File, seal: () -> LocalSeal?) {

    constructor(context: Context, seal: () -> LocalSeal?) :
        this(File(context.applicationContext.filesDir, "pinned.json"), seal)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val state = SealedState(
        file = file,
        place = "pinned",
        seal = seal,
        empty = emptyMap<String, String>(),
        isEmpty = { it.isEmpty() },
        decode = { json.decodeFromString(MAP_SERIALIZER, it) },
        encode = { json.encodeToString(MAP_SERIALIZER, it) },
    )

    val pins: StateFlow<Map<String, String>> get() = state.value

    fun pinnedId(chatId: String): String? = state.current()[chatId]

    fun pin(chatId: String, messageId: String) {
        state.update { it + (chatId to messageId) }
    }

    fun unpin(chatId: String) {
        state.update { it - chatId }
    }

    /** Returns true when the message is now pinned, false when it was unpinned. */
    fun toggle(chatId: String, messageId: String): Boolean {
        val nowPinned = state.current()[chatId] != messageId
        if (nowPinned) pin(chatId, messageId) else unpin(chatId)
        return nowPinned
    }

    fun clearAll() = state.clear()

    private companion object {
        val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
