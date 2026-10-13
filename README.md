# OpenCode Mobile (Android / Kotlin)

Bilgisayarınızda çalışan OpenCode'a telefondan bağlanan yerel Android uygulaması.
TUI estetiğinde: koyu zemin, monospace yazılar, kutulu paneller.

## Özellikler

- **Bağlantı**: `opencode pair` / `opencode pair --remote` ile verilen bağlantıyı yapıştırın —
  uygulama sunucu adresini ve kodu otomatik ayrıştırır, token'ı alır.
  Aynı Wi-Fi şart değildir; OpenTunnel adresi uzaktan erişim için yeterlidir.
- **Session listesi**: başlık, dizin, güncelleme zamanı, maliyet, çalışır durum göstergesi.
- **Mesaj akışı**: kullanıcı/assistant mesajları, katlanabilir "düşünme" (reasoning) blokları,
  araç (tool) kartları — girdi/çıktı ayrıntısıyla, canlı akış sırasında da.
- **Prompt girişi**: mesaj yazın, yanıt canlı aksın; çalışan görevi durdurma düğmesi.
- **İzin onayları**: "İzin isteği" kartında *bir kez / her zaman / reddet* düğmeleri.

## Kimlik doğrulama

OpenCode API'si HTTP Basic auth kullanır:

- kullanıcı adı: `opencode`
- parola: sunucu parolası **veya** pairing token'ı (ikisi de kabul edilir)

Uygulama pairing kodunu `GET /auth/connect/{code}` ile token'a çevirir ve saklar.

## Geliştirme

```powershell
# Araç zinciri bir kez (JDK 17 + Android SDK + Gradle):
powershell -ExecutionPolicy Bypass -File scripts\setup-toolchain.ps1

# Derle:
.\gradlew.bat assembleDebug

# Telefona kur (USB hata ayıklama açıkken):
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

APK'yi dosya olarak da kurabilirsiniz: `app\build\outputs\apk\debug\app-debug.apk`
dosyasını telefona kopyalayıp açın (bilinmeyen kaynaklardan kurulum izni gerekir).

## Uzaktan erişim (OpenTunnel)

Bilgisayarda:

```powershell
opencode pair --remote
```

İlk kullanımda internete açmayı onaylamanızı ister. Çıkan bağlantıyı
(`https://....../auth/connect/....`) telefon uygulamasına yapıştırın.
Erişim sunucu parolasıyla korunur; trafik uçtan uca şifrelidir.

Parolayı değiştirmek için: `opencode service set password "uzun-bir-parola"`.

## Mimari

```
ui/            ConnectScreen, SessionsScreen, SessionScreen (Jetpack Compose)
net/Api.kt     HTTP API istemcisi + SSE olay akışı (OkHttp)
data/          DataStore ile bağlantı ayarları
AppViewModel   Durum yönetimi, canlı akış birleştirme
```

OpenCode API'si hakkında: https://opencode.ai/v2/docs/api