package com.nahfid.docvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0E7C7B)
private val TealDark = Color(0xFF0A5E5D)
private val Amber = Color(0xFFC47A12)

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = TealDark,
    tertiary = Amber
)
private val DarkColors = darkColorScheme(
    primary = Teal,
    secondary = TealDark,
    tertiary = Amber
)

@Composable
fun DocVaultTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
