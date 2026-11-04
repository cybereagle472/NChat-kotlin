package com.nchat.app.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Raw brand palette. Never use these values directly from UI code - always go through
 * [NChatTheme.colorScheme] or [NcTheme.extra] so light/dark themes stay consistent.
 */
internal object NcPalette {
    val Emerald900 = Color(0xFF04392E)
    val Emerald800 = Color(0xFF06523F)
    val Emerald700 = Color(0xFF0B7D62)
    val Emerald600 = Color(0xFF0E9675)
    val Emerald500 = Color(0xFF12A97F)
    val Mint500 = Color(0xFF20C997)
    val Mint200 = Color(0xFFA8ECD2)
    val Mint100 = Color(0xFFDFF6EE)
    val Mint50 = Color(0xFFF0FBF6)

    val Ink900 = Color(0xFF0E1A17)
    val Ink700 = Color(0xFF243B35)
    val Ink500 = Color(0xFF5C736C)
    val Ink300 = Color(0xFF9CAFAA)
    val Ink100 = Color(0xFFE3EBE7)

    val Cloud0 = Color(0xFFFFFFFF)
    val Cloud50 = Color(0xFFF4F7F5)
    val Cloud100 = Color(0xFFE9F0EC)
    val Cloud200 = Color(0xFFD8E4DF)

    val Night950 = Color(0xFF070F12)
    val Night900 = Color(0xFF0B1418)
    val Night850 = Color(0xFF101B20)
    val Night800 = Color(0xFF14232A)
    val Night700 = Color(0xFF1B2E35)
    val Night600 = Color(0xFF24393F)
    val Night300 = Color(0xFF8FA3AA)
    val Night100 = Color(0xFFE7EEF0)

    val Amber = Color(0xFFE3A62F)
    val Coral = Color(0xFFE5533D)
    val CoralSoft = Color(0xFFFFE9E4)
    val Sky = Color(0xFF3E93D6)
}

/**
 * Semantic colors that Material 3 does not model directly (chat bubbles, presence,
 * wallpaper gradients, story rings, ...). Provided through [LocalNcColors].
 */
@Immutable
data class NcExtraColors(
    val online: Color,
    val offline: Color,
    val typing: Color,
    val outgoingBubble: Color,
    val outgoingText: Color,
    val incomingBubble: Color,
    val incomingText: Color,
    val bubbleTime: Color,
    val divider: Color,
    val mutedText: Color,
    val unreadBadge: Color,
    val unreadBadgeText: Color,
    val mutedBadge: Color,
    val pinnedTint: Color,
    val wallpaperTop: Color,
    val wallpaperBottom: Color,
    val storyRing: List<Color>,
    val headerGradient: List<Color>,
    val isDark: Boolean
)

private val LightExtraColors = NcExtraColors(
    online = NcPalette.Mint500,
    offline = NcPalette.Ink300,
    typing = NcPalette.Emerald600,
    outgoingBubble = NcPalette.Mint100,
    outgoingText = NcPalette.Ink900,
    incomingBubble = NcPalette.Cloud0,
    incomingText = NcPalette.Ink900,
    bubbleTime = NcPalette.Ink500,
    divider = NcPalette.Cloud100,
    mutedText = NcPalette.Ink500,
    unreadBadge = NcPalette.Mint500,
    unreadBadgeText = Color.White,
    mutedBadge = NcPalette.Ink300,
    pinnedTint = NcPalette.Mint50,
    wallpaperTop = NcPalette.Mint50,
    wallpaperBottom = NcPalette.Cloud50,
    storyRing = listOf(NcPalette.Mint500, NcPalette.Emerald600, NcPalette.Mint200),
    headerGradient = listOf(NcPalette.Emerald800, NcPalette.Emerald700, NcPalette.Emerald600),
    isDark = false
)

private val DarkExtraColors = NcExtraColors(
    online = NcPalette.Mint500,
    offline = NcPalette.Night600,
    typing = NcPalette.Mint500,
    outgoingBubble = NcPalette.Emerald800,
    outgoingText = Color(0xFFEAF7F2),
    incomingBubble = NcPalette.Night800,
    incomingText = NcPalette.Night100,
    bubbleTime = Color(0xFF9FB2B8),
    divider = NcPalette.Night700,
    mutedText = NcPalette.Night300,
    unreadBadge = NcPalette.Mint500,
    unreadBadgeText = NcPalette.Night900,
    mutedBadge = NcPalette.Night600,
    pinnedTint = NcPalette.Night850,
    wallpaperTop = NcPalette.Night950,
    wallpaperBottom = NcPalette.Night900,
    storyRing = listOf(NcPalette.Mint500, NcPalette.Emerald500, NcPalette.Emerald900),
    headerGradient = listOf(NcPalette.Night900, NcPalette.Night850, NcPalette.Emerald900),
    isDark = true
)

internal val NcLightColorScheme = lightColorScheme(
    primary = NcPalette.Emerald700,
    onPrimary = Color.White,
    primaryContainer = NcPalette.Mint100,
    onPrimaryContainer = NcPalette.Emerald800,
    secondary = NcPalette.Emerald600,
    onSecondary = Color.White,
    secondaryContainer = NcPalette.Mint50,
    onSecondaryContainer = NcPalette.Emerald800,
    tertiary = NcPalette.Sky,
    onTertiary = Color.White,
    background = NcPalette.Cloud50,
    onBackground = NcPalette.Ink900,
    surface = NcPalette.Cloud0,
    onSurface = NcPalette.Ink900,
    surfaceVariant = NcPalette.Cloud100,
    onSurfaceVariant = NcPalette.Ink500,
    outline = NcPalette.Cloud200,
    outlineVariant = NcPalette.Cloud100,
    error = NcPalette.Coral,
    onError = Color.White,
    errorContainer = NcPalette.CoralSoft,
    onErrorContainer = Color(0xFF7A1F12),
    scrim = Color(0xFF04110E),
    inverseSurface = NcPalette.Ink900,
    inverseOnSurface = NcPalette.Cloud50
)

internal val NcDarkColorScheme = darkColorScheme(
    primary = NcPalette.Mint500,
    onPrimary = NcPalette.Night900,
    primaryContainer = NcPalette.Emerald800,
    onPrimaryContainer = Color(0xFFD6F5EA),
    secondary = NcPalette.Mint200,
    onSecondary = NcPalette.Night900,
    secondaryContainer = NcPalette.Night700,
    onSecondaryContainer = NcPalette.Night100,
    tertiary = NcPalette.Sky,
    onTertiary = NcPalette.Night900,
    background = NcPalette.Night900,
    onBackground = NcPalette.Night100,
    surface = NcPalette.Night850,
    onSurface = NcPalette.Night100,
    surfaceVariant = NcPalette.Night700,
    onSurfaceVariant = NcPalette.Night300,
    outline = NcPalette.Night600,
    outlineVariant = NcPalette.Night700,
    error = Color(0xFFFF8A76),
    onError = NcPalette.Night900,
    errorContainer = Color(0xFF5C1B12),
    onErrorContainer = Color(0xFFFFD9D1),
    scrim = Color.Black,
    inverseSurface = NcPalette.Night100,
    inverseOnSurface = NcPalette.Night900
)

internal val LocalNcColors = staticCompositionLocalOf { LightExtraColors }

internal fun extraColorsFor(darkTheme: Boolean): NcExtraColors =
    if (darkTheme) DarkExtraColors else LightExtraColors
