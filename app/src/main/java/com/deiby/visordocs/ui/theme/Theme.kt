package com.deiby.visordocs.ui.theme

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF00639B),
    onPrimary = Color(0xFFFFFFFF),
    surface = Color(0xFFFCFCFF),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF42474E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF95CCFF),
    onPrimary = Color(0xFF003353),
    surface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFF42474E),
    onSurfaceVariant = Color(0xFFC2C7CF),
)

@Composable
fun VisorDocsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You: en Android 12+ toma los colores del fondo de pantalla.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
