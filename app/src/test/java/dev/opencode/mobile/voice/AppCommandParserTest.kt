package dev.opencode.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sesli uygulama komutlarının sözlük testi. Amaç: "gönder" gibi kelimeler
 * geçen normal isteklerin yanlışlıkla komut sayılmaması ve Türkçe eklerin
 * ("oturumu", "ajanı", "modeli", "temayı") doğru eşleşmesi.
 */
class AppCommandParserTest {

    @Test
    fun navigation() {
        assertEquals(AppAction.OPEN_SETTINGS, parse("ayarları aç")?.action)
        assertEquals(AppAction.OPEN_TELEGRAM, parse("telegramı aç")?.action)
        assertEquals(AppAction.BACK, parse("oturum listesine dön")?.action)
        assertEquals(AppAction.WHERE, parse("neredeyim")?.action)
        assertEquals(AppAction.OPEN_APP, parse("uygulamayı aç")?.action)
    }

    @Test
    fun sessions() {
        assertEquals(AppAction.LIST_SESSIONS, parse("oturumları listele")?.action)
        assertEquals(AppAction.LIST_SESSIONS, parse("hangi oturumlar var")?.action)

        val byNumber = parse("ikinci oturumu aç")
        assertEquals(AppAction.OPEN_SESSION, byNumber?.action)
        assertEquals(2, byNumber?.number)

        val byName = parse("fastpass oturumunu aç")
        assertEquals(AppAction.OPEN_SESSION, byName?.action)
        assertEquals("fastpass", byName?.arg)

        assertEquals(AppAction.NEW_SESSION, parse("yeni oturum aç")?.action)
        assertEquals("deneme", parse("yeni oturum aç adı deneme")?.arg)
        assertEquals("deneme", parse("deneme adında yeni oturum aç")?.arg)
    }

    @Test
    fun settingsAndTheme() {
        assertEquals(AppAction.THEME_DARK, parse("koyu tema")?.action)
        assertEquals(AppAction.THEME_LIGHT, parse("açık tema")?.action)
        assertEquals(AppAction.THEME_TOGGLE, parse("temayı değiştir")?.action)
        assertEquals(AppAction.NOTIFY_OFF, parse("bildirimleri kapat")?.action)
        assertEquals(AppAction.BACKGROUND_ON, parse("arka plan servisini aç")?.action)
        assertEquals(AppAction.VOICE_OFF, parse("sesli komutu kapat")?.action)
    }

    @Test
    fun agentAndModel() {
        assertEquals("plan", parse("plan ajanına geç")?.arg)
        assertEquals("plan", parse("ajanı plan yap")?.arg)
        assertEquals("qwen", parse("modeli qwen yap")?.arg)
    }

    @Test
    fun send() {
        assertEquals("yarın toplantı var", parse("gönder yarın toplantı var")?.arg)
        assertEquals("selam", parse("şunu gönder selam")?.arg)
        // Tek kelimelik "mesaj gönder" komut değil, normal istek.
        assertNull(parse("mesaj gönder"))
    }

    @Test
    fun normalPromptsAreNotCommands() {
        assertNull(parse("bana kısa bir hikaye anlat"))
        assertNull(parse("FastPass oturumunda hangi çelişkiler var"))
        assertNull(parse("kodu gözden geçir ve hataları düzelt"))
        assertNull(parse("özet"))
    }

    @Test
    fun interruptAndRead() {
        assertEquals(AppAction.READ_LAST, parse("son yanıtı oku")?.action)
        assertEquals(AppAction.INTERRUPT, parse("durdur")?.action)
        assertEquals(AppAction.HELP, parse("neler yapabilirsin")?.action)
    }

    private fun parse(text: String) = AppCommandParser.parse(text)
}
