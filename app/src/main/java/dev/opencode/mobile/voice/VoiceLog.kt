package dev.opencode.mobile.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sesli akışın **konuşma günlüğü**.
 *
 * Amaç: kullanıcı "yanlış duydu / yanlış gönderdi" dediğinde tahmin etmek
 * yerine gerçek metni görebilmek. Her satır tek olay:
 *
 * ```
 * 23:41:07.512  DİNLE      başladı
 * 23:41:11.940  DUYDU      "şunun yerine bu"
 * 23:41:12.010  DÜZELTME   hedef=şunun repl=bu
 * 23:41:12.480  GÖNDER     oturum=FastPass metin="..."
 * ```
 *
 * Dosya: `Android/data/dev.opencode.mobile/files/voice.log`
 * (`adb pull` ile kök izni olmadan alınabilir). 512 KB'ı geçince bir kez
 * `voice.log.1`e döner — telefonun depolaması şişmesin.
 */
object VoiceLog {

    private const val TAG = "VoiceLog"
    private const val NAME = "voice.log"
    private const val MAX_BYTES = 512 * 1024L

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale("tr", "TR"))
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale("tr", "TR"))

    @Volatile private var file: File? = null
    @Volatile private var enabled = true

    fun init(context: Context) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            file = File(dir, NAME)
            enabled = true
            write("OTURUM", "günlük açıldı · ${day.format(Date())}")
        }.onFailure {
            enabled = false
            Log.w(TAG, "günlük açılamadı: ${it.message}")
        }
    }

    /** Tek satır yazar. G/Ç hatası akışı bozmaz. */
    fun write(tag: String, message: String) {
        if (!enabled) return
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "$NAME.1")
                runCatching { old.delete() }
                runCatching { f.renameTo(old) }
            }
            f.appendText("${stamp.format(Date())}  ${tag.padEnd(9)} $message\n")
        }.onFailure { Log.w(TAG, "yazılamadı: ${it.message}") }
    }

    /** Duyulan/söylenen metni tırnak içinde, tek satırda yazar. */
    fun text(tag: String, value: String?) {
        write(tag, "\"${oneLine(value)}\"")
    }

    private fun oneLine(v: String?): String =
        (v ?: "").replace("\r", " ").replace("\n", " ⏎ ").take(600)

    /** Günlük dosyasının yolu (Ayarlar ekranında göstermek için). */
    fun path(): String? = file?.absolutePath
}
