package com.nchat.app.data.repository

import java.io.IOException

/** No upload is needed for the built-in avatar; uploaded photos must have shareable URLs. */
internal suspend fun <T> profileSetupAvatarUrl(image: T?, upload: suspend (T) -> String): String {
    if (image == null) return ""
    return upload(image).shareableMediaUrl()
        ?: throw IOException("The server did not provide a usable photo address")
}
