package dev.opencode.mobile.voice

import android.content.Context
import android.util.Log
import dev.opencode.mobile.data.Settings
import dev.opencode.mobile.net.Api
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
    private val onState: (String) -> Unit,
) {

    enum class Stage { IDLE, LISTENING, CONFIRMING, PICKING, SENDING, WAITING, SPEAKING }

    @Volatile var stage: Stage = Stage.IDLE
        private set

    private var pendingText: String? = null
    private var targetSessionId: String? = null

    /** Onay cümlesinde söylenen numara — onayla birlikte oturum seçimini de yapar. */
    private var pendingPick: Int? = null

    /** Okunan son özet — "tekrar oku" için yeniden özetlemeden kullanılır. */
    private var lastSummary: String? = null

    /** Aynı yanıt için kaç kez takip komutu dinlendiği (sonsuz döngü koruması). */
    private var followUps = 0

    /** TTS bittikten sonra mikrofonu açmadan önce beklenen süre (yankı önleme). */
    private suspend fun afterSpeechPause() = delay(500)

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
            audio.play(bytes)
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
        if (api == null) {
            onState("OpenCode bağlantısı yok")
            return
        }

        stage = Stage.LISTENING
        onState(if (attempt == 1) "dinliyorum…" else "tekrar dinliyorum…")
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
            if (attempt < 2) {
                say("Anlayamadım. Bir kez daha dinliyorum.", ev)
                listenAndConfirm(attempt + 1)
            } else {
                say("Anlayamadım. Hazır olduğunda dinle düğmesine bas.", ev)
            }
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

        val choice = parseYesNo(answer)
        // Tek nefeste onay+seçim: "evet iki", "iki", "mobile" gibi. Kısa ve
        // başka bir takip komutu olmayan cevaplarda numara/ad eşleştirilir.
        val pick = if (choice == false) null else run {
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
        const val MAX_FOLLOW_UPS = 2

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

    /** Onaylandı → oturum sor. */
    private suspend fun confirmAndPick() {
        val text = pendingText ?: return
        val api = api() ?: return
        stage = Stage.PICKING
        val ev = evren()
        val sessions = runCatching { api.sessions(6) }.getOrDefault(emptyList())
            .filter { it.str("parentID").isNullOrBlank() }
            .take(5)
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
        val list = sessions ?: runCatching { api.sessions(6) }.getOrDefault(emptyList())
            .filter { it.str("parentID").isNullOrBlank() }
            .take(5)

        if (attempt > 1) say("Bir ile beş arasında bir numara söyle.", ev)
        stage = Stage.LISTENING
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
        val sessions = runCatching { api.sessions(6) }.getOrDefault(emptyList())
            .filter { it.str("parentID").isNullOrBlank() }
            .take(5)
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
        if (!client.summaryConfigured()) {
            stage = Stage.IDLE
            // Özet anahtarı yoksa yanıtın kendisini kısaca oku.
            say(answer.take(400), client)
            return
        }
        onState("özetliyorum…")
        val summary = runCatching { client.summarize(answer, detail) }.getOrElse { e ->
            Log.w(TAG, "özet hatası: ${e.message}")
            stage = Stage.IDLE
            onState("özet hatası: ${e.message}")
            say("Özet çıkaramadım. Ham yanıtı okuyorum. ${answer.take(400)}", client)
            return
        }
        lastSummary = summary
        stage = Stage.IDLE
        Log.d(TAG, "özet (${summary.length} krk): ${summary.take(80)}")
        onState("özet: $summary")
        say(summary, client)
        stage = Stage.IDLE

        // Okuma bitti: mikrofonu yeniden aç ve takip komutu bekle. Kullanıcı
        // "tekrar oku", "daha detaylı", "yeni komut" ya da "tamam" diyebilir.
        followUp(api, client, sessionId)
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

        when {
            parseDetail(answer) -> {
                say("Daha ayrıntılı okuyorum.", ev)
                speakSummary(api, ev, sessionId, detail = true)
            }
            parseRepeat(answer) -> {
                val again = lastSummary
                if (again.isNullOrBlank()) speakSummary(api, ev, sessionId)
                else {
                    say(again, ev)
                    followUp(api, ev, sessionId)
                }
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
            val sessions = runCatching { api.sessions(3) }.getOrDefault(emptyList())
                .filter { it.str("parentID").isNullOrBlank() }
            sessions.firstOrNull()?.str("id")
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
