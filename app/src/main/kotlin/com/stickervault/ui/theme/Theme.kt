package com.stickervault.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF1F6F5C)
private val TealLight = Color(0xFF7FD1B9)

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = Teal,
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    secondary = TealLight,
)

@Composable
fun StickerVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
