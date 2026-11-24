package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.network.LocalSeal
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Archived conversations, kept on the device.
 *
 * WhatsApp's archive is a private filing decision: nothing about it is visible to the other side,
 * and the worker has no column for it (`/messages/chats` has no `archived` flag). So it lives here
 * next to the other per-device state, and archiving is instant and offline by construction.
 *
 * Archiving hides a chat from the main list but keeps every message, the pin, the mute and the
 * drafts exactly as they were — opening the archive and unarchiving puts the row back untouched.
 *
 * It names the conversations of the account, so the file is sealed for the account that is
 * signed in (see [SealedState]).
 */
class ArchivedStore internal constructor(file: File, seal: () -> LocalSeal?) {

    constructor(context: Context, seal: () -> LocalSeal?) :
        this(File(context.applicationContext.filesDir, "archived.json"), seal)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val state = SealedState(
        file = file,
        place = "archived",
        seal = seal,
        empty = emptySet<String>(),
        isEmpty = { it.isEmpty() },
        decode = { json.decodeFromString(LIST_SERIALIZER, it).toSet() },
        encode = { json.encodeToString(LIST_SERIALIZER, it.toList()) },
    )

    val ids: StateFlow<Set<String>> get() = state.value

    fun isArchived(chatId: String): Boolean = chatId in state.current()

    fun archive(chatId: String) {
        state.update { it + chatId }
    }

    fun unarchive(chatId: String) {
        state.update { it - chatId }
    }

    fun toggle(chatId: String) {
        if (isArchived(chatId)) unarchive(chatId) else archive(chatId)
    }

    fun clearAll() = state.clear()

    private companion object {
        val LIST_SERIALIZER = ListSerializer(String.serializer())
    }
}
