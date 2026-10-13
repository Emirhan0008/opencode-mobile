# AGENTS.md — opencode-mobile projesi kuralları

Bu dosya, bu projede (ve kapatma görevi alan oturumlarda) çalışan ajanların uyması
gereken kalıcı kuralları içerir. Kullanıcı bu kuralları her seferinde açıklamak
zorunda değildir; burada yazanlar her zaman geçerlidir.

## Kapatma görevi (shutdown watcher) — ZORUNLU KURALLAR

Kullanıcı "tüm işler bitince bilgisayarı kapat" dediğinde uygulama (veya ajan) bunu
bir OpenCode oturumuna **gözlemci prompt** olarak gönderir. Görev bilgisayar tarafında
yürütülür; telefon veya uygulama kapalı olsa da sürer.

1. **TEK SEFERLİK**: `shutdown.exe` komutu en fazla **BİR KEZ** çalıştırılır. Komut
   gönderildikten sonra görev biter; bekleme döngüsüne geri dönülmez.
2. **ASLA DÖNGÜYE GİRME**: kapatmayı periyodik olarak tekrarlama, kendini yeniden
   tetikleme, bu görevi başka zamanlarda kendiliğinden uygulama.
   **Geçmişte bir oturum bunu sürekli tekrarlayan bir döngüye soktu ve bilgisayar
   defalarca kendiliğinden kapandı — bu bir HATADır, asla tekrarlanmayacak.**
3. **OTONOM KAPATMA YOK**: kapatma yalnızca kullanıcının açık isteğiyle tetiklenir.
   İstek gelmeden kapatma görevi kurma veya kapatma komutu çalıştırma.
4. **TEK SEFERLİK GÖREV TAKİBİ**: bir kapatma görevi zaten açıkken (oturumda görev
   prompt'u görünürken) ikinci bir görev kurma; önce sor veya eskisini iptal et.
5. **İPTAL**: kullanıcı iptal ettiğinde bekleme bırakılır, kapatma komutu
   **çalıştırılmaz** ve görev hemen sonlandırılır.
6. **ZAMAN AŞIMI**: 12 saat içinde "tüm işler bitti" koşulu sağlanmazsa kapatma
   komutu çalıştırmadan görevi "zaman aşımı" diyerek bitir.
7. **KENDİNİ SAYMA**: aktif oturum kontrolünde gözlemci görevi yürüten kendi oturumun
   sayılmaz; yalnızca diğer oturumların bitmesi beklenir.

Görev prompt'unun kanonik metni `AppViewModel.shutdownTask()` içindedir
(`app/src/main/java/dev/opencode/mobile/AppViewModel.kt`). Metni değiştirirsen
bu kuralların tamamını koru; özellikle 1, 2 ve 3. maddeleri asla çıkarma.

## Proje geneli

- Proje dili Kotlin + Jetpack Compose; derleme `gradlew assembleDebug` (JDK 17).
- Değişiklikler cihazda (USB bağlı telefon, hata ayıklama açık) doğrulanır;
  derleme + kurulum + kontrol ajanın sorumluluğundadır.
- Kullanıcı arayüzü metinleri Türkçedir; kod içi yorumlar da Türkçe yazılır.
- Commit mesajları tek satır, Türkçe, neyin değiştiğini açıkça söyleyecek.