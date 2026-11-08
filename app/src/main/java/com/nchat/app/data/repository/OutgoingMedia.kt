package com.nchat.app.data.repository

import com.nchat.app.data.model.OutboxEntry
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Local file and content URIs can be played on this device but must never be sent as media URLs. */
internal fun String?.shareableMediaUrl(): String? =
    this?.trim()?.takeIf { it.toHttpUrlOrNull() != null }

/**
 * Repair older queued rows that recorded a local URI as a media URL only when they also retain
 * an explicit local file to upload. Other invalid media references cannot safely be delivered.
 */
internal fun OutboxEntry.prepareMediaForDelivery(): OutboxEntry? {
    val url = mediaUrl?.takeUnless { it.isBlank() }
    val shareable = url.shareableMediaUrl()
    if (url != null && shareable == null && localFile.isNullOrBlank()) return null
    if (type in setOf("voice", "audio", "image", "video", "document") &&
        shareable == null && localFile.isNullOrBlank()) return null
    // Voice thumbnails contain waveform data, not URLs; other thumbnails must be shareable.
    val thumbnail = if (type == "voice" || type == "audio") {
        thumbnailUrl?.takeUnless {
            it.startsWith("file://", ignoreCase = true) || it.startsWith("content://", ignoreCase = true)
        }
    } else {
        thumbnailUrl.shareableMediaUrl()
    }
    return if (mediaUrl == shareable && thumbnailUrl == thumbnail) this
    else copy(mediaUrl = shareable, thumbnailUrl = thumbnail)
}
