package com.nchat.app.network

import com.nchat.app.network.E2eeKeys.Outcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * to it: who registers, who stands down, what must be on the phone before
 * the server hears about it — and the signed prekey being replaced week by
 * week without any of that changing.
 */
class E2eeKeysTest {

    private companion object {
        const val HOUR = 60L * 60L * 1000L
        const val DAY = 24L * HOUR
        const val WEEK = 7L * DAY
        const val MONTH = 30L * DAY

        /** The id the first messages of these tests are sealed for; which one does not matter. */
        val MESSAGE = "c-first".toByteArray()

        /** Where the signed prekeys are stored, next to the record older builds know. */
        const val SIGNED_PREKEYS = "e2ee.spk"
        const val RECORD = "e2ee.keys"
    }

    private class MemoryVault : SecretVault {
        val entries = LinkedHashMap<String, String>()
        var writes = 0
        var failReads = false

        /** The write with this number, counted from the first one ever, is cut off: it changes nothing and fails. */
        var cutOffWrite = 0

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
            if (writes == cutOffWrite) throw IOException("the write was cut off")
            if (value == null) entries.remove(key) else entries[key] = value
        }
    }

    private class Refused : Exception("the server said no")

    /**
     * The server: one identity key, one signed prekey and one pool of
     * one-time prekeys for the account. Like the real one, it takes a signed
     * prekey only with the signature of the identity key it comes with.
     */
    private class Directory : KeyDirectory {
        var identityKey: String? = null
        var signedPrekey: String? = null
        var signature: String? = null
        val pool = ArrayList<String>()
        var statusRequests = 0
        var registrations = 0
        var uploads = 0
        var failure: Exception? = null

        /** Requests to replace the signed prekey, and how many of them the server carried out. */
        var replacementRequests = 0
        var replacements = 0

        /** A server from before this app: it has no way to replace a signed prekey. */
        var cannotReplace = false

        /** Runs just before an upload is stored — the moment to check what the phone has saved. */
        var beforeUpload: (List<String>) -> Unit = {}

        /** Runs while a status request is in flight. */
        var duringStatus: () -> Unit = {}

        /** Runs when a replacement arrives, before the server has looked at it; it is given the new public key. */
        var beforeReplacement: (String) -> Unit = {}

        /** Runs when the server has stored a replacement and the phone has not heard yet. */
        var afterReplacement: () -> Unit = {}

        private fun verified(identityKey: String, signedPrekey: String, signature: String) {
            val verifies = E2eeProtocol.verifyPrekeySignature(decodeBase64(signedPrekey)!!, decodeBase64(signature)!!, decodeBase64(identityKey)!!)
            if (!verifies) throw Refused()
        }

        override suspend fun status(): KeyDirectory.Status {
            failure?.let { throw it }
            statusRequests++
            duringStatus()
            return KeyDirectory.Status(identityKey, pool.size)
        }

        override suspend fun register(identityKey: String, signedPrekey: String, signature: String) {
            failure?.let { throw it }
            verified(identityKey, signedPrekey, signature)
            registrations++
            if (identityKey != this.identityKey) pool.clear()
            this.identityKey = identityKey
            this.signedPrekey = signedPrekey
            this.signature = signature
        }

        override suspend fun replaceSignedPrekey(identityKey: String, signedPrekey: String, signature: String): KeyDirectory.Replacement {
            failure?.let { throw it }
            replacementRequests++
            beforeReplacement(signedPrekey)
            if (cannotReplace) return KeyDirectory.Replacement.Unavailable
            verified(identityKey, signedPrekey, signature)
            // Only for the identity key that is registered: it never moves an account.
            if (identityKey != this.identityKey) return KeyDirectory.Replacement.Refused
            this.signedPrekey = signedPrekey
            this.signature = signature
            replacements++
            afterReplacement()
            return KeyDirectory.Replacement.Done
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

    /** The signed prekey with this public half (base64), as far as [keys] still holds it. */
    private fun held(keys: E2eeKeys, publicKey: String?): E2eeProtocol.PrekeyPair? =
        keys.signedPrekey(E2eeProtocol.keyReference(decodeBase64(publicKey!!)!!))

    /**
     * A first message from somebody who has just been handed the account's
     * keys by [directory] — without a one-time prekey, so the signed prekey
     * is all that stands between the message and whoever recorded it.
     * Making it fails when the signed prekey does not carry the signature of
     * the identity key: no app would seal for such keys.
     */
    private fun firstMessage(directory: Directory): E2eeProtocol.Envelope {
        val bundle = E2eeProtocol.PeerBundle(
            identityPublic = decodeBase64(directory.identityKey!!)!!,
            signedPrekeyPublic = decodeBase64(directory.signedPrekey!!)!!,
            signedPrekeySignature = decodeBase64(directory.signature!!)!!,
            oneTimePrekeyPublic = null,
        )
        val session = E2eeProtocol.initiate(E2eeProtocol.generateIdentity(), bundle, "stranger", "me")
        return E2eeProtocol.seal(session, "hello".toByteArray(), MESSAGE)
    }

    /** Whether the phone opens [message]: it has to hold the signed prekey the message names, and be the account's phone. */
    private fun opens(keys: E2eeKeys, message: E2eeProtocol.Envelope): Boolean {
        val identity = keys.identity() ?: return false
        val prekey = keys.signedPrekey(message.spk!!) ?: return false
        return E2eeProtocol.openFirst(identity, prekey, null, message, "me", "stranger", MESSAGE) != null
    }

    /** The record as the builds in use write it: a flag and five fields, and nothing about when the signed prekey was made. */
    private fun recordOfTheBuildsInUse(
        identity: E2eeProtocol.IdentityKeyPair,
        prekey: E2eeProtocol.PrekeyPair,
        signature: ByteArray = E2eeProtocol.signPrekey(prekey.public, identity.identityPrivate),
        registered: Boolean = true,
    ): String = (if (registered) "1" else "0") +
        listOf(identity.identityPrivate, identity.identityPublic, prekey.private, prekey.public, signature).joinToString("") { "." + encodeBase64(it) }

    private fun fails(what: String, block: () -> Unit) {
        try {
            block()
        } catch (expected: IOException) {
            return
        }
        fail(what)
    }

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
        assertNotNull("the phone holds the signed prekey the server was given", held(keys, directory.signedPrekey))

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
            keys.identity()!!, keys.signedPrekey(envelope.spk!!)!!, phone.store.oneTimeKey(envelope.otk!!), envelope, "me", "stranger", id,
        )
        assertEquals("hello", opened?.plaintext?.let { String(it) })

        // Private keys stay on the phone: nothing the server holds contains them.
        val served = listOfNotNull(directory.identityKey, directory.signedPrekey, directory.signature) + directory.pool
        assertFalse(served.contains(encodeBase64(keys.identity()!!.identityPrivate)))
        assertFalse(served.contains(encodeBase64(held(keys, directory.signedPrekey)!!.private)))
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
        phone.now += WEEK // …and its signed prekey has come due meanwhile: that changes nothing either
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
        assertEquals(0, directory.replacementRequests)
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

    // ------------------------------------------ replacing the signed prekey

    @Test
    fun theSignedPrekeyIsReplacedOnceItIsAWeekOld() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        val identity = directory.identityKey
        val first = directory.signedPrekey
        val pool = ArrayList(directory.pool)
        val early = firstMessage(directory)

        // Not before its time, however often the phone looks.
        phone.now += WEEK - 1
        assertEquals(Outcome.Ready, sync(keys))
        assertEquals(Outcome.Ready, sync(phone.start()))
        assertEquals(0, directory.replacementRequests)
        assertEquals(first, directory.signedPrekey)

        phone.now += 1
        assertEquals(Outcome.Ready, sync(keys))
        assertEquals(1, directory.replacements)
        assertNotEquals(first, directory.signedPrekey)
        // Nothing else moved: the same identity key, the same one-time prekeys — and no registration, which
        // is the request that takes an account over.
        assertEquals(identity, directory.identityKey)
        assertEquals(identity, identityKey(keys))
        assertEquals(pool, directory.pool)
        assertEquals(1, directory.registrations)
        assertEquals(0, phone.replaced)

        // What the server hands out now carries the identity key's signature, and this phone opens what is
        // sealed with it; what was sealed for the one before opens as well. After a restart too.
        val late = firstMessage(directory)
        for (app in listOf(keys, phone.start())) {
            assertTrue(opens(app, late))
            assertTrue("the one before is kept for what is still on its way", opens(app, early))
        }
        // The private half stays here.
        val served = listOfNotNull(directory.identityKey, directory.signedPrekey, directory.signature) + directory.pool
        assertFalse(served.contains(encodeBase64(held(keys, directory.signedPrekey)!!.private)))

        // Once is enough until another week has gone by.
        val restarted = phone.start()
        phone.now += WEEK - 1
        assertEquals(Outcome.Ready, sync(restarted))
        assertEquals(1, directory.replacementRequests)
        phone.now += 1
        assertEquals(Outcome.Ready, sync(restarted))
        assertEquals(2, directory.replacements)
        assertEquals(1, directory.registrations)
    }

    @Test
    fun theNewSignedPrekeyIsOnThePhoneBeforeTheServerHearsOfIt() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        phone.now += WEEK

        // From the moment the server has it, senders are handed it. So at the moment the request arrives,
        // the phone must already open what is sealed with it — this process, and one that starts right now.
        var checked = 0
        directory.beforeReplacement = { publicKey ->
            assertNotNull("in use before the server was told", held(keys, publicKey))
            assertNotNull("on stable storage before the server was told", held(phone.start(), publicKey))
            checked++
        }
        assertEquals(Outcome.Ready, sync(keys))
        assertEquals(1, checked)
    }

    @Test
    fun aReplacementTheServerNeverHeardOfIsSentAgainNotMadeAgain() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        val first = directory.signedPrekey
        val early = firstMessage(directory)
        phone.now += WEEK

        var offered: String? = null
        directory.beforeReplacement = {
            offered = it
            throw IOException("the connection was lost on the way")
        }
        fails("a replacement that did not happen must be reported") { sync(keys) }
        assertEquals(first, directory.signedPrekey)
        assertTrue("nothing was given up for it", opens(keys, firstMessage(directory)))

        // The app is killed. The next start offers that same key: a new one for every attempt would leave
        // the phone holding keys the server never handed out.
        var again: String? = null
        directory.beforeReplacement = { again = it }
        val restarted = phone.start()
        assertEquals(Outcome.Ready, sync(restarted))
        assertEquals(offered, again)
        assertEquals(offered, directory.signedPrekey)
        assertEquals(1, directory.replacements)
        assertTrue(opens(restarted, firstMessage(directory)))
        assertTrue(opens(restarted, early))
    }

    @Test
    fun aReplacementWhoseAnswerWasLostIsAlreadyInUse() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        val first = directory.signedPrekey
        val early = firstMessage(directory)
        phone.now += WEEK

        // The server stores the new key; the phone never hears.
        directory.afterReplacement = { throw IOException("the answer was lost") }
        fails("an answer that never came must be reported") { sync(keys) }
        val second = directory.signedPrekey
        assertNotEquals(first, second)

        // Senders are handed the new key from that moment. The phone opens what they seal with it, at once
        // and after a restart, as well as what was sealed for the one before.
        val sealedForTheNew = firstMessage(directory)
        for (app in listOf(keys, phone.start())) {
            assertTrue(opens(app, sealedForTheNew))
            assertTrue(opens(app, early))
        }

        // The next look finishes the job with the same key, and then there is nothing left to do. It
        // does so even when the clock was put back meanwhile and the old key no longer looks a week old:
        // what was begun is finished.
        directory.afterReplacement = {}
        phone.now -= DAY
        val restarted = phone.start()
        assertEquals(Outcome.Ready, sync(restarted))
        assertEquals(second, directory.signedPrekey)
        assertEquals(2, directory.replacementRequests)
        assertEquals(Outcome.Ready, sync(restarted))
        assertEquals(Outcome.Ready, sync(phone.start()))
        assertEquals(2, directory.replacementRequests)
        assertTrue(opens(restarted, sealedForTheNew))
        assertTrue(opens(restarted, early))
    }

    @Test
    fun whicheverWriteIsCutOffTheReplacementEndsWhole() {
        // The phone writes several times while it replaces the key. Each write in turn is cut off — the
        // battery dies, storage is full — and each time what is left must open everything, and the next
        // look must finish what was begun.
        var cutOff = 1
        while (true) {
            val directory = Directory()
            val phone = Phone(directory)
            val keys = phone.start()
            sync(keys)
            val identity = directory.identityKey
            val early = firstMessage(directory)
            phone.now += WEEK
            val offered = LinkedHashSet<String>()
            directory.beforeReplacement = { offered += it }

            phone.vault.cutOffWrite = phone.vault.writes + cutOff
            val interrupted = try {
                sync(keys)
                false
            } catch (expected: IOException) {
                true
            }
            phone.vault.cutOffWrite = 0
            if (!interrupted) break // the replacement has fewer writes than that: every one has had its turn

            // The process that was interrupted, and the one that starts after the phone came back.
            for (app in listOf(keys, phone.start())) {
                assertEquals("write $cutOff", identity, identityKey(app))
                assertTrue("write $cutOff: what the server hands out", opens(app, firstMessage(directory)))
                assertTrue("write $cutOff: what was sealed before", opens(app, early))
            }
            val restarted = phone.start()
            assertEquals(Outcome.Ready, sync(restarted))
            assertEquals("write $cutOff: one new key reached the server, however often it took", 1, offered.size)
            assertEquals("write $cutOff", offered.single(), directory.signedPrekey)
            assertNotEquals("write $cutOff", early.spk, firstMessage(directory).spk)
            for (app in listOf(restarted, phone.start())) {
                assertTrue("write $cutOff, finished: what the server hands out", opens(app, firstMessage(directory)))
                assertTrue("write $cutOff, finished: what was sealed before", opens(app, early))
            }
            assertEquals(
                "write $cutOff, finished: an older build finds the signed prekey the server hands out",
                recordOfTheBuildsInUse(restarted.identity()!!, held(restarted, directory.signedPrekey)!!, decodeBase64(directory.signature!!)!!),
                phone.vault.entries[RECORD],
            )
            assertEquals(identity, directory.identityKey)
            assertEquals(1, directory.registrations)
            assertEquals(0, phone.replaced)
            // Settled: looking again asks for nothing.
            val requests = directory.replacementRequests
            assertEquals(Outcome.Ready, sync(phone.start()))
            assertEquals("write $cutOff", requests, directory.replacementRequests)
            cutOff++
        }
        assertTrue("a replacement is more than one write, or this test has tested nothing", cutOff > 2)
    }

    @Test
    fun aReplacedSignedPrekeyIsRemovedOnceItsMonthIsOverAndThatOfTheOneReplacedAfterIt() {
        // Two phones. Each replaces its first key after a week and its second a week later. The next
        // replacement comes one millisecond before the second key's month is up on the one phone, and
        // exactly when it is up on the other.
        for (beforeTheMonthIsUp in listOf(1L, 0L)) {
            val directory = Directory()
            val phone = Phone(directory)
            val keys = phone.start()
            sync(keys)
            val first = directory.signedPrekey
            val firstPrivate = encodeBase64(held(keys, first)!!.private)
            val early = firstMessage(directory)
            fun stored() = phone.vault.entries.values.any { it.contains(firstPrivate) }

            phone.now += WEEK
            sync(keys)
            val second = directory.signedPrekey
            assertNotEquals(first, second)
            assertTrue(stored())
            phone.now += WEEK
            sync(keys)
            assertEquals(2, directory.replacements)
            val secondReplacedAt = phone.now

            phone.now = secondReplacedAt + MONTH - beforeTheMonthIsUp
            sync(keys)
            assertEquals(3, directory.replacements)
            if (beforeTheMonthIsUp > 0) {
                // The first key's own month has been over for a week. It is still there: of the keys
                // replaced that long ago it is the last one, and that one is always kept.
                for (app in listOf(keys, phone.start())) assertTrue("the key replaced after it is a moment short of its month", opens(app, early))
                assertTrue(stored())
                // The replacement after that one finds both months over.
                phone.now += WEEK
                sync(keys)
                assertEquals(4, directory.replacements)
            }
            for (app in listOf(keys, phone.start())) {
                assertNull("its month is over, and so is that of the key replaced after it", held(app, first))
                assertFalse(opens(app, early))
            }
            assertFalse("removed from storage, not merely passed over", stored())
            // Only that one: the key replaced after it is the one kept now, and what the server hands out opens.
            assertNotNull(held(phone.start(), second))
            assertTrue(opens(phone.start(), firstMessage(directory)))
        }
    }

    @Test
    fun aClockThatWasWrongForAnHourDoesNotCostTheSignedPrekeyThatWasInUse() {
        // A phone that starts with its clock years behind — it happens when the battery ran flat — and
        // is given the time by the network an hour later. Both looks replace the key: the first because
        // the key is dated later than "now", the second because the new key looks years old. The key
        // that was in use until that hour is now dated years back, and must not be removed for that:
        // a first message sealed for it this morning may still be on its way.
        val directory = Directory()
        val phone = Phone(directory)
        phone.now += 3000 * DAY
        val keys = phone.start()
        sync(keys)
        val inUse = directory.signedPrekey
        val early = firstMessage(directory)
        val rightTime = phone.now

        phone.now = rightTime - 2000 * DAY
        sync(keys)
        assertEquals(1, directory.replacements)
        phone.now = rightTime + HOUR
        sync(keys)
        assertEquals(2, directory.replacements)
        for (app in listOf(keys, phone.start())) {
            assertNotNull(held(app, inUse))
            assertTrue(opens(app, early))
        }

        // Nor a week later, at the next replacement. It goes when the key replaced after it has had its month.
        phone.now += WEEK
        sync(keys)
        assertEquals(3, directory.replacements)
        assertTrue(opens(phone.start(), early))
        phone.now = rightTime + HOUR + MONTH
        sync(keys)
        assertEquals(4, directory.replacements)
        assertNull(held(phone.start(), inUse))
    }

    @Test
    fun noMoreThanADozenReplacedSignedPrekeysAreKept() {
        // A clock that keeps being set back makes for a replacement at every look. The phone must not
        // pile up keys for it: the one in use and the twelve replaced last, and no more.
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        val served = arrayListOf(directory.signedPrekey!!)
        val privateHalves = arrayListOf(encodeBase64(held(keys, directory.signedPrekey)!!.private))
        repeat(20) {
            phone.now -= 1_000
            sync(keys)
            served += directory.signedPrekey!!
            privateHalves += encodeBase64(held(keys, directory.signedPrekey)!!.private)
        }
        assertEquals(21, served.toSet().size)
        for (app in listOf(keys, phone.start())) {
            assertEquals(served.takeLast(13), served.filter { held(app, it) != null })
        }
        assertEquals(
            "storage holds no more than those either",
            privateHalves.takeLast(13),
            privateHalves.filter { half -> phone.vault.entries.values.any { it.contains(half) } },
        )
    }

    @Test
    fun aClockThatWasPutRightDoesNotPutTheReplacementOff() {
        // Keys made while the phone's clock was a year ahead would otherwise count as new for a year.
        val directory = Directory()
        val phone = Phone(directory)
        phone.now += 400 * DAY
        val keys = phone.start()
        sync(keys)
        phone.now -= 400 * DAY

        assertEquals(Outcome.Ready, sync(keys))
        assertEquals(1, directory.replacements)
        // The new one was made by the clock as it is now: a week from here.
        assertEquals(Outcome.Ready, sync(phone.start()))
        phone.now += WEEK - 1
        sync(keys)
        assertEquals(1, directory.replacements)
        phone.now += 1
        sync(keys)
        assertEquals(2, directory.replacements)
    }

    // ----------------------------- …and never taking an account while doing so

    @Test
    fun replacingTheSignedPrekeyNeverTakesTheAccountBackFromAnotherPhone() {
        // The old phone asks, hears "the account is yours" — and before its new key arrives, the user has
        // signed in on another phone.
        val directory = Directory()
        val old = Phone(directory)
        val oldKeys = old.start()
        sync(oldKeys)
        val new = Phone(directory)
        val newKeys = new.start()
        old.now += WEEK

        var theirs: String? = null
        var ready: String? = null
        directory.beforeReplacement = {
            ready = it
            directory.beforeReplacement = {}
            newKeys.claim()
            sync(newKeys)
            theirs = directory.signedPrekey
        }
        assertEquals(Outcome.Replaced, sync(oldKeys))
        assertEquals(1, old.replaced)
        assertNull(oldKeys.identity())
        assertNull("…and it stays down after a restart, before any network", old.start().identity())

        // The other phone has the account with the keys it registered: nothing of them was touched.
        assertEquals(identityKey(newKeys), directory.identityKey)
        assertEquals(theirs, directory.signedPrekey)
        assertEquals("the old phone registered nothing", 2, directory.registrations)
        assertEquals(0, directory.replacements)
        assertEquals(50, directory.pool.size)
        assertTrue(opens(newKeys, firstMessage(directory)))
        assertEquals(Outcome.Ready, sync(new.start()))
        assertEquals(0, new.replaced)

        // Signing in on the old phone again is what brings the account back — and then the key it had
        // ready is the one it has the server hand out.
        val back = old.start().also { it.claim() }
        assertEquals(Outcome.Ready, sync(back))
        assertEquals(identityKey(back), directory.identityKey)
        assertEquals(ready, directory.signedPrekey)
        assertTrue(opens(back, firstMessage(directory)))
    }

    @Test
    fun aRefusedReplacementIsNotTakenForAnotherPhoneWhenTheDirectoryIsMerelyEmpty() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        val identity = directory.identityKey
        phone.now += WEEK

        // The server lost the account's keys between the phone's question and its request.
        directory.beforeReplacement = {
            directory.beforeReplacement = {}
            directory.identityKey = null
            directory.pool.clear()
        }
        fails("a look that did not end in agreement must be reported, so that it is repeated") { sync(keys) }
        assertEquals("nobody took the account: this phone is not signed out for it", 0, phone.replaced)
        assertNull(directory.identityKey)

        // The next look puts the same keys back and finishes the replacement.
        assertEquals(Outcome.Ready, sync(keys))
        assertEquals(identity, directory.identityKey)
        assertEquals(50, directory.pool.size)
        assertEquals(1, directory.replacements)
        assertTrue(opens(keys, firstMessage(directory)))
        assertEquals(0, phone.replaced)
    }

    @Test
    fun aPhoneThatLostTheAccountReplacesNothing() {
        val directory = Directory()
        val old = Phone(directory)
        sync(old.start())
        Phone(directory).start().also { it.claim() }.let(::sync)
        val served = directory.signedPrekey

        old.now += 2 * WEEK
        assertEquals(Outcome.Replaced, sync(old.start()))
        assertEquals(0, directory.replacementRequests)
        assertEquals(served, directory.signedPrekey)
    }

    @Test
    fun aServerThatCannotReplaceSignedPrekeysIsLeftAsItIs() {
        // An app newer than its server. The one thing it must not do is fall back on a registration.
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        val first = directory.signedPrekey
        directory.cannotReplace = true
        phone.now += WEEK

        var offered: String? = null
        directory.beforeReplacement = { offered = it }
        assertEquals(Outcome.Ready, sync(keys))
        assertEquals(first, directory.signedPrekey)
        assertEquals(1, directory.registrations)
        assertEquals(0, phone.replaced)
        assertTrue(opens(keys, firstMessage(directory)))

        // It asks again the next time it looks, with the same key, and gets its way once the server knows how.
        var again: String? = null
        directory.beforeReplacement = { again = it }
        assertEquals(Outcome.Ready, sync(phone.start()))
        assertEquals(2, directory.replacementRequests)
        assertEquals(offered, again)
        directory.cannotReplace = false
        assertEquals(Outcome.Ready, sync(phone.start()))
        assertEquals(offered, directory.signedPrekey)
        assertEquals(1, directory.registrations)
    }

    @Test
    fun whatTheServersRefusalOfAReplacementMeans() {
        assertEquals(KeyDirectory.Replacement.Refused, KeyDirectory.Replacement.ofRefusal(409))
        // "Not found" is the answer of a server that has no such route yet.
        assertEquals(KeyDirectory.Replacement.Unavailable, KeyDirectory.Replacement.ofRefusal(404))
        // Everything else stays an error: reported, tried again, and never taken for an answer.
        for (status in listOf(400, 401, 403, 405, 408, 410, 413, 429, 500, 501, 502, 503)) {
            assertNull("$status", KeyDirectory.Replacement.ofRefusal(status))
        }
    }

    @Test
    fun aReplacementThatFailsDoesNotHoldUpTheOneTimePrekeys() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        repeat(40) { directory.pool.removeAt(0) }
        phone.now += WEEK

        directory.beforeReplacement = { throw IOException("the connection was lost") }
        fails("a replacement that did not happen must be reported") { sync(keys) }
        assertEquals("the pool was topped up all the same", 50, directory.pool.size)
    }

    @Test
    fun aReplacementUnderWayWhenTheUserLogsOutWritesNothingAfterwards() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        phone.now += WEEK

        // The request is at the server when the user logs out.
        var left: Map<String, String>? = null
        directory.afterReplacement = {
            keys.close()
            left = LinkedHashMap(phone.vault.entries)
        }
        try {
            sync(keys)
            fail("a look that outlives the log-out must stop")
        } catch (expected: IllegalStateException) {
            // intended
        }
        assertEquals("nothing was written once the user had logged out", left, phone.vault.entries)

        // Logging out keeps what the phone holds. Signing in again finds the key the server now hands
        // out, and finishes what was begun.
        directory.afterReplacement = {}
        val back = phone.start().also { it.claim() }
        assertTrue(opens(back, firstMessage(directory)))
        val served = directory.signedPrekey
        assertEquals(Outcome.Ready, sync(back))
        assertEquals(served, directory.signedPrekey)
        assertEquals(1, directory.registrations)
        assertEquals(Outcome.Ready, sync(phone.start()))
        assertEquals(2, directory.replacementRequests)
    }

    @Test
    fun aReplacementThatWasBegunIsNotSentOnceTheUserHasLoggedOut() {
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        phone.now += WEEK
        directory.beforeReplacement = { throw IOException("the connection was lost") }
        fails("a replacement that did not happen must be reported") { sync(keys) }
        directory.beforeReplacement = {}

        // The new key is waiting on the phone. The user logs out while the next look is asking the server.
        val again = phone.start()
        directory.duringStatus = { again.close() }
        try {
            sync(again)
            fail("a look that outlives the log-out must stop")
        } catch (expected: IllegalStateException) {
            // intended
        }
        assertEquals("only the request that was lost", 1, directory.replacementRequests)
    }

    // ------------------------------------------- what builds before this left

    @Test
    fun keysOfABuildThatNeverReplacedThemAreKeptAndTheSignedPrekeyIsReplacedAtOnce() {
        // What a phone holds that has run only the builds in use so far: the record, and no word about
        // when its signed prekey was made.
        val directory = Directory()
        val phone = Phone(directory)
        val identity = E2eeProtocol.generateIdentity()
        val prekey = E2eeProtocol.generatePrekeyPair()
        val signature = E2eeProtocol.signPrekey(prekey.public, identity.identityPrivate)
        phone.vault.entries[RECORD] = recordOfTheBuildsInUse(identity, prekey, signature)
        directory.identityKey = encodeBase64(identity.identityPublic)
        directory.signedPrekey = encodeBase64(prekey.public)
        directory.signature = encodeBase64(signature)
        repeat(50) { directory.pool += encodeBase64(E2eeProtocol.generateOneTimePrekey().public) }
        val pool = ArrayList(directory.pool)
        val early = firstMessage(directory)

        // The update is installed and the app starts: before any network, the keys are there and in use.
        val keys = phone.start()
        assertEquals(directory.identityKey, identityKey(keys))
        assertTrue(opens(keys, early))

        // How old that signed prekey is, nobody wrote down. It is replaced the first time the phone looks.
        assertEquals(Outcome.Ready, sync(keys))
        assertEquals(1, directory.replacements)
        assertNotEquals(encodeBase64(prekey.public), directory.signedPrekey)
        assertEquals("contacts see the identity key they know", encodeBase64(identity.identityPublic), directory.identityKey)
        assertEquals(0, directory.registrations)
        assertEquals(pool, directory.pool)
        assertEquals(0, phone.replaced)
        for (app in listOf(keys, phone.start())) {
            assertTrue(opens(app, firstMessage(directory)))
            assertTrue("what was sealed for the old one still opens", opens(app, early))
        }

        // From here on it is a week like any other.
        assertEquals(Outcome.Ready, sync(phone.start()))
        assertEquals(1, directory.replacementRequests)
        phone.now += WEEK
        assertEquals(Outcome.Ready, sync(phone.start()))
        assertEquals(2, directory.replacements)
    }

    @Test
    fun theRecordOlderBuildsReadIsStillWrittenAndNamesTheSignedPrekeyTheServerHandsOut() {
        // An older build installed over this one reads that record and nothing else. It has to find the
        // identity key in it, and the signed prekey senders are handed now.
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        fun whatAnOlderBuildExpects(): String = recordOfTheBuildsInUse(
            keys.identity()!!, held(keys, directory.signedPrekey)!!, decodeBase64(directory.signature!!)!!,
        )
        assertEquals(whatAnOlderBuildExpects(), phone.vault.entries[RECORD])

        phone.now += WEEK
        sync(keys)
        assertEquals(1, directory.replacements)
        assertEquals(whatAnOlderBuildExpects(), phone.vault.entries[RECORD])
    }

    @Test
    fun signedPrekeysStoredForAnotherIdentityKeyAreNotUsed() {
        // An older build ran in between, found the record damaged and made new keys. What this build had
        // stored about signed prekeys belongs to the identity key that is gone.
        val directory = Directory()
        val phone = Phone(directory)
        sync(phone.start())
        phone.now += WEEK
        sync(phone.start())
        val stale = phone.vault.entries[SIGNED_PREKEYS]!!
        val strangers = directory.signedPrekey

        val identity = E2eeProtocol.generateIdentity()
        val prekey = E2eeProtocol.generatePrekeyPair()
        phone.vault.entries[RECORD] = recordOfTheBuildsInUse(identity, prekey, registered = false)
        assertEquals(stale, phone.vault.entries[SIGNED_PREKEYS])

        val keys = phone.start().also { it.claim() }
        assertNull("a key signed by another identity key is none of this phone's", held(keys, strangers))
        // The server takes a signed prekey only with the signature of the identity key it comes with: one
        // left over from the old identity would be refused, and this phone could never register.
        assertEquals(Outcome.Ready, sync(keys))
        assertEquals(encodeBase64(identity.identityPublic), directory.identityKey)
        assertTrue(opens(keys, firstMessage(directory)))
        assertNotNull("the signed prekey of the record is kept", held(keys, encodeBase64(prekey.public)))
    }

    @Test
    fun aSignedPrekeyOnlyTheOldRecordNamesIsKept() {
        // By this build's own writes the record never names a signed prekey the list does not know. If
        // it ever does, any of them may be the one the server hands out: all are kept, the next look
        // replaces the key — at once, since nobody knows its age — and all are kept past that too.
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        val replacedBefore = directory.signedPrekey
        phone.now += WEEK
        sync(keys)
        val listed = directory.signedPrekey
        assertNotEquals(replacedBefore, listed)
        val other = E2eeProtocol.generatePrekeyPair()
        phone.vault.entries[RECORD] = recordOfTheBuildsInUse(keys.identity()!!, other)

        val restarted = phone.start()
        val all = listOf(encodeBase64(other.public), listed, replacedBefore)
        for (key in all) assertNotNull(held(restarted, key))
        assertEquals(Outcome.Ready, sync(restarted))
        assertEquals("replaced at the next look, not a week later", 2, directory.replacements)
        for (app in listOf(restarted, phone.start())) {
            for (key in all + directory.signedPrekey) assertNotNull(held(app, key))
        }
    }

    @Test
    fun aSignedPrekeyOnlyTheOldRecordNamesIsNotLostWhenTheRecordIsWrittenAgain() {
        // The same disagreement between the two entries, and then the one thing that writes the record
        // without replacing a key: the phone learns that the account has moved to another phone. Both
        // keys are on the phone afterwards, after a restart too, and when the account comes back.
        val directory = Directory()
        val phone = Phone(directory)
        val keys = phone.start()
        sync(keys)
        val listed = directory.signedPrekey
        val other = E2eeProtocol.generatePrekeyPair()
        phone.vault.entries[RECORD] = recordOfTheBuildsInUse(keys.identity()!!, other)

        val elsewhere = Phone(directory).start().also { it.claim() }
        sync(elsewhere)
        assertEquals(Outcome.Replaced, sync(phone.start()))
        for (app in listOf(phone.start(), phone.start().also { it.claim() })) {
            assertNotNull(held(app, encodeBase64(other.public)))
            assertNotNull(held(app, listed))
        }

        val back = phone.start().also { it.claim() }
        assertEquals(Outcome.Ready, sync(back))
        for (app in listOf(back, phone.start())) {
            assertNotNull(held(app, encodeBase64(other.public)))
            assertNotNull(held(app, listed))
            assertTrue(opens(app, firstMessage(directory)))
        }
    }

    @Test
    fun aSignedPrekeyOnlyTheOldRecordNamesIsNotLostWhicheverWriteIsCutOffAfterThat() {
        // The disagreement once more, on a phone whose replacement was begun and never reached the
        // server. Then each write of the look that follows is cut off in turn. Whatever is left must
        // hold all four: the record's key, the list's, the one that was waiting, and what the server
        // hands out by then.
        var cutOff = 1
        while (true) {
            val directory = Directory()
            val phone = Phone(directory)
            val keys = phone.start()
            sync(keys)
            val listed = directory.signedPrekey
            phone.now += WEEK
            var waiting: String? = null
            directory.beforeReplacement = {
                waiting = it
                throw IOException("no connection")
            }
            fails("the replacement did not reach the server") { sync(keys) }
            directory.beforeReplacement = {}
            val other = E2eeProtocol.generatePrekeyPair()
            phone.vault.entries[RECORD] = recordOfTheBuildsInUse(keys.identity()!!, other)

            val restarted = phone.start()
            phone.vault.cutOffWrite = phone.vault.writes + cutOff
            val interrupted = try {
                sync(restarted)
                false
            } catch (expected: IOException) {
                true
            }
            phone.vault.cutOffWrite = 0

            for (app in listOf(restarted, phone.start())) {
                assertNotNull("write $cutOff: the record's", held(app, encodeBase64(other.public)))
                assertNotNull("write $cutOff: the list's", held(app, listed))
                assertNotNull("write $cutOff: the one that was waiting", held(app, waiting))
                assertNotNull("write $cutOff: what the server hands out", held(app, directory.signedPrekey))
            }
            if (!interrupted) break // the look has fewer writes than that: every one has had its turn
            cutOff++
        }
        assertTrue("that look writes more than once, or this test has tested nothing", cutOff > 2)
    }

    @Test
    fun aDamagedListOfSignedPrekeysCostsTheReplacedOnesAndNothingElse() {
        // The list is taken as it stands on the phone — whole, and in step with the record — and then
        // damaged in one way at a time. Whichever way: the phone goes by the record alone, which costs
        // it the replaced keys and the age of the one in use, and nothing else.
        fun edited(list: String, change: (Map<String, JsonElement>) -> Map<String, JsonElement>): String =
            JsonObject(change(Json.parseToJsonElement(list).jsonObject)).toString()
        fun part(list: Map<String, JsonElement>, name: String, change: (Map<String, JsonElement>) -> Map<String, JsonElement>) =
            list + (name to JsonObject(change(list.getValue(name).jsonObject)))
        fun firstReplaced(list: Map<String, JsonElement>, change: (Map<String, JsonElement>) -> Map<String, JsonElement>): Map<String, JsonElement> {
            val replaced = list.getValue("replaced").jsonArray
            return list + ("replaced" to JsonArray(listOf(JsonObject(change(replaced.first().jsonObject))) + replaced.drop(1)))
        }
        val notAKey = JsonPrimitive(encodeBase64(ByteArray(91) { 7 }))
        val damages = listOf<Pair<String, (String) -> String>>(
            "empty" to { "" },
            "an empty object" to { "{}" },
            "not JSON" to { "not json" },
            "a JSON list" to { "[]" },
            "cut off" to { it.dropLast(9) },
            "of a format this build does not know" to { edited(it) { list -> list + ("v" to JsonPrimitive(99)) } },
            "without a format" to { edited(it) { list -> list - "v" } },
            "without the identity key it was written for" to { edited(it) { list -> list - "identity" } },
            "without the signed prekey in use" to { edited(it) { list -> list - "prekey" } },
            "the signed prekey in use without its private half" to { edited(it) { list -> part(list, "prekey") { key -> key - "private" } } },
            "the signed prekey in use with a public half that is no key" to { edited(it) { list -> part(list, "prekey") { key -> key + ("public" to notAKey) } } },
            "the signed prekey in use without its signature" to { edited(it) { list -> part(list, "prekey") { key -> key - "signature" } } },
            "without the time the key in use was made" to { edited(it) { list -> list - "since" } },
            "the time as text" to { edited(it) { list -> list + ("since" to JsonPrimitive(list.getValue("since").jsonPrimitive.content)) } },
            "a waiting key that is no key" to { edited(it) { list -> list + ("next" to JsonObject(emptyMap())) } },
            "without the replaced keys" to { edited(it) { list -> list - "replaced" } },
            "the replaced keys not a list" to { edited(it) { list -> list + ("replaced" to JsonObject(emptyMap())) } },
            "a replaced key without the time it was replaced" to { edited(it) { list -> firstReplaced(list) { key -> key - "since" } } },
            "a replaced key with a public half that is no key" to { edited(it) { list -> firstReplaced(list) { key -> key + ("public" to notAKey) } } },
        )

        val directory = Directory()
        val phone = Phone(directory)
        sync(phone.start())
        phone.now += WEEK
        sync(phone.start())
        val identity = directory.identityKey

        for ((how, damage) in damages) {
            val whole = phone.vault.entries[SIGNED_PREKEYS]!!
            assertTrue("$how: there is a replaced key to lose", Json.parseToJsonElement(whole).jsonObject.getValue("replaced").jsonArray.isNotEmpty())
            // Whole, it is in step with the record and names a key made just now: nothing is due.
            val replacements = directory.replacements
            assertEquals(how, Outcome.Ready, sync(phone.start()))
            assertEquals(how, replacements, directory.replacements)

            val damaged = damage(whole)
            assertNotEquals(how, whole, damaged)
            phone.vault.entries[SIGNED_PREKEYS] = damaged
            val keys = phone.start()
            assertEquals(how, identity, identityKey(keys))
            assertTrue("$how: it opens what is sealed for the key the server hands out", opens(keys, firstMessage(directory)))
            // Nothing says how old that key is any more: it is replaced, and the list is whole again.
            assertEquals(how, Outcome.Ready, sync(keys))
            assertEquals(how, replacements + 1, directory.replacements)
            assertEquals(how, identity, directory.identityKey)
            assertEquals(how, 1, directory.registrations)
            assertEquals(how, 0, phone.replaced)
            assertTrue(how, opens(phone.start(), firstMessage(directory)))
        }
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
        val served = E2eeProtocol.keyReference(decodeBase64(directory.signedPrekey!!)!!)
        assertNotNull(phone.start().signedPrekey(served))

        for (damaged in listOf("", "1.only.three.fields", "1.!!.!!.!!.!!.!!", "7" + phone.vault.entries["e2ee.keys"]!!.drop(1))) {
            phone.vault.entries["e2ee.keys"] = damaged
            val keys = phone.start()
            assertNull(keys.identity())
            assertNull("without its identity key a signed prekey opens nothing, and is not handed out", keys.signedPrekey(served))
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
