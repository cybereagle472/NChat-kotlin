package com.nchat.app.data.local

import com.nchat.app.data.model.OutboxEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OutboxStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun deletedEntryCannotBeResurrectedByAnUploadCompletion() {
        val store = OutboxStore(folder.root.resolve("outbox.json"))
        val original = OutboxEntry(clientId = "one", chatId = "peer", ownerId = "user", text = "hello")
        store.add(original)
        assertTrue(store.replaceIfPresent(original.copy(mediaUrl = "https://example.test/file")))
        assertEquals("https://example.test/file", store.find("one")?.mediaUrl)
        store.remove("one")
        assertFalse(store.replaceIfPresent(original.copy(mediaUrl = "https://example.test/late")))
        assertNull(store.find("one"))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun updatingOneEntryPreservesOthers() {
        val store = OutboxStore(folder.root.resolve("outbox.json"))
        val first = OutboxEntry(clientId = "one", chatId = "peer", text = "first")
        val second = OutboxEntry(clientId = "two", chatId = "peer", text = "second")
        store.add(first)
        store.add(second)
        assertTrue(store.replaceIfPresent(first.copy(mediaUrl = "https://example.test/file")))
        assertEquals(second, store.find("two"))
    }

    @Test
    fun committedEntriesSurviveReopeningAndIgnoreAnInterruptedTemporaryFile() {
        val file = folder.root.resolve("outbox.json")
        val original = OutboxEntry(clientId = "one", chatId = "peer", text = "hello")
        assertTrue(OutboxStore(file).add(original))
        folder.root.resolve("outbox-interrupted.tmp").writeText("incomplete")
        assertEquals(original, OutboxStore(file).find("one"))
    }

    @Test
    fun failedReplacementPreservesPreviouslyCommittedQueue() {
        val file = folder.root.resolve("outbox.json")
        val first = OutboxEntry(clientId = "one", chatId = "peer", text = "first")
        val second = OutboxEntry(clientId = "two", chatId = "peer", text = "second")
        assertTrue(OutboxStore(file).add(first))
        val failing = OutboxStore(file) { _, _ -> false }
        assertFalse(failing.add(second))
        assertFalse(failing.remove("one"))
        assertFalse(failing.replaceIfPresent(first.copy(text = "changed")))
        assertEquals(listOf(first), OutboxStore(file).all())
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.startsWith("outbox-") })
    }

    @Test
    fun corruptQueueIsNotOverwrittenByMutations() {
        val file = folder.root.resolve("outbox.json")
        val corrupt = "{this is not valid JSON"
        file.writeText(corrupt)
        val store = OutboxStore(file)
        assertFalse(store.add(OutboxEntry(clientId = "one", chatId = "peer", text = "hello")))
        assertFalse(store.remove("one"))
        assertEquals(corrupt, file.readText())
    }

    @Test
    fun concurrentAddsDoNotOverwriteEachOther() {
        val store = OutboxStore(folder.root.resolve("outbox.json"))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..20).map { id ->
                executor.submit<Boolean> {
                    store.add(OutboxEntry(clientId = "id-$id", chatId = "peer", text = "hello"))
                }
            }
            assertTrue(futures.all { it.get(10, TimeUnit.SECONDS) })
            assertEquals(20, store.all().size)
        } finally {
            executor.shutdownNow()
        }
    }


    @Test
    fun removingConversationOnlyRemovesThatAccountsQueuedSends() {
        val store = OutboxStore(folder.root.resolve("outbox.json"))
        val target = OutboxEntry(clientId = "target", chatId = "peer", ownerId = "user", text = "pending")
        val otherChat = OutboxEntry(clientId = "other-chat", chatId = "group", ownerId = "user", text = "keep")
        val otherAccount = OutboxEntry(clientId = "other-account", chatId = "peer", ownerId = "other", text = "keep")
        listOf(target, otherChat, otherAccount).forEach { assertTrue(store.add(it)) }

        assertEquals(listOf(target), store.removeConversation("peer", "user"))
        assertEquals(listOf(otherChat, otherAccount), store.all())
    }

    @Test
    fun clearRemovesInterruptedTemporaryCopies() {
        val file = folder.root.resolve("outbox.json")
        val store = OutboxStore(file)
        assertTrue(store.add(OutboxEntry(clientId = "one", chatId = "peer", text = "private")))
        val leftover = folder.root.resolve("outbox-interrupted.tmp")
        leftover.writeText("partial private data")
        assertTrue(store.clear())
        assertFalse(file.exists())
        assertFalse(leftover.exists())
    }
}
