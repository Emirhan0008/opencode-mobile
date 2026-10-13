package dev.opencode.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** TUI estetiği: koyu zemin, monospace yazılar, kutulu paneller. */
object Oc {
    val bg = Color(0xFF0B0F14)
    val panel = Color(0xFF11171F)
    val panelAlt = Color(0xFF0E141B)
    val border = Color(0xFF22303C)
    val text = Color(0xFFC9D7E3)
    val dim = Color(0xFF6B7D8C)
    val accent = Color(0xFF3FB950)   // yeşil
    val blue = Color(0xFF58A6FF)
    val warn = Color(0xFFD29922)
    val err = Color(0xFFF85149)
    val purple = Color(0xFFBC8CFF)
    val userBg = Color(0xFF12202B)
    val codeBg = Color(0xFF0A0E13)
}

private val OcColors = darkColorScheme(
    primary = Oc.accent,
    onPrimary = Oc.bg,
    secondary = Oc.blue,
    background = Oc.bg,
    onBackground = Oc.text,
    surface = Oc.panel,
    onSurface = Oc.text,
    surfaceVariant = Oc.panelAlt,
    onSurfaceVariant = Oc.dim,
    outline = Oc.border,
    error = Oc.err,
)

private val OcTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = Oc.text,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = Oc.text,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = Oc.dim,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = Oc.text,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = Oc.text,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Oc.text,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = Oc.dim,
    ),
)

@Composable
fun OpenCodeTheme(content: @Composable () -> Unit) {
    // Uygulama her zaman koyu temadır (TUI görünümü)
    isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = OcColors,
        typography = OcTypography,
        content = content,
    )
}