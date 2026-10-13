package dev.opencode.mobile.net

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.opencode.mobile.data.Settings
import dev.opencode.mobile.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Tüm oturumlar bitince bilgisayarı kapat" bekçisi.
 *
 * Kullanıcı uygulamadan kapatmayı kurduğunda bu ön planda servis başlar ve
 * OpenCode'da hiçbir oturum aktif kalana kadar (alt ajanlar dahil) bekler.
 * Aktiflik sıfırlandığında hedef oturuma tek seferlik kapatma komutu gönderilir:
 * "opencode-mobile" başlıklı oturum varsa ona, yoksa en son güncellenen oturuma.
 * Komut doğrudan oturumun kabuğunda çalıştırılır; uç yoksa oturuma prompt olarak yazılır.
 */
class ShutdownWatcherService : Service() {

    companion object {
        const val EXTRA_FORCE = "force"
        private const val NOTIF_ID = 4242
        private const val GRACE_MS = 45_000L   // kurulum anındaki hareketlilik geçsin
        private const val POLL_MS = 20_000L
        private const val SHUTDOWN_DELAY_S = 120

        fun start(context: Context, force: Boolean) {
            val intent = Intent(context, ShutdownWatcherService::class.java)
                .putExtra(EXTRA_FORCE, force)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.stopService(Intent(context, ShutdownWatcherService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        startAsForeground(force)
        watchJob?.cancel()
        watchJob = scope.launch { watch(force) }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        watchJob?.cancel()
        super.onDestroy()
    }

    private fun startAsForeground(force: Boolean) {
        val text = if (force) {
            "Tüm oturumlar bitince bilgisayar kapatılacak (programlar zorla kapanır)."
        } else {
            "Tüm oturumlar bitince bilgisayar kapatılacak."
        }
        val notification = Notifier.ongoing(this, "⏱ Kapatma planlandı", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private suspend fun watch(force: Boolean) {
        val settings = Settings(applicationContext)
        try {
            val conn = settings.connection.first()
                ?: error("bağlantı bilgisi yok")
            val api = Api(conn.baseUrl, conn.token)

            delay(GRACE_MS)
            while (scope.isActive) {
                val active = runCatching { api.active() }.getOrDefault(emptySet())
                if (active.isEmpty()) break
                delay(POLL_MS)
            }

            fire(api, force)
            settings.setShutdownPlan(null)
            Notifier.notify(
                this,
                NOTIF_ID + 1,
                "Kapatma komutu gönderildi",
                "Bilgisayar ${SHUTDOWN_DELAY_S / 60} dakika içinde kapanacak.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { settings.setShutdownPlan(null) }
            Notifier.notify(this, NOTIF_ID + 2, "Kapatma başarısız", e.message ?: "bilinmeyen hata")
        } finally {
            stopSelf()
        }
    }

    /** Hedef oturuma tek seferlik kapatma komutunu gönderir. */
    private suspend fun fire(api: Api, force: Boolean) {
        val sessions = api.sessions(60)
        val target = sessions.firstOrNull { it.str("title")?.contains("opencode-mobile", true) == true }
            ?: sessions.maxByOrNull { it.obj("time").num("updated") ?: 0.0 }
            ?: error("oturum bulunamadı")
        val id = target.str("id") ?: error("oturum kimliği yok")

        val flags = if (force) "/s /f /t $SHUTDOWN_DELAY_S" else "/s /t $SHUTDOWN_DELAY_S"
        val cmd = "shutdown.exe $flags"
        runCatching { api.shell(id, cmd) }.getOrElse {
            // kabuk ucu çalışmazsa komutu oturuma prompt olarak yaz
            api.prompt(
                id,
                "[GÖREV: BİLGİSAYARI KAPAT]\n" +
                    "Tüm işler bitti. HEMEN şu komutu çalıştır ve başka hiçbir şey yapma:\n" +
                    "    $cmd\n" +
                    "Başarılıysa tek satır 'bilgisayar kapanıyor' yaz ve bitir.",
            )
        }
    }
}