package com.focusbank.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFB9F27C),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF162000),
    secondary = androidx.compose.ui.graphics.Color(0xFFFFB870),
    tertiary = androidx.compose.ui.graphics.Color(0xFF83D5D0),
    background = androidx.compose.ui.graphics.Color(0xFF101411),
    surface = androidx.compose.ui.graphics.Color(0xFF171D18),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF293229)
)

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF41651B),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = androidx.compose.ui.graphics.Color(0xFF9A4F00),
    tertiary = androidx.compose.ui.graphics.Color(0xFF006A68),
    background = androidx.compose.ui.graphics.Color(0xFFF8FAF3),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE4E9DC)
)

@Composable
fun FocusBankTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    // Edge-to-edge (enforced from targetSdk 35): the app draws behind transparent system bars, so
    // their icons must follow the app's own theme toggle rather than the system setting.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
