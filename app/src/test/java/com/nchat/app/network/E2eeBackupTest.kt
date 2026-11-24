package com.nchat.app.network

import com.nchat.app.network.E2eeBackup.Problem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * A backup is everything a phone kept of an account's sealed messages, in one
 * file the user keeps wherever they like — which means in places this app
 * knows nothing about. So these tests are about a file in the wrong hands,
 * a file that was damaged on the way, and a file that is not a backup at all.
 */
class E2eeBackupTest {
    private val account = "7f3c1e9a-alice"
    private val passphrase = "correct horse battery".toCharArray()

    /** Few rounds: these tests are not about how long a guess takes. */
    private val rounds = 200

    private class Kept(val chat: String, val key: String, val payload: E2eePayload) {
        override fun toString() = "$chat/$key/${payload.type}/${payload.body.take(20)}/${payload.replyTo}/${payload.attachment}"
    }

    private val history = listOf(
        Kept("user-bob-1234", "m:c-1#aaaa", E2eePayload.text("see you at seven")),
        Kept("user-bob-1234", "c:c-2#bbbb", E2eePayload.text("fine", replyTo = "s-1")),
        Kept("user-bob-1234", "e:s-1#cccc", E2eePayload.text("see you at eight")),
        Kept("user-bob-1234", "m:c-3#dddd", E2eePayload(E2eePayload.PHOTO, "the harbour", attachment = "v1:https://x.test/media/f:KEYKEY:1234:jpg")),
        Kept("g:family-9012", "m:13:user-bob-1234:c-9#eeee", E2eePayload.text("emoji 😀 and عربى and 日本語\nsecond line")),
        Kept("user-carol-5678", "m:c-4#ffff", E2eePayload(E2eePayload.LOCATION, "24.8607,67.0011")),
        Kept("user-carol-5678", "m:c-5#gggg", E2eePayload.text("")),
    )

    private fun write(entries: List<Kept>, forAccount: String = account, with: CharArray = passphrase): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = E2eeBackup.Writer(out, forAccount, with, rounds)
        var chat: String? = null
        for (entry in entries) {
            if (entry.chat != chat) {
                chat = entry.chat
                writer.chat(entry.chat)
            }
            writer.entry(entry.key, entry.payload)
        }
        writer.finish()
        return out.toByteArray()
    }

    private fun read(file: ByteArray, forAccount: String = account, with: CharArray = passphrase): List<Kept> {
        val found = ArrayList<Kept>()
        E2eeBackup.read(ByteArrayInputStream(file), forAccount, with) { chat, key, payload -> found += Kept(chat, key, payload) }
        return found
    }

    /** What reading [file] ends with: the problem, or null when it read to its end. [seen] gets what was handed over before. */
    private fun problem(file: ByteArray, forAccount: String = account, with: CharArray = passphrase, seen: MutableList<Kept>? = null): Problem? = try {
        E2eeBackup.read(ByteArrayInputStream(file), forAccount, with) { chat, key, payload -> seen?.add(Kept(chat, key, payload)) }
        null
    } catch (failure: E2eeBackup.Failure) {
        failure.problem
    }

    private fun ByteArray.holds(needle: String): Boolean {
        val wanted = needle.toByteArray(Charsets.UTF_8)
        if (wanted.isEmpty() || wanted.size > size) return false
        for (start in 0..size - wanted.size) {
            var index = 0
            while (index < wanted.size && this[start + index] == wanted[index]) index++
            if (index == wanted.size) return true
        }
        return false
    }

    /** Where the pieces of [file] are: after the heading, each as long as a piece is, the last one shorter. */
    private fun pieces(file: ByteArray): List<IntRange> {
        val found = ArrayList<IntRange>()
        var start = E2eeBackup.HEADING_BYTES
        while (start < file.size) {
            val end = minOf(start + E2eeBackup.SEALED_PIECE_BYTES, file.size)
            found += start until end
            start = end
        }
        return found
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    // ------------------------------------------------------- from passphrase to key

    @Test
    fun theKeyIsMadeFromThePassphraseTheStandardSlowWay() {
        // PBKDF2 with HMAC-SHA-256. The first two are the vectors of RFC 7914 §11 (their first
        // 32 bytes); all five were computed again outside this code base, with Python's hashlib.
        fun key(passphrase: String, salt: ByteArray, rounds: Int) = hex(E2eeBackup.stretch(passphrase.toCharArray(), salt, rounds))
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", key("passwd", "salt".toByteArray(), 1))
        assertEquals("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56", key("Password", "NaCl".toByteArray(), 80_000))
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43", key("password", "salt".toByteArray(), 2))
        assertEquals("c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a", key("password", "salt".toByteArray(), 4096))
        // A passphrase is taken as UTF-8, whatever script it is written in.
        assertEquals(
            "6e66853f3046a4b8dc625f2625f6238409f621e69ab584c980a21e9e36459015",
            key("пароль-پاس ورڈ", ByteArray(16) { it.toByte() }, 1000),
        )
    }

    @Test
    fun aPassphraseHasToBeWorthTheName() {
        // Whoever holds the file can try passphrases for as long as they like.
        for (weak in listOf("", "   ", "short", "123456789", "aaaaaaaaaaaaaa", "          ", "   short   ", "abab abab abab")) {
            assertTrue(weak, E2eeBackup.passphraseProblem(weak.toCharArray()) != null)
        }
        for (fine in listOf("correct horse battery", "0123456789", "пароль-پاس ورڈ", "ten chars!")) {
            assertNull(fine, E2eeBackup.passphraseProblem(fine.toCharArray()))
        }
        try {
            E2eeBackup.Writer(ByteArrayOutputStream(), account, "short".toCharArray(), rounds)
            fail("a backup under a passphrase that is none")
        } catch (expected: IllegalArgumentException) {
            // intended
        }
    }

    // ------------------------------------------------------------------ the file

    @Test
    fun aBackupGivesBackEveryMessageInTheOrderItWasKept() {
        val restored = read(write(history))
        assertEquals(history.map { it.toString() }, restored.map { it.toString() })
        // An edit comes after the message it replaces: the order is what makes it the newest.
        assertTrue(restored.indexOfFirst { it.key == "e:s-1#cccc" } > restored.indexOfFirst { it.key == "m:c-1#aaaa" })
        assertEquals("nothing kept is still a backup", emptyList<Kept>(), read(write(emptyList())))
    }

    @Test
    fun nothingOfItCanBeReadOffTheFile() {
        val file = write(history)
        for (word in listOf("seven", "harbour", "KEYKEY", "user-bob", "carol", "family", "c-1#aaaa", "24.8607", account, "alice")) {
            assertFalse(word, file.holds(word))
        }
        // The same history twice does not give the same file: nothing says "unchanged".
        assertNotEquals(file.toList(), write(history).toList())
        // Its size says roughly how much was kept, not what.
        assertTrue(file.size < history.sumOf { it.payload.body.length + it.key.length + it.chat.length } * 3 + 600)
    }

    @Test
    fun theWrongPassphraseOpensNothing() {
        val file = write(history)
        val seen = ArrayList<Kept>()
        assertEquals(Problem.WrongPassphrase, problem(file, with = "correct horse batterx".toCharArray(), seen = seen))
        assertEquals(Problem.WrongPassphrase, problem(file, with = "Correct horse battery".toCharArray(), seen = seen))
        assertEquals(Problem.WrongPassphrase, problem(file, with = "correct  horse battery".toCharArray(), seen = seen))
        assertEquals(Problem.WrongPassphrase, problem(file, with = "correcthorsebattery".toCharArray(), seen = seen))
        assertEquals(Problem.WrongPassphrase, problem(file, with = CharArray(0), seen = seen))
        assertEquals("nothing was handed over", emptyList<Kept>(), seen)
        assertEquals(history.size, read(file).size)
    }

    @Test
    fun spacesAroundAPassphraseAndHowItsLettersAreComposedAreNotPartOfIt() {
        // A keyboard adds a space after the last word, unseen; and one keyboard writes é as one
        // character where another writes e and an accent. Neither may lock somebody out.
        val file = write(history, with = "pass phras\u00e9 12".toCharArray())
        assertEquals(history.size, read(file, with = "  pass phrase\u0301 12 \n".toCharArray()).size)
        assertEquals(history.size, read(file, with = "pass phras\u00e9 12".toCharArray()).size)
        assertEquals("the accent is part of it", Problem.WrongPassphrase, problem(file, with = "pass phrase 12".toCharArray()))
        assertEquals("and so is the case", Problem.WrongPassphrase, problem(file, with = "Pass phras\u00e9 12".toCharArray()))
        // Written with spaces around it, read without.
        assertEquals(1, read(write(history.take(1), with = " correct horse battery ".toCharArray())).size)
    }

    @Test
    fun aBackupOfAnotherAccountIsTurnedAwayBeforeThePassphraseIsEvenTried() {
        val file = write(history)
        val seen = ArrayList<Kept>()
        assertEquals(Problem.OtherAccount, problem(file, forAccount = "02b8d4f6-bob", seen = seen))
        assertEquals("said even to somebody who does not know the passphrase", Problem.OtherAccount, problem(file, forAccount = "02b8d4f6-bob", with = "something else".toCharArray()))
        assertEquals(emptyList<Kept>(), seen)
        // Putting another account's name on the file does not make it that account's backup.
        val theirs = write(emptyList(), forAccount = "02b8d4f6-bob")
        val forged = theirs.copyOf(E2eeBackup.HEADING_BYTES) + file.copyOfRange(E2eeBackup.HEADING_BYTES, file.size)
        assertTrue(problem(forged, forAccount = "02b8d4f6-bob", seen = seen) in setOf(Problem.WrongPassphrase, Problem.Damaged))
        assertEquals(emptyList<Kept>(), seen)
    }

    @Test
    fun whatIsNotABackupIsNotReadAsOne() {
        val file = write(history)
        for (other in listOf(ByteArray(0), "{}".toByteArray(), "NCHATBK".toByteArray(), ByteArray(4096) { 7 }, file.copyOf(E2eeBackup.HEADING_BYTES - 1))) {
            assertEquals(Problem.NotABackup, problem(other))
        }
        // A file of a format this build has never heard of: said as such, not as a wrong passphrase.
        val later = file.copyOf().also { it[7] = '9'.code.toByte() }
        assertEquals(Problem.NewerVersion, problem(later))
        // A file that says its key takes more rounds than any phone has time for is not tried.
        val absurd = file.copyOf().also { for (index in 8..11) it[index] = 0x7f }
        val started = System.nanoTime()
        assertEquals(Problem.Damaged, problem(absurd))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun aFileThatWasChangedAnywhereIsNoticed() {
        val file = write(history)
        val original = history.map { it.toString() }
        for (index in file.indices) {
            val changed = file.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            val seen = ArrayList<Kept>()
            val problem = problem(changed, seen = seen)
            assertTrue("byte $index changed and nothing was said", problem != null)
            // Whatever was handed over before the damage was met is what was kept, in its order.
            assertEquals("byte $index", original.take(seen.size), seen.map { it.toString() })
        }
    }

    @Test
    fun aFileThatWasCutShortIsNoticed() {
        val file = write(history)
        val original = history.map { it.toString() }
        for (length in 0 until file.size) {
            val seen = ArrayList<Kept>()
            val problem = problem(file.copyOf(length), seen = seen)
            assertTrue("cut to $length bytes and nothing was said", problem != null)
            assertEquals(original.take(seen.size), seen.map { it.toString() })
        }
        // Something after the end is not part of the backup either.
        assertEquals(Problem.Damaged, problem(file + byteArrayOf(0)))
        assertEquals(Problem.Damaged, problem(file + file.copyOfRange(E2eeBackup.HEADING_BYTES, file.size)))
    }

    @Test
    fun aLongHistoryIsWrittenInPiecesThatCannotBeMovedDroppedOrRepeated() {
        // Enough for several pieces, and one message larger than a piece.
        val long = ArrayList<Kept>()
        for (index in 0 until 4_000) long += Kept("chat-${index / 500}", "m:c-$index#${index * 7}", E2eePayload.text("message number $index, with a little to say"))
        long += Kept("chat-8", "m:c-big#1", E2eePayload.text("x".repeat(300_000)))
        long += Kept("chat-8", "m:c-last#1", E2eePayload.text("the last one"))
        val file = write(long)
        assertEquals(long.map { it.toString() }, read(file).map { it.toString() })

        val pieces = pieces(file)
        assertTrue("${pieces.size} pieces", pieces.size >= 4)
        val heading = file.copyOf(E2eeBackup.HEADING_BYTES)
        fun join(order: List<Int>): ByteArray = order.fold(heading) { sofar, index -> sofar + file.copyOfRange(pieces[index].first, pieces[index].last + 1) }
        val all = pieces.indices.toList()
        assertEquals("the pieces as they were", null, problem(join(all)))
        assertEquals("two swapped", Problem.Damaged, problem(join(listOf(0, 2, 1) + all.drop(3))))
        assertEquals("one left out", Problem.Damaged, problem(join(all.filter { it != 1 })))
        assertEquals("one twice", Problem.Damaged, problem(join(listOf(0, 1, 1) + all.drop(2))))
        assertEquals("the last one left out: the file says where it ends", Problem.Damaged, problem(join(all.dropLast(1))))
        assertEquals("the first left out: the passphrase is right, the file is not", Problem.Damaged, problem(join(all.drop(1))))
        // A piece of another backup of the same history, under the same passphrase, does not fit either.
        val other = write(long)
        val foreign = pieces(other)[1]
        val mixed = heading + file.copyOfRange(pieces[0].first, pieces[0].last + 1) + other.copyOfRange(foreign.first, foreign.last + 1)
        assertEquals(Problem.Damaged, problem(mixed))
    }

    @Test
    fun theFileSaysHowManyRoundsItsKeyTook() {
        // Written with one count and read without being told it: a later build can ask for more.
        val out = ByteArrayOutputStream()
        E2eeBackup.Writer(out, account, passphrase, 2_500).also {
            it.chat("bob")
            it.entry("m:c-1#aaaa", E2eePayload.text("hello"))
            it.finish()
        }
        assertEquals("hello", read(out.toByteArray()).single().payload.body)
        assertArrayEquals(byteArrayOf(0, 0, 0x09, 0xc4.toByte()), out.toByteArray().copyOfRange(8, 12))
        assertTrue("what the app itself asks for is slow enough to matter", E2eeBackup.ROUNDS >= 600_000)
        // …and it is what a writer that is told nothing uses.
        val usual = ByteArrayOutputStream()
        E2eeBackup.Writer(usual, account, passphrase).finish()
        assertArrayEquals(byteArrayOf(0, 0x09, 0x27, 0xc0.toByte()), usual.toByteArray().copyOfRange(8, 12))
    }

    @Test
    fun aWriterIsUsedInOrderAndOnce() {
        val writer = E2eeBackup.Writer(ByteArrayOutputStream(), account, passphrase, rounds)
        try {
            writer.entry("m:c-1#aaaa", E2eePayload.text("which chat?"))
            fail("an entry before any chat")
        } catch (expected: IllegalStateException) {
            // intended
        }
        writer.chat("bob")
        writer.finish()
        for (late in listOf<() -> Unit>({ writer.chat("carol") }, { writer.entry("m:c-1#aaaa", E2eePayload.text("late")) }, { writer.finish() })) {
            try {
                late()
                fail("written to after it was finished")
            } catch (expected: IllegalStateException) {
                // intended
            }
        }
    }
}
