package com.nchat.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF075E54),
    secondary = Color(0xFF25D366),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun NChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
