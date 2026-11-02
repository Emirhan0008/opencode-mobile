package dev.opencode.mobile.voice

/**
 * Sesli komutlarla **uygulamayı kontrol etme** sözlüğü.
 *
 * Amaç: "ayarları aç", "yeni oturum aç", "koyu tema", "şunu gönder …" gibi
 * cümleleri bir LLM'e göndermeden, yerelde ve anında tanımak. Bu yüzden
 * eşleştirme **deterministiktir**: kalıp varsa komuttur, yoksa cümle normal
 * prompt akışına düşer. Böylece "gönder" gibi bir kelime geçen uzun bir istek
 * yanlışlıkla komut sayılmaz.
 */
enum class AppAction {
    HELP,
    OPEN_APP,
    OPEN_SETTINGS,
    OPEN_TELEGRAM,
    BACK,
    WHERE,
    LIST_SESSIONS,
    OPEN_SESSION,
    NEW_SESSION,
    REFRESH,
    THEME_DARK,
    THEME_LIGHT,
    THEME_TOGGLE,
    QUOTA,
    NOTIFY_ON,
    NOTIFY_OFF,
    BACKGROUND_ON,
    BACKGROUND_OFF,
    VOICE_OFF,
    SWITCH_AGENT,
    SWITCH_MODEL,
    SEND,
    INTERRUPT,
    READ_LAST,
}

data class AppCommand(
    val action: AppAction,
    /** Komuta özgü metin: oturum adı, prompt, ajan/model adı, başlık. */
    val arg: String? = null,
    /** Oturum komutlarında sıra numarası (1–5). */
    val number: Int? = null,
)

object AppCommandParser {

    private val ORDINALS = mapOf(
        "birinci" to 1, "bir" to 1, "1" to 1,
        "ikinci" to 2, "iki" to 2, "2" to 2,
        "üçüncü" to 3, "ucuncu" to 3, "üç" to 3, "uc" to 3, "3" to 3,
        "dördüncü" to 4, "dorduncu" to 4, "dört" to 4, "dort" to 4, "4" to 4,
        "beşinci" to 5, "besinci" to 5, "beş" to 5, "bes" to 5, "5" to 5,
    )

    fun normalize(text: String): String =
        text.lowercase()
            .replace('İ', 'i').replace('I', 'ı')
            .replace(Regex("[.,!?;:\"'()\\[\\]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Cümle bir uygulama komutuysa döndürür; değilse null. */
    fun parse(raw: String): AppCommand? {
        val t = normalize(raw)
        if (t.isBlank()) return null
        val words = t.split(" ").filter { it.isNotBlank() }

        fun has(vararg keys: String) = keys.any { k -> containsWord(t, k) }
        fun starts(vararg keys: String) = keys.any { k -> t == k || t.startsWith("$k ") }
        /** Türkçe ekler: "oturumu", "ajanı", "modeli", "temayı" köküyle eşleşir. */
        fun stems(vararg keys: String) = keys.any { k -> words.any { w -> w.startsWith(k) } }

        // ---------------------------------------------------------- yardım
        if (starts("yardım", "yardim") || has("neler yapabilirsin", "komutları say", "komutlari say")) {
            return AppCommand(AppAction.HELP)
        }

        // ------------------------------------------------------- gezinme
        if (has("uygulamayı aç", "uygulamayi ac", "ekranı aç", "ekrani ac", "uygulamaya git")) {
            return AppCommand(AppAction.OPEN_APP)
        }
        if (has("ayarları aç", "ayarlari ac", "ayarlara git", "ayarları göster", "ayarlari goster")) {
            return AppCommand(AppAction.OPEN_SETTINGS)
        }
        if (has("telegram") || stems("telegram")) {
            if (has("aç", "ac", "git", "göster", "goster")) {
                return AppCommand(AppAction.OPEN_TELEGRAM)
            }
        }
        if (has("oturum listesine dön", "oturum listesine don", "listeye dön", "listeye don") ||
            t == "geri" || t == "geri dön" || t == "geri don"
        ) {
            return AppCommand(AppAction.BACK)
        }
        if (has("neredeyim", "hangi ekran", "ne açık", "ne acik")) {
            return AppCommand(AppAction.WHERE)
        }

        // ------------------------------------------------------- oturumlar
        if (has(
                "oturumları listele", "oturumlari listele", "oturumları göster",
                "oturumlari goster", "hangi oturumlar var", "oturum listesi",
                "oturumları say", "oturumlari say",
            )
        ) {
            return AppCommand(AppAction.LIST_SESSIONS)
        }
        // "yeni oturum" her yerde yakalanır ("deneme adında yeni oturum aç" gibi).
        if (has("yeni oturum", "yeni bir oturum")) {
            return AppCommand(AppAction.NEW_SESSION, arg = newSessionTitle(t))
        }
        if (stems("oturum") && has("aç", "ac", "geç", "gec", "git") &&
            !has("listele", "göster", "goster")
        ) {
            val number = ORDINALS.entries
                .filter { containsWord(t, it.key) }
                .maxByOrNull { it.key.length }
                ?.value
            val name = sessionName(t)
            if (number != null || name != null) {
                return AppCommand(AppAction.OPEN_SESSION, arg = name, number = number)
            }
        }

        // ---------------------------------------------------------- genel
        if (t == "yenile" || t == "yenilen" || has("sayfayı yenile", "sayfayi yenile", "oturumları yenile", "oturumlari yenile")) {
            return AppCommand(AppAction.REFRESH)
        }
        if (has("koyu tema", "karanlık tema", "karanlik tema", "dark tema")) {
            return AppCommand(AppAction.THEME_DARK)
        }
        if (has("açık tema", "acik tema", "aydınlık tema", "aydinlik tema", "light tema")) {
            return AppCommand(AppAction.THEME_LIGHT)
        }
        if (stems("tema") && has("değiştir", "degistir", "değiş", "degis")) {
            return AppCommand(AppAction.THEME_TOGGLE)
        }
        if (has("kota", "kredim", "kalan token")) {
            return AppCommand(AppAction.QUOTA)
        }
        if (has("bildirimleri", "bildirim") && has("aç", "ac", "etkinleştir", "etkinlestir")) {
            return AppCommand(AppAction.NOTIFY_ON)
        }
        if (has("bildirimleri", "bildirim") && has("kapat")) {
            return AppCommand(AppAction.NOTIFY_OFF)
        }
        if (has("arka plan", "arka planı", "arka plani") && has("aç", "ac", "etkinleştir", "etkinlestir")) {
            return AppCommand(AppAction.BACKGROUND_ON)
        }
        if (has("arka plan", "arka planı", "arka plani") && has("kapat")) {
            return AppCommand(AppAction.BACKGROUND_OFF)
        }
        if (has("sesli komutu kapat", "sesli komut kapat", "sesli komutu devre dışı bırak")) {
            return AppCommand(AppAction.VOICE_OFF)
        }

        // ------------------------------------------------- ajan / model
        if (stems("ajan") && has("geç", "gec", "yap", "değiştir", "degistir", "seç", "sec")) {
            val name = words.lastOrNull {
                !it.startsWith("ajan") && it !in setOf("geç", "gec", "yap", "değiştir", "degistir", "seç", "sec")
            }
            return AppCommand(AppAction.SWITCH_AGENT, arg = name)
        }
        if (stems("model") && has("geç", "gec", "yap", "değiştir", "degistir", "seç", "sec")) {
            val name = words.lastOrNull {
                !it.startsWith("model") && it !in setOf("geç", "gec", "yap", "değiştir", "degistir", "seç", "sec")
            }
            return AppCommand(AppAction.SWITCH_MODEL, arg = name)
        }

        // -------------------------------------------------------- eylemler
        if (has("son yanıtı oku", "son yaniti oku", "yanıtı oku", "yaniti oku", "cevabı oku", "cevabi oku") ||
            t == "özetle" || t == "ozetle"
        ) {
            return AppCommand(AppAction.READ_LAST)
        }
        if (has("çalışmayı durdur", "calismayi durdur", "ajanı durdur", "ajani durdur", "turu durdur", "üretimi durdur", "uretimi durdur") ||
            t == "durdur" || t == "iptal et"
        ) {
            return AppCommand(AppAction.INTERRUPT)
        }

        // --------------------------------------------------------- gönder
        // Yalnızca net kalıplar: "gönder X", "şunu gönder: X", "X gönder".
        sendText(t)?.let { return AppCommand(AppAction.SEND, arg = it) }

        return null
    }

    /** "yeni oturum aç adı test" / "test adında yeni oturum aç" → "test" */
    private fun newSessionTitle(t: String): String? {
        Regex("(?:adı|adi|başlığı|basligi)\\s+(.+)$").find(t)?.let {
            return trimTail(it.groupValues[1])
        }
        Regex("(.+?)\\s+(?:adında|adinda|başlığında|basliginda)\\s+yeni").find(t)?.let {
            return trimTail(it.groupValues[1])
        }
        val rest = t
            .replace(Regex("^(yeni|bir|bir tane)\\s+"), "")
            .replace(Regex("^(oturum)\\s+"), "")
            .replace(Regex("^(aç|ac|oluştur|olustur|başlat|baslat)\\s*"), "")
            .trim()
        return rest.ifBlank { null }?.let { trimTail(it) }
    }

    /** "fastpass oturumunu aç" → "fastpass"; "şu oturumu aç" → null */
    private fun sessionName(t: String): String? {
        val cut = t.substringBefore("oturum").trim()
        val cleaned = cut
            .replace(Regex("^(şu|su|bu|o|son|sonuncu|yeni)\\s+"), "")
            .replace(Regex("^(aç|ac|git|geç|gec)\\s+"), "")
            .trim()
        if (cleaned.isBlank()) return null
        if (ORDINALS.keys.any { containsWord(cleaned, it) }) return null
        return cleaned
    }

    /** "gönder yarın toplantı var" → "yarın toplantı var" */
    private fun sendText(t: String): String? {
        val prefixes = listOf("şunu gönder", "sunu gonder", "gönder", "gonder", "şunu yolla", "sunu yolla", "yolla")
        for (p in prefixes) {
            if (t.startsWith("$p ")) {
                return trimTail(t.removePrefix(p))
            }
        }
        // "… gönder" (en az iki kelime): uzun komut cümleleri yanlış yakalanmasın
        if (t.endsWith(" gönder") || t.endsWith(" gonder")) {
            val body = t.removeSuffix(" gönder").removeSuffix(" gonder").trim()
            if (body.split(" ").size >= 2) return trimTail(body)
        }
        return null
    }

    private fun trimTail(s: String): String =
        s.trim().trim(':', '-', ' ').trim()

    private fun containsWord(t: String, w: String): Boolean =
        t == w || Regex("(^|\\s)${Regex.escape(w)}(\\s|$)").containsMatchIn(t)
}
