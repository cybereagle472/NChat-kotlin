package com.nchat.app.feature.auth

/** Never leave onboarding until the server has confirmed the profile. */
internal suspend fun completeProfileSetup(save: suspend () -> Unit, markComplete: () -> Unit) {
    save()
    markComplete()
}
