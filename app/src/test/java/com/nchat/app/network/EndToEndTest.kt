package com.nchat.app.network

import com.nchat.app.network.E2eeKeys.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * End-to-end encryption through the life of an installation: signing in,
 * restarting, an expired session, signing out, a phone that changes hands,
 * and storage that stops answering. Real files, real keys; only the server
 * and the Android Keystore are stand-ins.
 */
class EndToEndTest {

    @get:Rule val folder = TemporaryFolder()

    private class Keyring : StorageKeyring {
        var entry: StorageKeyring.Entry? = null
        var unreadable = false
        var stores = 0

        override fun load(): StorageKeyring.Entry? {
            if (unreadable) throw IOException("the keystore did not answer")
            return entry
        }

        override fun store(entry: StorageKeyring.Entry) {
            stores++
            this.entry = entry
        }

        override fun clear() {
            entry = null
        }
    }

    /** The server: per account one identity, one signed prekey and a pool of one-time prekeys. */
    private class Server {
        class Keys(var identityKey: String, var signedPrekey: String, var signature: String, val pool: ArrayDeque<String> = ArrayDeque())

        val accounts = HashMap<String, Keys>()
        var registrations = 0
        var offline = false

        fun directoryFor(userId: String) = object : KeyDirectory {
            override suspend fun status(): KeyDirectory.Status {
                if (offline) throw IOException("offline")
                return KeyDirectory.Status(accounts[userId]?.identityKey, accounts[userId]?.pool?.size ?: 0)
            }

            override suspend fun register(identityKey: String, signedPrekey: String, signature: String) {
                if (offline) throw IOException("offline")
                registrations++
                val known = accounts[userId]
                if (known == null || known.identityKey != identityKey) {
                    accounts[userId] = Keys(identityKey, signedPrekey, signature)
                } else {
                    known.signedPrekey = signedPrekey
                    known.signature = signature
                }
            }

            override suspend fun upload(identityKey: String, oneTimePrekeys: List<String>): Boolean {
                if (offline) throw IOException("offline")
                val known = accounts[userId] ?: return false
                if (known.identityKey != identityKey) return false
                known.pool += oneTimePrekeys
                return true
            }
        }

        fun bundle(userId: String): E2eeProtocol.PeerBundle? {
            if (offline) throw IOException("offline")
            val keys = accounts[userId] ?: return null
            return peerBundle(keys.identityKey, keys.signedPrekey, keys.signature, keys.pool.removeFirstOrNull())
        }

        /** What signing out asks of the server. */
        fun withdraw(userId: String, identityKey: String?) {
            if (identityKey != null && accounts[userId]?.identityKey == identityKey) accounts.remove(userId)
        }
    }

    /** One phone: its files and its keystore outlive the app process. */
    private inner class Phone(val server: Server) {
        val root: File = folder.newFolder()
        val keyring = Keyring()
        var replaced = 0
        val reports = ArrayList<String>()
        val securityChanges = ArrayList<String>()

        /** The directory answers for whoever is signed in on this phone. */
        var account = ""

        /** A new app process on this phone. */
        fun start(): EndToEnd = EndToEnd(
            root = root,
            keyring = keyring,
            directory = object : KeyDirectory {
                override suspend fun status() = server.directoryFor(account).status()
                override suspend fun register(identityKey: String, signedPrekey: String, signature: String) =
                    server.directoryFor(account).register(identityKey, signedPrekey, signature)
                override suspend fun upload(identityKey: String, oneTimePrekeys: List<String>) =
                    server.directoryFor(account).upload(identityKey, oneTimePrekeys)
            },
            fetchBundle = { server.bundle(it) },
            onReplaced = { replaced++ },
            report = { what, _ -> reports += what },
            onSecurityChanged = { securityChanges += it },
        )

        fun signIn(userId: String, app: EndToEnd = start()): EndToEnd {
            account = userId
            app.signedIn(userId)
            assertEquals(Outcome.Ready, runBlocking { app.sync() })
            return app
        }

        fun files(): List<File> = root.walkTopDown().filter { it.isFile }.toList()
    }

    private class Wire(val id: String, val clientId: String, val envelope: String)

    private var sequence = 0

    /** Sends through the app's own path; null when the app decided the message goes out unsealed. */
    private fun send(app: EndToEnd, to: String, text: String): Wire? {
        val n = ++sequence
        return runBlocking {
            app.messaging().sendSealed(to, "c-$n", E2eePayload.text(text), { false }) { Wire("s-$n", "c-$n", it.envelope) }
        }
    }

    private fun read(app: EndToEnd, from: String, wire: Wire): String? =
        app.messaging().reveal(from, wire.id, wire.clientId, mine = false, envelope = wire.envelope)?.body

    private fun readOwn(app: EndToEnd, to: String, wire: Wire): String? =
        app.messaging().reveal(to, wire.id, wire.clientId, mine = true, envelope = wire.envelope)?.body

    private fun refusesToSend(app: EndToEnd, to: String, why: String) {
        try {
            send(app, to, "must not leave this phone unsealed")
            fail(why)
        } catch (expected: IllegalStateException) {
            // intended: an error — null would have meant "send it in the clear"
        }
    }

    // ------------------------------------------------------------ the basics

    @Test
    fun twoPhonesConverseAndARestartLosesNothing() {
        val server = Server()
        val alicePhone = Phone(server)
        val bobPhone = Phone(server)
        val alice = alicePhone.signIn("alice")
        val bob = bobPhone.signIn("bob")

        val hello = send(alice, "bob", "hello bob")!!
        assertEquals("hello bob", read(bob, "alice", hello))
        val reply = send(bob, "alice", "hello alice")!!
        assertEquals("hello alice", read(alice, "bob", reply))

        // Both apps are killed and started again with the session still there.
        val registrations = server.registrations
        val aliceAgain = alicePhone.start().also { it.resume("alice") }
        val bobAgain = bobPhone.start().also { it.resume("bob") }
        assertEquals("hello bob", readOwn(aliceAgain, "bob", hello))
        assertEquals("hello alice", read(aliceAgain, "bob", reply))
        assertEquals("hello bob", read(bobAgain, "alice", hello))
        val more = send(aliceAgain, "bob", "still here")!!
        assertEquals("still here", read(bobAgain, "alice", more))
        assertEquals(Outcome.Ready, runBlocking { aliceAgain.sync() })
        assertEquals("a restart registers nothing", registrations, server.registrations)
        assertEquals(1, alicePhone.keyring.stores)
    }

    @Test
    fun theFirstMessageAfterSignInWaitsForRegistrationInsteadOfGoingOutUnsealed() {
        val server = Server()
        val bob = Phone(server).signIn("bob")
        val alicePhone = Phone(server).also { it.account = "alice" }
        val alice = alicePhone.start()
        alice.signedIn("alice") // the sync that follows a sign-in has not run yet

        val first = send(alice, "bob", "sealed from the very first message")
        assertNotNull("both ends have keys by the time it is sent", first)
        assertEquals("sealed from the very first message", read(bob, "alice", first!!))
        assertNotNull(server.accounts["alice"])
    }

    @Test
    fun aContactWithoutKeysStillGetsPlaintext() {
        val server = Server()
        val alice = Phone(server).signIn("alice")
        assertNull("an older app has no keys: the message goes out as before", send(alice, "carol", "hi carol"))
    }

    @Test
    fun securityCodesComeThroughTheAppsOwnDoor() {
        val server = Server()
        val alicePhone = Phone(server)
        val bobPhone = Phone(server)
        val alice = alicePhone.signIn("alice")
        val bob = bobPhone.signIn("bob")
        assertNull(alice.messaging().security("bob"))

        val hello = send(alice, "bob", "hello bob")!!
        assertEquals("hello bob", read(bob, "alice", hello))
        assertEquals("the app is told whose chat changed", listOf("bob"), alicePhone.securityChanges)
        assertEquals(listOf("alice"), bobPhone.securityChanges)

        val atAlice = alice.messaging().security("bob")!!
        val atBob = bob.messaging().security("alice")!!
        assertEquals(server.accounts["bob"]!!.identityKey, atAlice.key)
        assertEquals(server.accounts["alice"]!!.identityKey, atBob.key)
        assertTrue("encryption began a moment ago, by this phone's clock", System.currentTimeMillis() - atAlice.since in 0..60_000)
        val code = alice.messaging().securityCode("bob", atAlice.key!!)
        assertEquals(SecurityCode.DIGITS, code!!.length)
        assertEquals("both phones show the same number", code, bob.messaging().securityCode("alice", atBob.key!!))

        // A tick is kept across a restart, and is gone with everything else at sign-out.
        assertTrue(alice.messaging().setVerified("bob", atAlice.key!!, true))
        val again = alicePhone.start().also { it.resume("alice") }
        assertTrue(again.messaging().security("bob")!!.verified)
        again.signOut()
        assertNull(again.messaging().security("bob"))
        assertNull(alicePhone.signIn("alice").messaging().security("bob"))
    }

    @Test
    fun withNobodySignedInThereIsNothingToVerify() {
        val server = Server()
        val idle = Phone(server).start()
        assertNull(idle.messaging().security("bob"))
        assertNull(idle.messaging().securityCode("bob", "any"))
        assertFalse(idle.messaging().setVerified("bob", "any", true))
        assertFalse(idle.messaging().acceptChange("bob", "any"))
    }

    @Test
    fun withNobodySignedInNothingIsSentAndNothingIsShown() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val idle = phone.start()
        refusesToSend(idle, "bob", "nobody is signed in")
        assertNull(runBlocking { idle.sync() })
        assertEquals("unit of work still runs", 7, idle.messaging().batch { 7 })
        assertNull(idle.messaging().reveal("bob", "s-1", "c-1", mine = false, envelope = "{}"))
        assertTrue("nothing was created for nobody", phone.files().isEmpty())
        assertNull(phone.keyring.entry)

        // The same for an app started with a blank account id.
        val blank = phone.start().also { it.resume("") }
        refusesToSend(blank, "bob", "a blank account is nobody")
        assertNotNull(bob)
    }

    // ---------------------------------------------------------- opened files

    @Test
    fun openedFilesAreKeptWithTheAccountAndGoWhenItGoes() {
        val server = Server()
        val phone = Phone(server)
        Phone(server).signIn("bob")

        assertNull("nobody signed in: nowhere to keep anybody's files", phone.start().mediaDirectory())
        val alice = phone.signIn("alice")
        val media = alice.mediaDirectory()!!
        assertTrue("inside what belongs to the account", media.absolutePath.startsWith(phone.root.absolutePath))
        assertEquals(media, alice.mediaDirectory())
        // A photo that was opened, and the thumbnail of another.
        File(media, "thumbs").mkdirs()
        File(media, "opened.jpg").writeBytes(ByteArray(64) { 7 })
        File(media, "thumbs/t.jpg").writeBytes(ByteArray(8) { 9 })

        // A restart keeps them; opening the account again does not sweep them away.
        val restarted = phone.start().also { it.resume("alice") }
        assertEquals(media, restarted.mediaDirectory())
        assertNotNull(send(restarted, "bob", "still here"))
        assertTrue(File(media, "opened.jpg").isFile)

        // Signing out takes them with the keys, before any network.
        restarted.signOut()
        assertFalse(media.exists())
        assertTrue("files left behind: ${phone.files()}", phone.files().isEmpty())
        assertNull(restarted.mediaDirectory())

        // What one account opened is not there for the next.
        val again = phone.signIn("alice")
        File(again.mediaDirectory()!!.also { it.mkdirs() }, "opened.jpg").writeBytes(ByteArray(64) { 7 })
        val carol = phone.signIn("carol")
        assertNotEquals(again.mediaDirectory(), carol.mediaDirectory())
        assertTrue(phone.files().none { it.name == "opened.jpg" })
    }

    @Test
    fun withNobodySignedInNoFileIsPrepared() {
        val server = Server()
        val idle = Phone(server).start()
        try {
            runBlocking { idle.messaging().willSeal("bob") }
            fail("answered for nobody")
        } catch (_: IllegalStateException) {
            // intended: an error — "false" would have meant "upload it unsealed"
        }
    }

    @Test
    fun withNobodySignedInNothingGoesToAGroupAndNothingOfAGroupIsShown() {
        val idle = Phone(Server()).start().messaging()
        val transport = object : GroupTransport {
            override suspend fun roster(chainId: String?) = GroupRoster(emptyList(), stale = false)
            override suspend fun handOver(chainId: String, keys: List<GroupKey>) = Unit
            override fun rosterChanged(error: Throwable) = false
        }
        try {
            runBlocking { idle.sendGroupSealed("g", "c-1", E2eePayload.text("hello"), transport) { "posted" } }
            fail("answered for nobody")
        } catch (_: IllegalStateException) {
            // intended: an error — null would have meant "send it to the group readable"
        }
        assertNull(idle.revealGroup("g", "bob", "s-1", "c-1", mine = false, envelope = "{}"))
        assertEquals(emptyList<GroupKeyRef>(), idle.missingGroupKeys("g", listOf(GroupRow("bob", "{}"))))
        assertFalse(idle.acceptGroupKey("g", "bob", "chain", "{}"))
        idle.noGroupKey("g", "bob", "chain")
        assertNull(idle.groupSecurity("g"))
    }

    // ------------------------------------------------------------- sign-out

    @Test
    fun signingOutErasesKeysAndHistoryAtOnceAndNamesTheIdentityToWithdraw() {
        val server = Server()
        val alicePhone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = alicePhone.signIn("alice")
        val sent = send(alice, "bob", "for bob's eyes")!!
        val received = send(bob, "alice", "for alice's eyes")!!
        assertEquals("for alice's eyes", read(alice, "bob", received))
        val identity = server.accounts["alice"]!!.identityKey
        val before = alice.messaging()
        assertTrue(alicePhone.files().isNotEmpty())

        val withdraw = alice.signOut()
        // Before any network: nothing left on the phone, and nothing readable through what is still in memory.
        assertTrue("files left behind: ${alicePhone.files()}", alicePhone.files().isEmpty())
        assertNull(alicePhone.keyring.entry)
        assertNull(before.reveal("bob", received.id, received.clientId, mine = false, envelope = received.envelope))
        assertNull(readOwn(alice, "bob", sent))
        refusesToSend(alice, "bob", "signed out")
        assertNull(runBlocking { alice.sync() })

        // The server is told which keys to stop handing out.
        assertEquals(identity, runBlocking { withdraw() })
        server.withdraw("alice", runBlocking { withdraw() })
        assertNull(server.accounts["alice"])

        // Signing in again starts over: new keys, and the old history stays unreadable.
        val again = alicePhone.signIn("alice")
        assertNotEquals(identity, server.accounts["alice"]!!.identityKey)
        assertNull(read(again, "bob", received))
        assertNull(readOwn(again, "bob", sent))
        // …also for a process that starts later.
        assertNull(read(alicePhone.start().also { it.resume("alice") }, "bob", received))
    }

    @Test
    fun signingOutBeforeAnythingWasOpenedStillErasesAndWithdraws() {
        val server = Server()
        val phone = Phone(server)
        phone.signIn("alice")
        val identity = server.accounts["alice"]!!.identityKey

        // A new process; the user goes straight to "sign out".
        val app = phone.start().also { it.resume("alice") }
        val withdraw = app.signOut()
        assertTrue(phone.files().isEmpty())
        assertNull(phone.keyring.entry)
        assertEquals(identity, runBlocking { withdraw() })

        // A phone that never had keys has nothing to withdraw, and nothing appears by asking.
        val unused = Phone(server)
        val nothing = unused.start().also { it.resume("dave") }.signOut()
        assertNull(runBlocking { nothing() })
        assertTrue(unused.files().isEmpty())
        assertNull(unused.keyring.entry)
    }

    @Test
    fun afterSignOutNothingBringsTheAccountBack() {
        // Work that was under way when the user signed out must not recreate keys.
        val server = Server()
        val phone = Phone(server)
        val app = phone.signIn("alice")
        val bob = Phone(server).signIn("bob")
        assertNotNull(send(app, "bob", "hello"))

        val withdraw = app.signOut()
        server.withdraw("alice", runBlocking { withdraw() })
        assertNull(runBlocking { app.sync() })
        refusesToSend(app, "bob", "signed out")
        assertNull(app.messaging().reveal("bob", "s-9", "c-9", mine = false, envelope = "{}"))
        assertTrue(phone.files().isEmpty())
        assertNull(phone.keyring.entry)
        assertNull("no registration slipped in after the withdrawal", server.accounts["alice"])
        assertNotNull(bob)
    }

    // ------------------------------------------------------ expired session

    @Test
    fun anExpiredSessionErasesNothing() {
        // The server refused the refresh token; the user signs in again on the same phone.
        val server = Server()
        val alicePhone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = alicePhone.signIn("alice")
        val received = send(bob, "alice", "before the session expired")!!
        assertEquals("before the session expired", read(alice, "bob", received))
        val identity = server.accounts["alice"]!!.identityKey
        val registrations = server.registrations

        // Same process…
        alicePhone.signIn("alice", alice)
        assertEquals("before the session expired", read(alice, "bob", received))
        // …or a new one, started signed out.
        val later = alicePhone.start()
        refusesToSend(later, "bob", "not signed in yet")
        alicePhone.signIn("alice", later)
        assertEquals("before the session expired", read(later, "bob", received))
        assertEquals("same keys: contacts notice nothing", identity, server.accounts["alice"]!!.identityKey)
        assertEquals(registrations, server.registrations)
        assertEquals(1, alicePhone.keyring.stores)
        val next = send(bob, "alice", "and after")!!
        assertEquals("and after", read(later, "bob", next))
    }

    // ---------------------------------------------- a phone that changes hands

    @Test
    fun anotherAccountSigningInRemovesWhatThePreviousOneLeft() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        val secret = send(bob, "alice", "alice only")!!
        assertEquals("alice only", read(alice, "bob", secret))
        val aliceKey = phone.keyring.entry!!.key
        val aliceFiles = phone.files().map { it.path }.toSet()
        val stillHeld = alice.messaging() // work that was under way when the account changed

        // Alice's session expires and Carol signs in on the same phone — in the same process or a new one.
        for (app in listOf(alice, phone.start())) {
            val carol = phone.signIn("carol", app)
            assertEquals("carol", phone.keyring.entry!!.owner)
            assertFalse("a new account gets a new storage key", aliceKey.contentEquals(phone.keyring.entry!!.key))
            assertTrue("alice's files are gone", phone.files().none { it.path in aliceFiles })
            assertNull(carol.messaging().reveal("bob", secret.id, secret.clientId, mine = false, envelope = secret.envelope))
            assertNull("not from memory either", stillHeld.reveal("bob", secret.id, secret.clientId, mine = false, envelope = secret.envelope))
            val toBob = send(carol, "bob", "hi, carol here")!!
            assertEquals("hi, carol here", read(bob, "carol", toBob))
        }
    }

    @Test
    fun whatAnEarlierAccountCouldNotRemoveIsRemovedOnALaterStart() {
        // Deleting files can fail half-way; what is left must not stay on the phone for good.
        val server = Server()
        val phone = Phone(server)
        phone.signIn("alice")
        val mine = phone.files().map { it.path }.toSet()
        assertTrue(mine.isNotEmpty())
        val leftover = File(phone.root, "0123456789abcdef/vault/entry.bin").also {
            it.parentFile.mkdirs()
            it.writeText("sealed for somebody who is gone")
        }

        phone.start().also { it.resume("alice") }.messaging()
        assertFalse(leftover.exists())
        assertEquals("the account's own state is left alone", mine, phone.files().map { it.path }.toSet())
    }

    // ------------------------------------------------ storage that fails

    @Test
    fun storageThatCannotBeReadIsNeitherBypassedNorOverwritten() {
        val server = Server()
        val alicePhone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = alicePhone.signIn("alice")
        val received = send(bob, "alice", "kept")!!
        assertEquals("kept", read(alice, "bob", received))
        val key = alicePhone.keyring.entry!!.key.copyOf()
        val files = alicePhone.files().associate { it.path to it.readBytes().toList() }

        // The keystore does not answer in this run of the app.
        alicePhone.keyring.unreadable = true
        val broken = alicePhone.start().also { it.resume("alice") }
        refusesToSend(broken, "bob", "a chat that may have been encrypted must not go out in the clear")
        refusesToSend(broken, "carol", "…nor any other: this phone cannot know which chats were encrypted")
        assertNull(read(broken, "bob", received))
        assertNull(runBlocking { broken.sync() })
        assertEquals("asked once, not for every message on the screen", 1, alicePhone.reports.size)
        broken.signedIn("alice") // even a fresh sign-in must not replace what cannot be read
        refusesToSend(broken, "bob", "still unreadable")
        assertEquals("…and once more when the user signed in", 2, alicePhone.reports.size)
        assertEquals("nothing was written over the stored state", files, alicePhone.files().associate { it.path to it.readBytes().toList() })
        assertEquals(1, alicePhone.keyring.stores)

        // Next run it answers again: everything is where it was.
        alicePhone.keyring.unreadable = false
        assertArrayEquals(key, alicePhone.keyring.entry!!.key)
        val recovered = alicePhone.start().also { it.resume("alice") }
        assertEquals("kept", read(recovered, "bob", received))
        assertNotNull(send(recovered, "bob", "back"))
    }

    @Test
    fun signingOutIsTheWayOutOfStorageThatStaysUnreadable() {
        val server = Server()
        val phone = Phone(server)
        phone.signIn("alice")
        phone.keyring.unreadable = true
        val broken = phone.start().also { it.resume("alice") }
        refusesToSend(broken, "bob", "unreadable")

        val withdraw = broken.signOut()
        assertNull("the identity is in storage nobody can read", runBlocking { withdraw() })
        assertTrue(phone.files().isEmpty())
        phone.keyring.unreadable = false
        assertNull(phone.keyring.entry)

        val bob = Phone(server).signIn("bob")
        val fresh = phone.signIn("alice", broken)
        val hello = send(fresh, "bob", "working again")!!
        assertEquals("working again", read(bob, "alice", hello))
    }

    @Test
    fun aStorageKeyOfTheWrongShapeIsDebrisNotAKey() {
        val server = Server()
        val phone = Phone(server)
        phone.keyring.entry = StorageKeyring.Entry("alice", ByteArray(7))
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        assertEquals(32, phone.keyring.entry!!.key.size)
        val hello = send(alice, "bob", "hello")!!
        assertEquals("hello", read(bob, "alice", hello))
    }

    // ------------------------------------------------- one phone per account

    @Test
    fun theSecondPhoneToSignInTakesTheAccountAndTheFirstIsTold() {
        val server = Server()
        val bob = Phone(server).signIn("bob")
        val oldPhone = Phone(server)
        val old = oldPhone.signIn("alice")
        val toOld = send(bob, "alice", "to the old phone")!!
        assertEquals("to the old phone", read(old, "bob", toOld))

        val newPhone = Phone(server)
        val new = newPhone.signIn("alice")
        assertEquals(0, oldPhone.replaced)
        assertEquals(Outcome.Replaced, runBlocking { old.sync() })
        assertEquals(1, oldPhone.replaced)
        assertEquals(0, newPhone.replaced)

        // Being replaced erases nothing: what the old phone had opened, it can still show.
        assertEquals("to the old phone", read(old, "bob", toOld))
        assertNull("…and the new phone cannot: history does not move", read(new, "bob", toOld))
    }

    // ------------------------------------------------------------- bundles

    @Test
    fun aBundleThatIsNotWhatItClaimsIsAnErrorNeverNoKeys() {
        val identity = E2eeProtocol.generateIdentity()
        val prekey = E2eeProtocol.generatePrekeyPair()
        val oneTime = E2eeProtocol.generateOneTimePrekey()
        val good = listOf(
            encodeBase64(identity.identityPublic),
            encodeBase64(prekey.public),
            encodeBase64(E2eeProtocol.signPrekey(prekey.public, identity.identityPrivate)),
        )
        val bundle = peerBundle(good[0], good[1], good[2], encodeBase64(oneTime.public))
        assertArrayEquals(identity.identityPublic, bundle.identityPublic)
        assertArrayEquals(oneTime.public, bundle.oneTimePrekeyPublic)
        assertNull(peerBundle(good[0], good[1], good[2], null).oneTimePrekeyPublic)
        // It is a bundle the protocol accepts.
        assertNotNull(E2eeProtocol.initiate(E2eeProtocol.generateIdentity(), bundle, "me", "them"))

        for (part in 0..3) {
            for (damaged in listOf("", "not base64 !", " ")) {
                val parts = (good + encodeBase64(oneTime.public)).toMutableList().also { it[part] = damaged }
                try {
                    peerBundle(parts[0], parts[1], parts[2], parts[3])
                    fail("part $part = '$damaged' must be refused")
                } catch (expected: IllegalStateException) {
                    // intended
                }
            }
        }
    }

    @Test
    fun aServerThatServesABrokenBundleGetsAnErrorNotPlaintext() {
        val server = Server()
        val alice = Phone(server).signIn("alice")
        // The peer "has keys", but they are not keys: an unusable bundle must not read as "no keys".
        server.accounts["mallory"] = Server.Keys("AAAA", "AAAA", "AAAA")
        refusesToSend(alice, "mallory", "a broken bundle is not permission to send in the clear")
        // Well-formed keys under a signature that does not belong to them.
        val identity = E2eeProtocol.generateIdentity()
        val prekey = E2eeProtocol.generatePrekeyPair()
        val other = E2eeProtocol.generateIdentity()
        server.accounts["mallory"] = Server.Keys(
            encodeBase64(identity.identityPublic),
            encodeBase64(prekey.public),
            encodeBase64(E2eeProtocol.signPrekey(prekey.public, other.identityPrivate)),
        )
        refusesToSend(alice, "mallory", "a bundle that does not verify is not permission either")
    }
}
