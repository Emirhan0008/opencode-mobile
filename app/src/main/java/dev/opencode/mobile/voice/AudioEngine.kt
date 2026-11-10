package dev.opencode.mobile.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Kayıt + oynatma + Bluetooth kulaklık yönlendirmesi.
 *
 * Kulaklık mikrofonu için iletişim (SCO) kanalı açılır; okuma yaparken SCO
 * kapatılır ki ses A2DP (yüksek kalite) üzerinden gitsin. İkisi aynı anda iyi
 * çalışmadığı için sırayla kullanılır.
 */
class AudioEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * "Bitti ✓" düğmesine basıldığında `true` olur. Kayıt döngüsü bunu görünce
     * sessizlik beklemeden durur — kalabalık ortamda çevre sesi yüzünden
     * dinlemenin kapanmaması sorununu çözer.
     */
    @Volatile private var finishRequested = false

    /** Yeni dinleme turu başlarken önceki turdan kalan isteği temizler. */
    fun resetFinish() {
        finishRequested = false
    }

    /** Konuşma bitti: kaydı hemen bitir. */
    fun finishNow() {
        finishRequested = true
    }

    // ------------------------------------------------------- Bluetooth SCO

    private var scoDevice: AudioDeviceInfo? = null

    /** Kayıt için kulaklık mikrofonunu seçer (varsa). */
    fun openInputRoute(): Boolean {
        return runCatching {
            val bt = findBluetoothDevice()
            if (bt == null) {
                // Kulaklık o an görünmüyor: eski kod sessizce telefon mikrofonuna
                // düşüyordu. Kullanıcı kulaklığa konuştuğu için hiçbir şey
                // duyulmuyordu. Durumu logla — akış bunu bildirimde gösterir.
                Log.w(TAG, "bluetooth girişi yok — telefon mikrofonu kullanılacak")
                VoiceLog.write("SES", "bluetooth girişi yok — telefon mikrofonu")
                return@runCatching false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.setCommunicationDevice(bt)
                scoDevice = bt
            } else {
                @Suppress("DEPRECATION")
                run {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
            }
            // Kanalın gerçekten açılmasını bekle; hemen kayda başlamak boş
            // kayıt üretiyordu.
            val ready = awaitInputRoute()
            VoiceLog.write("SES", if (ready) "bluetooth girişi hazır" else "giriş kanalı gecikti")
            ready
        }.getOrElse { e ->
            Log.w(TAG, "giriş yönlendirmesi: ${e.message}")
            false
        }
    }

    /** Kayıt bitti: iletişim kanalını kapat, normal çıkışa dön. */
    fun closeInputRoute() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
                audioManager.mode = AudioManager.MODE_NORMAL
            } else {
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            }
            scoDevice = null
        }
    }

    private fun findBluetoothDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }
        @Suppress("DEPRECATION")
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    /**
     * Giriş yolu gerçekten hazır mı?
     *
     * `setCommunicationDevice` / `startBluetoothSco` **asenkron** çalışır: çağrı
     * döndüğünde SCO kanalı henüz açılmamış olabilir. Bu arada `AudioRecord`
     * başlatılırsa kayıt sessizce boş gelir ("dinliyorum" yazar ama hiçbir şey
     * duymaz). Burada kısa süre bekleyip gerçek yönlendirmeyi doğruluyoruz.
     */
    private fun awaitInputRoute(timeoutMs: Long = 600): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val active = audioManager.communicationDevice
                active != null && active.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn
            }
            if (ready) return true
            Thread.sleep(50)
        }
        return false
    }

    // ------------------------------------------------------------- kayıt

    /**
     * Sessizlik algılamalı kayıt (VAD).
     *
     * - Konuşma başlayana kadar en fazla [noSpeechMs] bekler; hiç konuşma yoksa `null`.
     * - Konuşma başladıktan sonra [silenceMs] sessizlik → durur.
     * - Toplam en fazla [maxMs].
     *
     * Dönen değer: 16 kHz mono 16-bit **WAV** (başlık dâhil, Whisper uyumlu).
     */
    suspend fun record(
        maxMs: Int = 25_000,
        silenceMs: Int = 1_200,
        noSpeechMs: Int = 6_000,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val sampleRate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return@withContext null
        val bufSize = maxOf(minBuf * 2, sampleRate) // ~0.5 sn

        // Kayıt cihazı açılamazsa hemen pes etmek yerine kısa aralıklarla
        // yeniden dene: mikrofon başka bir uygulama/sistem sesi tarafından
        // henüz bırakılmamış olabilir (tek denemede düşünce akış "dinle"
        // aşamasını atlıyordu).
        var record: AudioRecord? = null
        repeat(3) { attempt ->
            val r = runCatching {
                @Suppress("MissingPermission")
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                record = r
                return@repeat
            }
            runCatching { r?.release() }
            if (attempt < 2) {
                Log.w(TAG, "AudioRecord açılamadı (deneme ${attempt + 1}/3)")
                Thread.sleep(250L * (attempt + 1))
            }
        }
        val rec = record ?: run {
            Log.w(TAG, "AudioRecord hiç açılamadı")
            return@withContext null
        }

        val pcm = ByteArrayOutputStream(sampleRate * 2 * 8)
        val chunk = ShortArray(bufSize / 2)
        var speechStarted = false
        var lastVoiceAt = 0L
        var maxPeak = 0
        val startedAt = System.currentTimeMillis()
        // Gürültü tabanı her turda sıfırlanır. Önceden tek bir yüksek gürültü
        // (rüzgar, kapı, trafik) tabanı yukarı taşıyıp orada bırakıyordu; sonraki
        // tüm turlar sağır kalıyordu.
        var noiseFloor = NOISE_FLOOR_START
        var readFailures = 0

        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "kayıt başlamadı (recordingState=${rec.recordingState})")
                return@withContext null
            }
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) {
                    // Eskiden `continue` vardı: okuma kalıcı olarak hata dönerse
                    // döngü sonsuza kadar dönüyor, "dinliyorum" ekranda kalıyor ve
                    // mikrofon kilitleniyordu. Artık üst üste hata → turu bitir.
                    readFailures++
                    if (readFailures >= MAX_READ_FAILURES) {
                        Log.w(TAG, "okuma sürekli başarısız ($readFailures) — kayıt bitiriliyor")
                        break
                    }
                    Thread.sleep(10)
                    continue
                }
                readFailures = 0
                val now = System.currentTimeMillis()
                val elapsed = now - startedAt

                var sum = 0.0
                var peak = 0
                for (i in 0 until n) {
                    val v = abs(chunk[i].toInt())
                    if (v > peak) peak = v
                    sum += chunk[i].toDouble() * chunk[i]
                }
                val rms = sqrt(sum / n)
                val threshold = maxOf(MIN_THRESHOLD, noiseFloor * 2.6)
                if (rms < threshold) {
                    noiseFloor = (noiseFloor * 0.95 + rms * 0.05).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
                } else {
                    // Konuşma/yüksek ses: tabanı yukarı taşıma, yavaşça aşağı çek.
                    noiseFloor = (noiseFloor * 0.995).coerceIn(MIN_NOISE_FLOOR, MAX_NOISE_FLOOR)
                }

                val voice = rms > threshold || peak > 2_500
                if (peak > maxPeak) maxPeak = peak
                if (voice) {
                    if (!speechStarted) {
                        speechStarted = true
                        Log.d(TAG, "konuşma algılandı (rms=${rms.toInt()})")
                    }
                    lastVoiceAt = now
                }

                // PCM'i **her zaman** biriktir. Önceden yalnızca "konuşma
                // algılandı" işaretinden sonra yazılıyordu; kullanıcı "Bitti"
                // düğmesine gürültü eşiği aşılmadan bastığında tampon boş
                // kalıyor, kayıt null dönüyor ve akış bir sonraki aşamaya
                // geçmek yerine baştan dinlemeye düşüyordu.
                for (i in 0 until n) {
                    val s = chunk[i].toInt()
                    pcm.write(s and 0xFF)
                    pcm.write((s shr 8) and 0xFF)
                }
                val silenceFor = if (speechStarted) now - lastVoiceAt else 0
                // "Bitti ✓" düğmesi: kullanıcı konuşmasını bitirdiğini bildirdi.
                if (finishRequested) break
                if (speechStarted && silenceFor >= silenceMs) break
                if (!speechStarted && elapsed >= noSpeechMs) break
                if (elapsed >= maxMs) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "kayıt hatası: ${e.message}")
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }

        val explicitFinish = finishRequested
        finishRequested = false

        // Konuşma hiç algılanmadıysa otomatik zaman aşımında boş kayıt say.
        // Ama kullanıcı "Bitti"ye bastıysa elindeki sesi yine de gönder —
        // gürültü eşiği tutmasa bile söylediği cümle kaybolmasın.
        if (!speechStarted && !explicitFinish) return@withContext null
        val raw = pcm.toByteArray()
        if (raw.size < sampleRate / 4) return@withContext null // <~0.25 sn
        val bytes = wav(raw, sampleRate)
        // Teşhis: son kaydı diske bırak. Kullanıcı "yanlış duydu" dediğinde
        // mikrofonun gerçekte ne aldığını dinleyebilmek gerekir; ADB ile
        // çekilebilir (Android/data/.../files/son_kayit.wav).
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "son_kayit.wav").writeBytes(bytes)
        }
        VoiceLog.write(
            "KAYIT",
            "${raw.size / 32} ms · tepe $maxPeak · ${if (speechStarted) "konuşma" else "sessiz/bitti"}",
        )
        bytes
    }

    /** PCM'e 44 baytlık WAV başlığı ekler. */
    private fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray()); le32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(channels)
        le32(sampleRate); le32(byteRate); le16(channels * bits / 8); le16(bits)
        out.write("data".toByteArray()); le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    // ---------------------------------------------------------- oynatma

    private var player: MediaPlayer? = null

    /**
     * Oynatma bitene kadar askıda kalan devam nesnesi. [stop] bunu AÇIKÇA
     * tamamlar — MediaPlayer.stop() tamamlanma geri çağrısı üretmez, aksi
     * hâlde çağıran coroutine sonsuza kadar asılı kalır (servis kilitlenir).
     */
    @Volatile private var playCont: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    /** MP3 baytlarını oynatır; ses bitene kadar askıya alınır. */
    suspend fun play(bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext
        stop()
        val file = File(context.cacheDir, "voice_tts.mp3")
        runCatching { file.writeBytes(bytes) }
        val mp = MediaPlayer()
        player = mp
        suspendCancellableCoroutine { cont ->
            playCont = cont
            fun finish() {
                if (cont.isActive) cont.resume(Unit)
                playCont = null
            }
            runCatching {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { finish() }
                mp.setOnErrorListener { _, _, _ -> finish(); true }
                mp.prepare()
                // Bluetooth'ta A2DP akışı start() ile hemen açılmıyor; ilk
                // ~200 ms'lik hazırlık ilk hecelerin yutulmasını engeller.
                android.os.SystemClock.sleep(220)
                mp.start()
            }.onFailure {
                Log.w(TAG, "oynatma hatası: ${it.message}")
                finish()
            }
            cont.invokeOnCancellation { stop() }
        }
    }

    /** Oynatmayı hemen keser (tuşa basınca "sus" davranışı). */
    fun stop() {
        val cont = playCont
        playCont = null
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
            }
        }
        runCatching { player?.release() }
        player = null
        // Bekleyen coroutine'i uyandır: MediaPlayer.stop() kendisi geri çağrı üretmez.
        if (cont != null && cont.isActive) cont.resume(Unit)
    }

    /**
     * Telsiz ("roger beep") tarzı kısa sesli geri bildirim.
     *
     * Tek bir [ToneGenerator] üzerinde sıralı tonlar çalınır; her ton arasında
     * kısa boşluk vardır. Tonlar dinleme/okuma başlangıcı ve bitişinde farklıdır
     * ki kullanıcı ekrana bakmadan ne olduğunu anlayabilsin.
     */
    enum class Cue {
        /** Dinleme başlıyor: tek kısa, tiz "dit". */
        LISTEN_START,
        /** Dinleme bitti (yazıya çevriliyor): iki kısa tiz "di-dit". */
        LISTEN_END,
        /** Okuma başlıyor: tek kısa, pes "dum". */
        SPEAK_START,
        /** Okuma bitti: alçalan iki ton "dii-dum". */
        SPEAK_END,
    }

    private val toneHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun cue(kind: Cue) {
        val seq: List<Pair<Int, Int>> = when (kind) {
            Cue.LISTEN_START -> listOf(TONE_PIP to 70)
            Cue.LISTEN_END -> listOf(TONE_PIP to 55, TONE_PIP to 55)
            Cue.SPEAK_START -> listOf(TONE_DEEP to 80)
            Cue.SPEAK_END -> listOf(TONE_MID to 70, TONE_DEEP to 110)
        }
        playSequence(seq, volume = 85)
    }

    /** Eski çağrı biçimi: kayıt başlangıcı/bitişi. */
    fun beep(start: Boolean) = cue(if (start) Cue.LISTEN_START else Cue.LISTEN_END)

    private fun playSequence(seq: List<Pair<Int, Int>>, volume: Int) {
        runCatching {
            val tone = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            var at = 0L
            for ((toneType, duration) in seq) {
                toneHandler.postDelayed({
                    runCatching { tone.startTone(toneType, duration) }
                }, at)
                at += duration + 45
            }
            toneHandler.postDelayed({ runCatching { tone.release() } }, at + 250)
        }
    }

    private companion object {
        const val TAG = "AudioEngine"

        // Telsiz hissi veren kısa tonlar (ToneGenerator hazır sesleri).
        const val TONE_PIP = android.media.ToneGenerator.TONE_CDMA_PIP
        const val TONE_MID = android.media.ToneGenerator.TONE_PROP_BEEP
        const val TONE_DEEP = android.media.ToneGenerator.TONE_PROP_PROMPT

        /** Üst üste bu kadar okuma hatası → kayıt turu bitirilir (sonsuz döngü koruması). */
        const val MAX_READ_FAILURES = 100

        /** Gürültü tabanı sınırları — tur başına sıfırlanır. */
        const val NOISE_FLOOR_START = 180.0
        const val MIN_NOISE_FLOOR = 60.0
        const val MAX_NOISE_FLOOR = 900.0
        const val MIN_THRESHOLD = 420.0
    }
}
