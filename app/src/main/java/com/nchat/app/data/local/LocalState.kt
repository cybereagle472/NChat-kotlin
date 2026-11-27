package com.nchat.app.data.local

import java.io.File

/**
 * What the app keeps on the phone for an account beside its keys and its
 * encrypted history: drafts, stars, pins, the archive and the messages still
 * waiting to be sent — each sealed for the account — and what lies in the
 * open (see [PhoneFiles]).
 *
 * Two things can happen to it, and they are the whole difference between
 * "Log Out" and "Log out and erase". Here in one place, so that a store which
 * is added later is added to both, and so that both can be tried without a
 * phone.
 */
internal class LocalState(
    private val drafts: DraftStore,
    private val stars: StarredStore,
    private val pinned: PinnedMessageStore,
    private val archived: ArchivedStore,
    private val outbox: OutboxStore,
    private val filesDir: File,
    private val cacheDir: File,
) {

    /**
     * Whoever is signed in has changed, and nothing is erased: what is held
     * in memory is let go, and the files are read anew for whoever can open
     * them now. They wait for their account and open for nobody else.
     *
     * The messages waiting to be sent are not held in memory, so there is
     * nothing to let go of: the queue is read from its file each time, and
     * cannot be read — or added to — while its account is away.
     */
    fun reopen() {
        drafts.reopen()
        stars.reopen()
        pinned.reopen()
        archived.reopen()
    }

    /**
     * Nothing of whoever was here stays, sealed or in the open. A message
     * that was waiting to be sent goes with the rest: it is not sent later.
     *
     * False when the phone would not remove everything.
     */
    fun erase(): Boolean {
        drafts.clearAll()
        stars.clearAll()
        pinned.clearAll()
        archived.clearAll()
        val queueGone = outbox.clear()
        val filesGone = PhoneFiles.erase(filesDir, cacheDir)
        return queueGone && filesGone
    }
}
