package com.nchat.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Locale

/**
 * The number two people compare to make sure nobody sits between them. It is
 * only worth anything if both phones always show the same sixty digits for
 * the same two keys, and different digits for any other pair.
 */
class SecurityCodeTest {
    private val aliceId = "7f3c1e9a-alice"
    private val bobId = "02b8d4f6-bob"
    private val aliceKey = decodeBase64(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBo14tgBQwy4xkklVYoxAWIx4fx97FM3RzpvEUGi5mnz8K6UYP33Csty0W3FebRqX+CMUsi5wjB5wCXwoafv/Kw==",
    )!!
    private val bobKey = decodeBase64(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAETZ6zB+rPVQClBZ2GQu91Kd9s3e2CAr7PtZnbaLcf+YSoefqDR0kmNPN91cp+mOFPDdkJh5RdrOAthj5cilg1OQ==",
    )!!

    private fun newKey(): ByteArray = E2eeProtocol.generateIdentity().identityPublic

    @Test
    fun matchesTwoIndependentImplementations() {
        // Computed outside this code base, in Node and again in Python, from the description in SecurityCode.
        // Bob's half sorts first, and the last group begins with a zero.
        val expected = "427967518579389282864513861332751967621959880930629597202811"
        assertEquals(expected, SecurityCode.of(aliceId, aliceKey, bobId, bobKey))
        assertEquals("the other phone shows the same digits", expected, SecurityCode.of(bobId, bobKey, aliceId, aliceKey))
    }

    @Test
    fun isBoundToTheAccountsAndNotOnlyToTheKeys() {
        // The same two keys under the other account each: somebody who registers another person's
        // public key as their own does not get that person's code.
        val swapped = "350049749155535114857047000330917116255580531763070908569062"
        assertEquals(swapped, SecurityCode.of(bobId, aliceKey, aliceId, bobKey))
        assertNotEquals(SecurityCode.of(aliceId, aliceKey, bobId, bobKey), SecurityCode.of(aliceId, aliceKey, "02b8d4f6-bo", bobKey))
    }

    @Test
    fun isTheSameOnBothPhonesForAnyTwoKeys() {
        repeat(5) { round ->
            val mine = newKey()
            val theirs = newKey()
            val here = SecurityCode.of("me-$round", mine, "them-$round", theirs)
            assertEquals(here, SecurityCode.of("them-$round", theirs, "me-$round", mine))
        }
    }

    @Test
    fun changesWhenEitherKeyChanges() {
        val code = SecurityCode.of(aliceId, aliceKey, bobId, bobKey)
        assertNotEquals("somebody else's key in place of bob's", code, SecurityCode.of(aliceId, aliceKey, bobId, newKey()))
        assertNotEquals("somebody else's key in place of alice's", code, SecurityCode.of(aliceId, newKey(), bobId, bobKey))
        // One flipped bit that is still a point on the curve is too rare to hit; another whole key is the real case.
        assertEquals(code, SecurityCode.of(aliceId, aliceKey.copyOf(), bobId, bobKey.copyOf()))
    }

    @Test
    fun isSixtyPlainDigitsWhateverLanguageThePhoneIsSetTo() {
        val before = Locale.getDefault()
        try {
            // Languages whose number formatting uses digits of their own.
            for (tag in listOf("ar-SA", "fa-IR", "ur-PK", "hi-IN-u-nu-deva", "bn-BD", "en-US")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val code = SecurityCode.of(aliceId, aliceKey, bobId, bobKey)
                assertEquals(tag, "427967518579389282864513861332751967621959880930629597202811", code)
                assertEquals(tag, SecurityCode.DIGITS, code.length)
                assertTrue(tag, code.all { it in '0'..'9' })
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun isShownInTwelveGroupsOfFive() {
        val groups = SecurityCode.grouped(SecurityCode.of(aliceId, aliceKey, bobId, bobKey))
        assertEquals(
            listOf("42796", "75185", "79389", "28286", "45138", "61332", "75196", "76219", "59880", "93062", "95972", "02811"),
            groups,
        )
    }

    @Test
    fun aChatWithOneselfHasACodeToo() {
        val code = SecurityCode.of(aliceId, aliceKey, aliceId, aliceKey)
        assertEquals(SecurityCode.DIGITS, code.length)
        assertEquals("both halves are the same account and key", code.substring(0, 30), code.substring(30))
    }

    @Test
    fun refusesWhatIsNotAKeyOrAnAccount() {
        val notAKey = aliceKey.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val attempts = listOf<() -> String>(
            { SecurityCode.of(aliceId, notAKey, bobId, bobKey) },
            { SecurityCode.of(aliceId, aliceKey, bobId, notAKey) },
            { SecurityCode.of(aliceId, aliceKey.copyOf(40), bobId, bobKey) },
            { SecurityCode.of(aliceId, aliceKey, bobId, ByteArray(0)) },
            { SecurityCode.of("", aliceKey, bobId, bobKey) },
            { SecurityCode.of(aliceId, aliceKey, " ", bobKey) },
        )
        for ((index, attempt) in attempts.withIndex()) {
            try {
                attempt()
                fail("attempt $index produced a code for something that is not a key or an account")
            } catch (expected: IllegalArgumentException) {
                // intended: no code is better than a code for the wrong thing
            }
        }
    }

    // ----------------------------------------------------------------- as a QR code

    private val code = "427967518579389282864513861332751967621959880930629597202811"

    @Test
    fun theQrCodeCarriesTheSameSixtyDigits() {
        // Scanning replaces reading sixty digits aloud; it must compare exactly what the eye would.
        val text = SecurityCode.qrText(code)
        assertEquals(code, SecurityCode.fromQr(text))
        assertTrue("it says what it is, so that any other QR code is turned away", text.startsWith("NCHAT-SC1:"))
        // Only what a QR code packs densely: capitals, digits and a few signs. That keeps the
        // picture small enough to scan off a phone held at arm's length.
        assertTrue(text.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" })
        assertEquals("a scanner may hand the text over with a line end", code, SecurityCode.fromQr(" $text\n"))
        try {
            SecurityCode.qrText(code.dropLast(1))
            fail("a QR code for something that is not a security code")
        } catch (expected: IllegalArgumentException) {
            // intended
        }
    }

    @Test
    fun whatIsNotASecurityCodeIsNotReadAsOne() {
        val text = SecurityCode.qrText(code)
        val others = listOf(
            "", "https://example.com/", code, "NCHAT-SC1:", "NCHAT-SC1:" + code.dropLast(1), "NCHAT-SC1:" + code + "1",
            "NCHAT-SC1:" + code.replaceRange(10, 11, "x"), "nchat-sc1:" + code, "NCHAT-SC2:" + code, "NCHAT-SC1 " + code,
            text + text, "WIFI:T:WPA;S:home;P:" + code + ";;", "NCHAT-SC1:" + code.replace('1', '١'),
        )
        for (other in others) assertEquals(other, null, SecurityCode.fromQr(other))
    }

    @Test
    fun aScannedCodeIsTheSameOrItIsNot() {
        val mine = code
        assertEquals(SecurityCode.Scanned.Same, SecurityCode.scanned(SecurityCode.qrText(mine), mine))
        // One digit apart is another code: this is what somebody in between looks like — or the wrong chat.
        val other = mine.replaceRange(59, 60, if (mine[59] == '1') "2" else "1")
        assertEquals(SecurityCode.Scanned.Different, SecurityCode.scanned(SecurityCode.qrText(other), mine))
        for (index in mine.indices) {
            val changed = mine.replaceRange(index, index + 1, if (mine[index] == '5') "6" else "5")
            if (changed != mine) assertEquals("digit $index", SecurityCode.Scanned.Different, SecurityCode.scanned(SecurityCode.qrText(changed), mine))
        }
        assertEquals(SecurityCode.Scanned.NotACode, SecurityCode.scanned("https://example.com/", mine))
        assertEquals(SecurityCode.Scanned.NotACode, SecurityCode.scanned(mine, mine))
        // Nothing of this phone's own to compare with: never "the same".
        assertEquals(SecurityCode.Scanned.Different, SecurityCode.scanned(SecurityCode.qrText(mine), ""))
        assertEquals(SecurityCode.Scanned.Different, SecurityCode.scanned(SecurityCode.qrText(mine), mine.dropLast(1)))
    }
}
