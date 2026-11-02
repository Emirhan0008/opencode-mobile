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
import androidx.compose.ui.unit.dp
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
    /** Sohbet balonlarının çerçeve renkleri — kullanıcı ve model için ayrı. */
    val userBorder: Color,
    val aiBorder: Color,
)

val OcDark = OcPalette(
    bg = Color(0xFF0A0D12),
    panel = Color(0xFF141A22),
    panelAlt = Color(0xFF1B232D),
    border = Color(0xFF2A3644),
    text = Color(0xFFE6EDF3),
    dim = Color(0xFF8B9AAB),
    accent = Color(0xFF3DDC84),   // canlı yeşil
    blue = Color(0xFF5AA9FF),
    warn = Color(0xFFF0B232),
    err = Color(0xFFFF6B6B),
    purple = Color(0xFFA78BFA),
    userBg = Color(0xFF14202C),
    codeBg = Color(0xFF0D1219),
    userBorder = Color(0xFF3B82F6),   // kullanıcı balonu: mavi
    aiBorder = Color(0xFF8B5CF6),     // model balonu: mor
)

val OcLight = OcPalette(
    bg = Color(0xFFF4F6F9),
    panel = Color(0xFFFFFFFF),
    panelAlt = Color(0xFFEFF3F8),
    border = Color(0xFFD5DDE6),
    text = Color(0xFF10151B),
    dim = Color(0xFF5B6B7C),
    accent = Color(0xFF0E9F5A),
    blue = Color(0xFF1D6FE8),
    warn = Color(0xFFA66A00),
    err = Color(0xFFD92D20),
    purple = Color(0xFF7C4DFF),
    userBg = Color(0xFFE8F1FF),
    codeBg = Color(0xFFF2F5F9),
    userBorder = Color(0xFF1D6FE8),
    aiBorder = Color(0xFF7C4DFF),
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
    val userBorder get() = palette.userBorder
    val aiBorder get() = palette.aiBorder
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
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        color = p.text,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        color = p.text,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = p.dim,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
        color = p.text,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = p.text,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = p.text,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = p.text,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = p.text,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 11.sp,
        color = p.dim,
    ),
)

/** Modern, yumuşak köşeler — tüm Material bileşenleri bunu kullanır. */
private val OcShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
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
        shapes = OcShapes,
        content = content,
    )
}