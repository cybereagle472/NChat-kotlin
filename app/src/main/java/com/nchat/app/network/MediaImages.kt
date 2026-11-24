package com.nchat.app.network

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.ErrorResult
import coil.request.ImageResult
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.TimeUnit

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
 *
 * The token and the refresh are passed in as lambdas rather than as objects: building the
 * image loader must not drag in the token store or the API client, so a problem in either
 * can never stop the app from starting with pictures that simply lack a token.
 */
object MediaImages {

    /** 128 MB of decoded originals/posts on disk — plenty for a phone, capped on purpose. */
    private const val DISK_CACHE_BYTES = 128L * 1024 * 1024

    fun loader(
        context: Context,
        rootUrl: String,
        accessToken: () -> String?,
        renewSession: suspend () -> String?
    ): ImageLoader {
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                MediaAuthInterceptor(
                    root = rootUrl.trim().trimEnd('/'),
                    accessToken = accessToken,
                    renewSession = renewSession
                )
            )
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(http)
            // Photos and posters of encrypted conversations are opened on the phone first.
            .components { add(SealedImageInterceptor()) }
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
 * A picture of an encrypted conversation.
 *
 * Its address carries the key in its fragment (see [SealedMedia]). What the server holds is
 * sealed, so handing that address to Coil's own fetcher would download bytes no decoder can
 * read. This step comes first: the file is fetched and opened by [MediaFiles], and Coil is
 * given the opened copy on disk instead. Any other address passes through untouched.
 *
 * A picture that cannot be fetched or does not open is the ordinary error tile.
 */
internal class SealedImageInterceptor : coil.intercept.Interceptor {
    override suspend fun intercept(chain: coil.intercept.Interceptor.Chain): ImageResult {
        val request = chain.request
        val address = request.data as? String ?: return chain.proceed(request)
        // The thumbnail of a file that is still on its way: a picture written out as text.
        SealedMedia.inlineImageBytes(address)?.let { picture ->
            return chain.proceed(request.newBuilder().data(java.nio.ByteBuffer.wrap(picture)).build())
        }
        if (!SealedMedia.isSealed(address)) return chain.proceed(request)
        val file = MediaFiles.local(address).getOrElse { error ->
            return ErrorResult(request.error, request, error)
        }
        return chain.proceed(request.newBuilder().data(file).build())
    }
}

/**
 * Adds `Authorization: Bearer …` to media reads and renews the token once on `401`.
 *
 * It never throws: any failure inside the refresh path returns a plain `401` response, which
 * Coil turns into its normal error placeholder. A picture that cannot load is a picture that
 * cannot load — it is not a reason to kill the app.
 */
internal class MediaAuthInterceptor(
    private val root: String,
    private val accessToken: () -> String?,
    private val renewSession: suspend () -> String?
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // Only our own backend, and only file reads: everything else (a GIF host, a link
        // preview) is left exactly as Coil built it.
        if (!isOurMedia(request)) return chain.proceed(request)

        val token = runCatching { accessToken() }.getOrNull()
        val first = chain.proceed(if (token.isNullOrEmpty()) request else request.withToken(token))
        if (first.code != 401) return first

        // The access token expired between the last REST call and this image: renew it once
        // (runBlocking is fine here — Coil calls us from its own IO dispatcher).
        first.close()
        val renewed = runCatching { runBlocking { renewSession() } }.getOrNull()
            ?: return unauthorized(request)
        return runCatching { chain.proceed(request.withToken(renewed)) }
            .getOrElse { unauthorized(request) }
    }

    private fun isOurMedia(request: Request): Boolean {
        return isBackendMedia(root, request)
    }

    private fun Request.withToken(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun unauthorized(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Session expired")
        .body("".toResponseBody(null))
        .build()
}

/** Compare actual origins, not string prefixes such as `example.com.attacker.test`. */
internal fun isBackendMedia(root: String, request: Request): Boolean {
    val base = root.toHttpUrlOrNull() ?: return false
    val target = request.url
    if (target.scheme != base.scheme || target.host != base.host || target.port != base.port) return false
    val mediaPath = base.encodedPath.trimEnd('/') + "/media/"
    return request.method == "GET" && target.encodedPath.startsWith(mediaPath)
}
