package com.nchat.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Any non-2xx answer from the backend; [message] is the server's own `error` string. */
class ApiException(val status: Int, override val message: String) : Exception(message) {
    val isUnauthorized: Boolean get() = status == 401
}

/** Shared JSON configuration — the backend sends snake_case keys and extra fields. */
object NcJson {
    val format: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = true
    }

    val mediaType = "application/json; charset=utf-8".toMediaType()

    fun body(json: JsonObject): RequestBody = json.toString().toRequestBody(mediaType)
}

/**
 * Thin HTTP layer over the Rust backend (`server/`).
 *
 * The app talks to it only through the repositories, and only when a backend URL was baked in
 * at build time (`BuildConfig.NCHAT_API_BASE`) — an empty URL means the app keeps running on
 * the in-memory mock repositories, which is what the released demo APK does.
 *
 * Access tokens live 15 minutes; every request that gets a 401 is refreshed once (the server
 * rotates refresh tokens, so refreshes are serialised through [refreshMutex]) and retried.
 */
class ApiClient(
    rootUrl: String,
    private val tokens: TokenStore,
    val http: OkHttpClient = defaultClient()
) {

    val root: String = rootUrl.trim().trimEnd('/')

    /** `http(s)://` → `ws(s)://`, for `/realtime/connect`. */
    val socketRoot: String = when {
        root.startsWith("https://") -> "wss://" + root.removePrefix("https://")
        root.startsWith("http://") -> "ws://" + root.removePrefix("http://")
        else -> root
    }

    val isConfigured: Boolean get() = root.isNotEmpty()

    private val refreshMutex = Mutex()

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): String =
        send("GET", path, null, query)

    suspend fun post(path: String, body: JsonObject? = null): String =
        send("POST", path, body, emptyMap())

    suspend fun patch(path: String, body: JsonObject? = null): String =
        send("PATCH", path, body, emptyMap())

    suspend fun delete(path: String, body: JsonObject? = null): String =
        send("DELETE", path, body, emptyMap())

    /**
     * Multipart upload for `POST /media/upload`; returns the raw JSON body (which carries
     * `id`, `url`, `key`, `media_type`, `file_name`, `file_size`).
     */
    suspend fun upload(path: String, fileName: String, mimeType: String, bytes: ByteArray): String {
        check(isConfigured) { "No backend configured" }
        val media = (mimeType.ifBlank { "application/octet-stream" }).toMediaType()
        val body = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(media))
            .build()
        val request = Request.Builder()
            .url(url(path, emptyMap()))
            .apply { tokens.accessToken?.let { header("authorization", "Bearer $it") } }
            .post(body)
            .build()
        return ensureSuccess(execute(request))
    }

    private suspend fun send(
        method: String,
        path: String,
        body: JsonObject?,
        query: Map<String, String>
    ): String {
        check(isConfigured) { "No backend configured" }
        val payload = body?.let { NcJson.body(it) }
        val first = execute(build(method, url(path, query), payload, tokens.accessToken))
        if (first.status == 401) {
            val refreshed = refreshAccessToken()
            if (refreshed != null) {
                val retry = execute(build(method, url(path, query), payload, refreshed))
                return ensureSuccess(retry)
            }
        }
        return ensureSuccess(first)
    }

    private fun build(method: String, url: String, body: RequestBody?, token: String?): Request {
        val builder = Request.Builder().url(url)
        if (token != null) builder.header("authorization", "Bearer $token")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete(body)
            else -> builder.method(method, body ?: "".toRequestBody(NcJson.mediaType))
        }
        return builder.build()
    }

    private fun url(path: String, query: Map<String, String>): String {
        if (query.isEmpty()) return root + path
        val encoded = query.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return root + path + "?" + encoded
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun refreshAccessToken(): String? = refreshMutex.withLock {
        val refresh = tokens.refreshToken
        if (refresh.isNullOrEmpty()) {
            tokens.saveTokens(null, null)
            return@withLock null
        }
        val body = buildJsonObject { put("refresh_token", refresh) }
        val request = build("POST", url("/auth/refresh", emptyMap()), NcJson.body(body), null)
        val snapshot = execute(request)
        val json = runCatching { NcJson.format.parseToJsonElement(snapshot.body).jsonObject }
            .getOrNull()
        val access = json?.get("access_token")?.jsonPrimitive?.contentOrNull
        if (!snapshot.isSuccess || access.isNullOrEmpty()) {
            // The session is gone for good (revoked or expired) — sign the user out.
            tokens.saveTokens(null, null)
            return@withLock null
        }
        tokens.saveTokens(access, json?.get("refresh_token")?.jsonPrimitive?.contentOrNull ?: refresh)
        access
    }

    private suspend fun execute(request: Request): Snapshot = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            Snapshot(response.code, response.body?.string().orEmpty())
        }
    }

    private fun ensureSuccess(snapshot: Snapshot): String {
        if (snapshot.isSuccess) return snapshot.body
        throw ApiException(snapshot.status, messageOf(snapshot))
    }

    private fun messageOf(snapshot: Snapshot): String {
        val parsed = runCatching { NcJson.format.parseToJsonElement(snapshot.body).jsonObject }
            .getOrNull()
        val error = parsed?.get("error")?.jsonPrimitive?.contentOrNull
        return if (!error.isNullOrBlank()) error else "Request failed (${snapshot.status})"
    }

    private class Snapshot(val status: Int, val body: String) {
        val isSuccess: Boolean get() = status in 200..299
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
