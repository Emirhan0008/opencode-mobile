package dev.opencode.mobile.voice

import android.content.Context
import android.util.Log
import dev.opencode.mobile.AppControl
import dev.opencode.mobile.data.Settings
import dev.opencode.mobile.net.Api
import dev.opencode.mobile.net.num
import dev.opencode.mobile.net.obj
import dev.opencode.mobile.net.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * Sesli komut akışının **durum makinesi**. Servisten bağımsız tutuldu ki
 * test edilebilsin ve servis yalnızca yaşam döngüsü/kablo işini yapsın.
 *
 * Akış (ekrana bakmadan, kilitli ekranda) — **eller serbest**: tuş yalnızca
 * başlatmak için gerekir, gerisi konuşarak yürür:
 *
 *   [DINLE] → STT → [OKU: "şunu mu söyledin …"] → otomatik dinle → "evet"
 *        → [OTURUM SOR] → otomatik dinle → "iki" → prompt gönder
 *        → tur bitene kadar bekle → modelin YANITINI özetle → [OKU: özet]
 *
 * Sözler: evet/onayla/tamam · hayır/tekrar · iptal · bir…beş.
 *
 * Özet okunduktan sonra da dinlemeye devam edilir: "tekrar oku", "daha detaylı",
 * "yeni komut", "tamam/iptal". Böylece telefon cebite girerken tek tetikle
 * birden çok iş yapılabilir.
 */
class VoiceFlow(
    private val context: Context,
    private val settings: Settings,
    private val audio: AudioEngine,
    private val notify: (String) -> Unit,
    /** Aşama değişince çağrılır (bildirimi renkli/başlıklı güncellemek için). */
    private val onStage: (Stage, String) -> Unit = { _, _ -> },
) {

    enum class Stage { IDLE, LISTENING, CONFIRMING, PICKING, SENDING, WAITING, SPEAKING }

    /** En son yayınlanan durum metni — aşama değişiminde de taşınır. */
    private var lastState: String = ""

    @Volatile var stage: Stage = Stage.IDLE
        private set(value) {
            field = value
            runCatching { onStage(value, lastState) }
        }

    /** Durum metni + aşama bildirimi (eski `onState` çağrılarının yerini alır). */
    private fun onState(text: String) {
        lastState = text
        runCatching { notify(text) }
    }

    private var pendingText: String? = null
    private var targetSessionId: String? = null

    /** Onay cümlesinde söylenen numara — onayla birlikte oturum seçimini de yapar. */
    private var pendingPick: Int? = null

    /** Okunan son özet — "tekrar oku" için yeniden özetlemeden kullanılır. */
    private var lastSummary: String? = null

    /**
     * Son yanıtın **ham** metni (özetlenmemiş). Takip sırasında "kritik
     * çelişkiler hangileriydi" gibi bir soru sorulursa cevap özetten değil
     * bu tam metinden çıkarılır.
     */
    private var lastRawAnswer: String? = null

    /** Aynı yanıt için kaç kez takip komutu dinlendiği (sonsuz döngü koruması). */
    private var followUps = 0

    /**
     * `true` iken dinlenen cümle **yalnızca uygulama komutu** olarak yorumlanır;
     * prompt akışına (onay → oturum seç → gönder) hiç girilmez.
     *
     * Kullanıcı "diğer moda geç" dediğinde bu bayrak döner ve servis yaşadığı
     * sürece korunur — her dinlemede yeniden söylemek gerekmez.
     */
    @Volatile
    var commandMode = false
        private set

    /** TTS bittikten sonra mikrofonu açmadan önce beklenen süre (yankı önleme). */
    private suspend fun afterSpeechPause() = delay(500)

    /** Konuşma bitti: kaydı hemen kapat (sessizlik beklemez). */
    fun finishListening() {
        VoiceLog.write("BİTTİ", "kayıt kapatılıyor")
        audio.finishNow()
    }

    // ------------------------------------------------------------- yardımcı

    private suspend fun api(): Api? {
        val conn = settings.connection.first() ?: return null
        return Api(conn.baseUrl, conn.token)
    }

    private suspend fun evren(): EvrenClient? {
        val base = settings.evrenBaseUrl.first()
        val asr = settings.evrenAsrKey.first()
        val tts = settings.evrenTtsKey.first()
        val sum = settings.evrenSumKey.first()
        if (asr.isBlank() || tts.isBlank()) return null
        return EvrenClient(base, asr, tts, sum)
    }

    private suspend fun voice(): String = settings.voiceName.first()

    /** Metni kulaklığa okur. Hata olursa sessizce geçer (akış ölmesin). */
    private suspend fun say(text: String, ev: EvrenClient?) {
        if (text.isBlank()) return
        stage = Stage.SPEAKING
        onState(text)
        val client = ev ?: return
        try {
            val bytes = client.speak(text, voice())
            Log.d(TAG, "TTS ${bytes.size} bayt: ${text.take(60)}")
            // Kayıttan çıkışa geçişte (SCO → A2DP / hoparlör) ses yolu hemen
            // hazır olmuyor; kısa bir nefes ilk hecenin kırpılmasını önler.
            runCatching { audio.closeInputRoute() }
            delay(250)
            audio.cue(AudioEngine.Cue.SPEAK_START)
            delay(150)
            audio.play(bytes)
            audio.cue(AudioEngine.Cue.SPEAK_END)
            Log.d(TAG, "okuma bitti: ${text.take(40)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "okuma hatası: ${e.message}")
            onState("okuma hatası: ${e.message}")
        }
    }

    /** Tuşla kesilebilir okuma: oynatma sırasında [stopSpeaking] çağrılır. */
    fun stopSpeaking() {
        audio.stop()
    }

    // --------------------------------------------------------------- akış

    /** Kulaklık tuşu. Aşamaya göre davranır. */
    suspend fun onButton() {
        when (stage) {
            Stage.SPEAKING -> {
                // Konuşmayı kes ve bekleme moduna dön.
                stopSpeaking()
                stage = Stage.IDLE
                onState("sessiz")
            }
            Stage.CONFIRMING -> confirmAndPick()
            Stage.PICKING -> {
                onState("numara bekleniyor…")
                listenForNumber()
            }
            Stage.IDLE, Stage.WAITING, Stage.SENDING -> listenAndConfirm()
            Stage.LISTENING -> Unit // kayıt kendi kendine biter
        }
    }

    /** 1) Dinle → 2) metni oku → 3) sesli onay al. Tuş gerekmez. */
    private suspend fun listenAndConfirm(attempt: Int = 1) {
        val ev = evren()
        if (ev == null) {
            onState("EVREN anahtarları eksik — Ayarlar → Sesli Komut")
            return
        }
        val api = api()
        // Bağlantı yokken de uygulama komutları çalışmalı ("ayarları aç" gibi);
        // bu yüzden erken dönmüyoruz — prompt akışında kontrol edilir.
        // Yeni bir komut başlıyor: önceki yanıtın bağlamı artık geçerli değil.
        if (attempt == 1) lastRawAnswer = null

        stage = Stage.LISTENING
        onState(if (attempt == 1) "dinliyorum…" else "tekrar dinliyorum…")
        VoiceLog.write("DİNLE", if (attempt == 1) "başladı" else "tekrar (deneme $attempt)")
        audio.resetFinish()
        audio.beep(start = true)
        audio.openInputRoute()
        val wav = try {
            audio.record()
        } finally {
            audio.closeInputRoute()
        }
        audio.beep(start = false)

        if (wav == null) {
            stage = Stage.IDLE
            if (attempt < 2) {
                say("Ses duyamadım. Bir kez daha dinliyorum.", ev)
                listenAndConfirm(attempt + 1)
            } else {
                say("Ses duyamadım. Hazır olduğunda dinle düğmesine bas.", ev)
            }
            return
        }

        onState("yazıya çeviriyorum…")
        val text = runCatching { ev.transcribe(wav) }.getOrElse { e ->
            stage = Stage.IDLE
            onState("STT hatası: ${e.message}")
            say("Yazıya çeviremedim. İnterneti kontrol et.", ev)
            return
        }

        if (text.isBlank()) {
            stage = Stage.IDLE
            VoiceLog.write("DUYDU", "(boş)")
            if (attempt < 2) {
                say("Anlayamadım. Bir kez daha dinliyorum.", ev)
                listenAndConfirm(attempt + 1)
            } else {
                say("Anlayamadım. Hazır olduğunda dinle düğmesine bas.", ev)
            }
            return
        }

        // Uygulama komutu mu? ("ayarları aç", "yeni oturum aç", "koyu tema"…)
        // Öyleyse onay sormadan yerelde yürütülür — komutlar deterministik.
        VoiceLog.text("DUYDU", text)
        if (handleAppCommand(text, api, ev)) return

        // Komut modu: tanınmayan cümle prompt olarak gönderilmez; kullanıcı
        // yalnızca komut verebilir. Yanlış duyulmuş olabilir, kısaca uyarıp
        // tekrar dinlenir (akış ölmez).
        if (commandMode) {
            stage = Stage.IDLE
            onState("komut anlaşılmadı: $text")
            say("Komut olarak anlayamadım. Yalnızca komut veriyorsun. Yardım için ne yapabilirsin de.", ev)
            listenAgain(api, ev)
            return
        }

        if (api == null) {
            stage = Stage.IDLE
            onState("OpenCode bağlantısı yok")
            say("OpenCode bağlantısı yok. Ayarları aç dersen ayarlara gidebilirim.", ev)
            return
        }

        pendingText = text
        onState("duyduğum: $text")

        if (settings.voiceConfirm.first()) {
            stage = Stage.CONFIRMING
            say("Şunu mu söyledin: $text", ev)
            // Ekstra "evet ya da hayır de" yok: okuma biter bitmez mikrofon açılır
            // ve kullanıcı ne derse o alınır (onay, düzeltme ya da numara).
            awaitConfirm(ev, attempt = 1)
        } else {
            confirmAndPick()
        }
    }

    /**
     * Okunan cümleden sonraki **tek** dinleme. Ayrı bir "evet/hayır de" turu
     * yoktur — kullanıcının söylediği her şey değerlendirilir:
     *
     *  - evet / onayla / tamam / gönder  → oturum seçimine geç
     *  - bir…beş                        → onayla **ve** o oturumu seç (tek nefes)
     *  - hayır / yanlış / tekrar        → yeniden dinle
     *  - iptal / vazgeç / dur           → akışı bitir
     *  - başka bir cümle                → düzeltme kabul edilir, yeni komut olur
     *  - sessizlik                      → ikinci turda sessizce geç (kilitlemesin)
     */
    private suspend fun awaitConfirm(ev: EvrenClient, attempt: Int) {
        afterSpeechPause()
        stage = Stage.LISTENING
        audio.resetFinish()
        audio.beep(start = true)
        audio.openInputRoute()
        val wav = try {
            audio.record(maxMs = 9_000, silenceMs = 900, noSpeechMs = 5_500)
        } finally {
            audio.closeInputRoute()
        }
        audio.beep(start = false)

        val answer = if (wav == null) "" else runCatching { ev.transcribe(wav) }.getOrDefault("")
        onState("cevap: ${answer.ifBlank { "—" }}")
        VoiceLog.text("CEVAP", answer)
        VoiceLog.write("BİTTİ", "onay dinlemesi tamamlandı")

        val choice = parseYesNo(answer)
        // Düzeltme cümlesi ("şunun yerine bu") oturum seçimi sayılmamalı;
        // yoksa oturum adı eşleşmesi düzeltmeyi yutuyordu.
        val isCorrection = parseCorrection(answer) != null || looksLikeCorrection(answer)
        // Tek nefeste onay+seçim: "evet iki", "iki", "mobile" gibi. Kısa ve
        // başka bir takip komutu olmayan cevaplarda numara/ad eşleştirilir.
        val pick = if (choice == false || isCorrection) null else run {
            val words = normalize(answer).split(" ").filter { it.isNotBlank() }
            val other = parseNewCommand(answer) || parseRepeat(answer) ||
                parseDetail(answer) || parseCancel(answer)
            if (words.isEmpty() || words.size > 5 || other) null
            else parseOrdinal(answer) ?: matchSession(answer)
        }

        when {
            pick != null -> {
                pendingPick = pick
                confirmAndPick()
            }
            // Kısmi düzeltme: "iki yerine üç", "son kelimeyi sil", "sonuna ekle".
            // Tüm cümleyi tekrar söylemek gerekmez.
            isCorrection -> {
                val current = pendingText.orEmpty()
                val edited = parseCorrection(answer)?.let { (target, repl) ->
                    replaceInText(current, target, repl)
                }
                val deletion = parseDeletion(answer)
                val addition = parseAddition(answer)
                val newText = when {
                    edited != null -> edited
                    deletion != null -> deleteFromText(current, deletion)
                    addition != null -> applyAddition(current, addition)
                    else -> null
                }
                VoiceLog.write(
                    "DÜZELTME",
                    "eski=\"${current.take(120)}\" yeni=\"${newText?.take(120) ?: "—"}\"",
                )
                if (newText != null && newText.isNotBlank()) {
                    pendingText = newText
                    onState("düzeltildi: $newText")
                    stage = Stage.CONFIRMING
                    say("Düzelttim: $newText", ev)
                    if (attempt < 5) {
                        awaitConfirm(ev, attempt + 1)
                    } else {
                        confirmAndPick()
                    }
                } else {
                    say("Neyi değiştireyim? Örneğin 'iki yerine üç' ya da 'son kelimeyi sil'.", ev)
                    if (attempt < 4) awaitConfirm(ev, attempt + 1) else restartListening()
                }
            }
            choice == true -> {
                pendingPick = null
                confirmAndPick()
            }
            choice == false -> {
                say("Peki, yeniden söyle.", ev)
                restartListening()
            }
            parseCancel(answer) -> {
                cancelCurrent()
                say("Vazgeçtim.", ev)
            }
            // Dolu ama "evet/hayır"a benzemeyen cümle: kullanıcı düzeltiyor olabilir.
            answer.isNotBlank() && attempt < 2 -> {
                pendingText = answer
                onState("duyduğum: $answer")
                stage = Stage.CONFIRMING
                say("Tamam: $answer", ev)
                awaitConfirm(ev, attempt + 1)
            }
            answer.isBlank() && attempt < 2 -> {
                // Sessizlik: tekrar sormadan bir kez daha dinle.
                awaitConfirm(ev, attempt + 1)
            }
            else -> {
                // İkinci turda da net bir şey yok: kullanıcıyı kilitlemek yerine devam et.
                confirmAndPick()
            }
        }
    }

    // ------------------------------------------------- kısmi düzeltme

    /**
     * Söylenen cümlenin yalnızca birkaç kelimesi yanlış duyulduysa tüm cümleyi
     * tekrar söylemek gerekmez. Şu kalıplar desteklenir:
     *
     *  - "istanbul yerine ankara"      → o kelimeyi değiştir
     *  - "son kelimeyi sil" / "iki kelime sil" → s sondan sil
     *  - "bursa'yı sil"                → o kelimeyi sil
     *  - "sonuna yarın ekle" / "ekle: yarın" → sona ekle
     *
     * Eşleştirme bulanıktır: STT bir kelimeyi azıcık farklı yazdıysa (bir harf
     * eksik/fazla) yine bulunur.
     */
    private val CORRECTION_HINTS = listOf(
        "yerine", "sil", "sıl", "ekle", "düzelt", "duzelt", "değiştir", "degistir",
        "çıkar", "cikar", "kaldır", "kaldir", "düzeltme",
    )

    private fun looksLikeCorrection(text: String): Boolean =
        hasAny(normalize(text), CORRECTION_HINTS)

    /** "X yerine Y" → (X, Y) */
    private fun parseCorrection(text: String): Pair<String, String>? {
        val m = Regex("(?iu)(.+?)\\s+yerine\\s+(.+)").find(text.trim()) ?: return null
        val target = cleanTarget(m.groupValues[1])
        val repl = m.groupValues[2].trim().trimEnd('.', ',', '!', '?', ';')
        if (target.isBlank()) return null
        return target to repl
    }

    /** Hedef kelime öbeğini sadeleştir: "şunu", "son", iyelik/hâl ekleri. */
    private fun cleanTarget(raw: String): String {
        var t = raw.trim().trim('"', '\'', '“', '”')
        t = t.replace(Regex("(?iu)^(şunu|sunu|şu|su|bu|o|son|sonuncu)\\s+"), "")
        t = t.replace(
            Regex("(?iu)\\s+(kelimesini|kelimeyi|kelimesi|sözcüğü|sozcugu|kısmını|kismini)$"),
            "",
        )
        return t.trim()
    }

    /** "son [iki] kelimeyi sil" → "last:2" ; "bursa'yı sil" → "word:bursa" */
    private fun parseDeletion(text: String): String? {
        val t = normalize(text)
        // son N kelimeyi sil
        val m = Regex(
            "(?:^|\\s)(son|sonuncu)?\\s*(bir|iki|üç|uc|dört|dort|beş|bes|\\d+)?\\s*" +
                "(kelime|kelimeler|sözcük|sozcuk)\\w*\\s*(sil|çıkar|cikar|kaldır|kaldir)",
        ).find(t)
        if (m != null) {
            val n = when (m.groupValues[2]) {
                "iki" -> 2
                "üç", "uc" -> 3
                "dört", "dort" -> 4
                "beş", "bes" -> 5
                else -> m.groupValues[2].toIntOrNull() ?: 1
            }
            return "last:$n"
        }
        // "X sil" / "X'i sil": sil'den önceki kısım hedeftir
        val cut = Regex("(?iu)\\s*(sil|çıkar|cikar|kaldır|kaldir)\\s*$").find(text.trim())
        if (cut != null) {
            val target = cleanTarget(text.trim().substring(0, cut.range.first))
            if (target.isNotBlank()) return "word:$target"
        }
        // "sil X"
        val head = Regex("(?iu)^\\s*(sil|çıkar|cikar|kaldır|kaldir)\\s+(.+)").find(text.trim())
        if (head != null) {
            val target = cleanTarget(head.groupValues[2])
            if (target.isNotBlank()) return "word:$target"
        }
        return null
    }

    /** "sonuna X ekle" / "X ekle" / "ekle X" → eklenecek metin */
    private fun parseAddition(text: String): String? {
        val t = text.trim()
        Regex("(?iu)(?:sonuna|sona|arkasına|arkasina)\\s+(.+?)\\s+ekle").find(t)?.let {
            return it.groupValues[1].trim().trimEnd('.', ',', '!', '?')
        }
        Regex("(?iu)^\\s*(?:ekle|ilave et)\\s+(.+)").find(t)?.let {
            return it.groupValues[1].trim().trimEnd('.', ',', '!', '?')
        }
        Regex("(?iu)(.+?)\\s+(?:ekle|ilave et)\\s*[.!?]?$").find(t)?.let {
            return it.groupValues[1].trim()
        }
        return null
    }

    private fun replaceInText(current: String, target: String, repl: String): String? {
        val words = current.split(" ").filter { it.isNotBlank() }.toMutableList()
        val want = normalize(target).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty() || want.isEmpty()) return null
        val n = want.size
        var bestAt = -1
        var bestScore = 0
        for (start in 0..(words.size - n)) {
            var score = 0
            for (i in 0 until n) if (tokenMatches(normalize(words[start + i]), want[i])) score++
            if (score > bestScore) {
                bestScore = score
                bestAt = start
            }
        }
        if (bestAt < 0 || bestScore < n) return null
        val out = words.toMutableList()
        repeat(n) { out.removeAt(bestAt) }
        if (repl.isNotBlank()) out.add(bestAt, repl)
        return out.joinToString(" ").trim()
    }

    private fun deleteFromText(current: String, spec: String): String? {
        val words = current.split(" ").filter { it.isNotBlank() }
        return when {
            spec.startsWith("last:") -> {
                val n = spec.removePrefix("last:").toIntOrNull() ?: 1
                if (words.size <= n) "" else words.dropLast(n).joinToString(" ")
            }
            spec.startsWith("word:") -> {
                val target = spec.removePrefix("word:")
                replaceInText(current, target, "")
            }
            else -> null
        }
    }

    private fun applyAddition(current: String, extra: String): String =
        (current.trim() + " " + extra.trim()).trim()

    /** Bulanık kelime eşleşmesi: birebir, önek veya 1-2 harf farkı. */
    private fun tokenMatches(a: String, b: String): Boolean {
        if (a == b) return true
        val x = a.trim('\'', '"', '.', ',', '!', '?')
        val y = b.trim('\'', '"', '.', ',', '!', '?')
        if (x == y) return true
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.length >= 4 && y.length >= 4 && (x.startsWith(y) || y.startsWith(x))) return true
        val d = editDistance(x, y)
        return d <= if (minOf(x.length, y.length) >= 6) 2 else 1
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /**
     * "Evet/hayır" sınıflandırması. Türkçe konuşmada sık geçen kısa onay ve
     * ret sözlerinin tamamı tanınır. Belirsizse null.
     */
    private fun parseYesNo(text: String): Boolean? {
        val t = normalize(text)
        if (t.isEmpty()) return null
        // Olumsuzlar önce: "hayır" içinde "evet" yok ama "yok" gibi kısa sözler
        // başka kelimelerin içinde geçebilir, bu yüzden tam kelime aranır.
        if (hasAny(t, NO_WORDS)) return false
        if (hasAny(t, YES_WORDS)) return true
        return null
    }

    private fun parseCancel(text: String): Boolean = hasAny(normalize(text), CANCEL_WORDS)

    /** "tekrar oku / bir daha / yeniden" — son özeti tekrar ister. */
    private fun parseRepeat(text: String): Boolean =
        hasAny(normalize(text), REPEAT_WORDS) && !hasAny(normalize(text), CANCEL_WORDS)

    /** "daha detaylı / uzun anlat / ayrıntılı" — ayrıntılı özet ister. */
    private fun parseDetail(text: String): Boolean = hasAny(normalize(text), DETAIL_WORDS)

    /**
     * Soru mu? Türkçede soru çoğu zaman soru ekiyle ya da soru sözcüğüyle
     * kurulur; ikisi de yakalanır. Tek kelimelik gürültü ("ne") soru sayılmaz —
     * en az iki kelime gerekir, yoksa yanlışlıkla LLM çağrısı yapılırdı.
     */
    private fun looksLikeQuestion(text: String): Boolean {
        val tokens = normalize(text).split(" ").filter { it.isNotBlank() }
        if (tokens.size < 2) return false
        val prefixes = listOf(
            "neyd", "neler", "nedir", "neyi", "neye", "ne ", "hang", "kaç", "kac",
            "kim", "nasıl", "nasil", "neden", "niye", "nerede", "neresi", "kaçtane",
        )
        val exact = listOf("ne", "mi", "mı", "mu", "mü", "midir", "mıdır", "mudur", "müdür")
        if (tokens.any { it in exact }) return true
        if (tokens.any { tk -> prefixes.any { p -> tk.startsWith(p) } }) return true
        val joined = tokens.joinToString(" ")
        return joined.startsWith("ne ") || joined.startsWith("ned")
    }

    /**
     * Takip sırasında sorulan soruyu yanıtlar. Bağlam, son yanıtın **ham**
     * metnidir (özet değil) — böylece "özet çıkarırken atlanan" ayrıntılar da
     * sorulabilir. Yanıt okunur ve mikrofon yeniden açılır; kullanıcı zincirleme
     * soru sorabilir.
     */
    private suspend fun answerQuestion(api: Api, ev: EvrenClient, sessionId: String, question: String) {
        val context = lastRawAnswer
            ?: runCatching { api.assistantText(sessionId) }.getOrDefault("")
        if (context.isBlank() || !ev.summaryConfigured()) {
            followUps = 0
            stage = Stage.IDLE
            onState("soru için yanıt metni yok")
            return
        }
        stage = Stage.WAITING
        onState("soru: $question")
        val reply = runCatching { ev.ask(question, context) }
            .getOrElse { e ->
                Log.w(TAG, "soru yanıtı hatası: ${e.message}")
                onState("soru hatası: ${e.message}")
                followUps = 0
                stage = Stage.IDLE
                return
            }
            .trim()
        if (reply.isBlank()) {
            followUps = 0
            stage = Stage.IDLE
            return
        }
        lastSummary = reply
        Log.d(TAG, "soru yanıtı (${reply.length} krk): ${reply.take(200)}")
        onState("cevap: $reply")
        runCatching { settings.setVoiceLastSpoken(reply) }
        say(reply, ev)
        followUp(api, ev, sessionId)
    }

    /** "yeni komut / yeni istek / başka bir şey" — yeni sesli komut başlatır. */
    private fun parseNewCommand(text: String): Boolean = hasAny(normalize(text), NEW_WORDS)

    /** "tamam / teşekkürler / yeter / dur" — iş bitti, akışı kapat. */
    private fun parseDone(text: String): Boolean =
        hasAny(normalize(text), DONE_WORDS) && !hasAny(normalize(text), DETAIL_WORDS)

    /** Türkçe küçük harf + noktalama temizliği (STT virgül/nokta ekler). */
    private fun normalize(text: String): String =
        text.lowercase()
            .replace('İ', 'i').replace('I', 'ı')
            .replace(Regex("[.,!?;:\"'()\\[\\]]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Tam kelime eşleşmesi: "yok" → "yoktur" içinde sayılmaz. */
    private fun hasAny(t: String, words: List<String>): Boolean =
        words.any { w -> t == w || Regex("(^|\\s)${Regex.escape(w)}(\\s|$)").containsMatchIn(t) }

    private companion object {
        const val TAG = "VoiceFlow"
        const val MAX_FOLLOW_UPS = 4

        /**
         * Bu sınırların altındaki **düz yazı** yanıtlar özetlenmeden doğrudan
         * okunur. TTS zaten 1.200 karakterde kestiği için bu, okunabilen en
         * uzun doğal metindir; ötesi özetlenir.
         */
        const val DIRECT_READ_MAX_CHARS = 1200
        const val DIRECT_READ_MAX_WORDS = 260

        val YES_WORDS = listOf(
            "evet", "evet efendim", "onayla", "onay", "onaylıyorum", "tamam", "tamamdır",
            "doğru", "dogru", "doğrudur", "olur", "olsun", "gönder", "gonder", "gönderelim",
            "gonderelim", "yolla", "yollayalım", "kabul", "kabul ediyorum", "aynen", "peki",
            "tabii", "tabi", "elbette", "kesinlikle", "he", "hee", "hı hı", "ok", "okey",
            "okay", "yes", "süper", "harika", "devam", "başla", "basla", "aynen öyle",
            "doğru anladın", "bu", "şu", "bu olsun", "gitsin", "yapıştır", "ilet", "iletelim",
        )
        val NO_WORDS = listOf(
            "hayır", "hayir", "yok", "yoktur", "olmaz", "olmasın", "yanlış", "yanlis",
            "değil", "degil", "tekrar", "yeniden", "bir daha", "düzelt", "duzelt",
            "sil", "temizle", "beğenmedim", "begenmedim", "no", "olmuyor", "yanlış duydun",
            "öyle değil", "oyle degil", "değiştir", "degistir",
        )
        val CANCEL_WORDS = listOf(
            "iptal", "iptal et", "vazgeç", "vazgec", "boşver", "bosver", "dur", "dursun",
            "sus", "kes", "çık", "cik", "bırak", "birak", "kapat", "hayır vazgeç",
            "yapma", "gerek yok", "istemiyorum", "boş ver",
        )
        val REPEAT_WORDS = listOf(
            "tekrar", "tekrar oku", "bir daha", "yeniden", "yeniden oku", "bir daha oku",
            "baştan", "bastan", "baştan oku", "anlamadım", "anlamadim", "duyamadım",
            "duyamadim", "ne dedin", "tekrarla", "yinele", "ses yok", "duymadım", "duymadim",
        )
        val DETAIL_WORDS = listOf(
            "detay", "detaylı", "detayli", "ayrıntı", "ayrinti", "ayrıntılı", "ayrintili",
            "uzun", "uzunca", "açıkla", "acikla", "açıklar mısın", "geniş", "genis",
            "daha fazla", "hepsini", "tamamını", "tamamen", "madde madde", "özetle anlat",
        )
        val NEW_WORDS = listOf(
            "yeni komut", "yeni istek", "yeni görev", "yeni gorev", "başka bir şey",
            "baska bir sey", "başka komut", "baska komut", "yeni mesaj", "yeniden dinle",
            "dinle", "yeni soru", "yeni", "başka", "baska", "sıfırdan", "sifirdan",
            "bir şey daha", "bir sey daha",
        )
        val DONE_WORDS = listOf(
            "tamam", "tamamdır", "tamamdir", "teşekkürler", "tesekkurler", "teşekkür",
            "tesekkur", "yeter", "bitti", "bu kadar", "güle güle", "gorusuruz",
            "görüşürüz", "kapat", "kapatabilirsin", "sus", "dur", "sağ ol", "sag ol",
            "görüşmek üzere", "iyi geceler", "hoşça kal",
        )
    }

    /**
     * Son oturumlar — **üst düzey** olanlar önce. Önceden yalnızca en yeni 6
     * kayıt çekilip alt ajanlar ayıklandığında 5'e bile ulaşmıyordu (alt
     * oturumlar listenin başını dolduruyordu). Artık geniş bir pencere çekilip
     * zaman damgasına göre sıralanır; üst düzey 5'e ulaşılamazsa kalanlar da
     * eklenir ki liste hiçbir zaman boş kalmasın.
     */
    private suspend fun recentSessions(api: Api, count: Int = 5): List<JsonObject> {
        val all = runCatching { api.sessions(60) }.getOrDefault(emptyList())
        if (all.isEmpty()) return emptyList()
        val sorted = all.sortedByDescending { it.obj("time").num("updated") ?: 0.0 }
        val top = sorted.filter { it.str("parentID").isNullOrBlank() }
        if (top.size >= count) return top.take(count)
        return (top + sorted.filterNot { it.str("parentID").isNullOrBlank() }).distinctBy { it.str("id") }
            .take(count)
    }

    /** Onaylandı → oturum sor. */
    private suspend fun confirmAndPick() {
        val text = pendingText ?: return
        val api = api() ?: return
        stage = Stage.PICKING
        val ev = evren()
        val sessions = recentSessions(api, 5)
        if (sessions.isEmpty()) {
            onState("oturum yok")
            stage = Stage.IDLE
            say("Hiç oturum bulamadım.", ev)
            return
        }

        // Onay cümlesinde numara söylendiyse ikinci kez sormadan gönder.
        val pre = pendingPick
        pendingPick = null
        if (pre != null) {
            sessions.getOrNull(pre - 1)?.let { chosen ->
                targetSessionId = chosen.str("id")
                val title = chosen.str("title") ?: "oturum"
                onState("hedef: $title")
                VoiceLog.write("OTURUM", "seçildi=\"$title\" (onay ile #$pre)")
                say("Tamam, $title oturumuna gönderiyorum.", ev)
                afterSpeechPause()
                send(api, ev, chosen.str("id")!!, title)
                return
            }
        }

        val list = sessions.mapIndexed { i, s ->
            "${i + 1}. ${s.str("title")?.take(48) ?: "oturum"}"
        }
        onState("hangi oturum? ${list.joinToString(" , ")}")
        // Tek cümlede hem listeyi hem soruyu oku: ayrı bir "numarayı söyle"
        // turu kullanıcıyı boşuna bekletiyordu.
        say("Hangi oturuma göndereyim? ${list.joinToString(". ")}. Numara ya da oturum adı söyle.", ev)
        stage = Stage.PICKING
        // Eller serbest: soru okunur okunmaz dinlemeye geç (tuş yok).
        afterSpeechPause()
        listenForNumber(sessions, attempt = 1)
    }

    /** Numarayı dinle ve eşleştir; anlaşılmazsa bir kez daha sorar. */
    private suspend fun listenForNumber(
        sessions: List<JsonObject>? = null,
        attempt: Int = 1,
    ) {
        val ev = evren() ?: return
        val api = api() ?: return
        val list = sessions ?: recentSessions(api, 5)

        if (attempt > 1) say("Bir ile beş arasında bir numara söyle.", ev)
        stage = Stage.LISTENING
        audio.resetFinish()
        audio.beep(start = true)
        audio.openInputRoute()
        val wav = try {
            audio.record(maxMs = 8_000, silenceMs = 900, noSpeechMs = 4_000)
        } finally {
            audio.closeInputRoute()
        }
        audio.beep(start = false)

        val answer = if (wav == null) "" else runCatching { ev.transcribe(wav) }.getOrDefault("")
        onState("seçim: ${answer.ifBlank { "—" }}")

        if (parseCancel(answer)) {
            pendingText = null
            stage = Stage.IDLE
            say("Vazgeçtim.", ev)
            return
        }

        // "tekrar oku" → listeyi yeniden oku ve numarayı yine sor.
        if (parseRepeat(answer) && attempt < 3) {
            val titles = list.mapIndexed { i, s -> "${i + 1}. ${s.str("title")?.take(48) ?: "oturum"}" }
            say("Oturumlar: ${titles.joinToString(". ")}. Hangisi?", ev)
            afterSpeechPause()
            listenForNumber(list, attempt + 1)
            return
        }

        val idx = parseOrdinal(answer)
        // Numarayı söylemediyse oturum adıyla eşleştir ("mobile", "fastpass" gibi).
        val byTitle = if (idx == null) matchSessionIndex(answer, list) else null
        val chosenIdx = idx ?: byTitle
        if (chosenIdx == null) {
            // Sessiz/anlaşılmaz: bir kez daha dinle, sonra en yeni oturuma düş.
            if (attempt < 2) {
                listenForNumber(list, attempt + 1)
                return
            }
        }
        val chosen = list.getOrNull((chosenIdx ?: 1) - 1) ?: list.firstOrNull()
        if (chosen == null) {
            stage = Stage.IDLE
            return
        }
        targetSessionId = chosen.str("id")
        val title = chosen.str("title") ?: "oturum"
        onState("hedef: $title")
        VoiceLog.write("OTURUM", "seçildi=\"$title\" (cevap #${chosenIdx ?: 1})")
        say("Tamam, $title oturumuna gönderiyorum.", ev)
        afterSpeechPause()
        send(api, ev, chosen.str("id")!!, title)
    }

    /** Türkçe sayı adlarını/rakamları 1..5'e çevirir ("en yeni"/"son" = 1). */
    private fun parseOrdinal(text: String): Int? {
        val t = normalize(text)
        val words = listOf(
            1 to listOf("bir", "1", "birinci", "ilk", "ilkini", "ilki", "en yeni", "son", "sonuncu", "en son"),
            2 to listOf("iki", "2", "ikinci", "ikincisi", "ikisini"),
            3 to listOf("üç", "uc", "3", "üçüncü", "ucuncu", "üçüncüsü"),
            4 to listOf("dört", "dort", "4", "dördüncü", "dorduncu"),
            5 to listOf("beş", "bes", "5", "beşinci", "besinci"),
        )
        for ((n, keys) in words) if (hasAny(t, keys)) return n
        return null
    }

    /**
     * Oturum **adıyla** seçim: "mobile", "fastpass", "nashi" gibi. Söylenen
     * cümledeki kelimeler oturum başlıklarıyla karşılaştırılır; en çok eşleşen
     * kazanır. Belirsizse null (numara sorulmaya devam eder).
     */
    private suspend fun matchSession(text: String): Int? {
        if (text.isBlank()) return null
        val api = api() ?: return null
        val sessions = recentSessions(api, 5)
        if (sessions.isEmpty()) return null
        return matchSessionIndex(text, sessions)
    }

    private fun matchSessionIndex(text: String, sessions: List<JsonObject>): Int? {
        val spoken = normalize(text).split(" ").filter { it.length >= 3 }
        if (spoken.isEmpty()) return null
        val scores = sessions.map { s ->
            val words = normalize(s.str("title").orEmpty()).split(" ").filter { it.length >= 3 }
            if (words.isEmpty()) 0
            else spoken.count { w -> words.any { it.startsWith(w) || w.startsWith(it) } }
        }
        val best = scores.maxOrNull() ?: 0
        if (best < 1) return null
        // Beraberlik varsa tahmin etme — numara sormak daha doğru.
        if (scores.count { it == best } != 1) return null
        return scores.indexOf(best) + 1
    }

    /** Promptu gönder, tur bitince modelin yanıtını özetleyip oku. */
    private suspend fun send(api: Api, ev: EvrenClient?, sessionId: String, title: String) {
        val text = pendingText ?: return
        val client = ev ?: evren()
        stage = Stage.SENDING
        VoiceLog.write("GÖNDER", "oturum=\"$title\" id=$sessionId metin=\"${text.take(300)}\"")
        val before = runCatching { api.assistantText(sessionId) }.getOrDefault("")
        val sent = runCatching { api.prompt(sessionId, text) }.isSuccess
        pendingText = null
        if (!sent) {
            stage = Stage.IDLE
            onState("gönderilemedi")
            say("Gönderemedim. Bağlantıyı kontrol et.", client)
            return
        }
        onState("gönderildi → $title")
        say("Gönderdim. Bitince özetini okuyacağım.", client)

        stage = Stage.WAITING
        val done = waitForTurn(api, sessionId, before, timeoutMs = 10 * 60_000)
        if (!done) {
            stage = Stage.IDLE
            onState("tur bitmedi (zaman aşımı)")
            return
        }
        if (!settings.voiceAutoSummary.first()) {
            stage = Stage.IDLE
            onState("tur bitti")
            return
        }
        followUps = 0
        speakSummary(api, ev, sessionId)
    }

    /**
     * Turun bitmesini bekler: oturum aktiflikten çıkana VE asistanın yeni bir
     * metin yanıtı görünene kadar. (Sadece aktifliğe bakmak yetmez; model
     * araç çağırırken de pasif görünebilir.)
     */
    private suspend fun waitForTurn(
        api: Api,
        sessionId: String,
        before: String,
        timeoutMs: Int,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        var sawRunning = false
        while (System.currentTimeMillis() - startedAt < timeoutMs) {
            delay(4_000)
            val running = runCatching { sessionId in api.active() }.getOrDefault(false)
            if (running) {
                sawRunning = true
                continue
            }
            val now = runCatching { api.assistantText(sessionId) }.getOrDefault("")
            if (now.isNotEmpty() && now != before) return true
            if (sawRunning && now.isNotEmpty()) return true
        }
        return false
    }

    /**
     * Metni sesli okumaya uygun hâle getirir: kod blokları, markdown
     * işaretleri, bağlantılar ve tablo çizgileri çıkarılır; sesli okununca
     * anlamsız olan satırlar atlanır.
     */
    private fun speakable(text: String): String {
        var t = text
        // ```kod blokları``` — sesli okunmaz, "kod bloğu" diye geçilir.
        val hadCode = Regex("(?s)```.*?```").containsMatchIn(t)
        t = t.replace(Regex("(?s)```.*?```"), " ")
        // satır içi kod, kalın/italik, başlık, alıntı işaretleri
        t = t.replace(Regex("`([^`]*)`"), "$1")
        t = t.replace(Regex("[*_#>|]+"), " ")
        // markdown bağlantısı → yalnızca metni
        t = t.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        // çıplak URL'ler sesli okununca çok uzun; kısalt
        t = t.replace(Regex("https?://\\S+"), " bağlantı ")
        // madde işaretleri ve numaralandırma
        t = t.replace(Regex("(?m)^\\s*[-•]\\s*"), " ")
        t = t.replace(Regex("(?m)^\\s*\\d+[.)]\\s*"), " ")
        // tablo ayırıcı satırları (---|---)
        t = t.replace(Regex("(?m)^\\s*\\|?[-: ]{3,}\\|.*$"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        return if (hadCode && t.length < 40) "Yanıt yalnızca kod içeriyor." else t
    }

    /** Modelin kullanıcıya yazdığı yanıtı özetler ve kulaklığa okur. */
    suspend fun speakSummary(api: Api, ev: EvrenClient?, sessionId: String, detail: Boolean = false) {
        val client = ev ?: evren() ?: return
        val answer = runCatching { api.assistantText(sessionId) }.getOrDefault("")
        if (answer.isBlank()) {
            stage = Stage.IDLE
            onState("yanıt yok")
            say("Henüz bir yanıt yok.", client)
            return
        }
        lastRawAnswer = answer

        // Doğrudan okuma kuralı: yanıt **konuşmaya uygun düz yazıysa** özet
        // çıkarmaya gerek yok. Önceden 320 karakter sınırı yüzünden 600-700
        // karakterlik bir hikâye bile yeniden özetleniyordu (kullanıcı özeti
        // değil, modelin kendi cümlelerini duymak istiyor).
        //
        // Özet yalnızca şu durumlarda: çok uzun (TTS zaten 1.200 karakterde
        // kesiyor) ya da yapısal içerik (kod bloğu, tablo, madde listesi) var.
        val plain = speakable(answer)
        val bullets = Regex("(?m)^\\s*[-*•]\\s+").findAll(answer).count()
        val tableRows = Regex("(?m)^\\s*\\|.*\\|\\s*$").findAll(answer).count()
        val structured = answer.contains("```") || bullets >= 4 || tableRows >= 2
        val words = plain.split(" ").count { it.isNotBlank() }
        val direct = !detail &&
            plain.length <= DIRECT_READ_MAX_CHARS &&
            words <= DIRECT_READ_MAX_WORDS &&
            !structured
        if (direct || !client.summaryConfigured()) {
            lastSummary = plain
            stage = Stage.IDLE
            Log.d(TAG, "doğrudan okunuyor (${plain.length} krk, $words kelime)")
            onState("yanıt: $plain")
            runCatching { settings.setVoiceLastSpoken(plain) }
            say(plain, client)
            stage = Stage.IDLE
            followUp(api, client, sessionId)
            return
        }

        onState("özetliyorum…")
        val summary = runCatching { client.summarize(answer, detail) }.getOrElse { e ->
            Log.w(TAG, "özet hatası: ${e.message}")
            stage = Stage.IDLE
            onState("özet hatası: ${e.message}")
            say("Özet çıkaramadım. Ham yanıtı okuyorum. ${plain.take(400)}", client)
            return
        }.trim()
        // Model boş metin döndürebiliyor (içerik yerine yalnızca düşünme).
        // Boşsa hiç ses çıkmazdı — ham yanıtın başına düş.
        if (summary.length < 3) {
            Log.w(TAG, "özet boş döndü, ham yanıt okunuyor")
            stage = Stage.IDLE
            val raw = plain.take(500)
            lastSummary = raw
            onState("özet: $raw")
            say(raw, client)
        } else {
            lastSummary = summary
            stage = Stage.IDLE
            Log.d(TAG, "özet (${summary.length} krk): ${summary.take(200)}")
            onState("özet: $summary")
            runCatching { settings.setVoiceLastSpoken(summary) }
            say(summary, client)
        }
        stage = Stage.IDLE

        // Okuma bitti: mikrofonu yeniden aç ve takip komutu bekle. Kullanıcı
        // "tekrar oku", "daha detaylı", "yeni komut" ya da "tamam" diyebilir.
        followUp(api, client, sessionId)
    }

    /**
     * Sesli komutla uygulamayı kontrol eder. Komut tanınırsa `true` döner ve
     * akış burada biter; tanınmazsa `false` → cümle normal prompt olarak devam
     * eder. Eşleştirme yereldir (LLM çağrısı yok), bu yüzden anında çalışır.
     */
    private suspend fun handleAppCommand(text: String, api: Api?, ev: EvrenClient): Boolean {
        val cmd = AppCommandParser.parse(text) ?: return false
        Log.d(TAG, "uygulama komutu: ${cmd.action} arg=${cmd.arg} no=${cmd.number}")
        onState("komut: ${cmd.action.name.lowercase()}")
        stage = Stage.IDLE

        when (cmd.action) {
            AppAction.MODE_COMMAND -> {
                commandMode = true
                say(
                    "Komut moduna geçtim. Artık sadece komut dinliyorum: " +
                        "ayarları aç, oturumları listele, ikinci oturumu aç, koyu tema, kota, durdur. " +
                        "Prompt göndermek için normal moda geç de.",
                    ev,
                )
                listenAgain(api, ev)
            }

            AppAction.MODE_PROMPT -> {
                commandMode = false
                say(
                    "Normal moda geçtim. Söylediğini istek olarak alıp onaya sunacağım.",
                    ev,
                )
                listenAgain(api, ev)
            }

            AppAction.MODE_TOGGLE -> {
                commandMode = !commandMode
                say(
                    if (commandMode) {
                        "Komut moduna geçtim. Sadece komut dinliyorum. Prompt için normal moda geç de."
                    } else {
                        "Normal moda geçtim. Artık söylediğini istek olarak göndereceğim."
                    },
                    ev,
                )
                listenAgain(api, ev)
            }

            AppAction.HELP -> {
                val help = if (commandMode) {
                    "Komut modundayım. Ayarları aç, yeni oturum aç, birinci oturumu aç, " +
                        "oturumları listele, koyu tema, açık tema, kota, son yanıtı oku, durdur. " +
                        "Prompt moduna dönmek için normal moda geç de."
                } else {
                    "Şunları yapabilirim: ayarları aç, yeni oturum aç, birinci oturumu aç, " +
                        "oturumları listele, koyu tema, açık tema, kotayı yenile, son yanıtı oku, " +
                        "durdur, ve gönder dediğinde bir istek gönder. " +
                        "Sadece komut vermek istersen komut moduna geç de."
                }
                say(help, ev)
                listenAgain(api, ev)
            }

            AppAction.OPEN_APP -> {
                AppControl.launch(context, AppControl.Dest.APP)
                say("Uygulamayı açtım.", ev)
            }

            AppAction.OPEN_SETTINGS -> {
                AppControl.onMain { it.openSettings() }
                AppControl.launch(context, AppControl.Dest.SETTINGS)
                say("Ayarları açtım.", ev)
            }

            AppAction.OPEN_TELEGRAM -> {
                AppControl.onMain { it.openTelegram() }
                AppControl.launch(context, AppControl.Dest.TELEGRAM)
                say("Telegram ayarlarını açtım.", ev)
            }

            AppAction.BACK -> {
                val vm = AppControl.viewModel()
                if (vm != null) AppControl.onMain { it.goBack() }
                else AppControl.launch(context, AppControl.Dest.SESSIONS)
                say("Oturum listesine döndüm.", ev)
            }

            AppAction.WHERE -> {
                val where = if (AppControl.viewModel() != null) AppControl.screenName()
                else "uygulama şu an kapalı"
                say("Şu an $where.", ev)
                listenAgain(api, ev)
            }

            AppAction.LIST_SESSIONS -> {
                val a = api ?: return noConnection(ev)
                val sessions = recentSessions(a, 5)
                if (sessions.isEmpty()) {
                    say("Oturum bulamadım.", ev)
                } else {
                    val list = sessions.mapIndexed { i, s ->
                        "${i + 1}. ${s.str("title")?.take(48) ?: "oturum"}"
                    }
                    say("Son oturumlar: ${list.joinToString(". ")}.", ev)
                }
                listenAgain(api, ev, sessions.firstOrNull()?.str("id"))
            }

            AppAction.OPEN_SESSION -> {
                val a = api ?: return noConnection(ev)
                val sessions = recentSessions(a, 5)
                val idx = cmd.number?.minus(1)
                    ?: cmd.arg?.let { matchSessionIndex(it, sessions) }
                val chosen = idx?.let { sessions.getOrNull(it) }
                if (chosen == null) {
                    say("O oturumu bulamadım. Oturumları listele dersen sayabilirim.", ev)
                    listenAgain(api, ev)
                } else {
                    openSessionById(chosen.str("id"), chosen.str("title") ?: "oturum", ev)
                    listenAgain(api, ev, chosen.str("id"))
                }
            }

            AppAction.NEW_SESSION -> {
                val a = api ?: return noConnection(ev)
                val created = runCatching { a.createSession(cmd.arg?.ifBlank { null }) }.getOrNull()
                val id = created?.str("id")
                if (id == null) {
                    say("Yeni oturum açamadım.", ev)
                    listenAgain(api, ev)
                } else {
                    val title = created.str("title") ?: cmd.arg ?: "Yeni oturum"
                    AppControl.onMain { vm -> vm.refreshSessions() }
                    openSessionById(id, title, ev)
                    listenAgain(api, ev, id)
                }
            }

            AppAction.REFRESH -> {
                val vm = AppControl.viewModel()
                if (vm != null) {
                    AppControl.onMain { it.refreshSessions() }
                    say("Yeniledim.", ev)
                } else {
                    say("Yenilemek için uygulamayı açtım.", ev)
                }
                AppControl.launch(context, AppControl.Dest.APP)
                listenAgain(api, ev)
            }

            AppAction.THEME_DARK, AppAction.THEME_LIGHT, AppAction.THEME_TOGGLE -> {
                val current = AppControl.viewModel()?.voiceDarkTheme()
                    ?: settings.darkTheme.first()
                val dark = when (cmd.action) {
                    AppAction.THEME_DARK -> true
                    AppAction.THEME_LIGHT -> false
                    else -> !current
                }
                settings.setDarkTheme(dark)
                AppControl.onMain { it.setDarkTheme(dark) }
                say(if (dark) "Koyu temaya geçtim." else "Açık temaya geçtim.", ev)
                listenAgain(api, ev)
            }

            AppAction.QUOTA -> {
                if (AppControl.viewModel() != null) {
                    AppControl.onMain { it.refreshQuota() }
                    say("Kota bilgisini yeniledim; oturum listesindeki rozette görebilirsin.", ev)
                } else {
                    AppControl.launch(context, AppControl.Dest.APP)
                    say("Kotayı görmek için uygulamayı açtım.", ev)
                }
                listenAgain(api, ev)
            }

            AppAction.NOTIFY_ON, AppAction.NOTIFY_OFF -> {
                val enabled = cmd.action == AppAction.NOTIFY_ON
                settings.setNotifyPermissions(enabled)
                say(if (enabled) "Bildirimleri açtım." else "Bildirimleri kapattım.", ev)
                listenAgain(api, ev)
            }

            AppAction.BACKGROUND_ON, AppAction.BACKGROUND_OFF -> {
                val enabled = cmd.action == AppAction.BACKGROUND_ON
                settings.setBackgroundService(enabled)
                say(if (enabled) "Arka plan servisini açtım." else "Arka plan servisini kapattım.", ev)
                listenAgain(api, ev)
            }

            AppAction.VOICE_OFF -> {
                say("Sesli komutu kapatıyorum.", ev)
                settings.setVoiceEnabled(false)
            }

            AppAction.SWITCH_AGENT -> {
                val name = cmd.arg
                if (name.isNullOrBlank()) {
                    say("Hangi ajana geçeyim?", ev)
                    listenAgain(api, ev)
                    return true
                }
                val ok = if (AppControl.viewModel() != null) {
                    AppControl.viewModel()!!.voiceSwitchAgent(name)
                } else {
                    val sid = commandSessionId(api)
                    sid != null && api != null && runCatching { api.switchAgent(sid, name) }.isSuccess
                }
                say(if (ok) "$name ajanına geçtim." else "Ajanı değiştiremedim; bir oturum açık olmalı.", ev)
                listenAgain(api, ev)
            }

            AppAction.SWITCH_MODEL -> {
                val name = cmd.arg
                val vm = AppControl.viewModel()
                val ok = vm != null && !name.isNullOrBlank() && vm.voiceSwitchModel(name)
                if (ok) {
                    say("Modeli değiştirdim.", ev)
                } else {
                    say("Modeli değiştiremedim; oturum açık olmalı ve model listede bulunmalı.", ev)
                }
                listenAgain(api, ev)
            }

            AppAction.READ_LAST -> {
                val a = api ?: return noConnection(ev)
                val sid = commandSessionId(a)
                if (sid == null) {
                    say("Okuyacak oturum bulamadım.", ev)
                } else {
                    summarizeLast(sid)
                }
            }

            AppAction.INTERRUPT -> {
                val a = api ?: return noConnection(ev)
                val sid = commandSessionId(a)
                if (sid == null) {
                    say("Durdurulacak bir oturum yok.", ev)
                } else {
                    val ok = runCatching { a.interrupt(sid) }.isSuccess
                    say(if (ok) "Durdurdum." else "Durdurulamadı.", ev)
                }
                listenAgain(api, ev)
            }

            AppAction.SEND -> {
                val body = cmd.arg?.trim()
                val a = api ?: return noConnection(ev)
                val sid = commandSessionId(a)
                if (body.isNullOrBlank()) {
                    say("Ne göndereyim?", ev)
                    listenAgain(api, ev)
                } else if (sid == null) {
                    say("Gönderecek oturum bulamadım.", ev)
                } else {
                    val title = runCatching { a.session(sid).str("title") }
                        .getOrNull() ?: "oturum"
                    pendingText = body
                    onState("duyduğum: $body")
                    send(a, ev, sid, title)
                }
            }
        }
        return true
    }

    /** Bağlantı yokken komut: sesli bilgi ver ve akışı bitir. */
    private suspend fun noConnection(ev: EvrenClient): Boolean {
        stage = Stage.IDLE
        onState("OpenCode bağlantısı yok")
        say("OpenCode bağlantısı yok. Ayarları aç dersen ayarlara gidebilirim.", ev)
        return true
    }

    /** Komut sonrası mikrofonu yeniden açar (zincirleme komut için). */
    private suspend fun listenAgain(api: Api?, ev: EvrenClient, sessionId: String? = null) {
        val sid = sessionId ?: commandSessionId(api) ?: return
        val a = api ?: return
        followUp(a, ev, sid)
    }

    /** Komut için hedef oturum: açık oturum → son hedef → en yeni oturum. */
    private suspend fun commandSessionId(api: Api?): String? =
        AppControl.viewModel()?.voiceCurrentSessionId()
            ?: targetSessionId
            ?: api?.let { recentSessions(it, 1).firstOrNull()?.str("id") }

    /** Oturumu açar: uygulama yaşıyorsa doğrudan, değilse niyet ile. */
    private suspend fun openSessionById(id: String?, title: String, ev: EvrenClient) {
        if (id == null) return
        targetSessionId = id
        val vm = AppControl.viewModel()
        if (vm != null) {
            AppControl.onMain {
                it.refreshSessions()
                it.openSession(id)
            }
            AppControl.launch(context, AppControl.Dest.APP)
        } else {
            AppControl.launch(context, AppControl.Dest.SESSION, id)
        }
        say("$title oturumunu açtım.", ev)
    }

    /**
     * Özet sonrası eller serbest takip komutları. Kullanıcı ekrana bakmadan
     * zinciri sürdürebilsin diye en fazla [MAX_FOLLOW_UPS] kez dinlenir.
     */
    private suspend fun followUp(api: Api, ev: EvrenClient, sessionId: String) {
        if (followUps >= MAX_FOLLOW_UPS) {
            followUps = 0
            return
        }
        followUps++
        afterSpeechPause()
        stage = Stage.LISTENING
        audio.resetFinish()
        audio.beep(start = true)
        audio.openInputRoute()
        val wav = try {
            audio.record(maxMs = 7_000, silenceMs = 900, noSpeechMs = 5_000)
        } finally {
            audio.closeInputRoute()
        }
        audio.beep(start = false)

        val answer = if (wav == null) "" else runCatching { ev.transcribe(wav) }.getOrDefault("")
        if (answer.isBlank()) {
            // Sessizlik: akışı sessizce kapat (kullanıcı artık iş yapmıyor).
            followUps = 0
            stage = Stage.IDLE
            onState("hazır")
            return
        }
        onState("takip: $answer")

        // Takip sırasında da uygulama komutu verilebilir ("ayarları aç",
        // "koyu tema", "yeni oturum aç"…).
        if (handleAppCommand(answer, api, ev)) return

        // Komut modunda takip cümlesi prompt/soru olarak yorumlanmaz.
        if (commandMode) {
            followUps = 0
            stage = Stage.IDLE
            onState("komut anlaşılmadı: $answer")
            listenAgain(api, ev, sessionId)
            return
        }

        when {
            // "tekrar oku / ne dedin" önce: bunlar soru sözcüğü içerebilir.
            parseRepeat(answer) -> {
                val again = lastSummary
                if (again.isNullOrBlank()) speakSummary(api, ev, sessionId)
                else {
                    say(again, ev)
                    followUp(api, ev, sessionId)
                }
            }
            // Soru: "kritik olan çelişkiler neydi", "hangileri düşük düzeyde"…
            // Özet değil, yanıtın TAMAMI bağlam olarak modele verilir.
            looksLikeQuestion(answer) -> answerQuestion(api, ev, sessionId, answer)
            parseDetail(answer) -> {
                say("Daha ayrıntılı okuyorum.", ev)
                speakSummary(api, ev, sessionId, detail = true)
            }
            parseNewCommand(answer) -> {
                followUps = 0
                restartListening()
            }
            parseCancel(answer) || parseDone(answer) -> {
                followUps = 0
                stage = Stage.IDLE
                say("Tamam, kapatıyorum.", ev)
            }
            parseYesNo(answer) == true -> {
                // "evet" = devam et / yeni komut dinle.
                followUps = 0
                restartListening()
            }
            else -> {
                // Tanınmayan bir şey duyuldu: "anlamadım" diye tekrar sormak
                // yerine sessizce bitir (gürültüde döngüye girmesin).
                Log.d(TAG, "takip komutu tanınmadı: $answer")
                followUps = 0
                stage = Stage.IDLE
                onState("hazır — dinlemek için Dinle")
            }
        }
    }

    /** Son oturumun yanıtını özetleyip okur (tuş/bildirim "özet" istendiğinde). */
    suspend fun summarizeLast(sessionId: String? = null) {
        val api = api() ?: return
        val ev = evren() ?: return
        val sid = sessionId ?: targetSessionId ?: run {
            recentSessions(api, 1).firstOrNull()?.str("id")
        } ?: run {
            onState("oturum bulunamadı")
            say("Özetlenecek oturum bulamadım.", ev)
            return
        }
        targetSessionId = sid
        followUps = 0

        // Tur hâlâ sürüyorsa yanıt henüz yazılmamış olur; boş özet okumak
        // yerine kısa bir süre bitmesini bekle (en fazla 2 dk).
        val running = runCatching { sid in api.active() }.getOrDefault(false)
        if (running) {
            onState("tur bitene kadar bekliyorum…")
            say("Tur bitene kadar bekliyorum, sonra okuyacağım.", ev)
            val startedAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedAt < 120_000) {
                delay(3_000)
                if (runCatching { sid !in api.active() }.getOrDefault(true)) break
            }
        }
        speakSummary(api, ev, sid)
    }

    /** Onay aşamasında "yanlış duydun" → temizle ve yeniden dinle. */
    suspend fun restartListening() {
        pendingText = null
        targetSessionId = null
        stage = Stage.IDLE
        listenAndConfirm()
    }

    fun reset() {
        stage = Stage.IDLE
        pendingText = null
        pendingPick = null
        followUps = 0
    }

    /**
     * **Durdur**: yalnızca süren işi keser — özellik açık kalır, bildirimdeki
     * Dinle düğmesi hemen çalışmaya devam eder. Özelliği tamamen kapatmak için
     * bildirimdeki **Kapat** kullanılır.
     */
    fun cancelCurrent() {
        audio.stop()
        stage = Stage.IDLE
        pendingText = null
        pendingPick = null
        followUps = 0
        onState("durduruldu — dinlemek için Dinle")
    }
}
