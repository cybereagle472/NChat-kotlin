package com.nchat.app.network

import android.content.Context
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.nchat.app.network.E2eeKeys.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.KeyStore

/**
 * The two things the JVM tests can only stand in for, on Android itself: the
 * Keystore that guards the key this phone's encryption state is sealed with,
 * and the files under the app's own directory.
 *
 * Runs on an emulator (see `.github/workflows/android-device-tests.yaml`),
 * together with every test in `src/test/.../network` — those are compiled into
 * the same test APK, so the protocol, the group chains and the sealed files
 * are exercised against Android's cryptography and not only the desktop JVM's.
 */
class KeystoreStorageDeviceTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = ArrayList<String>()
    private val roots = ArrayList<File>()

    private fun name(tag: String): String = ("device-test-$tag-" + System.nanoTime()).also { names += it }

    private fun root(tag: String): File = File(context.filesDir, "device-test-$tag-" + System.nanoTime()).also { roots += it }

    @After
    fun removeWhatTheTestsLeft() {
        for (name in names) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(name) }
        }
        roots.forEach { it.deleteRecursively() }
    }

    /** The stored form of [key] with one bit of its ciphertext changed. */
    private fun damage(name: String, key: String) {
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val stored = raw.getString(key, null) ?: error("nothing stored under $key")
        val bytes = Base64.decode(stored.removePrefix("v1:"), Base64.NO_WRAP)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertTrue(raw.edit().putString(key, "v1:" + Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
    }

    @Test
    fun whatSecurePrefsKeepsIsSealedByTheKeystoreAndReadBackByANewProcess() {
        val name = name("prefs")
        val first = SecurePrefs(context, name)
        assertTrue("the Android Keystore is usable on this system", first.isEncrypted)
        assertTrue(first.putStringsSync("token" to "s3cret-value-1234567890", "other" to "second"))

        val stored = context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("token", null)
        assertNotNull(stored)
        assertTrue(stored!!.startsWith("v1:"))
        assertFalse("the value itself is not in the file", stored.contains("s3cret-value"))

        val again = SecurePrefs(context, name)
        assertEquals("s3cret-value-1234567890", again.getString("token"))
        assertEquals("second", again.getString("other"))

        // A changed byte is noticed: the value is not handed out, and is still known to be there.
        damage(name, "token")
        val damaged = SecurePrefs(context, name)
        assertNull(damaged.getString("token"))
        assertTrue(damaged.contains("token"))
        assertEquals("second", damaged.getString("other"))
    }

    @Test
    fun theStorageKeyIsKeptReadBackAndForgotten() {
        val name = name("keyring")
        val key = ByteArray(SealedBox.KEY_BYTES) { (it * 7 + 3).toByte() }
        assertNull(PrefsKeyring(SecurePrefs(context, name)).load())

        PrefsKeyring(SecurePrefs(context, name)).store(StorageKeyring.Entry("alice", key))
        val loaded = PrefsKeyring(SecurePrefs(context, name)).load()
        assertNotNull(loaded)
        assertEquals("alice", loaded!!.owner)
        assertArrayEquals(key, loaded.key)

        // Stored but not readable is an error — never "nothing there", which would make new keys.
        damage(name, "storage_key")
        try {
            PrefsKeyring(SecurePrefs(context, name)).load()
            fail("a storage key that does not decrypt was read as something")
        } catch (expected: IOException) {
            // as it should be
        }

        PrefsKeyring(SecurePrefs(context, name)).clear()
        assertNull(PrefsKeyring(SecurePrefs(context, name)).load())
    }

    /** The server's key directory, as much of it as two phones need. */
    private class Server {
        class Keys(var identityKey: String, var signedPrekey: String, var signature: String, val pool: ArrayDeque<String> = ArrayDeque())

        val accounts = HashMap<String, Keys>()

        fun directoryFor(userId: String) = object : KeyDirectory {
            override suspend fun status(): KeyDirectory.Status =
                KeyDirectory.Status(accounts[userId]?.identityKey, accounts[userId]?.pool?.size ?: 0)

            override suspend fun register(identityKey: String, signedPrekey: String, signature: String) {
                val known = accounts[userId]
                if (known == null || known.identityKey != identityKey) {
                    accounts[userId] = Keys(identityKey, signedPrekey, signature)
                } else {
                    known.signedPrekey = signedPrekey
                    known.signature = signature
                }
            }

            override suspend fun upload(identityKey: String, oneTimePrekeys: List<String>): Boolean {
                val known = accounts[userId] ?: return false
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

    /** One phone: its files and its Keystore entry outlive the app process. */
    private inner class Phone(val server: Server, val userId: String) {
        val root: File = root(userId)
        val prefsName: String = name(userId)
        val reports = ArrayList<String>()

        /** A new app process on this phone. */
        fun start(): EndToEnd = EndToEnd(
            root = root,
            keyring = PrefsKeyring(SecurePrefs(context, prefsName)),
            directory = server.directoryFor(userId),
            fetchBundle = { server.bundle(it) },
            onReplaced = { reports += "replaced" },
            report = { what, _ -> reports += what },
        )

        fun files(): List<File> = root.walkTopDown().filter { it.isFile }.toList()
    }

    private fun send(app: EndToEnd, to: String, clientId: String, text: String): String =
        runBlocking { app.messaging().sendSealed(to, clientId, E2eePayload.text(text), { false }) { it.envelope } }
            ?: error("the message was not sealed")

    @Test
    fun twoPhonesExchangeSealedMessagesAndFindThemAgainAfterARestart() {
        val server = Server()
        val alicePhone = Phone(server, "alice")
        val bobPhone = Phone(server, "bob")
        val alice = alicePhone.start().also { it.signedIn("alice") }
        val bob = bobPhone.start().also { it.signedIn("bob") }
        assertEquals(Outcome.Ready, runBlocking { alice.sync() })
        assertEquals(Outcome.Ready, runBlocking { bob.sync() })

        val hello = send(alice, "bob", "c-1", "hello from the keystore")
        assertFalse(hello.contains("hello from the keystore"))
        assertEquals("hello from the keystore", bob.messaging().reveal("alice", "s-1", "c-1", mine = false, envelope = hello)?.body)
        val back = send(bob, "alice", "c-2", "and back again")
        assertEquals("and back again", alice.messaging().reveal("bob", "s-2", "c-2", mine = false, envelope = back)?.body)

        // A new process on each phone: the storage key comes back out of the Keystore, and
        // with it the keys and everything that was opened.
        val aliceAgain = alicePhone.start().also { it.resume("alice") }
        val bobAgain = bobPhone.start().also { it.resume("bob") }
        assertEquals("hello from the keystore", bobAgain.messaging().reveal("alice", "s-1", "c-1", mine = false, envelope = hello)?.body)
        assertEquals("hello from the keystore", aliceAgain.messaging().reveal("bob", "s-1", "c-1", mine = true, envelope = hello)?.body)
        val later = send(aliceAgain, "bob", "c-3", "after the restart")
        assertEquals("after the restart", bobAgain.messaging().reveal("alice", "s-3", "c-3", mine = false, envelope = later)?.body)
        assertEquals(emptyList<String>(), alicePhone.reports)
        assertEquals(emptyList<String>(), bobPhone.reports)

        // Nothing either phone keeps contains what was written.
        for (phone in listOf(alicePhone, bobPhone)) {
            val files = phone.files()
            assertTrue(files.isNotEmpty())
            for (file in files) {
                val content = String(file.readBytes(), Charsets.ISO_8859_1)
                assertFalse(file.name, content.contains("hello from the keystore") || content.contains("after the restart"))
            }
        }

        // Logging out keeps both, and shows nothing meanwhile: a later process finds the key in
        // the Keystore again, and the same account signing in reads what it had.
        assertNull("nothing is withdrawn from the server", runBlocking { aliceAgain.logOut(LogOut.Keep)() })
        assertNull(aliceAgain.messaging().reveal("bob", "s-1", "c-1", mine = true, envelope = hello))
        assertTrue(alicePhone.files().isNotEmpty())
        assertNotNull(PrefsKeyring(SecurePrefs(context, alicePhone.prefsName)).load())
        val aliceBack = alicePhone.start()
        assertFalse("its keys were here: nothing starts anew", aliceBack.signedIn("alice"))
        assertEquals("hello from the keystore", aliceBack.messaging().reveal("bob", "s-1", "c-1", mine = true, envelope = hello)?.body)
        assertEquals(emptyList<String>(), alicePhone.reports)

        // Logging out and erasing leaves neither the files nor the key that opened them.
        runBlocking { aliceBack.logOut(LogOut.Erase)() }
        assertEquals(emptyList<File>(), alicePhone.files())
        assertNull(PrefsKeyring(SecurePrefs(context, alicePhone.prefsName)).load())
        assertNull(aliceBack.messaging().reveal("bob", "s-1", "c-1", mine = true, envelope = hello))
        assertTrue("and the next sign-in starts anew", alicePhone.start().signedIn("alice"))
    }
}
