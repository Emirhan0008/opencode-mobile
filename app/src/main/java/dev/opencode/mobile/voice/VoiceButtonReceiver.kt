package dev.opencode.mobile.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Kulaklık tuşu için **yedek** giriş yolu.
 *
 * Normalde tuş, etkin [android.media.session.MediaSession] üzerinden
 * `onMediaButtonEvent` ile gelir. Ancak bazı OEM'ler (MIUI/HyperOS dâhil) tuşu
 * medya oturumuna değil doğrudan `ACTION_MEDIA_BUTTON` yayınına gönderir —
 * bu alıcı o durumda devreye girer ve aynı jesti servise iletir.
 *
 * Kısa/uzun basış ayrımı burada yapılır: DOWN anı saklanır, UP'ta süre ölçülür.
 * Servis tarafında 400 ms'lik bir yutma penceresi vardır; tuş hem oturuma hem
 * yayına düşerse çift işlem olmaz.
 */
class VoiceButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val ev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        // KeyEvent yoksa (bazı kabuklar) tek dokunuş say — en azından dinlemeyi başlatır.
        if (ev == null) {
            VoiceService.button(context, longPress = false)
            return
        }
        if (ev.keyCode !in HANDLED_KEYS) return
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> if (ev.repeatCount == 0) lastDownAt = SystemClock.elapsedRealtime()
            KeyEvent.ACTION_UP -> {
                val held = SystemClock.elapsedRealtime() - lastDownAt
                VoiceService.button(context, longPress = held >= 700)
            }
        }
    }

    private companion object {
        /** Son DOWN zamanı — süreç içinde tek tuş olduğu için tek değişken yeterli. */
        var lastDownAt = 0L

        val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
