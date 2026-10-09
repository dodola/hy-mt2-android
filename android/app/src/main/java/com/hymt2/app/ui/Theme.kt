package com.hymt2.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(primary = Color(0xFF0B57D0), secondary = Color(0xFF5E6B7E))
private val Dark = darkColorScheme(primary = Color(0xFFA8C7FA), secondary = Color(0xFFB4BCC8))

@Composable
fun HyMtTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
