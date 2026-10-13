package dev.opencode.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** TUI estetiği: monospace yazılar, kutulu paneller. Koyu ve açık palet. */
class OcPalette(
    val bg: Color,
    val panel: Color,
    val panelAlt: Color,
    val border: Color,
    val text: Color,
    val dim: Color,
    val accent: Color,
    val blue: Color,
    val warn: Color,
    val err: Color,
    val purple: Color,
    val userBg: Color,
    val codeBg: Color,
)

val OcDark = OcPalette(
    bg = Color(0xFF0B0F14),
    panel = Color(0xFF11171F),
    panelAlt = Color(0xFF0E141B),
    border = Color(0xFF22303C),
    text = Color(0xFFC9D7E3),
    dim = Color(0xFF6B7D8C),
    accent = Color(0xFF3FB950),   // yeşil
    blue = Color(0xFF58A6FF),
    warn = Color(0xFFD29922),
    err = Color(0xFFF85149),
    purple = Color(0xFFBC8CFF),
    userBg = Color(0xFF12202B),
    codeBg = Color(0xFF0A0E13),
)

val OcLight = OcPalette(
    bg = Color(0xFFF6F8FA),
    panel = Color(0xFFFFFFFF),
    panelAlt = Color(0xFFEDF1F5),
    border = Color(0xFFC9D1D9),
    text = Color(0xFF1F2328),
    dim = Color(0xFF57606A),
    accent = Color(0xFF1A7F37),
    blue = Color(0xFF0969DA),
    warn = Color(0xFF9A6700),
    err = Color(0xFFCF222E),
    purple = Color(0xFF8250DF),
    userBg = Color(0xFFDDF4FF),
    codeBg = Color(0xFFF0F3F6),
)

/** Erişilen her özellik güncel paleti okuduğu için tema değişince ekranlar yeniden çizilir. */
object Oc {
    var palette: OcPalette by mutableStateOf(OcDark)
        private set

    fun setDark(dark: Boolean) {
        palette = if (dark) OcDark else OcLight
    }

    val bg get() = palette.bg
    val panel get() = palette.panel
    val panelAlt get() = palette.panelAlt
    val border get() = palette.border
    val text get() = palette.text
    val dim get() = palette.dim
    val accent get() = palette.accent
    val blue get() = palette.blue
    val warn get() = palette.warn
    val err get() = palette.err
    val purple get() = palette.purple
    val userBg get() = palette.userBg
    val codeBg get() = palette.codeBg
}

private fun ocColorScheme(p: OcPalette, dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = p.accent,
            onPrimary = p.bg,
            secondary = p.blue,
            background = p.bg,
            onBackground = p.text,
            surface = p.panel,
            onSurface = p.text,
            surfaceVariant = p.panelAlt,
            onSurfaceVariant = p.dim,
            outline = p.border,
            error = p.err,
        )
    } else {
        lightColorScheme(
            primary = p.accent,
            onPrimary = p.bg,
            secondary = p.blue,
            background = p.bg,
            onBackground = p.text,
            surface = p.panel,
            onSurface = p.text,
            surfaceVariant = p.panelAlt,
            onSurfaceVariant = p.dim,
            outline = p.border,
            error = p.err,
        )
    }

private fun ocTypography(p: OcPalette) = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = p.text,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = p.text,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = p.dim,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = p.text,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = p.text,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = p.text,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = p.dim,
    ),
)

/**
 * Tema kabuğu. [dark] null ise sistem teması izlenir; aksi halde seçilen palet kullanılır.
 */
@Composable
fun OpenCodeTheme(dark: Boolean? = null, content: @Composable () -> Unit) {
    val isDark = dark ?: isSystemInDarkTheme()
    Oc.setDark(isDark)
    val p = Oc.palette
    MaterialTheme(
        colorScheme = ocColorScheme(p, isDark),
        typography = ocTypography(p),
        content = content,
    )
}