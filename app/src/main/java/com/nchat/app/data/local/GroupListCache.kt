package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.data.model.Group
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The last known group list, kept on disk.
 *
 * Same rule as the chat list and the conversations (`ChatListCache`, `MessageStore`): the
 * Groups tab is a list of things the account already belongs to, so it must not depend on a
 * request succeeding. Without this, opening the app offline showed an empty Groups tab even
 * though the group *rows* were in the cached chat list and their entire message history was on
 * the device.
 *
 * The roster is cached with it (`members`), which is also what lets group bubbles name their
 * author offline instead of saying "NChat user".
 *
 * A cache, never a source of truth: replaced wholesale by the next successful `GET /groups`,
 * cleared on sign-out, and a corrupt file is simply ignored.
 */
class GroupListCache(context: Context) {

    private val file = File(context.applicationContext.filesDir, "cache/groups.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<Group> = runCatching {
        if (!file.exists()) return emptyList()
        json.decodeFromString(LIST_SERIALIZER, file.readText())
            .filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList())

    fun save(groups: List<Group>) {
        // An empty list is not a fact worth persisting: it is what a failed request looks
        // like, and writing it would empty the tab on the next start-up.
        if (groups.isEmpty()) return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(LIST_SERIALIZER, groups))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(Group.serializer())
    }
}
