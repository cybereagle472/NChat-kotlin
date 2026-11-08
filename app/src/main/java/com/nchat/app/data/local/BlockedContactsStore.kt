package com.nchat.app.data.local

import android.content.Context
import com.nchat.app.data.model.BlockedContact
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-account local mirror of the server block list. This enables immediate UI gating while
 * the remote block is persisted and enforced by the backend across all devices.
 */
class BlockedContactsStore internal constructor(private val root: File) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "blocked").also {
            // Older versions used one unscoped file. It has no owner metadata, so importing it
            // into whichever account signs in first could leak one account's private list.
            kotlin.runCatching { File(it.parentFile, "blocked.json").delete() }
        }
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Block lists are account-private; never load one account's IDs for another account. */
    fun load(ownerId: String): List<BlockedContact> {
        if (ownerId.isBlank()) return emptyList()
        return runCatching {
            val file = fileFor(ownerId)
            if (!file.exists()) return emptyList()
            json.decodeFromString(LIST_SERIALIZER, file.readText())
        }.getOrDefault(emptyList())
    }

    fun save(ownerId: String, contacts: List<BlockedContact>) {
        if (ownerId.isBlank()) return
        runCatching {
            val file = fileFor(ownerId)
            if (contacts.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(LIST_SERIALIZER, contacts))
        }
    }

    fun clear(ownerId: String) {
        if (ownerId.isNotBlank()) runCatching { fileFor(ownerId).delete() }
    }

    private fun fileFor(ownerId: String): File {
        // Hash opaque identity strings instead of using them as paths.
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ownerId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, "$digest.json")
    }

    private companion object {
        val LIST_SERIALIZER = ListSerializer(BlockedContact.serializer())
    }
}
