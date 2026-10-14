package dev.opencode.mobile.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.opencode.mobile.MainActivity

/** Uygulama arka plandayken izin istekleri, sorular ve görev bitişleri için bildirim. */
object Notifier {

    /** Olay bildirimleri kanalı (izin/soru/görev) — yüksek öncelik. */
    const val CHANNEL_ID = "opencode"

    /** Sürekli (ongoing) ön plan servisi durum bildirimi kanalı — sessiz. */
    const val CHANNEL_STATUS = "opencode_sync"

    private const val CHANNEL_NAME = "OpenCode"
    private const val CHANNEL_STATUS_NAME = "OpenCode bağlantı durumu"

    /** Bildirime dokununca açılacak oturumu taşıyan ekstra anahtarı. */
    const val EXTRA_SESSION_ID = "sessionID"

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH),
            )
        }
        if (manager.getNotificationChannel(CHANNEL_STATUS) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, CHANNEL_STATUS_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    /** İçerik dokunuşunda uygulamayı açar; [sessionId] verilirse o oturum açılır. */
    private fun contentIntent(context: Context, id: Int, sessionId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (sessionId != null) putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Basit bildirim. İmza geriye dönük uyumlu; [sessionId] isteğe bağlıdır. */
    fun notify(context: Context, id: Int, title: String, text: String, sessionId: String? = null) {
        init(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(dev.opencode.mobile.R.drawable.ic_stat_phone)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, id, sessionId))
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    /** Eylem düğmeli bildirim (ör. izin Onayla/Reddet). İçerik dokunuşu [sessionId] ile uygulama açar. */
    fun notifyActions(
        context: Context,
        id: Int,
        title: String,
        text: String,
        actions: List<NotificationCompat.Action>,
        sessionId: String? = null,
    ) {
        init(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(dev.opencode.mobile.R.drawable.ic_stat_phone)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, id, sessionId))
        for (a in actions) builder.addAction(a)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, builder.build())
    }

    /**
     * Sürekli durum bildirimi (ön plan servisi): `setOngoing(true)`, otomatik iptal yok.
     * Aynı id ile çağrıldıkça günceller ve oluşturulan bildirimi döndürür
     * (startForeground'a vermek için).
     */
    fun ongoing(
        context: Context,
        id: Int,
        title: String,
        text: String,
        sessionId: String? = null,
    ): Notification {
        init(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(dev.opencode.mobile.R.drawable.ic_stat_phone)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent(context, id, sessionId))
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
        return notification
    }

    /** Bildirimi iptal eder (ör. izin uygulama içinden yanıtlanınca). */
    fun cancel(context: Context, id: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(id)
    }
}
