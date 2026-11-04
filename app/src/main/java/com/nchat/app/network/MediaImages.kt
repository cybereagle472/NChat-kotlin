package com.nchat.app.network

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * The image loader every photo, video poster and thumbnail goes through.
 *
 * Photos are private on the backend: `GET /media/file/...` only answers a caller who is
 * part of the conversation (see `server/src/routes/media.rs`). Coil's default loader sends
 * no credentials, so on its own every image would come back `401`. This loader:
 *
 * 1. attaches the same bearer token the rest of the app uses, and only to our own
 *    origin — a token must never leak to a third-party host a model might point at;
 * 2. on `401`, renews the session once through [ApiClient] (the same single-flight
 *    refresh the REST layer uses) and retries, so an image that arrives after the
 *    15-minute access token expired still loads instead of showing a broken tile;
 * 3. keeps a bounded on-disk cache, so a chat reopened offline still shows pictures.
 *
 * Against a deployment that still serves media publicly (the Cloudflare worker, or
 * `NCHAT_MEDIA_PUBLIC=1`) the extra header is simply ignored, so the app works with both.
 */
object MediaImages {

    /** 128 MB of decoded originals/posts on disk — plenty for a phone, capped on purpose. */
    private const val DISK_CACHE_BYTES = 128L * 1024 * 1024

    fun loader(context: Context, api: ApiClient, tokens: TokenStore): ImageLoader {
        val http = api.http.newBuilder()
            .addInterceptor(MediaAuthInterceptor(api.root, tokens, api))
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(http)
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("media-cache"))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .build()
    }
}

/**
 * Adds `Authorization: Bearer …` to media reads, renews the token once on `401`.
 */
private class MediaAuthInterceptor(
    private val root: String,
    private val tokens: TokenStore,
    private val api: ApiClient
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Only our own backend, and only file reads: everything else (a GIF host, a
        // link preview) is left exactly as Coil built it.
        if (root.isEmpty() || !request.url.toString().startsWith(root) || !isMediaRead(request)) {
            return chain.proceed(request)
        }

        val token = tokens.accessToken
        val first = chain.proceed(if (token.isNullOrEmpty()) request else request.withToken(token))
        if (first.code != 401) return first

        // The access token expired between the last REST call and this image: renew it
        // once (runBlocking is fine here — Coil calls us from its own IO dispatcher).
        first.close()
        val renewed = runBlocking { api.renewAccessToken() }
            ?: return unauthorized(request)
        return chain.proceed(request.withToken(renewed))
    }

    private fun isMediaRead(request: okhttp3.Request): Boolean =
        request.method == "GET" && request.url.encodedPath.contains("/media/")

    private fun okhttp3.Request.withToken(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun unauthorized(request: okhttp3.Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Session expired")
        .body("".toResponseBody(null))
        .build()
}
