package com.nchat.app.data.repository

import androidx.compose.ui.graphics.ImageBitmap
import com.nchat.app.data.model.UserProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * The signed-in user's own profile. Text fields live in [profile]; a picked-and-cropped
 * photo lives in [avatar] (null while the gradient initials stand in for it).
 */
interface ProfileRepository {
    val profile: StateFlow<UserProfile>
    val avatar: StateFlow<ImageBitmap?>
    fun updateName(name: String)
    fun updateAbout(about: String)
    fun updateUsername(username: String)
    fun update(fullName: String, username: String, about: String)
    fun setAvatar(image: ImageBitmap?)

    /** Pulls the account from the backend. */
    suspend fun refresh() = Unit

    /** `GET /users/username-available` — the live availability check on the setup screen. */
    suspend fun isUsernameAvailable(username: String): Boolean = true
}
