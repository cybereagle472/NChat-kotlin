package com.nchat.app.data.repository

import com.nchat.app.network.EndToEnd
import com.nchat.app.network.LogOut

/**
 * What the phone does with the things it keeps for an account when the
 * account arrives and when it leaves: which are opened, which wait where
 * they are, and which are erased.
 *
 * The rule, in one place:
 *  - an account that **logs out** ([LogOut.Keep]), or whose session ended
 *    without being asked, leaves everything on the phone and nothing in
 *    view. The same account signing in finds it all — messages included,
 *    with no backup;
 *  - an account that **logs out and erases** ([LogOut.Erase]) leaves
 *    nothing;
 *  - an account that signs in on a phone that holds nothing of it — a new
 *    phone, an erased one, one another account used last — starts anew, and
 *    whatever is still lying there goes first. Its earlier encrypted
 *    messages come back from a backup or not at all, which is what
 *    [RestoreOffer] asks about.
 *
 * Nothing here needs the network, or Android: the session itself (tokens,
 * the server) is `RemoteAuthRepository`'s, which calls these two functions.
 */
class AccountOnPhone(
    /** The account's keys and encrypted history; it decides whether the phone holds anything of an account. */
    private val endToEnd: EndToEnd?,
    /**
     * What else is private to the account — drafts, stars, pins, the archive: lets go of what is
     * held in memory and reads the files anew for whoever can open them now. They are sealed
     * for their account, so they wait for it and open for nobody else.
     */
    private val reopenLocalState: () -> Unit = {},
    /** The same, erased from the phone — together with what the app keeps in the open. */
    private val eraseLocalState: () -> Unit = {},
    /** Who is to be asked "Restore your messages?". */
    private val restoreOffer: RestoreOffer? = null,
) {

    /**
     * Somebody signed in — [existingAccount] — or has just signed up. Call
     * it before the screens are told that there is a session: they read
     * what this opens.
     *
     * Without a [userId] nobody's things are opened and nobody's are erased.
     */
    fun arrived(userId: String?, existingAccount: Boolean) {
        val startedAnew = userId != null && runCatching { endToEnd?.signedIn(userId) }.getOrNull() == true
        if (startedAnew) {
            // The phone held nothing of this account. Whatever the stores still have was
            // somebody else's, or sealed with keys that are gone: it goes.
            eraseLocalState()
        } else {
            // Its keys were here — and so is what it left when it logged out.
            reopenLocalState()
        }
        // An account from before, on a phone that starts anew, may have a backup to read.
        // Somebody who has just signed up has nothing from before.
        restoreOffer?.signedIn(startedAnew && existingAccount)
    }

    /**
     * The account leaves, [how] the user chose — or [LogOut.Keep] when the
     * session ended by itself. Before this returns nothing of the account is
     * shown, opened or sealed any more.
     *
     * The answer is for the server, once nothing of the account can reach it
     * any more: the identity key it should stop handing out. Null unless
     * the keys were erased.
     */
    fun left(how: LogOut): suspend () -> String? {
        val withdrawn = runCatching { endToEnd?.logOut(how) }.getOrNull()
        restoreOffer?.signedOut()
        when (how) {
            LogOut.Keep -> reopenLocalState()
            LogOut.Erase -> eraseLocalState()
        }
        return { withdrawn?.invoke() }
    }
}
