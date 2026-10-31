package dev.opencode.mobile.voice

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.opencode.mobile.data.Settings
import dev.opencode.mobile.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Sesli komut ön plan servisi.
 *
 * Neden servis: ekran kilitliyken mikrofon ve kulaklık tuşu yalnızca ön plan
 * servisi + MediaSession ile güvenilir çalışır. Ekran kapalı olsa da yaşar.
 *
 * Tuş: [MediaSession] üzerinden `HEADSETHOOK` / `MEDIA_PLAY_PAUSE`.
 *  - kısa basış → aşamaya göre dinle / onayla / sus
 *  - uzun basış (≥700 ms) → yeniden söyle (onay aşamasında) / son özeti oku
 */
class VoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var audio: AudioEngine
    private lateinit var flow: VoiceFlow
    private var mediaSession: MediaSession? = null

    @Volatile private var busy = false
    private var lastDownAt = 0L

    /** Son işlenen jest (ms) — çift yönlendirmeyi yutmak için. */
    @Volatile private var lastGestureAt = 0L
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.init(this)
        audio = AudioEngine(this)
        startForegroundCompat(buildNotification("Sesli komut hazır — kulaklık tuşuna bas"))

        val settings = Settings(applicationContext)
        flow = VoiceFlow(applicationContext, settings, audio) { st -> updateNotification(st) }
        setupMediaSession()
        registerScreenReceiver()
        claimMediaButtonSession()

        // Ayarlar kapatılırsa servis kendini durdurur.
        scope.launch {
            settings.voiceEnabled.collect { enabled ->
                if (!enabled) {
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    runCatching { Settings(applicationContext).setVoiceEnabled(false) }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_LISTEN -> scope.launch { handleButton() }
            ACTION_BUTTON -> {
                val long = intent.getBooleanExtra(EXTRA_LONG, false)
                scope.launch { gesture(long) }
            }
            ACTION_SUMMARY -> {
                val sid = intent.getStringExtra(EXTRA_SESSION_ID)
                scope.launch { withBusy { flow.summarizeLast(sid) } }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { screenReceiver?.let { unregisterReceiver(it) } }
        screenReceiver = null
        runCatching { mediaSession?.isActive = false }
        runCatching { mediaSession?.release() }
        mediaSession = null
        abandonAudioFocus()
        audio.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------- MediaSession

    // ------------------------------------------------------- media session

    private var audioFocus: android.media.AudioManager? = null
    private var focusRequest: android.media.AudioFocusRequest? = null

    /** Ekran kapanınca medya tuşu sahipliğini tazelemek için. */
    private var screenReceiver: android.content.BroadcastReceiver? = null

    /**
     * Medya tuşu sahipliğini **gerçek bir ses çalımı** ile kazanır.
     *
     * Android 13'te `MediaSessionService`, medya tuşunu yalnızca "son ses çalan"
     * uygulamalar arasından seçiyor (`getSortedAudioPlaybackClientUids`). Hiç ses
     * çalmayan bir oturum, aktif ve odak sahibi olsa bile **medya tuşu oturumu
     * sayılmıyor** — tuş o zaman "son bilinen alıcıya" (YouTube/Spotify) gidiyor.
     * 60 ms'lik sessiz bir PCM çalımı bizi o listeye sokar ve tuş bize gelir.
     */
    private fun claimMediaButtonSession() {
        scope.launch {
            runCatching {
                val attrs = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val format = android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44_100)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val frames = 2_646 // ~60 ms
                val track = android.media.AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(frames * 2)
                    .build()
                try {
                    track.write(ShortArray(frames), 0, frames)
                    track.play()
                    delay(120)
                    runCatching { track.stop() }
                } finally {
                    runCatching { track.release() }
                }
                android.util.Log.d(TAG, "medya tuşu sahipliği istendi")
            }.onFailure { android.util.Log.w(TAG, "tuş sahipliği alınamadı: ${it.message}") }
        }
    }

    private fun registerScreenReceiver() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) claimMediaButtonSession()
            }
        }
        screenReceiver = r
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            r,
            android.content.IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Medya tuşlarının bize yönlenmesi için ses odağı ister. [AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK]
     * kullanılır: müzik çalıyorsa tamamen durdurmaz, yalnızca kısar — kullanıcının
     * arka planda müzik dinlemesini bozmadan tuşu alırız.
     */
    private fun requestAudioFocus() {
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioFocus = am
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
            android.util.Log.d(TAG, "ses odağı istendi")
        }.onFailure { android.util.Log.w(TAG, "ses odağı alınamadı: ${it.message}") }
    }

    private fun abandonAudioFocus() {
        runCatching {
            val am = audioFocus ?: return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }

    private fun setupMediaSession() {
        val session = MediaSession(this, "OpenCodeVoice")
        // ÖNEMLİ: Android medya tuşlarını "son çalan" / ses odağı alan oturuma
        // yönlendirir. Odak istemezsek kulaklık tuşu başka uygulamaya (ya da
        // Google Asistan'a) gider ve bize hiç ulaşmaz.
        requestAudioFocus()

        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // Oturumun "yerel oynatıcı" olduğunu ve ses özniteliklerini bildir.
        // Bu yapılmazsa oturum USAGE_MEDIA/CONTENT_TYPE_UNKNOWN olarak görünür ve
        // bazı OEM'ler (MIUI) oturumu medya tuşu hedefi saymaz.
        session.setPlaybackToLocal(attrs)

        // MIUI'de medya tuşu oturumu boş kalırsa sistem "son bilinen" medya tuşu
        // alıcısına (ör. YouTube/Spotify) gidiyor. Kendi alıcımızı bildirerek o
        // yedeğin de bize dönmesini sağla.
        val buttonPi = PendingIntent.getBroadcast(
            this,
            3,
            Intent(this, VoiceButtonReceiver::class.java).apply {
                action = Intent.ACTION_MEDIA_BUTTON
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        session.setMediaButtonReceiver(buttonPi)

        session.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                android.util.Log.d(TAG, "onMediaButtonEvent: ${mediaButtonIntent.action} extras=${mediaButtonIntent.extras?.keySet()}")
                val ev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                } ?: return false
                android.util.Log.d(TAG, "medya tuşu: code=${ev.keyCode} action=${ev.action}")
                return handleKey(ev)
            }
        })
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID,
                )
                // PLAYING: bazı OEM'ler kulaklık tuşunu yalnız "çalan" oturuma yönlendirir.
                .setState(PlaybackState.STATE_PLAYING, 0, 1f, SystemClock.elapsedRealtime())
                .build(),
        )
        session.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "OpenCode sesli komut")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "OpenCode")
                .build(),
        )
        session.isActive = true
        mediaSession = session
    }

    private fun handleKey(ev: KeyEvent): Boolean {
        val code = ev.keyCode
        val relevant = code == KeyEvent.KEYCODE_HEADSETHOOK ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY ||
            code == KeyEvent.KEYCODE_MEDIA_PAUSE
        if (!relevant) return false

        when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (ev.repeatCount == 0) lastDownAt = SystemClock.elapsedRealtime()
                return true
            }
            KeyEvent.ACTION_UP -> {
                val held = SystemClock.elapsedRealtime() - lastDownAt
                val long = held >= 700
                scope.launch { handleGesture(long) }
                return true
            }
        }
        return false
    }

    private suspend fun handleGesture(long: Boolean) {
        gesture(long)
    }

    /**
     * Tek jest girişi. Tuş hem MediaSession'a hem yayına düşerse 400 ms içinde
     * gelen ikinci çağrı yutulur (çift dinleme/çift gönderim olmasın).
     */
    private fun gesture(long: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGestureAt < 400) return
        lastGestureAt = now
        scope.launch { dispatchGesture(long) }
    }

    private suspend fun dispatchGesture(long: Boolean) {
        when {
            // Konuşuyorsa her iki jest de susturur (acil durum).
            flow.stage == VoiceFlow.Stage.SPEAKING -> {
                flow.stopSpeaking()
                if (long) withBusy { flow.summarizeLast() }
                return
            }
            long -> when (flow.stage) {
                // Onay aşamasında uzun basış = "yeniden söyle".
                VoiceFlow.Stage.CONFIRMING -> withBusy { flow.restartListening() }
                else -> withBusy { flow.summarizeLast() }
            }
            else -> handleButton()
        }
    }

    private suspend fun handleButton() {
        withBusy { flow.onButton() }
    }

    /** Aynı anda iki akış çalışmasın. */
    private suspend fun withBusy(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        try {
            block()
        } catch (e: Exception) {
            updateNotification("hata: ${e.message?.take(80)}")
        } finally {
            busy = false
        }
    }

    // ---------------------------------------------------------- bildirim

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, Class.forName("dev.opencode.mobile.MainActivity")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val listen = PendingIntent.getService(
            this,
            2,
            Intent(this, VoiceService::class.java).apply { action = ACTION_LISTEN },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = PendingIntent.getService(
            this,
            4,
            Intent(this, VoiceService::class.java).apply { action = ACTION_SUMMARY },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, Notifier.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("OpenCode — sesli komut")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Dinle", listen)
            .addAction(0, "Özet", summary)
            .addAction(0, "Durdur", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        stateJob?.cancel()
        stateJob = scope.launch {
            delay(120) // hızlı art arda gelen güncellemeleri birleştir
            runCatching {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(FOREGROUND_ID, buildNotification(text))
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(FOREGROUND_ID, notification)
        }
    }

    companion object {
        private const val TAG = "VoiceService"
        private const val FOREGROUND_ID = 1002
        private const val ACTION_STOP = "dev.opencode.mobile.voice.STOP"
        private const val ACTION_BUTTON = "dev.opencode.mobile.voice.BUTTON"
        private const val EXTRA_LONG = "dev.opencode.mobile.voice.LONG"

        /** Bildirim düğmeleri ve [Notifier] ile paylaşılan eylem adları. */
        const val ACTION_LISTEN = "dev.opencode.mobile.voice.LISTEN"
        const val ACTION_SUMMARY = "dev.opencode.mobile.voice.SUMMARY"
        const val EXTRA_SESSION_ID = "dev.opencode.mobile.voice.SESSION_ID"

        /** Yedek yol: medya tuşu yayınından gelen jest. */
        fun button(context: Context, longPress: Boolean) {
            runCatching {
                val intent = Intent(context, VoiceService::class.java).apply {
                    action = ACTION_BUTTON
                    putExtra(EXTRA_LONG, longPress)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }

        /** Bildirimden "Sesli dinle": verilen oturumun son yanıtını özetleyip okur. */
        fun summary(context: Context, sessionId: String? = null) {
            runCatching {
                val intent = Intent(context, VoiceService::class.java).apply {
                    action = ACTION_SUMMARY
                    if (!sessionId.isNullOrBlank()) putExtra(EXTRA_SESSION_ID, sessionId)
                }
                ContextCompat.startForegroundService(context, intent)
            }
        }

        /** Bildirimden "Dinle": sesli komut akışını başlatır. */
        fun listen(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, VoiceService::class.java).apply { action = ACTION_LISTEN },
                )
            }
        }

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, VoiceService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, VoiceService::class.java)) }
        }
    }
}
