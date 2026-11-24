package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.network.LocalSeal
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Half-written messages, per conversation, on the device.
 *
 * Typing a paragraph, leaving the chat and coming back must not lose it — WhatsApp keeps the
 * text in the composer and shows a red **Draft** on the chat row until it is sent or erased.
 * The server has nowhere to store this (it is not a message and must never be delivered), and
 * it is per-device by definition, so it lives in a small JSON file next to the other caches.
 *
 * Writes are debounced by the view model, not here: this class is the storage, and the caller
 * decides how often it is asked to persist.
 *
 * A draft is a message that has not been sent yet, so the file is sealed for the account that
 * is signed in (see [SealedState]); with nothing to seal with, a draft lives for as long as the
 * app runs and is not written down.
 */
class DraftStore internal constructor(file: File, seal: () -> LocalSeal?) {

    constructor(context: Context, seal: () -> LocalSeal?) :
        this(File(context.applicationContext.filesDir, "drafts.json"), seal)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val state = SealedState(
        file = file,
        place = "drafts",
        seal = seal,
        empty = emptyMap<String, String>(),
        isEmpty = { it.isEmpty() },
        decode = { json.decodeFromString(MAP_SERIALIZER, it) },
        encode = { json.encodeToString(MAP_SERIALIZER, it) },
    )

    val drafts: StateFlow<Map<String, String>> get() = state.value

    fun get(chatId: String): String = state.current()[chatId].orEmpty()

    /** Stores [text] for [chatId]; blank text removes the entry instead of keeping an empty one. */
    fun set(chatId: String, text: String) {
        state.update { drafts -> if (text.isBlank()) drafts - chatId else drafts + (chatId to text) }
    }

    fun clear(chatId: String) = set(chatId, "")

    fun clearAll() = state.clear()

    private companion object {
        val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
