package com.qianbi.writer.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE0B36A),
    onPrimary = Color(0xFF2A1E08),
    secondary = Color(0xFF9FD8C0),
    onSecondary = Color(0xFF0B241C),
    background = Color(0xFF11151A),
    onBackground = Color(0xFFE8EDF2),
    surface = Color(0xFF1B222B),
    onSurface = Color(0xFFE8EDF2),
    surfaceVariant = Color(0xFF26303B),
    onSurfaceVariant = Color(0xFFB9C6D2),
    error = Color(0xFFE57373),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors) {
        // 页面根布局不是 Surface，LocalContentColor 默认是黑色 → 顶栏文字会看不见，这里统一兜底
        CompositionLocalProvider(
            LocalContentColor provides DarkColors.onBackground,
            content = content,
        )
    }
}
