package com.localmotion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Copper,
    onPrimary = Ash,
    secondary = Sage,
    onSecondary = Ash,
    background = Ash,
    onBackground = Ink,
    surface = Sand,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = Copper,
    onPrimary = Ash,
    secondary = Sage,
    onSecondary = Ash,
    background = Ink,
    onBackground = Sand,
    surface = Graphite,
    onSurface = Sand,
)

@Composable
fun LocalMotionTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

