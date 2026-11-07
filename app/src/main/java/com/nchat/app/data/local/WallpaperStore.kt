package com.nchat.app.data.local

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.nchat.app.core.designsystem.NcTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A conversation background, chosen once and used by every chat.
 *
 * WhatsApp has had chat wallpapers forever and its absence is the kind of thing a user notices in
 * the first minute without being able to name it: every conversation looked like every other one.
 * The presets are plain gradients drawn by Compose — no image files, no download, no per-chat
 * storage — so "pick a background" cannot fail, cannot go missing and costs nothing at runtime.
 *
 * The choice is per device by definition (it is not information about the account), so it lives in
 * `SharedPreferences` like the other local preferences.
 */
class WallpaperStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _selection = MutableStateFlow(
        runCatching { prefs.getString(KEY, Wallpaper.Default.id) }.getOrNull()
            ?.takeIf { id -> Wallpaper.entries.any { it.id == id } }
            ?: Wallpaper.Default.id
    )

    /** The chosen preset's id. Collect it anywhere the conversation background is drawn. */
    val selection: StateFlow<String> = _selection.asStateFlow()

    val current: Wallpaper
        get() = Wallpaper.entries.firstOrNull { it.id == _selection.value } ?: Wallpaper.Default

    fun set(id: String) {
        if (Wallpaper.entries.none { it.id == id }) return
        _selection.value = id
        runCatching { prefs.edit().putString(KEY, id).apply() }
    }

    private companion object {
        const val PREFS = "nchat_wallpaper"
        const val KEY = "chat_wallpaper"
    }
}

/**
 * The wallpapers, as data rather than images.
 *
 * [Default] is the app's own body gradient, so "no choice made" looks exactly like NChat has
 * always looked — the setting can be ignored without the app looking unfinished.
 */
enum class Wallpaper(val id: String, val label: String, private val stops: List<Color>) {
    Default("default", "NChat", emptyList()),
    Violet("violet", "Violet", listOf(Color(0xFFF3E8FF), Color(0xFFE9D5FF), Color(0xFFDDD6FE))),
    Sunset("sunset", "Sunset", listOf(Color(0xFFFFE4E6), Color(0xFFFFEDD5), Color(0xFFFEF3C7))),
    Mint("mint", "Mint", listOf(Color(0xFFDCFCE7), Color(0xFFD1FAE5), Color(0xFFCCFBF1))),
    Sky("sky", "Sky", listOf(Color(0xFFE0F2FE), Color(0xFFDBEAFE), Color(0xFFE0E7FF))),
    Sand("sand", "Sand", listOf(Color(0xFFFAF5EB), Color(0xFFF5EDE0), Color(0xFFEDE4D3))),
    Midnight("midnight", "Midnight", listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF1E293B))),
    Graphite("graphite", "Graphite", listOf(Color(0xFF1F2937), Color(0xFF111827), Color(0xFF0F172A)));

    /** True when the background is dark, so overlays can pick light text. */
    val isDark: Boolean get() = this == Midnight || this == Graphite

    /**
     * The brush to paint behind a conversation.
     *
     * Composable because the default wallpaper *is* the app theme's body gradient — reading it
     * outside a composition would be reading a `CompositionLocal` from nowhere.
     */
    @Composable
    fun brush(): Brush =
        if (this == Default) Brush.linearGradient(NcTheme.extra.bodyGradient)
        else Brush.linearGradient(stops)
}
