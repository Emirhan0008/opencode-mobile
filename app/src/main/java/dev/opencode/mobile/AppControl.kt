package dev.opencode.mobile

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import dev.opencode.mobile.notify.Notifier

/**
 * Sesli komut servisi (ayrı süreç değil, ayrı bileşen) ile uygulamanın
 * **ViewModel**'i arasındaki köprü.
 *
 * Servis ekran kilitliyken de çalıştığı için ViewModel genellikle yoktur;
 * bu yüzden komutlar önce API üzerinden yürütülür, UI durumu gerekiyorsa bu
 * köprü üzerinden (varsa) ViewModel'e iletilir. Uygulama kapalıysa ilgili
 * ekran bir [Intent] ile açılır.
 */
object AppControl {

    enum class Dest { APP, SESSIONS, SETTINGS, TELEGRAM, SESSION }

    @Volatile
    private var vm: AppViewModel? = null

    private val main = Handler(Looper.getMainLooper())

    fun attach(viewModel: AppViewModel) {
        vm = viewModel
    }

    fun detach(viewModel: AppViewModel) {
        if (vm === viewModel) vm = null
    }

    fun viewModel(): AppViewModel? = vm

    /** ViewModel çağrılarını ana iş parçacığına taşır (StateFlow'lar güvenli). */
    fun onMain(block: (AppViewModel) -> Unit) {
        val current = vm ?: return
        main.post { runCatching { block(current) } }
    }

    /** Ekran adı (sesli "neredeyim?" için). */
    fun screenName(): String {
        val current = vm ?: return "uygulama kapalı"
        return when (val s = current.screen.value) {
            Screen.Connect -> "bağlantı ekranı"
            Screen.Sessions -> "oturum listesi"
            Screen.Settings -> "ayarlar"
            Screen.Telegram -> "telegram ayarları"
            is Screen.Session -> "bir oturum ekranı"
        }
    }

    /** Uygulamayı/istenen ekranı öne getirir. */
    fun launch(context: Context, dest: Dest, sessionId: String? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            when (dest) {
                Dest.SETTINGS -> putExtra(MainActivity.EXTRA_DEST, MainActivity.DEST_SETTINGS)
                Dest.TELEGRAM -> putExtra(MainActivity.EXTRA_DEST, MainActivity.DEST_TELEGRAM)
                Dest.SESSIONS -> putExtra(MainActivity.EXTRA_DEST, MainActivity.DEST_SESSIONS)
                Dest.SESSION -> {
                    putExtra(MainActivity.EXTRA_DEST, MainActivity.DEST_SESSION)
                    if (sessionId != null) putExtra(Notifier.EXTRA_SESSION_ID, sessionId)
                }
                Dest.APP -> Unit
            }
        }
        runCatching { context.startActivity(intent) }
    }
}
