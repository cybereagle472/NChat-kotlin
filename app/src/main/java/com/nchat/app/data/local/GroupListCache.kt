package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.data.model.Group
import com.nchat.app.network.LocalSeal
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
 *
 * It names the groups, their members and what was last said in them, so the file is sealed for
 * the account that is signed in (see [LocalFiles]); with nothing to seal with it is neither
 * read nor written.
 */
class GroupListCache internal constructor(private val file: File, seal: () -> LocalSeal?) {

    constructor(context: Context, seal: () -> LocalSeal?) :
        this(File(context.applicationContext.filesDir, "cache/groups.json"), seal)

    private val files = LocalFiles(seal)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): List<Group> = runCatching {
        val stored = files.read(file, PLACE) as? Stored.Readable ?: return emptyList()
        val groups = json.decodeFromString(LIST_SERIALIZER, stored.text)
            .filter { it.id.isNotBlank() }
        // Written by a build before sealing: read once, sealed from then on.
        if (stored.plain) files.write(file, PLACE, stored.text)
        groups
    }.getOrDefault(emptyList())

    fun save(groups: List<Group>) {
        // An empty list is not a fact worth persisting: it is what a failed request looks
        // like, and writing it would empty the tab on the next start-up.
        if (groups.isEmpty()) return
        runCatching { files.write(file, PLACE, json.encodeToString(LIST_SERIALIZER, groups)) }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(Group.serializer())
        const val PLACE = "groups"
    }
}
