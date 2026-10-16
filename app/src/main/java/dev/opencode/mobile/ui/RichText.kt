package dev.opencode.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/*
 * TUI'ye yakın, bağımlılık eklemeyen basit metin biçimlendirici.
 *
 * Desteklenenler: ``` kod çitleri (dil etiketiyle), satır içi `kod`,
 * **kalın**, *italik*, # / ## / ### başlıklar ve [metin](url) bağlantısı
 * (altı çizili + renk, tıklama YOK). Tablo/resim/tıklanabilir link bilinçli
 * olarak atlandı.
 */

/** Metnin kod çiti dışı / içi parçaları. */
private sealed interface RichSeg {
    data class Txt(val text: String) : RichSeg
    data class Code(val lang: String, val code: String) : RichSeg
}

/** ```dil ... ``` çitlerini ayırır; çit yoksa tek metin parçası döner. */
private fun splitFences(text: String): List<RichSeg> {
    val out = mutableListOf<RichSeg>()
    val buf = StringBuilder()
    val code = StringBuilder()
    var inCode = false
    var lang = ""
    for (line in text.split('\n')) {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (!inCode) {
                if (buf.isNotEmpty()) {
                    out += RichSeg.Txt(buf.toString().trimEnd('\n'))
                    buf.clear()
                }
                inCode = true
                lang = trimmed.removePrefix("```").trim()
                code.clear()
            } else {
                out += RichSeg.Code(lang, code.toString().trimEnd('\n'))
                inCode = false
            }
            continue
        }
        if (inCode) code.append(line).append('\n') else buf.append(line).append('\n')
    }
    if (inCode && code.isNotEmpty()) out += RichSeg.Code(lang, code.toString().trimEnd('\n'))
    if (buf.isNotEmpty()) out += RichSeg.Txt(buf.toString().trimEnd('\n'))
    return out
}

private val INLINE = Regex(
    "(\\*\\*[^*\\n]+\\*\\*)" +                 // **kalın**
        "|(\\*[^*\\n]+\\*)" +                  // *italik*
        "|(`[^`\\n]+`)" +                      // `kod`
        "|(\\[[^\\]\\n]+\\]\\([^)\\n]+\\))",  // [metin](url)
)

/** Tek bir satır içi parçayı (kalın/italik/kod/link) biçimli ekler. */
private fun AnnotatedString.Builder.emitToken(token: String, codeBg: Color, linkColor: Color) {
    when {
        token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(token.substring(2, token.length - 2))
        }

        token.startsWith("`") -> withStyle(
            SpanStyle(background = codeBg, fontFamily = FontFamily.Monospace),
        ) {
            append(token.substring(1, token.length - 1))
        }

        token.startsWith("[") -> {
            val label = token.substringAfter('[').substringBefore(']')
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                append(label)
            }
        }

        token.startsWith("*") -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            append(token.substring(1, token.length - 1))
        }

        else -> append(token)
    }
}

/** Başlık satırı öneki — döngü içinde yeniden derlenmesin diye sabit. */
private val HEADER = Regex("^#{1,3} ")

/** Tüm metni AnnotatedString'e çevirir; başlık satırları hafif büyük/kalın olur. */
private fun buildInline(
    text: String,
    codeBg: Color,
    linkColor: Color,
    baseSize: Float,
): AnnotatedString = buildAnnotatedString {
    val lines = text.split('\n')
    for ((idx, line) in lines.withIndex()) {
        if (idx > 0) append('\n')
        val header = HEADER.find(line)
        val level = header?.value?.length?.minus(1) ?: 0
        val content = if (header != null) line.substring(header.value.length) else line
        val emit: () -> Unit = {
            var last = 0
            for (m in INLINE.findAll(content)) {
                if (m.range.first > last) append(content.substring(last, m.range.first))
                emitToken(m.value, codeBg, linkColor)
                last = m.range.last + 1
            }
            if (last < content.length) append(content.substring(last))
        }
        if (level > 0) {
            // # → +2sp, ## → +1sp, ### → +0sp (abartmadan)
            withStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = (baseSize + (3 - level).coerceAtLeast(0)).sp,
                ),
            ) { emit() }
        } else {
            emit()
        }
    }
}

/**
 * Markdown-benzeri metni TUI'ye yakın biçimde çizer. Kod blokları yatay
 * kaydırılabilir monospace kutularda, satır içi öğeler ise span olarak gösterilir.
 */
@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Oc.text,
) {
    val segments = remember(text) { splitFences(text) }
    val codeBg = Oc.codeBg
    val linkColor = Oc.blue
    val baseSize = if (style.fontSize.isSp) style.fontSize.value else 13f

    Column(modifier) {
        for (seg in segments) {
            when (seg) {
                is RichSeg.Txt -> if (seg.text.isNotBlank()) {
                    // Akış (delta) sırasında her karede yeniden ayrıştırılmasın diye önbellek.
                    val annotated = remember(seg.text, codeBg, linkColor, baseSize) {
                        buildInline(seg.text, codeBg, linkColor, baseSize)
                    }
                    Text(annotated, style = style, color = color)
                }

                is RichSeg.Code -> CodeBlock(seg.lang, seg.code)
            }
        }
    }
}

/** Yatay kaydırılabilir, zeminli monospace kod bloğu; varsa dil etiketi üstte. */
@Composable
private fun CodeBlock(lang: String, code: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (lang.isNotBlank()) {
            Text(
                lang,
                color = Oc.dim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .background(Oc.codeBg)
                .horizontalScroll(rememberScrollState())
                .padding(6.dp),
        ) {
            Text(
                code,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Oc.text,
                    fontFamily = FontFamily.Monospace,
                ),
                softWrap = false,
            )
        }
    }
}

// ------------------------------------------------------------- sayı biçimleri

/** 1_234 → "1,2k", 1_234_567 → "1,2M", 1_234_567_890 → "1,23G" (Türkçe ondalık virgül). */
internal fun fmtCount(n: Long): String {
    val a = kotlin.math.abs(n)
    return when {
        a < 1_000 -> n.toString()
        a < 1_000_000 -> oneDecimal(n / 1_000.0) + "k"
        a < 1_000_000_000 -> oneDecimal(n / 1_000_000.0) + "M"
        else -> twoDecimal(n / 1_000_000_000.0) + "G"
    }
}

/** Milisaniye → "12,4 sn". */
internal fun fmtDuration(ms: Long): String =
    oneDecimal(ms / 1000.0) + " sn"

/** Maliyet → "$0,012" (3 ondalık). */
internal fun fmtCost(cost: Double): String = "$" + threeDecimal(cost)

private fun oneDecimal(v: Double): String =
    String.format(Locale.US, "%.1f", v).replace('.', ',')

private fun twoDecimal(v: Double): String =
    String.format(Locale.US, "%.2f", v).replace('.', ',')

private fun threeDecimal(v: Double): String =
    String.format(Locale.US, "%.3f", v).replace('.', ',')
