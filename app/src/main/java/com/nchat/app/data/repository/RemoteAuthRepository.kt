package com.nchat.app.data.repository

import com.nchat.app.network.ApiException
import com.nchat.app.network.AuthResponse
import com.nchat.app.data.local.ChatListCache
import com.nchat.app.network.LogOut
import com.nchat.app.network.NChatApi
import com.nchat.app.network.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * `AuthRepository` against the Rust backend — sign-up and sign-in, plus the
 * refresh-token logout the worker expects.
 *
 * A restored session survives restarts: the token pair and the identity live in
 * [TokenStore], so the app opens straight into the signed-in state without a round trip and
 * the first protected request refreshes the access token if it has expired.
 */
class RemoteAuthRepository(
    private val api: NChatApi,
    private val tokens: TokenStore,
    private val scope: CoroutineScope,
    /** The lists as the server last sent them. They are fetched again; no session, no copy. */
    private val cache: ChatListCache? = null,
    /**
     * What the phone keeps for an account — its keys and encrypted history, drafts, stars,
     * pins, the archive. It is told about every sign-in and about every end of a session, and
     * decides what is opened, what waits and what is erased (see [AccountOnPhone]).
     */
    private val onPhone: AccountOnPhone? = null,
    /** Registers this phone's public keys with the server; started after every sign-in. */
    private val syncKeys: () -> Unit = {}
) : AuthRepository {

    // No session without its tokens: an e-mail address that outlived them (a session that expired
    // and an app that was closed before anybody signed in again) opens the sign-in screen, not a
    // home screen on which nothing can load.
    private val _session = MutableStateFlow(tokens.email.takeIf { tokens.signedIn.value })
    override val session: StateFlow<String?> = _session.asStateFlow()

    private val _profileComplete = MutableStateFlow(tokens.profileComplete)
    override val profileComplete: StateFlow<Boolean> = _profileComplete.asStateFlow()

    init {
        // If the backend ever refuses our refresh token (revoked, expired, rotated by another
        // client) the transport clears the pair. The stored e-mail used to survive that, so the
        // app stayed on the home screen with every list empty — indistinguishable from "my data
        // is gone". Dropping the session sends the user to the sign-in screen instead, and one
        // sign-in brings the whole account back, because nothing was ever deleted server-side.
        scope.launch {
            tokens.sessionLost.collect { lost ->
                if (!lost) return@collect
                // As when the user logs out: nothing is erased, and nothing is shown or sealed
                // until somebody signs in. The same account finds everything where it was.
                onPhone?.left(LogOut.Keep)
                cache?.clear()
                _session.value = null
                _profileComplete.value = false
            }
        }
    }

    override suspend fun signIn(email: String, password: String): AuthResult {
        val normalized = email.trim().lowercase()
        if (!EMAIL_RE.matches(normalized)) {
            return AuthResult.Failure("Please enter a valid email address")
        }
        if (password.length < 6) {
            return AuthResult.Failure("Password must be at least 6 characters")
        }
        return try {
            val response = api.signIn(normalized, password)
            adopt(response, profileCompleteByDefault = true)
            AuthResult.Success
        } catch (error: ApiException) {
            AuthResult.Failure(error.message)
        } catch (_: IOException) {
            AuthResult.Failure(OFFLINE)
        }
    }

    override suspend fun signUp(email: String, password: String): AuthResult {
        val normalized = email.trim().lowercase()
        if (!EMAIL_RE.matches(normalized)) {
            return AuthResult.Failure("Please enter a valid email address")
        }
        if (password.length < 6) {
            return AuthResult.Failure("Password must be at least 6 characters")
        }
        return try {
            // The backend wants a display name at signup; the real one arrives from the
            // profile-setup step, exactly like the reference's placeholder handling.
            val response = api.signUp(normalized, password, normalized.substringBefore('@'))
            adopt(response, profileCompleteByDefault = false)
            AuthResult.Success
        } catch (error: ApiException) {
            AuthResult.Failure(error.message)
        } catch (_: IOException) {
            AuthResult.Failure(OFFLINE)
        }
    }

    override fun completeProfile() {
        tokens.profileComplete = true
        _profileComplete.value = true
    }

    override fun logOut(how: LogOut) {
        val refresh = tokens.refreshToken
        // Forget the session immediately: logging out must never depend on the network.
        tokens.clear()
        // Here and now nothing of the account is shown, opened or sealed any more. Erasing
        // removes its keys and every message they opened as well; keeping leaves them for the
        // same account to find. What comes back names the public key the server should stop
        // handing out — none unless the keys were erased.
        val withdrawnKey = onPhone?.left(how)
        cache?.clear()
        _session.value = null
        _profileComplete.value = false
        if (refresh != null) {
            // Best effort: revoke the refresh token server-side (`DELETE FROM sessions`), and
            // withdraw this phone's public keys with it when they were erased.
            scope.launch { runCatching { api.logout(refresh, withdrawnKey?.invoke()) } }
        }
    }

    private fun adopt(response: AuthResponse, profileCompleteByDefault: Boolean) {
        val user = response.user
        val email = user?.email
        tokens.saveTokens(response.accessToken, response.refreshToken)
        tokens.saveIdentity(
            id = user?.id,
            email = email,
            fullName = user?.fullName,
            username = user?.username,
            avatarUrl = user?.avatarUrl
        )
        if (!tokens.profileComplete && profileCompleteByDefault) {
            // An existing account with a username has already been through onboarding.
            tokens.profileComplete = !user?.username.isNullOrBlank()
        }
        // Signing in is what makes this the account's phone for encrypted chats; the
        // registration itself may fail and is retried (see `ServiceLocator.syncE2eeKeys`).
        // What the phone kept of this account is opened, and what it kept of another is erased
        // — before the screens are told there is a session: they read what follows.
        onPhone?.arrived(user?.id, existingAccount = profileCompleteByDefault)
        _session.value = email
        _profileComplete.value = tokens.profileComplete
        syncKeys()
    }

    private companion object {
        const val OFFLINE = "Cannot reach the server — check your connection"
        val EMAIL_RE = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
