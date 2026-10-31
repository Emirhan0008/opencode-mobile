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

    // ------------------------------------------------------- Bluetooth SCO

    private var scoDevice: AudioDeviceInfo? = null

    /** Kayıt için kulaklık mikrofonunu seçer (varsa). */
    fun openInputRoute() {
        runCatching {
            val bt = findBluetoothDevice()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (bt != null) {
                    audioManager.setCommunicationDevice(bt)
                    scoDevice = bt
                }
            } else {
                @Suppress("DEPRECATION")
                if (bt != null || audioManager.isBluetoothScoAvailableOffCall) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION")
                    audioManager.startBluetoothSco()
                    @Suppress("DEPRECATION")
                    audioManager.isBluetoothScoOn = true
                }
            }
        }.onFailure { Log.w(TAG, "giriş yönlendirmesi: ${it.message}") }
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

        val record = runCatching {
            @Suppress("MissingPermission")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        }.getOrNull() ?: return@withContext null

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext null
        }

        val pcm = ByteArrayOutputStream(sampleRate * 2 * 8)
        val chunk = ShortArray(bufSize / 2)
        var speechStarted = false
        var lastVoiceAt = 0L
        val startedAt = System.currentTimeMillis()
        // Gürültü tabanı: sessizlikte yavaşça güncellenir.
        var noiseFloor = 180.0

        try {
            record.startRecording()
            while (true) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) continue
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
                val threshold = maxOf(420.0, noiseFloor * 2.6)

                if (rms < threshold) {
                    noiseFloor = noiseFloor * 0.95 + rms * 0.05
                }

                val voice = rms > threshold || peak > 2_500
                if (voice) {
                    if (!speechStarted) {
                        speechStarted = true
                        // Konuşmanın başını kaçırmamak için tampondaki son ~0.3 sn'yi tut.
                        Log.d(TAG, "konuşma algılandı (rms=${rms.toInt()})")
                    }
                    lastVoiceAt = now
                }

                if (speechStarted) {
                    for (i in 0 until n) {
                        val s = chunk[i].toInt()
                        pcm.write(s and 0xFF)
                        pcm.write((s shr 8) and 0xFF)
                    }
                }

                val silenceFor = if (speechStarted) now - lastVoiceAt else 0
                if (speechStarted && silenceFor >= silenceMs) break
                if (!speechStarted && elapsed >= noSpeechMs) break
                if (elapsed >= maxMs) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "kayıt hatası: ${e.message}")
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
        }

        if (!speechStarted) return@withContext null
        val raw = pcm.toByteArray()
        if (raw.size < sampleRate / 2) return@withContext null // <0.25 sn
        wav(raw, sampleRate)
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

    /** Kısa bip — kaydın başladığını kulaklıkta duyurmak için. */
    fun beep(start: Boolean) {
        runCatching {
            val tone = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(
                if (start) android.media.ToneGenerator.TONE_PROP_BEEP else android.media.ToneGenerator.TONE_PROP_ACK,
                120,
            )
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ runCatching { tone.release() } }, 400)
        }
    }

    private companion object {
        const val TAG = "AudioEngine"
    }
}
