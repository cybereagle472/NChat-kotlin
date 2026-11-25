package com.nchat.app.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Restore your messages?" is asked once, and only of somebody it concerns:
 * an account that signed in on a phone which held nothing of it, and which
 * then met a message it has no key for. Asked of anybody else it would be a
 * scare — somebody whose history is all there would be told it might not be.
 */
class RestoreOfferTest {

    @Test
    fun nobodyIsAskedWhoWasNotSignedInOnAPhoneThatStartedAnew() {
        val offer = RestoreOffer()
        assertFalse(offer.asking.value)
        // The app was simply opened: a group key that has not arrived yet is not a reason to ask.
        offer.cannotShow()
        assertFalse(offer.asking.value)
        // Signed in on the phone that kept everything.
        offer.signedIn(startedAnew = false)
        offer.cannotShow()
        assertFalse(offer.asking.value)
    }

    @Test
    fun aPhoneThatStartedAnewAsksOnceItMeetsAMessageItCannotShow() {
        val offer = RestoreOffer()
        offer.signedIn(startedAnew = true)
        assertFalse("an account with nothing from before is never asked", offer.asking.value)
        offer.cannotShow()
        assertTrue(offer.asking.value)
        offer.cannotShow()
        assertTrue(offer.asking.value)

        // Answered, either way: not asked again, however many more such messages there are.
        offer.answered()
        assertFalse(offer.asking.value)
        offer.cannotShow()
        assertFalse(offer.asking.value)
    }

    @Test
    fun somebodyWhoRestoredWithoutBeingAskedIsNotAskedAfterwards() {
        val offer = RestoreOffer()
        offer.signedIn(startedAnew = true)
        offer.answered()
        offer.cannotShow()
        assertFalse(offer.asking.value)
    }

    @Test
    fun theQuestionLeavesWithTheAccountAndTheNextSignInDecidesAnew() {
        val offer = RestoreOffer()
        offer.signedIn(startedAnew = true)
        offer.cannotShow()
        assertTrue(offer.asking.value)
        offer.signedOut()
        assertFalse("nobody is there to be asked", offer.asking.value)
        offer.cannotShow()
        assertFalse(offer.asking.value)

        // The same account, back on the phone that kept everything: nothing to ask.
        offer.signedIn(startedAnew = false)
        offer.cannotShow()
        assertFalse(offer.asking.value)

        // And on a phone that starts anew, it is asked again.
        offer.signedOut()
        offer.signedIn(startedAnew = true)
        offer.cannotShow()
        assertTrue(offer.asking.value)
    }
}
