package com.nchat.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NChatColors = lightColorScheme(
    primary = Color(0xFF087E68), secondary = Color(0xFF20C997),
    background = Color(0xFFF6F8F7), surface = Color.White
)

@Composable
fun NChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NChatColors, content = content)
}
