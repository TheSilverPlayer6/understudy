package dev.understudy.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A restrained palette for the non-dynamic fallback. The app is a utility that people open to
// do one anxious thing — rescue a save file — so it should look calm and not compete for
// attention with the information on screen.
private val LightPrimary = Color(0xFF3B5F8A)
private val LightSecondary = Color(0xFF3E7A5E)
private val DarkPrimary = Color(0xFF9FC3E8)
private val DarkSecondary = Color(0xFF8FD6B0)

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    secondary = LightSecondary,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    secondary = DarkSecondary,
)

/**
 * Material 3 with dynamic colour where the platform supports it.
 *
 * Dynamic colour is API 31+; below that we fall back to the static scheme rather than to
 * nothing, so the app looks intentional on an Android 8 tablet as well as on a current Pixel.
 */
@Composable
fun UnderstudyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography, content = content)
}
