package com.example.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = Navy900,
    primaryContainer = AmberContainer,
    onPrimaryContainer = AmberLight,
    secondary = BlueInfo,
    onSecondary = Color.White,
    secondaryContainer = Navy700,
    onSecondaryContainer = BlueLight,
    tertiary = EmeraldSuccess,
    onTertiary = Color.White,
    background = BellSurfaceDark,
    onBackground = Slate100,
    surface = BellCardDark,
    onSurface = Slate100,
    surfaceVariant = Navy800,
    onSurfaceVariant = Slate200,
    outline = Slate600,
    error = RoseError,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = AmberDark,
    onPrimary = Color.White,
    primaryContainer = AmberLight,
    onPrimaryContainer = AmberContainer,
    secondary = BlueInfo,
    onSecondary = Color.White,
    secondaryContainer = BlueLight,
    onSecondaryContainer = Navy800,
    tertiary = EmeraldSuccess,
    onTertiary = Color.White,
    background = BellSurfaceLight,
    onBackground = Slate800,
    surface = BellCardLight,
    onSurface = Slate800,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    outline = Slate200,
    error = RoseError,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Use our handcrafted school bell palette
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
