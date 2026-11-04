package com.nchat.app.data.repository

import androidx.compose.ui.graphics.ImageBitmap
import com.nchat.app.data.mock.MockDataProvider
import com.nchat.app.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
}

class InMemoryProfileRepository(
    seed: UserProfile = MockDataProvider.profile
) : ProfileRepository {

    private val _profile = MutableStateFlow(seed)
    override val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _avatar = MutableStateFlow<ImageBitmap?>(null)
    override val avatar: StateFlow<ImageBitmap?> = _avatar.asStateFlow()

    override fun updateName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _profile.update { it.copy(fullName = trimmed) }
    }

    override fun updateAbout(about: String) {
        _profile.update { it.copy(about = about.trim()) }
    }

    override fun updateUsername(username: String) {
        val sanitized = username.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        if (sanitized.length < 3) return
        _profile.update { it.copy(handle = "@$sanitized") }
    }

    override fun update(fullName: String, username: String, about: String) {
        updateName(fullName)
        updateUsername(username)
        updateAbout(about)
    }

    override fun setAvatar(image: ImageBitmap?) {
        _avatar.value = image
    }
}
