package com.nchat.app.data.local

import com.nchat.app.data.model.BlockedContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BlockedContactsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun blockListsAreIsolatedByAccountAndClearedIndependently() {
        val store = BlockedContactsStore(folder.root.resolve("blocked"))
        val firstAccount = listOf(BlockedContact("peer-a", "Peer A", blockedAt = 100L))
        val secondAccount = listOf(BlockedContact("peer-b", "Peer B", blockedAt = 200L))

        store.save("account-a", firstAccount)
        store.save("account-b", secondAccount)

        assertEquals(firstAccount, store.load("account-a"))
        assertEquals(secondAccount, store.load("account-b"))
        store.clear("account-a")
        assertTrue(store.load("account-a").isEmpty())
        assertEquals(secondAccount, store.load("account-b"))
    }

    @Test
    fun blankAccountNeverReadsOrWritesPrivateState() {
        val store = BlockedContactsStore(folder.root.resolve("blocked"))
        store.save("", listOf(BlockedContact("peer", "Peer")))
        assertTrue(store.load("").isEmpty())
        assertTrue(folder.root.resolve("blocked").listFiles().orEmpty().isEmpty())
    }
}
