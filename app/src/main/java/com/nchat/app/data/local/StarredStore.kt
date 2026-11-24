package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.network.LocalSeal
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One starred message.
 *
 * The text is copied in on purpose: **Starred messages** has to keep working after the original
 * has scrolled out of the loaded page, and after the sender deletes it (WhatsApp keeps the star
 * with a "message deleted" body). A server reference would go blank in both cases.
 */
@Serializable
data class StarredMessage(
    val chatId: String,
    val messageId: String,
    val authorName: String = "",
    val preview: String = "",
    /** Epoch millis of the original message, used for ordering the list. */
    val at: Long = 0L
)

/**
 * The device's starred messages (`StarredMessage`), newest first.
 *
 * Like drafts, starring is a private, per-device flag: it is not part of the wire format the
 * worker speaks, and no other member of a group should ever learn that you starred something.
 * Storing it locally means starring works offline and never fails.
 *
 * A star carries a copy of the message's text, so the file is sealed for the account that is
 * signed in (see [SealedState]); with nothing to seal with it is not written down.
 */
class StarredStore internal constructor(file: File, seal: () -> LocalSeal?) {

    constructor(context: Context, seal: () -> LocalSeal?) :
        this(File(context.applicationContext.filesDir, "starred.json"), seal)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val state = SealedState(
        file = file,
        place = "starred",
        seal = seal,
        empty = emptyList<StarredMessage>(),
        isEmpty = { it.isEmpty() },
        decode = { json.decodeFromString(LIST_SERIALIZER, it) },
        encode = { json.encodeToString(LIST_SERIALIZER, it) },
    )

    val items: StateFlow<List<StarredMessage>> get() = state.value

    fun isStarred(chatId: String, messageId: String): Boolean =
        state.current().any { it.chatId == chatId && it.messageId == messageId }

    fun forChat(chatId: String): List<StarredMessage> =
        state.current().filter { it.chatId == chatId }.sortedByDescending { it.at }

    /** Stars [item], or un-stars the same message if it already is — one tap, both directions. */
    fun toggle(item: StarredMessage) {
        state.update { current ->
            if (current.any { it.chatId == item.chatId && it.messageId == item.messageId }) {
                current.filterNot { it.chatId == item.chatId && it.messageId == item.messageId }
            } else {
                (current + item).sortedByDescending { it.at }
            }
        }
    }

    fun unstar(chatId: String, messageId: String) {
        state.update { current -> current.filterNot { it.chatId == chatId && it.messageId == messageId } }
    }

    fun clearAll() = state.clear()

    private companion object {
        val LIST_SERIALIZER = ListSerializer(StarredMessage.serializer())
    }
}
