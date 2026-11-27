package com.nchat.app.data.repository

import com.nchat.app.network.LogOut
import kotlinx.coroutines.flow.StateFlow

/** Outcome of a sign-in / sign-up attempt. */
sealed interface AuthResult {
    data object Success : AuthResult

    data class Failure(val message: String) : AuthResult
}

/**
 * The reference's `authService`, backed by the NChat API (`/auth` endpoints). The session survives
 * restarts because the token pair is stored on the device; the account itself lives on the
 * server, so the same login works on every install.
 */
interface AuthRepository {
    /** Signed-in email, or null when signed out. */
    val session: StateFlow<String?>

    /** False until the profile-setup step has been completed for this account. */
    val profileComplete: StateFlow<Boolean>

    /** Both authentication operations are HTTP round trips against the backend. */
    suspend fun signIn(email: String, password: String): AuthResult
    suspend fun signUp(email: String, password: String): AuthResult
    fun completeProfile()

    /**
     * Ends the session on this phone, at once and without the network.
     *
     * [LogOut.Keep] leaves what the phone holds of the account where it is, shown to nobody: the
     * same account signing in again finds its messages, with no backup. [LogOut.Erase] removes
     * it for good — for a phone that goes to somebody else.
     */
    fun logOut(how: LogOut)
}
