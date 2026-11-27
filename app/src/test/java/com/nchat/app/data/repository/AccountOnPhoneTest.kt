package com.nchat.app.data.repository

import com.nchat.app.data.local.ArchivedStore
import com.nchat.app.data.local.DraftStore
import com.nchat.app.data.local.LocalState
import com.nchat.app.data.local.OutboxStore
import com.nchat.app.data.local.PinnedMessageStore
import com.nchat.app.data.local.StarredMessage
import com.nchat.app.data.local.StarredStore
import com.nchat.app.data.model.OutboxEntry
import com.nchat.app.network.E2eeKeys.Outcome
import com.nchat.app.network.E2eePayload
import com.nchat.app.network.E2eeProtocol
import com.nchat.app.network.EndToEnd
import com.nchat.app.network.KeyDirectory
import com.nchat.app.network.LocalSeal
import com.nchat.app.network.LogOut
import com.nchat.app.network.StorageKeyring
import com.nchat.app.network.peerBundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The two ways of leaving, as far as the phone is concerned — everything the
 * app keeps for an account, taken together as the app wires it: the keys and
 * the encrypted history, drafts, stars, pins and the archive, the messages
 * still waiting to be sent, and what lies in the open (downloaded pictures,
 * recordings not sent yet).
 *
 *  - "Log out" keeps all of it and shows none of it; the same account finds
 *    it again. A session that expired is the same thing, not asked for.
 *  - "Log out and erase" leaves nothing, and says which key the server
 *    should stop handing out.
 *  - Another account signing in finds nothing of the one before, whichever
 *    way that one left.
 */
class AccountOnPhoneTest {
    @get:Rule val folder = TemporaryFolder()

    private class Keyring : StorageKeyring {
        var entry: StorageKeyring.Entry? = null
        override fun load(): StorageKeyring.Entry? = entry
        override fun store(entry: StorageKeyring.Entry) {
            this.entry = entry
        }
        override fun clear() {
            entry = null
        }
    }

    /** The server's key directory, as much of it as two phones need. */
    private class Server {
        class Keys(var identityKey: String, var signedPrekey: String, var signature: String, val pool: ArrayDeque<String> = ArrayDeque())

        val accounts = HashMap<String, Keys>()

        fun directoryFor(userId: () -> String) = object : KeyDirectory {
            override suspend fun status(): KeyDirectory.Status =
                KeyDirectory.Status(accounts[userId()]?.identityKey, accounts[userId()]?.pool?.size ?: 0)

            override suspend fun register(identityKey: String, signedPrekey: String, signature: String) {
                val known = accounts[userId()]
                if (known == null || known.identityKey != identityKey) {
                    accounts[userId()] = Keys(identityKey, signedPrekey, signature)
                } else {
                    known.signedPrekey = signedPrekey
                    known.signature = signature
                }
            }

            override suspend fun replaceSignedPrekey(identityKey: String, signedPrekey: String, signature: String): KeyDirectory.Replacement {
                val known = accounts[userId()]
                if (known == null || known.identityKey != identityKey) return KeyDirectory.Replacement.Refused
                known.signedPrekey = signedPrekey
                known.signature = signature
                return KeyDirectory.Replacement.Done
            }

            override suspend fun upload(identityKey: String, oneTimePrekeys: List<String>): Boolean {
                val known = accounts[userId()] ?: return false
                if (known.identityKey != identityKey) return false
                known.pool += oneTimePrekeys
                return true
            }
        }

        fun bundle(userId: String): E2eeProtocol.PeerBundle? {
            val keys = accounts[userId] ?: return null
            return peerBundle(keys.identityKey, keys.signedPrekey, keys.signature, keys.pool.removeFirstOrNull())
        }
    }

    /** One phone: the app's two directories and its keystore, and on them what the app builds — wired as `ServiceLocator` wires it. */
    private inner class Phone(val server: Server) {
        val files: File = folder.newFolder()
        val cache: File = folder.newFolder()
        val keyring = Keyring()
        var account = ""

        val endToEnd = EndToEnd(
            root = File(files, "e2ee"),
            keyring = keyring,
            directory = server.directoryFor { account },
            fetchBundle = { server.bundle(it) },
            onReplaced = { },
        )
        private val seal: () -> LocalSeal? = { endToEnd.localSeal() }
        val drafts = DraftStore(File(files, "drafts.json"), seal)
        val stars = StarredStore(File(files, "starred.json"), seal)
        val pins = PinnedMessageStore(File(files, "pinned.json"), seal)
        val archive = ArchivedStore(File(files, "archived.json"), seal)
        val outbox = OutboxStore(File(files, "outbox.json"), seal)
        val offer = RestoreOffer()

        private val local = LocalState(drafts, stars, pins, archive, outbox, files, cache)
        var erased: Boolean? = null

        val onPhone = AccountOnPhone(
            endToEnd = endToEnd,
            reopenLocalState = { local.reopen() },
            eraseLocalState = { erased = local.erase() },
            restoreOffer = offer,
        )

        fun signIn(userId: String, existingAccount: Boolean = true) {
            account = userId
            onPhone.arrived(userId, existingAccount)
            assertEquals(Outcome.Ready, runBlocking { endToEnd.sync() })
        }

        /** What a user leaves on a phone in a day: marks on their chats, a message that has not left yet, and files nobody sealed. */
        fun use() {
            drafts.set("bob", "half a sentence")
            stars.toggle(StarredMessage(chatId = "bob", messageId = "m1", preview = "kept for later"))
            pins.pin("bob", "m1")
            archive.archive("carol")
            assertTrue(outbox.add(OutboxEntry(clientId = "w-1", chatId = "bob", ownerId = account, text = "written with no network")))
            for (path in listOf("media-cache/photo.0", "captures/capture_1.jpg")) put(File(cache, path))
            for (path in listOf("voice-out/w-2.m4a", "blocked/list.json", "cache/chats.json", "cache/messages/bob.json")) put(File(files, path))
            put(File(files, "updates/nchat.apk"))
        }

        private fun put(file: File) {
            file.parentFile.mkdirs()
            file.writeText("something")
        }

        fun marks(): List<Any?> = listOf(drafts.drafts.value, stars.items.value.map { it.messageId }, pins.pins.value, archive.ids.value)

        /** The messages that are waiting to be sent, as far as anybody can read them now. */
        fun waiting(): List<String> = outbox.all().map { it.text }

        fun all(): Set<String> = (files.walkTopDown() + cache.walkTopDown()).filter { it.isFile }.map { it.path }.toSet()

        fun encryptionFiles(): List<File> = File(files, "e2ee").walkTopDown().filter { it.isFile }.toList()
    }

    private class Wire(val id: String, val clientId: String, val envelope: String)

    private var sequence = 0

    private fun send(from: Phone, to: String, text: String): Wire {
        val n = ++sequence
        return runBlocking {
            from.endToEnd.messaging().sendSealed(to, "c-$n", E2eePayload.text(text), { false }) { Wire("s-$n", "c-$n", it.envelope) }
        } ?: error("the message was not sealed")
    }

    private fun read(on: Phone, from: String, wire: Wire): String? =
        on.endToEnd.messaging().reveal(from, wire.id, wire.clientId, mine = false, envelope = wire.envelope)?.body

    private val used = listOf(mapOf("bob" to "half a sentence"), listOf("m1"), mapOf("bob" to "m1"), setOf("carol"))
    private val nothing = listOf(emptyMap<String, String>(), emptyList<String>(), emptyMap<String, String>(), emptySet<String>())

    @Test
    fun loggingOutKeepsEverythingAndShowsNothingUntilTheAccountIsBack() {
        val server = Server()
        val bob = Phone(server).also { it.signIn("bob") }
        val phone = Phone(server)
        phone.signIn("alice")
        val received = send(bob, "alice", "for alice")
        assertEquals("for alice", read(phone, "bob", received))
        phone.use()
        assertEquals(used, phone.marks())
        assertEquals(listOf("written with no network"), phone.waiting())
        val identity = server.accounts["alice"]!!.identityKey
        val kept = phone.all()
        // The first sign-in on a phone clears it for the account. From here on nothing may be erased.
        assertEquals(true, phone.erased)
        phone.erased = null

        val withdraw = phone.onPhone.left(LogOut.Keep)
        assertNull("nothing is withdrawn: the keys the server hands out are still this phone's", runBlocking { withdraw() })
        assertEquals("nothing is shown", nothing, phone.marks())
        assertNull(read(phone, "bob", received))
        // Nothing is sent for an account that has left, and nothing is written into its queue.
        assertEquals(emptyList<String>(), phone.waiting())
        assertFalse(phone.outbox.add(OutboxEntry(clientId = "w-3", chatId = "bob", ownerId = "alice", text = "after logging out")))
        assertEquals("and nothing was removed", kept, phone.all())
        assertNull("nothing was erased, so there is nothing to report", phone.erased)
        assertEquals("alice", phone.keyring.entry!!.owner)

        phone.signIn("alice")
        assertEquals("the same account finds its marks", used, phone.marks())
        assertEquals("and its messages", "for alice", read(phone, "bob", received))
        assertEquals("the one that had not left yet is still waiting to be sent", listOf("written with no network"), phone.waiting())
        assertEquals(kept, phone.all())
        assertNull(phone.erased)
        assertEquals("same keys: contacts notice nothing", identity, server.accounts["alice"]!!.identityKey)
        // It kept everything, so there is nothing to ask about a backup — even if a message does not open.
        phone.offer.cannotShow()
        assertFalse(phone.offer.asking.value)
    }

    @Test
    fun erasingLeavesNothingAndNamesTheKeyToWithdraw() {
        val server = Server()
        val bob = Phone(server).also { it.signIn("bob") }
        val phone = Phone(server)
        phone.signIn("alice")
        val received = send(bob, "alice", "for alice")
        assertEquals("for alice", read(phone, "bob", received))
        phone.use()
        val identity = server.accounts["alice"]!!.identityKey
        assertTrue(phone.encryptionFiles().isNotEmpty())

        val withdraw = phone.onPhone.left(LogOut.Erase)
        // Before any network: the keys, the history, the marks, and what lay in the open.
        assertNull(phone.keyring.entry)
        assertEquals(emptyList<File>(), phone.encryptionFiles())
        assertEquals(nothing, phone.marks())
        assertNull(read(phone, "bob", received))
        assertEquals("only what was never the account's is left", setOf(File(phone.files, "updates/nchat.apk").path), phone.all())
        assertEquals("and the phone could remove all of it", true, phone.erased)
        assertEquals("the server is told which keys to stop handing out", identity, runBlocking { withdraw() })

        // Signing in again starts anew: nothing comes back by itself, and that is when a backup is asked about.
        phone.signIn("alice")
        assertEquals(nothing, phone.marks())
        assertEquals("what was waiting to be sent was erased with the rest: it is not sent later", emptyList<String>(), phone.waiting())
        assertNull(read(phone, "bob", received))
        assertFalse("not before there is a message it cannot show", phone.offer.asking.value)
        phone.offer.cannotShow()
        assertTrue(phone.offer.asking.value)
    }

    @Test
    fun erasingGoesOnPastWhatThePhoneWillNotRemoveAndSaysSo() {
        val phone = Phone(Server())
        phone.signIn("alice")
        phone.use()
        // Where the queue is kept there is now something that does not go away like a file does.
        val queue = File(phone.files, "outbox.json")
        assertTrue(queue.delete())
        val stuck = File(queue, "stuck").also {
            it.parentFile.mkdirs()
            it.writeText("cannot be removed")
        }

        phone.onPhone.left(LogOut.Erase)
        assertEquals("it is reported, not passed over", false, phone.erased)
        // …and everything else went all the same.
        assertNull(phone.keyring.entry)
        assertEquals(nothing, phone.marks())
        assertEquals(setOf(stuck.path, File(phone.files, "updates/nchat.apk").path), phone.all())
    }

    @Test
    fun anotherAccountFindsNothingOfTheOneThatOnlyLoggedOut() {
        val server = Server()
        val bob = Phone(server).also { it.signIn("bob") }
        val phone = Phone(server)
        phone.signIn("alice")
        val received = send(bob, "alice", "alice only")
        assertEquals("alice only", read(phone, "bob", received))
        phone.use()
        phone.onPhone.left(LogOut.Keep)
        // A save that was still on its way when Alice logged out.
        phone.drafts.set("bob", "typed a moment before logging out")

        phone.signIn("zoe")
        assertEquals("nothing of alice's is shown to zoe", nothing, phone.marks())
        assertEquals("or sent in her name", emptyList<String>(), phone.waiting())
        assertNull(read(phone, "bob", received))
        assertEquals("zoe", phone.keyring.entry!!.owner)
        // …or left lying on the phone she now uses: her own encryption state, and what was never an account's.
        val left = phone.all()
        assertTrue(left.toString(), left.all { it.startsWith(File(phone.files, "e2ee").path) || it.endsWith("updates/nchat.apk") })

        // What Zoe does is kept for Zoe.
        phone.drafts.set("bob", "zoe's own")
        assertTrue(phone.outbox.add(OutboxEntry(clientId = "z-1", chatId = "bob", ownerId = "zoe", text = "zoe's, not sent yet")))
        phone.onPhone.left(LogOut.Keep)
        phone.signIn("zoe")
        assertEquals(mapOf("bob" to "zoe's own"), phone.drafts.drafts.value)
        assertEquals(listOf("zoe's, not sent yet"), phone.waiting())

        // Alice, back on it, starts anew like on any other phone — and is asked about a backup.
        phone.onPhone.left(LogOut.Keep)
        phone.signIn("alice")
        assertEquals(nothing, phone.marks())
        assertEquals(emptyList<String>(), phone.waiting())
        assertNull(read(phone, "bob", received))
        phone.offer.cannotShow()
        assertTrue(phone.offer.asking.value)
    }

    @Test
    fun theQuestionAboutABackupIsForAnAccountFromBeforeAndLeavesWithIt() {
        val server = Server()
        // Somebody who has just signed up has nothing from before, on any phone.
        val created = Phone(server)
        created.signIn("dave", existingAccount = false)
        created.offer.cannotShow()
        assertFalse(created.offer.asking.value)

        // An account from before on a phone that is new to it: asked — and no longer once it has left.
        val phone = Phone(server)
        phone.signIn("alice")
        phone.offer.cannotShow()
        assertTrue(phone.offer.asking.value)
        for (how in LogOut.values()) {
            phone.onPhone.left(how)
            assertFalse("$how", phone.offer.asking.value)
            phone.offer.cannotShow()
            assertFalse(phone.offer.asking.value)
            phone.signIn("alice")
        }
        assertNotNull(phone.keyring.entry)
    }

    @Test
    fun withoutAnAccountIdNothingIsOpenedAndNothingIsErased() {
        val server = Server()
        val phone = Phone(server)
        phone.signIn("alice")
        phone.use()
        phone.onPhone.left(LogOut.Keep)
        val kept = phone.all()

        // A sign-in the server answered without saying who it is: nobody's things are opened, and nobody's are removed.
        phone.onPhone.arrived(null, existingAccount = true)
        phone.onPhone.arrived(" ", existingAccount = true)
        assertEquals(nothing, phone.marks())
        assertEquals(kept, phone.all())
        phone.offer.cannotShow()
        assertFalse(phone.offer.asking.value)
    }
}
