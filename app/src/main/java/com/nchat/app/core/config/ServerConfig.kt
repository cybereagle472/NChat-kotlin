package com.nchat.app.core.config

import android.content.Context

/**
 * Which backend this install talks to.
 *
 * Precedence: the value saved in Settings ▸ Server, then `BuildConfig.NCHAT_API_BASE`
 * (baked in at build time — the deployed NChat worker). There is no offline mode: the app
 * is a thin client over the backend, exactly like the reference web app.
 *
 * The value is read once when the repositories are created, so changing it asks for a
 * restart instead of trying to swap live sockets and repositories underneath the UI.
 */
object ServerConfig {

    private const val PREFS = "nchat_server"
    private const val KEY_BASE = "base_url"

    /** The saved override, or null when the build default should be used. */
    fun override(context: Context): String? = prefs(context)
        .getString(KEY_BASE, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun save(context: Context, url: String?) {
        val cleaned = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        prefs(context).edit().putString(KEY_BASE, cleaned).apply()
    }

    /** What the app will actually connect to. */
    fun effective(context: Context, buildDefault: String): String =
        (override(context) ?: buildDefault).trim().trimEnd('/')

    /** A cleared override means "use the build default", never "go offline". */
    fun isConfigured(context: Context, buildDefault: String): Boolean =
        effective(context, buildDefault).isNotEmpty()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
