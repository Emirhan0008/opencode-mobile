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
    MODE_COMMAND,
    MODE_PROMPT,
    MODE_TOGGLE,
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
        "birinci" to 1, "bir" to 1, "1" to 1, "ilk" to 1, "ilki" to 1,
        "ikinci" to 2, "iki" to 2, "2" to 2,
        "üçüncü" to 3, "ucuncu" to 3, "üç" to 3, "uc" to 3, "3" to 3,
        "dördüncü" to 4, "dorduncu" to 4, "dört" to 4, "dort" to 4, "4" to 4,
        "beşinci" to 5, "besinci" to 5, "beş" to 5, "bes" to 5, "5" to 5,
    )

    /** "git/aç/geç" fiillerinin Türkçe ekli hâlleri (buyurma, istek, öneri). */
    private val OPEN_VERBS = listOf(
        "aç", "ac", "açar", "acar", "açar mısın", "acar misin", "açsana", "acsana",
        "açın", "acin", "açalım", "acalim", "açabilir", "acabilir",
        "açabilir misin", "acabilir misin", "açar mısın acaba",
        "göster", "goster", "göstersene", "gostersene", "gösterir misin",
        "gosterir misin", "gösterelim", "gosterelim",
        "git", "gitsene", "gidelim", "gitsek", "geç", "gec", "geçsene", "gecsene",
        "geçelim", "gecelim", "geçer misin", "gecer misin", "geçsek", "gecsek",
        "dön", "don", "dönsene", "donsene", "dönelim", "donelim",
        "getir", "getsene", "gelsene", "gel", "bak", "baksana",
    )

    /** "kapat/durdur" fiillerinin ekli hâlleri. */
    private val CLOSE_VERBS = listOf(
        "kapat", "kapatır mısın", "kapatir misin", "kapatsana", "kapatın", "kapatin",
        "kapatabilir", "kapatabilir misin", "kapativer", "kapatıver",
        "durdur", "durdurur musun", "durdursana", "durdurun", "dur", "kes",
        "sustur", "gizle", "gizlesene", "devre dışı bırak", "devre disi birak",
        "iptal", "iptal et", "kapat bakalım",
    )

    /** "değiştir/seç" fiillerinin ekli hâlleri. */
    private val CHANGE_VERBS = listOf(
        "değiştir", "degistir", "değiştirsene", "degistirsene", "değiştirir misin",
        "degistirir misin", "değiş", "degis", "değişelim", "degiselim",
        "çevir", "cevir", "çevirsene", "cevirsene", "değiş tokuş",
        "seç", "sec", "seçsene", "secsene", "seçelim", "secelim",
        "yap", "yapsana", "yapalım", "yapalim", "ayarla", "ayarlasana",
        "kullan", "kullansana", "kullanayım", "kullanayim", "geç", "gec",
        "geçsene", "gecsene", "geçelim", "gecelim", "aktar", "aktarsana",
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
        fun anyOf(keys: Collection<String>) = keys.any { k -> containsWord(t, k) }
        fun starts(vararg keys: String) = keys.any { k -> t == k || t.startsWith("$k ") }
        /** Türkçe ekler: "oturumu", "ajanı", "modeli", "temayı" köküyle eşleşir. */
        fun stems(vararg keys: String) = keys.any { k -> words.any { w -> w.startsWith(k) } }
        fun wantsOpen() = anyOf(OPEN_VERBS)
        fun wantsClose() = anyOf(CLOSE_VERBS)
        fun wantsChange() = anyOf(CHANGE_VERBS)

        // ------------------------------------------------------- mod geçişi
        // "diğer moda geç" iki yönlü çalışır: prompt → komut, komut → prompt.
        if (has(
                "diğer moda geç", "diger moda gec", "diğer moda geçsene",
                "diger moda gecsene", "diğer modu aç", "diger modu ac",
                "diğer moda geçelim", "diger moda gecelim", "diğer moda al",
                "diger moda al", "öbür moda geç", "obur moda gec",
                "öteki moda geç", "oteki moda gec", "modu değiştir", "modu degistir",
                "mod değiştir", "mod degistir", "modu çevir", "modu cevir",
            )
        ) {
            return AppCommand(AppAction.MODE_TOGGLE)
        }
        if (has(
                "komut moduna geç", "komut moduna gec", "komut modunu aç", "komut modunu ac",
                "komut modu", "komut moduna al", "komuta geç", "komuta gec",
                "kontrol moduna geç", "kontrol moduna gec", "kontrol modu",
                "uygulama moduna geç", "uygulama moduna gec", "uygulama kontrol modu",
                "app moduna geç", "app moduna gec", "uygulamayı kontrol et modu",
                "uygulamayi kontrol et modu",
            )
        ) {
            return AppCommand(AppAction.MODE_COMMAND)
        }
        if (has(
                "normal moda geç", "normal moda gec", "normal mod", "normal moda dön",
                "normal moda don", "prompt moduna geç", "prompt moduna gec", "prompt modu",
                "sohbet moduna geç", "sohbet moduna gec", "sohbet modu",
                "yazışma moduna geç", "yazisma moduna gec", "konuşma moduna geç",
                "konusma moduna gec", "komut modundan çık", "komut modundan cik",
                "komuttan çık", "komuttan cik",
            )
        ) {
            return AppCommand(AppAction.MODE_PROMPT)
        }

        // ---------------------------------------------------------- yardım
        if (starts("yardım", "yardim", "help") ||
            has(
                "neler yapabilirsin", "ne yapabilirsin", "neler yapabiliyorsun",
                "komutları say", "komutlari say", "komutları listele", "komutlari listele",
                "komutları göster", "komutlari goster", "komutlar neler", "hangi komutlar var",
                "komutları oku", "komutlari oku", "yardım eder misin", "yardim eder misin",
                "bana yardım et", "bana yardim et",
            )
        ) {
            return AppCommand(AppAction.HELP)
        }

        // ------------------------------------------------------- gezinme
        if (has(
                "uygulamayı aç", "uygulamayi ac", "uygulamayı göster", "uygulamayi goster",
                "uygulamaya git", "uygulamaya dön", "uygulamaya don",
                "ekranı aç", "ekrani ac", "ana ekran", "ana ekrana dön", "ana ekrana don",
                "uygulama ekranı", "uygulama ekrani", "uygulamanın kendisi",
            )
        ) {
            return AppCommand(AppAction.OPEN_APP)
        }
        if (stems("ayar", "tercih", "ayarlar") && wantsOpen()) {
            return AppCommand(AppAction.OPEN_SETTINGS)
        }
        if (has(
                "ayarları aç", "ayarlari ac", "ayarlara git", "ayarlara geç", "ayarlara gec",
                "ayarları göster", "ayarlari goster", "ayarlar ekranı", "ayarlar ekrani",
                "ayarları getir", "ayarlari getir", "tercihleri aç", "tercihleri ac",
            )
        ) {
            return AppCommand(AppAction.OPEN_SETTINGS)
        }
        if (stems("telegram")) {
            if (wantsOpen()) return AppCommand(AppAction.OPEN_TELEGRAM)
        }
        if (has(
                "oturum listesine dön", "oturum listesine don", "listeye dön", "listeye don",
                "oturumlara dön", "oturumlara don", "oturumlara git", "oturum listesine git",
                "bir önceki ekran", "bir onceki ekran", "önceki ekran", "onceki ekran",
                "önceki ekrana", "onceki ekrana", "önceki sayfaya", "onceki sayfaya",
                "bir önceki ekrana", "bir onceki ekrana", "bir önceki sayfaya", "bir onceki sayfaya",
                "geri dönelim", "geri donelim",
            ) || t == "geri" || t == "geri dön" || t == "geri don" ||
            t == "geri gel" || t == "geri dönelim"
        ) {
            return AppCommand(AppAction.BACK)
        }
        if (has(
                "neredeyim", "neredeyiz", "hangi ekran", "hangi ekrandayım", "hangi ekrandayim",
                "hangi sayfa", "hangi sayfadayım", "hangi sayfadayim",
                "ne açık", "ne acik", "ne açık şu an", "ne acik su an",
            )
        ) {
            return AppCommand(AppAction.WHERE)
        }

        // ------------------------------------------------------- oturumlar
        if (has(
                "oturumları listele", "oturumlari listele", "oturumları göster",
                "oturumlari goster", "hangi oturumlar var", "oturum listesi",
                "oturumları say", "oturumlari say", "oturumları oku", "oturumlari oku",
                "oturumları sırala", "oturumlari sirala", "son oturumlar",
                "oturumlarım", "oturumlarim", "oturum listesini göster",
                "oturum listesini goster", "kaç oturum var", "kac oturum var",
                "oturumları sayar mısın", "oturumlari sayar misin",
                "son oturumları sayar mısın", "son oturumlari sayar misin",
                "oturumları gösterir misin", "oturumlari gosterir misin",
            ) || (stems("oturum", "sohbet") && has(
                "listele", "liste", "say", "sayar", "sayısı", "sayisi", "kaç", "kac",
                "sırala", "sirala",
            ) && sessionName(t) == null)
        ) {
            return AppCommand(AppAction.LIST_SESSIONS)
        }
        // "yeni oturum" her yerde yakalanır ("deneme adında yeni oturum aç" gibi).
        if (has(
                "yeni oturum", "yeni bir oturum", "yeni sohbet", "yeni bir sohbet",
                "yeni konuşma", "yeni konusma", "yeni chat", "temiz oturum",
                "boş oturum", "bos oturum", "sıfırdan oturum", "sifirdan oturum",
                "oturum oluştur", "oturum olustur", "oturum açsana", "oturum acsana",
            )
        ) {
            return AppCommand(AppAction.NEW_SESSION, arg = newSessionTitle(t))
        }
        if (stems("oturum", "sohbet") && wantsOpen() &&
            !has("listele", "göster", "goster", "say", "oku")
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
        if (t == "yenile" || t == "yenilen" || t == "tazele" || t == "güncelle" ||
            t == "guncelle" || stems("yenile") || stems("tazele") ||
            has("sayfayı yenile", "sayfayi yenile", "oturumları yenile", "oturumlari yenile",
                "listeyi yenile", "verileri yenile")
        ) {
            return AppCommand(AppAction.REFRESH)
        }
        if (has(
                "koyu tema", "karanlık tema", "karanlik tema", "dark tema", "siyah tema",
                "koyu mod", "karanlık mod", "karanlik mod", "gece modu", "gece moduna geç",
                "gece moduna gec", "dark mod", "siyah mod", "karanlık taraf", "karanlik taraf",
                "koyu moda", "karanlık moda", "karanlik moda", "gece moduna", "siyah moda",
            )
        ) {
            return AppCommand(AppAction.THEME_DARK)
        }
        if (has(
                "açık tema", "acik tema", "aydınlık tema", "aydinlik tema", "light tema",
                "beyaz tema", "açık mod", "acik mod", "aydınlık mod", "aydinlik mod",
                "gündüz modu", "gunduz modu", "light mod", "aydınlık taraf", "aydinlik taraf",
                "açık moda", "acik moda", "aydınlık moda", "aydinlik moda",
                "gündüz moduna", "gunduz moduna", "aydınlık moduna", "aydinlik moduna",
            )
        ) {
            return AppCommand(AppAction.THEME_LIGHT)
        }
        if ((stems("tema", "temayı", "temayi", "temaya") || has("mod", "modu", "moda")) && wantsChange()) {
            return AppCommand(AppAction.THEME_TOGGLE)
        }
        if (stems("kota", "kred", "bakiye", "bakiyem", "limit") && !has("limit aşımı") ||
            has(
                "kalan token", "kalan kredi", "ne kadar kaldı", "ne kadar kaldi",
                "kotayı göster", "kotayi goster", "kotayı oku", "kotayi oku",
                "kotayı yenile", "kotayi yenile", "param ne kadar", "bakiye ne kadar",
            )
        ) {
            return AppCommand(AppAction.QUOTA)
        }
        if (stems("bildirim") && (wantsOpen() || has("etkinleştir", "etkinlestir", "aç bakalım"))) {
            return AppCommand(AppAction.NOTIFY_ON)
        }
        if (stems("bildirim") && wantsClose()) {
            return AppCommand(AppAction.NOTIFY_OFF)
        }
        if (has("arka plan", "arka planı", "arka plani", "arkaplan") && wantsOpen()) {
            return AppCommand(AppAction.BACKGROUND_ON)
        }
        if (has("arka plan", "arka planı", "arka plani", "arkaplan") && wantsClose()) {
            return AppCommand(AppAction.BACKGROUND_OFF)
        }
        if (has(
                "sesli komutu kapat", "sesli komut kapat", "sesli komutları kapat",
                "sesli komutlari kapat", "sesli komutu devre dışı bırak",
                "sesli komutu devre disi birak", "dinlemeyi kapat", "dinlemeyi kapatır mısın",
                "sesli asistanı kapat", "sesli asistani kapat", "sesli komutları devre dışı bırak",
            )
        ) {
            return AppCommand(AppAction.VOICE_OFF)
        }

        // ------------------------------------------------- ajan / model
        if (stems("ajan", "agent") && wantsChange()) {
            val name = words.lastOrNull {
                !it.startsWith("ajan") && !it.startsWith("agent") &&
                    !CHANGE_VERBS.contains(it)
            }
            return AppCommand(AppAction.SWITCH_AGENT, arg = name)
        }
        if (stems("model") && wantsChange()) {
            val name = words.lastOrNull {
                !it.startsWith("model") && !CHANGE_VERBS.contains(it)
            }
            return AppCommand(AppAction.SWITCH_MODEL, arg = name)
        }

        // -------------------------------------------------------- eylemler
        if (has(
                "son yanıtı oku", "son yaniti oku", "yanıtı oku", "yaniti oku",
                "cevabı oku", "cevabi oku", "son cevabı oku", "son cevabi oku",
                "son yanıtı tekrar oku", "son yaniti tekrar oku", "tekrar oku",
                "bunu oku", "okur musun", "okurmusun", "sesli oku", "yüksek sesle oku",
                "yuksek sesle oku", "özetle ve oku", "ozetle ve oku",
            ) || t == "özetle" || t == "ozetle"
        ) {
            return AppCommand(AppAction.READ_LAST)
        }
        if (has(
                "çalışmayı durdur", "calismayi durdur", "ajanı durdur", "ajani durdur",
                "turu durdur", "üretimi durdur", "uretimi durdur", "işlemi durdur",
                "islemi durdur", "çalışmayı kes", "calismayi kes", "yeter",
            ) || t == "durdur" || t == "iptal et" || t == "iptal" || t == "kes"
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
        Regex("(?:adı|adi|başlığı|basligi|ismi)\\s+(.+)$").find(t)?.let {
            return trimTail(it.groupValues[1])
        }
        Regex("(.+?)\\s+(?:adında|adinda|başlığında|basliginda|isminde|isimli)\\s+").find(t)?.let {
            return trimTail(it.groupValues[1])
        }
        // Bilinen kelimeleri at, kalanı başlık say.
        val noise = setOf(
            "yeni", "bir", "tane", "oturum", "sohbet", "konuşma", "konusma", "chat",
            "aç", "ac", "açsana", "acsana", "açar", "acar", "açar mısın", "acar misin",
            "oluştur", "olustur", "oluştursana", "olustursana",
            "başlat", "baslat", "başlatsana", "baslatsana", "lütfen", "lutfen",
            "bana", "adında", "adinda", "adı", "adi", "başlığı", "basligi",
            "isminde", "isimli", "sıfırdan", "sifirdan", "temiz", "boş", "bos",
        )
        val title = t.split(" ")
            .filter { it.isNotBlank() && it !in noise }
            .joinToString(" ")
            .trim()
        return title.ifBlank { null }?.let { trimTail(it) }
    }

    /** "fastpass oturumunu aç" → "fastpass"; "şu oturumu aç" → null */
    private fun sessionName(t: String): String? {
        val cut = t.substringBefore("oturum").substringBefore("sohbet").trim()
        val cleaned = cut
            .replace(Regex("^(şu|su|bu|o|son|sonuncu|yeni|önceki|onceki)\\s+"), "")
            .replace(Regex("^(aç|ac|git|geç|gec|göster|goster)\\s+"), "")
            .trim()
        if (cleaned.isBlank()) return null
        if (ORDINALS.keys.any { containsWord(cleaned, it) }) return null
        return cleaned
    }

    /** "gönder yarın toplantı var" → "yarın toplantı var" */
    private fun sendText(t: String): String? {
        val prefixes = listOf(
            "şunu gönder", "sunu gonder", "şunu yolla", "sunu yolla",
            "şunu ilet", "sunu ilet", "şunu gönderiver", "sunu gonderiver",
            "gönder", "gonder", "yolla", "ilet", "gönderiver", "gonderiver",
        )
        for (p in prefixes) {
            if (t.startsWith("$p ")) {
                return trimTail(t.removePrefix(p))
            }
        }
        // "… gönder" (en az iki kelime): uzun komut cümleleri yanlış yakalanmasın
        for (p in listOf("gönder", "gonder", "yolla", "ilet", "gönderiver", "gonderiver")) {
            if (t.endsWith(" $p")) {
                val body = t.removeSuffix(" $p").trim()
                if (body.split(" ").size >= 2) return trimTail(body)
            }
        }
        return null
    }

    private fun trimTail(s: String): String =
        s.trim().trim(':', '-', ' ').trim()

    private fun containsWord(t: String, w: String): Boolean =
        t == w || Regex("(^|\\s)${Regex.escape(w)}(\\s|$)").containsMatchIn(t)
}
