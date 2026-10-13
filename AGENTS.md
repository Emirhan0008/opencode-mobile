# AGENTS.md — opencode-mobile projesi kuralları

Bu dosya, bu projede (ve kapatma görevi alan oturumlarda) çalışan ajanların uyması
gereken kalıcı kuralları içerir. Kullanıcı bu kuralları her seferinde açıklamak
zorunda değildir; burada yazanlar her zaman geçerlidir.

## Kapatma görevi (shutdown watcher) — ZORUNLU KURALLAR

**Her şey global "kapatma" skill'inde:**
`C:\Users\YILMAZ\.config\opencode\skills\kapatma\` (SKILL.md + shutdown-watch.ps1).
Görev veya "[İPTAL]" prompt'u gelince **DÜŞÜNME**; skill'de yazan betiği aynen
çalıştır ve bitir. Bekleme, kontrol ve kapatmanın tamamı betikte — ajan yalnızca
tek satır komutu çalıştırır, kendisi hiçbir şey yapmaz.

1. **TEK SEFERLİK**: `shutdown.exe` komutu en fazla **BİR KEZ** çalıştırılır ve o da
   yalnızca betik tarafından. Komut gönderildikten sonra görev biter.
2. **ASLA DÖNGÜYE GİRME**: kapatmayı periyodik olarak tekrarlama, kendini yeniden
   tetikleme, bu görevi başka zamanlarda kendiliğinden uygulama.
   **Geçmişte bir oturum bunu sürekli tekrarlayan bir döngüye soktu ve bilgisayar
   defalarca kendiliğinden kapandı — bu bir HATADır, asla tekrarlanmayacak.**
3. **OTONOM KAPATMA YOK**: kapatma yalnızca kullanıcının açık isteğiyle tetiklenir
   (görev prompt'u veya doğrudan istek). İstek gelmeden kapatma komutu çalıştırma.
4. **TEK SEFERLİK GÖREV TAKİBİ**: bir kapatma görevi zaten açıkken ikincisini kurma;
   önce sor veya eskisini iptal et.
5. **İPTAL**: "[İPTAL]" gelince betiğin `-Cancel` komutunu çalıştır; kapatma komutu
   asla çalıştırılmaz. (12 saat zaman aşımı betiğin içindedir; kapatma için aktif
   olan **her** oturumun bitmesi gerekir — kendi oturumu ayırmak gerekmez, görev
   prompt'u olduğu gibi başka oturuma yapıştırılabilir.)
6. **DÜŞÜNMEYİ KALDIR**: bu görevde yorum/tartışma/araştırma yapma — komutu çalıştır,
   tek satır raporla, bitir.

Görev prompt'unun kanonik üreticisi `AppViewModel.shutdownTask()` içindedir
(`app/src/main/java/dev/opencode/mobile/AppViewModel.kt`); metni değiştirirsen
skill'deki betik yolunu ve bu kuralların tamamını koru.

## Proje geneli

- Proje dili Kotlin + Jetpack Compose; derleme `gradlew assembleDebug` (JDK 17).
- Değişiklikler cihazda (USB bağlı telefon, hata ayıklama açık) doğrulanır;
  derleme + kurulum + kontrol ajanın sorumluluğundadır.
- Kullanıcı arayüzü metinleri Türkçedir; kod içi yorumlar da Türkçe yazılır.
- Commit mesajları tek satır, Türkçe, neyin değiştiğini açıkça söyleyecek.