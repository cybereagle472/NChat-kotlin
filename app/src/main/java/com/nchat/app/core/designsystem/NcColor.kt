package com.nchat.app.core.designsystem

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette lifted 1:1 from the nchat-friends Tailwind theme:
 * brand gradient violet-500 -> purple-500 -> fuchsia-500, slate text scale,
 * violet/purple/fuchsia-50 app background.
 */
internal object NcPalette {
    val Violet500 = Color(0xFF8B5CF6)
    val Purple500 = Color(0xFFA855F7)
    val Fuchsia500 = Color(0xFFD946EF)
    val Violet600 = Color(0xFF7C3AED)
    val Violet700 = Color(0xFF6D28D9)

    val Violet50 = Color(0xFFF5F3FF)
    val Purple50 = Color(0xFFFAF5FF)
    val Fuchsia50 = Color(0xFFFDF4FF)
    val Violet300 = Color(0xFFC4B5FD)

    val Slate900 = Color(0xFF0F172A)
    val Slate800 = Color(0xFF1E293B)
    val Slate700 = Color(0xFF334155)
    val Slate600 = Color(0xFF475569)
    val Slate500 = Color(0xFF64748B)
    val Slate400 = Color(0xFF94A3B8)
    val Slate300 = Color(0xFFCBD5E1)
    val Slate200 = Color(0xFFE2E8F0)
    val Slate100 = Color(0xFFF1F5F9)
    val Slate50 = Color(0xFFF8FAFC)

    val Green500 = Color(0xFF22C55E)
    val Green300 = Color(0xFF86EFAC)
    val Sky500 = Color(0xFF0EA5E9)
    val Red500 = Color(0xFFEF4444)
    val Amber500 = Color(0xFFF59E0B)
}

/**
 * Semantic surfaces and colours that Material 3 does not model: the glass layers,
 * bubble fills and the translucent chrome used across NChat.
 */
@Immutable
data class NcExtraColors(
    val brandGradient: List<Color>,
    val bodyGradient: List<Color>,
    val bubbleMineGradient: List<Color>,
    val composerGradient: List<Color>,

    val glassFill: Color,
    val glassBorder: Color,
    val glassShadow: Color,
    val rowFill: Color,
    val rowBorder: Color,

    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textMuted: Color,

    val online: Color,
    val onlineRing: Color,
    val readTick: Color,
    val typing: Color,

    val bubbleIncoming: Color,
    val bubbleIncomingText: Color,
    val bubbleMeta: Color,
    val bubbleMetaOnGradient: Color,

    val divider: Color,
    val unreadBadgeGradient: List<Color>
)

private val NcLightExtraColors = NcExtraColors(
    brandGradient = listOf(NcPalette.Violet500, NcPalette.Purple500, NcPalette.Fuchsia500),
    bodyGradient = listOf(NcPalette.Violet50, NcPalette.Purple50, NcPalette.Fuchsia50),
    bubbleMineGradient = listOf(NcPalette.Violet500, NcPalette.Purple500),
    composerGradient = listOf(NcPalette.Purple500, NcPalette.Violet500, NcPalette.Violet600),

    glassFill = Color(0x38FFFFFF),
    glassBorder = Color(0x8CFFFFFF),
    glassShadow = Color(0x2E000000),
    rowFill = Color(0xB3FFFFFF),
    rowBorder = Color(0xCCFFFFFF),

    textPrimary = NcPalette.Slate900,
    textSecondary = NcPalette.Slate700,
    textTertiary = NcPalette.Slate500,
    textMuted = NcPalette.Slate400,

    online = NcPalette.Green500,
    onlineRing = NcPalette.Slate50,
    readTick = NcPalette.Sky500,
    typing = NcPalette.Violet500,

    bubbleIncoming = Color.White,
    bubbleIncomingText = NcPalette.Slate800,
    bubbleMeta = NcPalette.Slate400,
    bubbleMetaOnGradient = Color(0xB3FFFFFF),

    divider = NcPalette.Slate200,
    unreadBadgeGradient = listOf(NcPalette.Violet500, NcPalette.Purple500, NcPalette.Fuchsia500)
)

internal val NcColorScheme = lightColorScheme(
    primary = NcPalette.Violet500,
    onPrimary = Color.White,
    primaryContainer = NcPalette.Violet50,
    onPrimaryContainer = NcPalette.Violet700,
    secondary = NcPalette.Purple500,
    onSecondary = Color.White,
    secondaryContainer = NcPalette.Purple50,
    onSecondaryContainer = NcPalette.Violet700,
    tertiary = NcPalette.Fuchsia500,
    onTertiary = Color.White,
    background = NcPalette.Violet50,
    onBackground = NcPalette.Slate800,
    surface = Color.White,
    onSurface = NcPalette.Slate800,
    surfaceVariant = NcPalette.Slate100,
    onSurfaceVariant = NcPalette.Slate500,
    outline = NcPalette.Slate300,
    outlineVariant = NcPalette.Slate200,
    error = NcPalette.Red500,
    onError = Color.White,
    scrim = NcPalette.Slate900
)

internal val LocalNcColors = staticCompositionLocalOf { NcLightExtraColors }

internal fun extraColors(): NcExtraColors = NcLightExtraColors
