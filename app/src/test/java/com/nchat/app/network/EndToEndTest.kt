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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * End-to-end encryption through the life of an installation: signing in,
 * restarting, an expired session, logging out — keeping what the phone
 * holds, or erasing it —, a phone that changes hands, and storage that stops
 * answering. Real files, real keys; only the server and the Android Keystore
 * are stand-ins.
 */
class EndToEndTest {

    private companion object {
        const val WEEK = 7L * 24L * 60L * 60L * 1000L
    }

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

        /** Signed prekeys replaced without a registration — which is how a phone replaces its own, week by week. */
        var replacements = 0
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

            override suspend fun replaceSignedPrekey(identityKey: String, signedPrekey: String, signature: String): KeyDirectory.Replacement {
                if (offline) throw IOException("offline")
                val known = accounts[userId]
                // Only for the identity key that is registered: it never moves an account.
                if (known == null || known.identityKey != identityKey) return KeyDirectory.Replacement.Refused
                known.signedPrekey = signedPrekey
                known.signature = signature
                replacements++
                return KeyDirectory.Replacement.Done
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

        /** What logging out and erasing asks of the server. */
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

        /** What the last sign-in on this phone answered: true when the account started here with new keys. */
        var startedAnew: Boolean? = null

        /** How far this phone's clock has been moved on: time passes here when a test says so. */
        var ahead = 0L

        private val directory = object : KeyDirectory {
            override suspend fun status() = server.directoryFor(account).status()
            override suspend fun register(identityKey: String, signedPrekey: String, signature: String) =
                server.directoryFor(account).register(identityKey, signedPrekey, signature)
            override suspend fun replaceSignedPrekey(identityKey: String, signedPrekey: String, signature: String) =
                server.directoryFor(account).replaceSignedPrekey(identityKey, signedPrekey, signature)
            override suspend fun upload(identityKey: String, oneTimePrekeys: List<String>) =
                server.directoryFor(account).upload(identityKey, oneTimePrekeys)
        }

        /** A new app process on this phone. Its backups take few rounds: these tests are not about how long a guess takes. */
        fun start(): EndToEnd = EndToEnd(
            root = root,
            keyring = keyring,
            directory = directory,
            fetchBundle = { server.bundle(it) },
            onReplaced = { replaced++ },
            report = { what, _ -> reports += what },
            onSecurityChanged = { securityChanges += it },
            backupRounds = 200,
            clock = { System.currentTimeMillis() + ahead },
        )

        /** The same, told nothing about backups — as the app starts it. */
        fun startAsTheAppDoes(): EndToEnd = EndToEnd(
            root = root,
            keyring = keyring,
            directory = directory,
            fetchBundle = { server.bundle(it) },
            onReplaced = { replaced++ },
            report = { what, _ -> reports += what },
            onSecurityChanged = { securityChanges += it },
        )

        fun signIn(userId: String, app: EndToEnd = start()): EndToEnd {
            account = userId
            startedAnew = app.signedIn(userId)
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

        // A tick is kept across a restart, and is gone with everything else when the account is erased from the phone.
        assertTrue(alice.messaging().setVerified("bob", atAlice.key!!, true))
        val again = alicePhone.start().also { it.resume("alice") }
        assertTrue(again.messaging().security("bob")!!.verified)
        again.logOut(LogOut.Erase)
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

    @Test
    fun withNobodySignedInAnAccountThatIsStillOpenInsideSealsAndShowsNothing() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        val received = send(bob, "alice", "for alice")!!
        assertEquals("for alice", read(alice, "bob", received))
        assertNotNull(alice.localSeal())
        assertNotNull(alice.mediaDirectory())

        // Not a log-out: the app's word that no account is signed in, while this one is still open inside.
        alice.resume(null)
        assertNull("nothing to seal with for nobody", alice.localSeal())
        assertNull("nowhere to keep nobody's files", alice.mediaDirectory())
        refusesToSend(alice, "bob", "nobody is signed in")
        assertNull(read(alice, "bob", received))
        assertNull(runBlocking { alice.sync() })

        // Nothing was removed by it: the account is where it was.
        alice.resume("alice")
        assertEquals("for alice", read(alice, "bob", received))
        assertNotNull(alice.localSeal())
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

        // Erasing takes them with the keys, before any network.
        restarted.logOut(LogOut.Erase)
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
        try {
            runBlocking { idle.willSealGroup("g", transport) }
            fail("answered for nobody")
        } catch (_: IllegalStateException) {
        }
        assertNull(idle.revealGroup("g", "bob", "s-1", "c-1", mine = false, envelope = "{}"))
        assertEquals(emptyList<GroupKeyRef>(), idle.missingGroupKeys("g", listOf(GroupRow("bob", "{}"))))
        assertFalse(idle.acceptGroupKey("g", "bob", "chain", "{}"))
        idle.noGroupKey("g", "bob", "chain")
        assertNull(idle.groupSecurity("g"))
        // Nothing was kept for nobody, and there is nothing to forget.
        assertNull(idle.kept("bob", "s-1", "c-1", mine = false))
        assertNull(idle.keptInGroup("g", "bob", "s-1", "c-1", mine = false))
        idle.forget("bob", "s-1", "c-1")
        idle.forgetInGroup("g", "bob", "s-1", "c-1")
    }

    // ---------------------------------------------- logging out and erasing

    @Test
    fun loggingOutAndErasingLeavesNothingAndNamesTheIdentityToWithdraw() {
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

        val withdraw = alice.logOut(LogOut.Erase)
        // Before any network: nothing left on the phone, and nothing readable through what is still in memory.
        assertTrue("files left behind: ${alicePhone.files()}", alicePhone.files().isEmpty())
        assertNull(alicePhone.keyring.entry)
        assertNull(before.reveal("bob", received.id, received.clientId, mine = false, envelope = received.envelope))
        assertNull(readOwn(alice, "bob", sent))
        refusesToSend(alice, "bob", "erased")
        assertNull(runBlocking { alice.sync() })

        // The server is told which keys to stop handing out.
        assertEquals(identity, runBlocking { withdraw() })
        server.withdraw("alice", runBlocking { withdraw() })
        assertNull(server.accounts["alice"])

        // Signing in again starts over: new keys, and the old history stays unreadable.
        val again = alicePhone.signIn("alice")
        assertEquals("and the sign-in says so", true, alicePhone.startedAnew)
        assertNotEquals(identity, server.accounts["alice"]!!.identityKey)
        assertNull(read(again, "bob", received))
        assertNull(readOwn(again, "bob", sent))
        // …also for a process that starts later.
        assertNull(read(alicePhone.start().also { it.resume("alice") }, "bob", received))
    }

    @Test
    fun erasingBeforeAnythingWasOpenedStillErasesAndWithdraws() {
        val server = Server()
        val phone = Phone(server)
        phone.signIn("alice")
        val identity = server.accounts["alice"]!!.identityKey

        // A new process; the user goes straight to "Log out and erase".
        val app = phone.start().also { it.resume("alice") }
        val withdraw = app.logOut(LogOut.Erase)
        assertTrue(phone.files().isEmpty())
        assertNull(phone.keyring.entry)
        assertEquals(identity, runBlocking { withdraw() })

        // A phone that never had keys has nothing to withdraw, and nothing appears by asking.
        val unused = Phone(server)
        val nothing = unused.start().also { it.resume("dave") }.logOut(LogOut.Erase)
        assertNull(runBlocking { nothing() })
        assertTrue(unused.files().isEmpty())
        assertNull(unused.keyring.entry)
    }

    @Test
    fun afterErasingNothingBringsTheAccountBack() {
        // Work that was under way when the user erased the account must not recreate keys.
        val server = Server()
        val phone = Phone(server)
        val app = phone.signIn("alice")
        val bob = Phone(server).signIn("bob")
        assertNotNull(send(app, "bob", "hello"))

        val withdraw = app.logOut(LogOut.Erase)
        server.withdraw("alice", runBlocking { withdraw() })
        assertNull(runBlocking { app.sync() })
        refusesToSend(app, "bob", "erased")
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
        assertEquals("it found its keys: nothing starts anew", false, alicePhone.startedAnew)
        assertEquals("before the session expired", read(alice, "bob", received))
        // …or a new one, started signed out.
        val later = alicePhone.start()
        refusesToSend(later, "bob", "not signed in yet")
        alicePhone.signIn("alice", later)
        assertEquals(false, alicePhone.startedAnew)
        assertEquals("before the session expired", read(later, "bob", received))
        assertEquals("same keys: contacts notice nothing", identity, server.accounts["alice"]!!.identityKey)
        assertEquals(registrations, server.registrations)
        assertEquals(1, alicePhone.keyring.stores)
        val next = send(bob, "alice", "and after")!!
        assertEquals("and after", read(later, "bob", next))
    }

    // ------------------------------------------- logging out, and coming back

    @Test
    fun loggingOutKeepsKeysAndHistoryAndShowsNothingUntilTheAccountIsBack() {
        val server = Server()
        val alicePhone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = alicePhone.signIn("alice")
        assertEquals("a phone that held nothing of the account starts anew", true, alicePhone.startedAnew)
        val sent = send(alice, "bob", "from alice, before")!!
        assertEquals("from alice, before", read(bob, "alice", sent))
        val received = send(bob, "alice", "from bob, before")!!
        assertEquals("from bob, before", read(alice, "bob", received))
        val identity = server.accounts["alice"]!!.identityKey
        val registrations = server.registrations
        val key = alicePhone.keyring.entry!!.key.copyOf()
        val files = alicePhone.files().associate { it.path to it.readBytes().toList() }
        assertTrue(files.isNotEmpty())
        val held = alice.messaging() // work that was under way when the user logged out

        val withdraw = alice.logOut(LogOut.Keep)
        // Locked, before any network: nothing is shown and nothing is sent — not through the
        // app, and not through what it still held.
        assertNull(read(alice, "bob", received))
        assertNull(readOwn(alice, "bob", sent))
        assertNull(held.reveal("bob", received.id, received.clientId, mine = false, envelope = received.envelope))
        refusesToSend(alice, "bob", "logged out")
        assertNull("nothing to seal the rest with", alice.localSeal())
        assertNull("nowhere to open a file into", alice.mediaDirectory())
        assertNull(runBlocking { alice.sync() })
        // Kept: the key, and every file as it was.
        assertEquals("alice", alicePhone.keyring.entry!!.owner)
        assertArrayEquals(key, alicePhone.keyring.entry!!.key)
        assertEquals(files, alicePhone.files().associate { it.path to it.readBytes().toList() })
        // The server is asked to withdraw nothing: the keys it hands out are still this phone's.
        assertNull(runBlocking { withdraw() })
        server.withdraw("alice", runBlocking { withdraw() })
        assertEquals(identity, server.accounts["alice"]!!.identityKey)

        // So what is sent meanwhile is sealed for this phone, and waits for it.
        val meanwhile = send(bob, "alice", "while alice was logged out")!!

        // The same account signs in again — in the same process, or in a new one.
        for (app in listOf(alice, alicePhone.start())) {
            alicePhone.signIn("alice", app)
            assertEquals("it found its keys: nothing starts anew", false, alicePhone.startedAnew)
            assertEquals("from bob, before", read(app, "bob", received))
            assertEquals("from alice, before", readOwn(app, "bob", sent))
            assertEquals("while alice was logged out", read(app, "bob", meanwhile))
            val back = send(app, "bob", "back")!!
            assertEquals("back", read(bob, "alice", back))
            app.logOut(LogOut.Keep)
        }
        assertEquals("same keys: contacts notice nothing", identity, server.accounts["alice"]!!.identityKey)
        assertEquals("and nothing was registered again", registrations, server.registrations)
        assertEquals("one storage key, from first to last", 1, alicePhone.keyring.stores)
        assertEquals(0, alicePhone.replaced)
    }

    @Test
    fun loggingOutBeforeAnythingWasOpenedKeepsEverythingToo() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val received = send(bob, "alice", "nobody there yet")
        assertNull("alice has no keys yet: this one went unsealed", received)
        phone.signIn("alice")
        val sealed = send(bob, "alice", "for alice")!!
        val files = phone.files().associate { it.path to it.readBytes().toList() }

        // A new process; the user goes straight to "Log out".
        val app = phone.start().also { it.resume("alice") }
        assertNull(runBlocking { app.logOut(LogOut.Keep)() })
        assertEquals(files, phone.files().associate { it.path to it.readBytes().toList() })
        assertEquals("alice", phone.keyring.entry!!.owner)
        refusesToSend(app, "bob", "logged out")

        // A phone that never had keys has nothing to keep, and nothing appears by logging out of it.
        val unused = Phone(server)
        assertNull(runBlocking { unused.start().also { it.resume("dave") }.logOut(LogOut.Keep)() })
        assertTrue(unused.files().isEmpty())
        assertNull(unused.keyring.entry)

        phone.signIn("alice", app)
        assertEquals(false, phone.startedAnew)
        assertEquals("for alice", read(app, "bob", sealed))
    }

    @Test
    fun whatALoggedOutAccountLeftIsRemovedWhenAnotherSignsIn() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        val secret = send(bob, "alice", "alice only")!!
        assertEquals("alice only", read(alice, "bob", secret))
        val aliceKey = phone.keyring.entry!!.key.copyOf()
        val aliceFiles = phone.files().map { it.path }.toSet()
        alice.logOut(LogOut.Keep)

        // Somebody else signs in on the phone Alice only logged out of.
        val carol = phone.signIn("carol", alice)
        assertEquals("nothing of carol's was here", true, phone.startedAnew)
        assertEquals("carol", phone.keyring.entry!!.owner)
        assertFalse("a new account gets a new storage key", aliceKey.contentEquals(phone.keyring.entry!!.key))
        assertTrue("alice's files are gone", phone.files().none { it.path in aliceFiles })
        assertNull(carol.messaging().reveal("bob", secret.id, secret.clientId, mine = false, envelope = secret.envelope))

        // Alice, back on it, starts anew as she would on any other phone: only a backup brings her history back.
        carol.logOut(LogOut.Keep)
        val again = phone.signIn("alice", carol)
        assertEquals(true, phone.startedAnew)
        assertNull(read(again, "bob", secret))
        assertNotNull(send(again, "bob", "alice again"))
    }

    @Test
    fun loggingOutNeitherRepairsNorOverwritesStorageThatCannotBeRead() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        val received = send(bob, "alice", "kept")!!
        assertEquals("kept", read(alice, "bob", received))
        val files = phone.files().associate { it.path to it.readBytes().toList() }

        // The keystore does not answer; the user logs out and signs in again.
        phone.keyring.unreadable = true
        val broken = phone.start().also { it.resume("alice") }
        refusesToSend(broken, "bob", "unreadable")
        assertNull(runBlocking { broken.logOut(LogOut.Keep)() })
        assertEquals("not a new start: what is here was not replaced", false, broken.signedIn("alice"))
        refusesToSend(broken, "bob", "still unreadable")
        assertEquals("nothing was written over the stored state", files, phone.files().associate { it.path to it.readBytes().toList() })
        assertEquals(1, phone.keyring.stores)

        // Once it answers again, everything is where it was.
        phone.keyring.unreadable = false
        val recovered = phone.start()
        phone.signIn("alice", recovered)
        assertEquals(false, phone.startedAnew)
        assertEquals("kept", read(recovered, "bob", received))
    }

    @Test
    fun loggingOutCutsABackupAndARestoreShortWithoutCostingWhatThePhoneKeeps() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        val first = send(bob, "alice", "kept")!!
        assertEquals("kept", read(alice, "bob", first))
        val whole = ByteArrayOutputStream().also { alice.backUp(it, "correct horse battery".toCharArray()) }.toByteArray()

        // The user logs out while the file is being written, before anything was read for it.
        val file = object : ByteArrayOutputStream() {
            var left = false
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                if (!left) {
                    left = true
                    alice.logOut(LogOut.Keep)
                }
            }
        }
        try {
            alice.backUp(file, "correct horse battery".toCharArray())
            fail("a backup of an account that logged out meanwhile")
        } catch (expected: IOException) {
            // intended: what was written is not a backup
        }

        // …and while a file is being read.
        phone.signIn("alice", alice)
        val input = object : ByteArrayInputStream(whole) {
            var left = false
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (!left) {
                    left = true
                    alice.logOut(LogOut.Keep)
                }
                return super.read(target, offset, length)
            }
        }
        assertNull("there is nobody to restore it for", alice.restore(input, "correct horse battery".toCharArray()))

        // Neither cost anything: the account comes back to what it had.
        phone.signIn("alice", alice)
        assertEquals("kept", read(alice, "bob", first))
        assertEquals("all of it is here already", 0, alice.restore(ByteArrayInputStream(whole), "correct horse battery".toCharArray()))
    }

    // ------------------------------------ the rest of what is kept on the phone

    @Test
    fun whatElseThePhoneKeepsIsSealedForTheAccountAndGoesWithIt() {
        // Cached conversations, previews, the outbox, drafts: sealed with what `localSeal` hands out.
        val server = Server()
        val phone = Phone(server)
        val words = "kept on the phone".toByteArray(Charsets.UTF_8)

        assertNull("nobody signed in: nothing to seal with, nothing to open", phone.start().localSeal())

        val alice = phone.signIn("alice")
        val stored = alice.localSeal()!!.seal("outbox", words)
        assertArrayEquals(words, alice.localSeal()!!.open("outbox", stored))
        assertFalse("not the storage key itself", stored.toList().windowed(32).any { it == phone.keyring.entry!!.key.toList() })

        // A restart, and a session that expired: the same account opens what it sealed.
        assertArrayEquals(words, phone.start().also { it.resume("alice") }.localSeal()!!.open("outbox", stored))
        val later = phone.start()
        assertNull("started signed out", later.localSeal())
        phone.signIn("alice", later)
        assertArrayEquals(words, later.localSeal()!!.open("outbox", stored))

        // It is not the key of the encrypted history: neither opens what the other sealed.
        // (A sealed file is a heading of 13 bytes — mark, version, which key — and then the box.)
        val box = SealedBox(phone.keyring.entry!!.key)
        val heading = 13
        assertNull(box.open("outbox", stored.copyOfRange(heading, stored.size)))
        assertNull(later.localSeal()!!.open("outbox", stored.copyOf(heading) + box.seal("outbox", words)))

        // Erasing: nothing to seal with any more, and signing in again does not bring the old key back.
        runBlocking { later.logOut(LogOut.Erase)() }
        assertNull(later.localSeal())
        val again = phone.signIn("alice", later)
        assertNull("sealed before it was erased", again.localSeal()!!.open("outbox", stored))
        assertFalse("and it can tell: not sealed with the key it has now", again.localSeal()!!.sealedHere(stored))

        // Somebody else on this phone cannot open it either.
        val mine = again.localSeal()!!.seal("outbox", words)
        val carol = phone.signIn("carol", phone.start())
        assertNull(carol.localSeal()!!.open("outbox", mine))
        assertFalse(carol.localSeal()!!.sealedHere(mine))
    }

    @Test
    fun withStorageThatCannotBeReadThereIsNothingToSealWith() {
        val server = Server()
        val phone = Phone(server)
        val alice = phone.signIn("alice")
        val stored = alice.localSeal()!!.seal("drafts", "typed".toByteArray(Charsets.UTF_8))

        // The keystore does not answer in this run: no seal — and above all no new key.
        phone.keyring.unreadable = true
        val broken = phone.start().also { it.resume("alice") }
        assertNull(broken.localSeal())
        assertNull(broken.localSeal())
        assertEquals("asked once", 1, phone.reports.size)
        assertEquals(1, phone.keyring.stores)

        phone.keyring.unreadable = false
        val recovered = phone.start().also { it.resume("alice") }
        assertEquals("typed", String(recovered.localSeal()!!.open("drafts", stored)!!, Charsets.UTF_8))
    }

    // ------------------------------------------------------------- a backup

    private fun holds(bytes: ByteArray, needle: String): Boolean = String(bytes, Charsets.ISO_8859_1).contains(needle)

    @Test
    fun aBackupBringsTheHistoryBackAfterErasingAndSigningInAgain() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        val passphrase = "correct horse battery"
        val received = send(bob, "alice", "from bob, before")!!
        assertEquals("from bob, before", read(alice, "bob", received))
        val sent = send(alice, "bob", "from alice, before")!!
        assertEquals("from alice, before", readOwn(alice, "bob", sent))

        val file = ByteArrayOutputStream()
        val written = alice.backUp(file, passphrase.toCharArray())!!
        assertEquals("both messages", 2, written.messages)
        assertEquals(1, written.chats)
        assertEquals(0, written.unreadableChats)
        assertFalse(holds(file.toByteArray(), "before"))

        // Logging out and erasing leaves nothing. Signing in again starts with new keys and nothing kept.
        alice.logOut(LogOut.Erase)
        val again = phone.signIn("alice", alice)
        assertNull(read(again, "bob", received))
        assertNull(readOwn(again, "bob", sent))

        assertEquals(2, again.restore(ByteArrayInputStream(file.toByteArray()), passphrase.toCharArray()))
        assertEquals("what was received", "from bob, before", read(again, "bob", received))
        assertEquals("and what was sent", "from alice, before", readOwn(again, "bob", sent))
        assertEquals("a second time adds nothing", 0, again.restore(ByteArrayInputStream(file.toByteArray()), passphrase.toCharArray()))
        fun keysIn(backup: ByteArray): List<String> {
            val keys = ArrayList<String>()
            E2eeBackup.read(ByteArrayInputStream(backup), "alice", passphrase.toCharArray()) { _, key, _ -> keys += key }
            return keys
        }
        // It is kept like any other history from here on: a restart finds it, sealed for the new key.
        val restarted = phone.start().also { it.resume("alice") }
        assertEquals("from bob, before", read(restarted, "bob", received))
        assertTrue(phone.files().none { holds(it.readBytes(), "before") })
        // …and the next backup holds it again, with what came since.
        val after = send(restarted, "bob", "after the restore")!!
        assertEquals("after the restore", read(bob, "alice", after))
        val second = ByteArrayOutputStream()
        assertEquals(3, restarted.backUp(second, passphrase.toCharArray())?.messages)
        // What came back stands in the order it was kept in, ahead of what came since.
        assertEquals(keysIn(file.toByteArray()), keysIn(second.toByteArray()).take(2))
    }

    @Test
    fun aBackupIsRestoredOnlyWithItsPassphraseAndOnlyIntoItsAccount() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        val received = send(bob, "alice", "for alice")!!
        assertEquals("for alice", read(alice, "bob", received))
        val file = ByteArrayOutputStream().also { alice.backUp(it, "correct horse battery".toCharArray()) }.toByteArray()
        alice.logOut(LogOut.Erase)
        val again = phone.signIn("alice", alice)

        fun problemOf(app: EndToEnd, passphrase: String): E2eeBackup.Problem? = try {
            app.restore(ByteArrayInputStream(file), passphrase.toCharArray())
            null
        } catch (failure: E2eeBackup.Failure) {
            failure.problem
        }
        assertEquals(E2eeBackup.Problem.WrongPassphrase, problemOf(again, "correct horse batterx"))
        assertNull("and nothing was restored", read(again, "bob", received))

        val carol = Phone(server).signIn("carol")
        assertEquals(E2eeBackup.Problem.OtherAccount, problemOf(carol, "correct horse battery"))
        assertNull(carol.messaging().reveal("bob", received.id, received.clientId, mine = false, envelope = received.envelope))

        // A passphrase that is none is refused before anything is written.
        val nothing = ByteArrayOutputStream()
        try {
            again.backUp(nothing, "short".toCharArray())
            fail("a backup under a passphrase that is none")
        } catch (expected: IllegalArgumentException) {
            // intended
        }
        assertEquals(0, nothing.size())

        // Nobody signed in: nothing to back up, and nothing to restore into.
        val nobody = Phone(server).start()
        assertNull(nobody.backUp(ByteArrayOutputStream(), "correct horse battery".toCharArray()))
        assertNull(nobody.restore(ByteArrayInputStream(file), "correct horse battery".toCharArray()))

        assertEquals(1, again.restore(ByteArrayInputStream(file), "correct horse battery".toCharArray()))
        assertEquals("for alice", read(again, "bob", received))
    }

    @Test
    fun theAppWritesItsBackupsTheSlowWay() {
        val phone = Phone(Server())
        val app = phone.signIn("alice", phone.startAsTheAppDoes())
        val file = ByteArrayOutputStream().also { app.backUp(it, "correct horse battery".toCharArray()) }.toByteArray()
        var rounds = 0
        for (index in 8..11) rounds = (rounds shl 8) or (file[index].toInt() and 0xff)
        assertEquals(E2eeBackup.ROUNDS, rounds)
    }

    @Test
    fun aBackupCutOffByLoggingOutIsNotABackup() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        assertEquals("kept", read(alice, "bob", send(bob, "alice", "kept")!!))

        // The account leaves while the file is being written: here, before anything was read for it.
        val file = object : ByteArrayOutputStream() {
            var left = false
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                if (!left) {
                    left = true
                    alice.logOut(LogOut.Erase)
                }
            }
        }
        try {
            alice.backUp(file, "correct horse battery".toCharArray())
            fail("a backup of an account that left meanwhile")
        } catch (expected: IOException) {
            // intended
        }
        // What was written does not restore as an empty backup: it is no backup.
        val again = phone.signIn("alice")
        try {
            again.restore(ByteArrayInputStream(file.toByteArray()), "correct horse battery".toCharArray())
            fail("half a file restored as a backup")
        } catch (failure: E2eeBackup.Failure) {
            assertEquals(E2eeBackup.Problem.Damaged, failure.problem)
            assertEquals(0, failure.restored)
        }
    }

    @Test
    fun loggingOutOnceEverythingWasReadStillLeavesAWholeBackup() {
        // The other way round would be the worst moment to refuse: the backup is what is left.
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        val wires = (0 until 30).map { send(bob, "alice", "message $it " + "x".repeat(4_000))!! }
        wires.forEach { assertNotNull(read(alice, "bob", it)) }

        // Leaves when the second portion is written: the heading is the first, a full piece the next.
        val file = object : ByteArrayOutputStream() {
            var writes = 0
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                if (++writes == 2) alice.logOut(LogOut.Erase)
            }
        }
        val written = alice.backUp(file, "correct horse battery".toCharArray())!!
        assertEquals(30, written.messages)
        assertTrue("it left during the writing, not after it", file.writes > 2)
        assertTrue(phone.files().isEmpty())

        val again = phone.signIn("alice")
        assertEquals(30, again.restore(ByteArrayInputStream(file.toByteArray()), "correct horse battery".toCharArray()))
        assertTrue(read(again, "bob", wires[29])!!.startsWith("message 29 "))
    }

    @Test
    fun loggingOutBeforeEverythingWasReadLeavesNoBackup() {
        // Two chats, each longer than a piece of the file. The account leaves while the first
        // is being written: the second can no longer be read, and a file that holds one chat
        // out of two must not be handed over as the backup.
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val carol = Phone(server).signIn("carol")
        val alice = phone.signIn("alice")
        for (index in 0 until 20) {
            assertNotNull(read(alice, "bob", send(bob, "alice", "from bob $index " + "x".repeat(4_000))!!))
            assertNotNull(read(alice, "carol", send(carol, "alice", "from carol $index " + "x".repeat(4_000))!!))
        }
        val file = object : ByteArrayOutputStream() {
            var writes = 0
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                super.write(bytes, offset, length)
                if (++writes == 2) alice.logOut(LogOut.Erase)
            }
        }
        try {
            val written = alice.backUp(file, "correct horse battery".toCharArray())
            fail("a backup of ${written?.chats} chat(s) out of two, passed off as whole")
        } catch (expected: IOException) {
            // intended
        }
        val again = phone.signIn("alice")
        try {
            again.restore(ByteArrayInputStream(file.toByteArray()), "correct horse battery".toCharArray())
            fail("half a backup restored as if it were one")
        } catch (failure: E2eeBackup.Failure) {
            assertEquals(E2eeBackup.Problem.Damaged, failure.problem)
        }
    }

    @Test
    fun aRestoreCutOffByLoggingOutWritesNothing() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        assertEquals("kept", read(alice, "bob", send(bob, "alice", "kept")!!))
        val file = ByteArrayOutputStream().also { alice.backUp(it, "correct horse battery".toCharArray()) }.toByteArray()
        alice.logOut(LogOut.Erase)
        val again = phone.signIn("alice", alice)

        // The account leaves while the file is being read.
        val input = object : ByteArrayInputStream(file) {
            var left = false
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                if (!left) {
                    left = true
                    again.logOut(LogOut.Erase)
                }
                return super.read(target, offset, length)
            }
        }
        assertNull("there is nobody to restore it for", again.restore(input, "correct horse battery".toCharArray()))
        assertEquals("nothing was written back for an account that has left", emptyList<File>(), phone.files())
    }

    @Test
    fun aChatWithNothingLeftInItIsNotInTheBackup() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        val received = send(bob, "alice", "to be deleted")!!
        assertEquals("to be deleted", read(alice, "bob", received))
        alice.messaging().forget("bob", received.id, received.clientId)

        val file = ByteArrayOutputStream()
        val written = alice.backUp(file, "correct horse battery".toCharArray())!!
        assertEquals(0, written.messages)
        assertEquals("a chat that is only a name is not counted as backed up", 0, written.chats)
        assertEquals(0, written.unreadableChats)
        val found = ArrayList<String>()
        E2eeBackup.read(ByteArrayInputStream(file.toByteArray()), "alice", "correct horse battery".toCharArray()) { chat, _, _ -> found += chat }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun aChatThePhoneCanNoLongerNameIsSaidToBeMissingFromTheBackup() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val carol = Phone(server).signIn("carol")
        val alice = phone.signIn("alice")
        assertEquals("from bob", read(alice, "bob", send(bob, "alice", "from bob")!!))
        assertEquals("from carol", read(alice, "carol", send(carol, "alice", "from carol")!!))

        // One chat's file loses the line that says whose it is; what it holds cannot be read
        // without that.
        val log = File(File(File(phone.root, hashedName("alice")), "archive"), hashedName("bob") + ".log")
        val lines = log.readLines().filter { it.isNotBlank() }
        assertEquals("the name, then the message", 2, lines.size)
        log.writeText(lines.drop(1).joinToString("\n", postfix = "\n"))

        val file = ByteArrayOutputStream()
        val written = alice.backUp(file, "correct horse battery".toCharArray())!!
        assertEquals("the other chat is in the backup", 1, written.chats)
        assertEquals(1, written.messages)
        assertEquals("and the one that is not is counted, not passed over", 1, written.unreadableChats)
        val chats = ArrayList<String>()
        E2eeBackup.read(ByteArrayInputStream(file.toByteArray()), "alice", "correct horse battery".toCharArray()) { chat, _, _ -> chats += chat }
        assertEquals(listOf("carol"), chats)
    }

    @Test
    fun aDamagedBackupGivesBackWhatCanStillBeRead() {
        val server = Server()
        val phone = Phone(server)
        val bob = Phone(server).signIn("bob")
        val alice = phone.signIn("alice")
        // Enough for more than one piece of the file.
        val wires = (0 until 40).map { send(bob, "alice", "message $it " + "x".repeat(4_000))!! }
        wires.forEachIndexed { index, wire -> assertTrue(read(alice, "bob", wire)!!.startsWith("message $index ")) }
        val file = ByteArrayOutputStream().also { alice.backUp(it, "correct horse battery".toCharArray()) }.toByteArray()
        assertTrue(file.size > E2eeBackup.HEADING_BYTES + E2eeBackup.SEALED_PIECE_BYTES)
        alice.logOut(LogOut.Erase)
        val again = phone.signIn("alice", alice)
        assertNull("it was tried before the backup was there, and could not be shown", read(again, "bob", wires[0]))

        // The end of the file is damaged; its first piece is whole.
        val damaged = file.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            again.restore(ByteArrayInputStream(damaged), "correct horse battery".toCharArray())
            fail("a damaged file restored without a word")
        } catch (failure: E2eeBackup.Failure) {
            assertEquals(E2eeBackup.Problem.Damaged, failure.problem)
            assertTrue("${failure.restored} restored", failure.restored in 1 until 40)
            assertTrue(read(again, "bob", wires[0])!!.startsWith("message 0 "))
            assertNull("what was in the damaged part is not back", read(again, "bob", wires[39]))
        }
        // The whole file afterwards brings the rest, and nothing twice.
        val before = try {
            again.restore(ByteArrayInputStream(damaged), "correct horse battery".toCharArray())
            -1
        } catch (failure: E2eeBackup.Failure) {
            failure.restored
        }
        assertEquals("the same damaged file again adds nothing", 0, before)
        val rest = again.restore(ByteArrayInputStream(file), "correct horse battery".toCharArray())!!
        assertTrue("$rest more", rest in 1 until 40)
        assertTrue(read(again, "bob", wires[39])!!.startsWith("message 39 "))
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
    fun erasingIsTheWayOutOfStorageThatStaysUnreadable() {
        val server = Server()
        val phone = Phone(server)
        phone.signIn("alice")
        phone.keyring.unreadable = true
        val broken = phone.start().also { it.resume("alice") }
        refusesToSend(broken, "bob", "unreadable")

        val withdraw = broken.logOut(LogOut.Erase)
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

    // ----------------------------------------- the signed prekey is replaced

    @Test
    fun replacingTheSignedPrekeyIsNoticedByNobodyAndCostsNoMessage() {
        val server = Server()
        val alicePhone = Phone(server)
        val bobPhone = Phone(server)
        val alice = alicePhone.signIn("alice")
        val bob = bobPhone.signIn("bob")
        val dave = Phone(server).signIn("dave")

        // Alice and Bob are talking.
        assertEquals("hello alice", read(alice, "bob", send(bob, "alice", "hello alice")!!))
        assertEquals("hello bob", read(bob, "alice", send(alice, "bob", "hello bob")!!))
        val code = bob.messaging().securityCode("alice", bob.messaging().security("alice")!!.key!!)
        val told = bobPhone.securityChanges.size
        // Dave writes to her for the first time, and his message is slow to arrive.
        val slow = send(dave, "alice", "written before")!!
        val identity = server.accounts["alice"]!!.identityKey
        val served = server.accounts["alice"]!!.signedPrekey
        val registrations = server.registrations

        // A week on, Alice's phone looks at the server, as it does every few hours.
        assertEquals(Outcome.Ready, runBlocking { alice.sync() })
        assertEquals("not before the week is up", 0, server.replacements)
        alicePhone.ahead += WEEK
        assertEquals(Outcome.Ready, runBlocking { alice.sync() })
        assertEquals(1, server.replacements)
        assertNotEquals("senders are handed a new signed prekey", served, server.accounts["alice"]!!.signedPrekey)
        assertEquals("under the identity key her contacts know", identity, server.accounts["alice"]!!.identityKey)
        assertEquals("and without a registration, which is what moves an account", registrations, server.registrations)
        assertEquals(0, alicePhone.replaced)

        // Bob notices nothing: the conversation goes on both ways, and he is told of no change of keys.
        assertEquals("still here", read(alice, "bob", send(bob, "alice", "still here")!!))
        assertEquals("so am I", read(bob, "alice", send(alice, "bob", "so am I")!!))
        val aliceAtBob = bob.messaging().security("alice")!!
        assertEquals(identity, aliceAtBob.key)
        assertFalse(aliceAtBob.changed)
        assertEquals("the same security code", code, bob.messaging().securityCode("alice", aliceAtBob.key!!))
        assertEquals(told, bobPhone.securityChanges.size)

        // Dave's message arrives now. It was sealed for the key before, and opens.
        assertEquals("written before", read(alice, "dave", slow))
        // Carol writes for the first time, and is handed the new one.
        val fresh = send(Phone(server).signIn("carol"), "alice", "written after")!!
        assertEquals("written after", read(alice, "carol", fresh))

        // All of it through a restart of Alice's phone, where looking again replaces nothing more.
        val again = alicePhone.start().also { it.resume("alice") }
        assertEquals("written before", read(again, "dave", slow))
        assertEquals("written after", read(again, "carol", fresh))
        assertEquals("once more", read(again, "dave", send(dave, "alice", "once more")!!))
        assertEquals("and back", read(dave, "alice", send(again, "dave", "and back")!!))
        assertEquals(Outcome.Ready, runBlocking { again.sync() })
        assertEquals(1, server.replacements)
        assertEquals(registrations + 1, server.registrations) // carol's
    }

    @Test
    fun aFirstMessageMoreThanAMonthOnItsWayDoesNotOpenAndTheChatMendsItselfWhenItsOwnerWrites() {
        // What replacing the key costs. Erin writes to Alice for the first time — and her message does
        // not reach Alice's phone for six weeks.
        val server = Server()
        val alicePhone = Phone(server)
        val alice = alicePhone.signIn("alice")
        val bob = Phone(server).signIn("bob")
        val erin = Phone(server).signIn("erin")
        val known = send(bob, "alice", "from somebody she is already talking to")!!
        assertEquals("from somebody she is already talking to", read(alice, "bob", known))
        val late = send(erin, "alice", "six weeks on its way")!!

        repeat(6) {
            alicePhone.ahead += WEEK
            assertEquals(Outcome.Ready, runBlocking { alice.sync() })
        }
        assertEquals(6, server.replacements)

        // The key it was sealed for was replaced five weeks ago and has been removed from the phone.
        assertNull(read(alice, "erin", late))
        // Nobody Alice was already talking to is affected — six weeks later, and from storage.
        val again = alicePhone.start().also { it.resume("alice") }
        assertEquals("still talking", read(again, "bob", send(bob, "alice", "still talking")!!))
        assertEquals("from somebody she is already talking to", read(again, "bob", known))
        // And with Erin it mends itself as soon as Alice writes to her.
        assertEquals("hello erin", read(erin, "alice", send(again, "erin", "hello erin")!!))
        assertEquals("hello alice", read(again, "erin", send(erin, "alice", "hello alice")!!))
        assertNull("what she wrote before stays closed", read(again, "erin", late))
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
