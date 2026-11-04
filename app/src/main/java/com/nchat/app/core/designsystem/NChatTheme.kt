package com.nchat.app.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * NChat theme. The reference client is a single light "glassmorphism violet" theme,
 * so there is no dark variant to mirror yet.
 */
@Composable
fun NChatTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context.findActivity()
        if (activity != null) {
            SideEffect {
                val controller = WindowCompat.getInsetsController(activity.window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = true
            }
        }
    }

    CompositionLocalProvider(LocalNcColors provides extraColors()) {
        MaterialTheme(
            colorScheme = NcColorScheme,
            typography = NcTypography,
            shapes = NcShapes,
            content = content
        )
    }
}

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
