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
import org.junit.Test
import java.io.IOException

/**
 * One account, the server's key directory, and one or two phones signed in
 * to it: who registers, who stands down, and what must be on the phone
 * before the server hears about it.
 */
class E2eeKeysTest {

    private class MemoryVault : SecretVault {
        val entries = LinkedHashMap<String, String>()
        var writes = 0
        var failReads = false

        @Synchronized
        override fun read(key: String): String? {
            if (failReads) throw IOException("storage is gone")
            return entries[key]
        }

        @Synchronized
        override fun contains(key: String): Boolean = entries.containsKey(key)

        @Synchronized
        override fun write(key: String, value: String?, durable: Boolean) {
            assertTrue("key material must be on stable storage before it is used", durable)
            writes++
            if (value == null) entries.remove(key) else entries[key] = value
        }
    }

    private class Refused : Exception("the server said no")

    /** The server: one identity key and one pool of one-time prekeys for the account. */
    private class Directory : KeyDirectory {
        var identityKey: String? = null
        var signedPrekey: String? = null
        var signature: String? = null
        val pool = ArrayList<String>()
        var statusRequests = 0
        var registrations = 0
        var uploads = 0
        var failure: Exception? = null

        /** Runs just before an upload is stored — the moment to check what the phone has saved. */
        var beforeUpload: (List<String>) -> Unit = {}

        /** Runs while a status request is in flight. */
        var duringStatus: () -> Unit = {}

        override suspend fun status(): KeyDirectory.Status {
            failure?.let { throw it }
            statusRequests++
            duringStatus()
            return KeyDirectory.Status(identityKey, pool.size)
        }

        override suspend fun register(identityKey: String, signedPrekey: String, signature: String) {
            failure?.let { throw it }
            registrations++
            if (identityKey != this.identityKey) pool.clear()
            this.identityKey = identityKey
            this.signedPrekey = signedPrekey
            this.signature = signature
        }

        override suspend fun upload(identityKey: String, oneTimePrekeys: List<String>): Boolean {
            failure?.let { throw it }
            beforeUpload(oneTimePrekeys)
            if (identityKey != this.identityKey) return false
            uploads++
            pool += oneTimePrekeys
            return true
        }
    }

    /** One installation of the app, signed in to the account. */
    private class Phone(val directory: Directory) {
        val vault = MemoryVault()
        val store = E2eeStore(vault)
        var replaced = 0
        var now = 1_000_000L

        /** A new object over the same storage — what an app restart produces. */
        fun start() = E2eeKeys(vault, store, directory, onReplaced = { replaced++ }, clock = { now })
    }

    private fun sync(keys: E2eeKeys): Outcome = runBlocking { keys.sync() }

    private fun identityKey(keys: E2eeKeys): String? = keys.identity()?.let { encodeBase64(it.identityPublic) }

    // ---------------------------------------------------------- first sync

    @Test
    fun firstSyncRegistersKeysThatVerifyAndPublishesOneTimePrekeys() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        assertNull("nothing is usable before the server has confirmed it", keys.identity())

        // Every private half must already be saved when its public half reaches the server.
        var checked = 0
        directory.beforeUpload = { publicKeys ->
            for (publicKey in publicKeys) {
                assertNotNull(phone.store.oneTimeKey(E2eeProtocol.keyReference(decodeBase64(publicKey)!!)))
                checked++
            }
        }
        assertEquals(Outcome.Ready, sync(keys))
        assertEquals(1, directory.registrations)
        assertEquals(50, directory.pool.size)
        assertEquals(50, checked)
        assertEquals(50, directory.pool.toSet().size)
        assertEquals(directory.identityKey, identityKey(keys))
        assertEquals(directory.signedPrekey, encodeBase64(keys.signedPrekey()!!.public))

        // What the directory now serves is a bundle a stranger can start a session with —
        // which proves the signature — and that this phone can answer.
        val bundle = E2eeProtocol.PeerBundle(
            identityPublic = decodeBase64(directory.identityKey!!)!!,
            signedPrekeyPublic = decodeBase64(directory.signedPrekey!!)!!,
            signedPrekeySignature = decodeBase64(directory.signature!!)!!,
            oneTimePrekeyPublic = decodeBase64(directory.pool.first())!!,
        )
        val stranger = E2eeProtocol.generateIdentity()
        val session = E2eeProtocol.initiate(stranger, bundle, "stranger", "me")
        val id = "c-1".toByteArray()
        val envelope = E2eeProtocol.seal(session, "hello".toByteArray(), id)
        val opened = E2eeProtocol.openFirst(
            keys.identity()!!, keys.signedPrekey()!!, phone.store.oneTimeKey(envelope.otk!!), envelope, "me", "stranger", id,
        )
        assertEquals("hello", opened?.plaintext?.let { String(it) })

        // Private keys stay on the phone: nothing the server holds contains them.
        val served = listOfNotNull(directory.identityKey, directory.signedPrekey, directory.signature) + directory.pool
        assertFalse(served.contains(encodeBase64(keys.identity()!!.identityPrivate)))
        assertFalse(served.contains(encodeBase64(keys.signedPrekey()!!.private)))
    }

    @Test
    fun keysAreNotUsableUntilTheServerHasConfirmedThem() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        directory.failure = IOException("offline")
        try {
            sync(keys)
            fail("a failed registration must be reported")
        } catch (expected: IOException) {
            // intended
        }
        assertNull(keys.identity())
        assertNull("…also after a restart", phone.start().identity())

        directory.failure = null
        assertEquals(Outcome.Ready, sync(keys))
        assertNotNull(keys.identity())
        // The same keys that were generated before the failure: nothing is thrown away by a retry.
        val restarted = phone.start()
        assertEquals(identityKey(keys), identityKey(restarted))
        assertEquals("a restart needs no network to use confirmed keys", 1, directory.registrations)
    }

    // ------------------------------------------------------------ launches

    @Test
    fun anotherLaunchConfirmsWithoutRegisteringOrUploadingAgain() {
        val directory = Directory()
        val phone = Phone(directory)
        sync(phone.start())
        val identity = directory.identityKey
        val writes = phone.vault.writes

        val relaunched = phone.start()
        assertEquals(Outcome.Ready, sync(relaunched))
        assertEquals(Outcome.Ready, sync(relaunched))
        assertEquals(1, directory.registrations)
        assertEquals(1, directory.uploads)
        assertEquals(identity, directory.identityKey)
        assertEquals("nothing changed, so nothing is written", writes, phone.vault.writes)
    }

    @Test
    fun oneTimePrekeysAreToppedUpWhenTheyRunLow() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)

        repeat(30) { directory.pool.removeAt(0) } // 20 left: still enough
        sync(keys)
        assertEquals(20, directory.pool.size)

        directory.pool.removeAt(0) // 19 left: refill to the target
        sync(keys)
        assertEquals(50, directory.pool.size)
        assertEquals(2, directory.uploads)
        for (publicKey in directory.pool) {
            assertNotNull(phone.store.oneTimeKey(E2eeProtocol.keyReference(decodeBase64(publicKey)!!)))
        }
    }

    @Test
    fun aDirectoryThatLostTheKeysGetsTheSameOnesBack() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        val identity = directory.identityKey
        directory.identityKey = null
        directory.pool.clear()

        assertEquals(Outcome.Ready, sync(phone.start()))
        assertEquals(identity, directory.identityKey)
        assertEquals(50, directory.pool.size)
        assertEquals(0, phone.replaced)
    }

    // ------------------------------------------------- one phone per account

    @Test
    fun signingInOnAnotherPhoneTakesTheAccountOver() {
        val directory = Directory()
        val old = Phone(directory)
        val oldKeys = old.start()
        sync(oldKeys)
        val oldIdentity = directory.identityKey

        val new = Phone(directory)
        val newKeys = new.start()
        newKeys.claim() // the user signs in on the new phone
        assertEquals(Outcome.Ready, sync(newKeys))
        assertNotEquals(oldIdentity, directory.identityKey)
        assertEquals(directory.identityKey, identityKey(newKeys))
        assertEquals("the old phone's one-time prekeys are gone with its identity", 50, directory.pool.size)
        val pool = ArrayList(directory.pool)

        // The old phone finds out the next time it looks, and stands down.
        assertEquals(Outcome.Replaced, sync(oldKeys))
        assertEquals(1, old.replaced)
        assertNull(oldKeys.identity())
        assertNull("…and stays down after a restart, before any network", old.start().identity())
        assertEquals("it registered nothing", 2, directory.registrations)
        assertEquals("it uploaded nothing", pool, directory.pool)
        assertEquals(directory.identityKey, identityKey(newKeys))

        // The new phone is untouched by all of it.
        assertEquals(Outcome.Ready, sync(new.start()))
        assertEquals(0, new.replaced)
    }

    @Test
    fun withoutASignInAPhoneNeverTakesAnAccountBack() {
        // The fault this design removes: two phones registering on every
        // launch, each discarding the other's keys.
        val directory = Directory()
        val first = Phone(directory)
        sync(first.start())
        val second = Phone(directory)
        second.start().also { it.claim() }.let(::sync)
        val owner = directory.identityKey

        repeat(3) {
            assertEquals(Outcome.Replaced, sync(first.start()))
            assertEquals(Outcome.Ready, sync(second.start()))
        }
        assertEquals(owner, directory.identityKey)
        assertEquals(2, directory.registrations)
        assertEquals(3, first.replaced)
    }

    @Test
    fun aSignInIsHonouredEvenIfTheAppIsKilledBeforeItCouldRegister() {
        val directory = Directory()
        val old = Phone(directory)
        sync(old.start())

        val new = Phone(directory)
        val interrupted = new.start()
        interrupted.claim()
        directory.failure = IOException("connection lost right after sign-in")
        try {
            sync(interrupted)
            fail("the failure must be reported")
        } catch (expected: IOException) {
            // intended
        }
        directory.failure = null

        // Next launch: no new sign-in, yet this is still the phone the user chose.
        val relaunched = new.start()
        assertEquals(Outcome.Ready, sync(relaunched))
        assertEquals(directory.identityKey, identityKey(relaunched))
        assertEquals(0, new.replaced)
        // The claim is spent: from now on this phone, too, yields to a newer sign-in.
        val newest = Phone(directory)
        newest.start().also { it.claim() }.let(::sync)
        assertEquals(Outcome.Replaced, sync(new.start()))
    }

    @Test
    fun signingInAgainOnTheSamePhoneKeepsItsKeys() {
        // The session expired, nothing was erased, the user signs in again.
        val directory = Directory()
        val phone = Phone(directory)
        sync(phone.start())
        val identity = directory.identityKey
        val pool = ArrayList(directory.pool)

        val again = phone.start()
        again.claim()
        assertEquals(Outcome.Ready, sync(again))
        assertEquals(identity, directory.identityKey)
        assertEquals("same identity, so existing chats and prekeys carry on", pool, directory.pool)
        assertEquals(1, directory.registrations)
    }

    @Test
    fun anUploadTheServerRefusesMeansThePhoneWasReplacedMeanwhile() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        directory.pool.clear()
        // Another phone registers between this phone's status check and its upload.
        directory.beforeUpload = { directory.identityKey = "somebody else" }

        assertEquals(Outcome.Replaced, sync(keys))
        assertEquals(1, phone.replaced)
        assertNull(keys.identity())
        assertTrue(directory.pool.isEmpty())
    }

    // --------------------------------------------- a message waiting to go

    @Test
    fun ensureRegisteredRetriesTheNetworkButAcceptsARefusal() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()

        // No connection: the message waits; it is not sent unsealed.
        directory.failure = IOException("offline")
        try {
            runBlocking { keys.ensureRegistered() }
            fail("an unreachable server must be reported")
        } catch (expected: IOException) {
            // intended
        }

        // The server answers and refuses: this phone has no keys, as before it knew of any …
        directory.failure = Refused()
        runBlocking { keys.ensureRegistered() }
        assertNull(keys.identity())
        // … and is not asked again for every message,
        directory.failure = null
        runBlocking { keys.ensureRegistered() }
        assertEquals(0, directory.registrations)
        // only after a pause.
        phone.now += 60_000
        runBlocking { keys.ensureRegistered() }
        assertEquals(1, directory.registrations)
        assertNotNull(keys.identity())
    }

    @Test
    fun aReplacedPhoneSendsNothing() {
        val directory = Directory()
        val old = Phone(directory)
        val oldKeys = old.start()
        sync(oldKeys)
        Phone(directory).start().also { it.claim() }.let(::sync)

        try {
            runBlocking { oldKeys.ensureRegistered() }
            fail("a replaced phone must not go on to send unsealed")
        } catch (expected: IllegalStateException) {
            // intended
        }
        assertEquals(1, old.replaced)
    }

    // ------------------------------------------------------------ sign-out

    @Test
    fun signOutLearnsWhichIdentityToWithdrawEvenAfterTheVaultIsErased() {
        val directory = Directory()
        val unused = Phone(directory).start()
        unused.close()
        assertNull("a phone that never had keys has nothing to withdraw", runBlocking { unused.published() })

        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        keys.close()
        phone.vault.entries.clear() // sign-out erases the phone
        assertEquals(directory.identityKey, runBlocking { keys.published() })

        // A restart in between changes nothing: the next object reads the same identity before the erasure.
        val other = Phone(directory)
        other.start().also { it.claim() }.let(::sync)
        val restarted = other.start()
        restarted.close()
        other.vault.entries.clear()
        assertEquals(directory.identityKey, runBlocking { restarted.published() })
    }

    @Test
    fun theIdentityToWithdrawIsOnlyAnsweredOnceNothingCanRegisterAnyMore() {
        // Sign-out while a sync is in flight: the withdrawal must come after anything that sync still sends.
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        val order = ArrayList<String>()
        val inFlight = java.util.concurrent.CountDownLatch(1)
        val signedOut = java.util.concurrent.CountDownLatch(1)
        directory.duringStatus = {
            inFlight.countDown()
            signedOut.await()
            synchronized(order) { order += "status answered" }
        }
        val syncing = Thread {
            try {
                sync(keys)
            } catch (expected: IllegalStateException) {
                // intended: the sync finds the object closed and stops
            }
        }.also { it.start() }
        inFlight.await()

        keys.close()
        val withdrawing = Thread {
            runBlocking { keys.published() }
            synchronized(order) { order += "withdraw" }
        }.also { it.start() }
        Thread.sleep(100) // the withdrawal has to wait; give it every chance to jump the queue
        signedOut.countDown()
        syncing.join()
        withdrawing.join()

        assertEquals(listOf("status answered", "withdraw"), order)
        assertEquals("the sync that was in flight registered nothing", 0, directory.registrations)
    }

    @Test
    fun aClosedObjectWritesNothingAndReportsNothing() {
        // Signing out erases the vault; a sync still in flight must not put keys back.
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        Phone(directory).start().also { it.claim() }.let(::sync) // would make the next sync report "replaced"
        directory.pool.clear()

        keys.close()
        val entries = LinkedHashMap(phone.vault.entries)
        val asked = directory.statusRequests
        for (attempt in listOf<() -> Unit>({ sync(keys) }, { keys.claim() })) {
            try {
                attempt()
            } catch (expected: IllegalStateException) {
                // intended (claim() simply does nothing)
            }
        }
        assertEquals(entries, phone.vault.entries)
        assertEquals("a closed object does not even ask", asked, directory.statusRequests)
        assertEquals(0, phone.replaced)
        assertTrue(directory.pool.isEmpty())

        // Closed while the request is in flight: whatever the answer, it arrives too late to act on —
        // neither "you were replaced" …
        val owner = directory.identityKey
        val late = Phone(directory)
        val lateKeys = late.start()
        directory.duringStatus = { lateKeys.close() }
        try {
            sync(lateKeys)
            fail("a sync that outlives the sign-out must stop")
        } catch (expected: IllegalStateException) {
            // intended
        }
        assertEquals(0, late.replaced)
        // … nor "go ahead and register": a signed-out phone must not take the account over.
        val claiming = Phone(directory)
        val claimingKeys = claiming.start().also { it.claim() }
        directory.duringStatus = { claimingKeys.close() }
        try {
            sync(claimingKeys)
            fail("a sync that outlives the sign-out must stop")
        } catch (expected: IllegalStateException) {
            // intended
        }
        assertEquals(owner, directory.identityKey)
    }

    // ------------------------------------------------------------- storage

    @Test
    fun aFailedReadIsNotMistakenForAPhoneWithoutKeys() {
        val directory = Directory()
        val phone = Phone(directory)
        sync(phone.start())
        val identity = directory.identityKey
        val stored = phone.vault.entries["e2ee.keys"]

        phone.vault.failReads = true
        val keys = phone.start()
        try {
            sync(keys)
            fail("storage trouble must be reported, not papered over with new keys")
        } catch (expected: IOException) {
            // intended
        }
        assertEquals(stored, phone.vault.entries["e2ee.keys"])
        assertEquals(1, directory.registrations)

        phone.vault.failReads = false
        assertEquals(Outcome.Ready, sync(keys))
        assertEquals(identity, identityKey(keys))
    }

    @Test
    fun damagedKeysAreReplacedByNewOnesThatMustEarnTheirPlace() {
        val directory = Directory()
        val phone = Phone(directory)
        sync(phone.start())
        val identity = directory.identityKey

        for (damaged in listOf("", "1.only.three.fields", "1.!!.!!.!!.!!.!!", "7" + phone.vault.entries["e2ee.keys"]!!.drop(1))) {
            phone.vault.entries["e2ee.keys"] = damaged
            val keys = phone.start()
            assertNull(keys.identity())
            assertNull(keys.signedPrekey())
            // New keys are not the registered ones, and no sign-in backs them: this phone stands down.
            assertEquals(Outcome.Replaced, sync(keys))
            assertEquals(identity, directory.identityKey)
        }
        // A sign-in is what puts new keys in place.
        val keys = phone.start()
        keys.claim()
        assertEquals(Outcome.Ready, sync(keys))
        assertNotEquals(identity, directory.identityKey)
        assertArrayEquals(decodeBase64(directory.identityKey!!), keys.identity()!!.identityPublic)
    }
}
