package com.nchat.app.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Root theme for NChat. Applies the Material 3 colour scheme, the NChat typography and
 * shape scale, and the extra semantic colours used by chat surfaces.
 */
@Composable
fun NChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) NcDarkColorScheme else NcLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context.findActivity()
        if (activity != null) {
            SideEffect {
                val controller = WindowCompat.getInsetsController(activity.window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalNcColors provides extraColorsFor(darkTheme)) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = NcTypography,
            shapes = NcShapes,
            content = content
        )
    }
}

/** Access point for NChat specific colours that Material 3 does not expose. */
object NcTheme {
    val extra: NcExtraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNcColors.current
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
