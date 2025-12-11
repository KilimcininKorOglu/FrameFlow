package com.keremgok.frameflow.ui.theme

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

// FrameFlow brand colors - Twitch-inspired purple with modern twist
private val FrameFlowPurple = Color(0xFF9146FF)
private val FrameFlowPurpleLight = Color(0xFFB380FF)
private val FrameFlowPurpleDark = Color(0xFF772CE8)
private val FrameFlowAccent = Color(0xFF00D4AA)

// Streaming state colors
val StreamingLive = Color(0xFF00D4AA)      // Green - active/live
val StreamingStopped = Color(0xFFFF4444)   // Red - stopped/error
val StreamingConnecting = Color(0xFFFFAA00) // Orange - connecting

private val DarkColorScheme = darkColorScheme(
    primary = FrameFlowPurple,
    secondary = FrameFlowAccent,
    tertiary = FrameFlowPurpleDark,
    background = Color(0xFF0E0E10),
    surface = Color(0xFF18181B),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = FrameFlowPurple,
    secondary = FrameFlowAccent,
    tertiary = FrameFlowPurpleDark,
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun FrameFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
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
        content = content
    )
}
