# OpenCode Mobile (Android / Kotlin)

<p align="center"><img src="docs/icon-512.png" width="120" alt="OpenCode Mobile" /></p>

Bilgisayarınızda çalışan OpenCode'a telefondan bağlanan yerel Android uygulaması.
TUI estetiğinde: koyu zemin, monospace yazılar, kutulu paneller.

## Özellikler

- **Bağlantı**: `opencode pair` / `opencode pair --remote` ile verilen bağlantıyı yapıştırın —
  uygulama sunucu adresini ve kodu otomatik ayrıştırır, token'ı alır.
  Aynı Wi-Fi şart değildir; OpenTunnel adresi uzaktan erişim için yeterlidir.
- **Session listesi**: başlık, dizin, güncelleme zamanı, maliyet, çalışır durum ve *başarısız*
  göstergesi; başlık/dizin araması ve *üst düzey / tümü / aktif / son 24s* filtresi.
  Alt ajan (subagent) oturumları `↳` ile işaretlenir ve varsayılan olarak gizlenir —
  *tümü* filtresiyle açılır (OpenCode her alt ajan çalıştırması için ayrı oturum kaydı açar).
- **Mesaj akışı**: kullanıcı/assistant mesajları, katlanabilir "düşünme" (reasoning) blokları,
  araç (tool) kartları — girdi/çıktı ayrıntısıyla, canlı akış sırasında da;
  model/sağlayıcı hataları mesaj içinde görünür.
- **Prompt girişi**: mesaj yazın, yanıt canlı aksın; çalışan görevi durdurma düğmesi.
- **Ajan & model seçimi**: oturum için ajan (build/…) ve model değiştirme.
- **Komutlar**: oturum ekranındaki "⌨ komutlar" düğmesi OpenCode'daki tüm komutları
  (`/init`, `/review`, uzantı komutları…) aranabilir listeler; isteğe bağlı bağımsız
  değişkenle doğrudan oturumda çalıştırır.
- **Kaydırma ile gezinme**: mesaj akışında sağa/sola kaydırınca önceki/sonraki oturuma geçilir.
- **Kapatma görevi**: ⏻ düğmesiyle "tüm işler bitince bilgisayarı kapat" görevi kurulur.
  Görev **seçtiğiniz oturuma** gözlemci prompt olarak gönderilir (hedef diyalogdan seçilir;
  "otomatik" ise uygulamanın kendisi için açtığı **⏻ Kapatma Gözcüsü** oturumu kullanılır —
  bu oturum kalıcıdır, ilk açılışta çekirdek talimatla tohumlanır; yoksa "opencode-mobile"
  oturumu, o da yoksa en son güncellenen oturum) ve bilgisayar
  tarafında yürütülür: aktiflikler boşalana kadar bekler, sonra kapatma komutunu
  (`shutdown.exe /s /t 120`, istenirse `/f` ile programları zorla kapatarak) **yalnızca bir kez**
  çalıştırır. Telefon kapalı olsa da uygulama kapalı olsa da sürer; iptal edilebilir
  (gözlemci oturuma iptal mesajı + kesme gönderilir). Görev kuralları global `kapatma`
  skill'inde + `scripts/` yerine `~/.config/opencode/skills/kapatma/shutdown-watch.ps1`
  betiğindedir.
- **Uzun basma menüleri**:
  - Oturum satırı: başlık/ID kopyalama, yeniden adlandırma, çoğaltma (fork), silme (onaylı).
  - Mesaj kartı: metni kopyalama, yanıta alıntı olarak ekleme, o noktaya kadar geri alma
    (`revert/stage` + `revert/commit`).
- **Soru kartları**: `question` aracıdan gelen etkileşimli formlar (seçenekli/serbest yanıt)
  doğrudan telefondan yanıtlanabilir.
- **İzin onayları**: "İzin isteği" kartında *bir kez / her zaman / reddet* düğmeleri.
- **Bildirimler**: uygulama arka plandayken izin isteği, soru ve görev bitişi bildirimleri.
- **Telegram yedek erişim**: üst bardaki ✈ düğmesi Telegram köprüsünü açar; bot
  kullanıcı adı + isteğe bağlı yerel durum kontrolü (`/health`) ve tek dokunuşla
  `t.me` linki. Uygulama veya OpenTunnel kapalıyken Telegram'dan devam edilebilir.
- **Tema**: koyu / açık tema anahtarı (kalıcı).

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

## Telegram yedek erişim

`telegram-bridge/` altındaki küçük Node/TypeScript köprüsü, OpenCode'u Telegram
üzerinden kullanmayı sağlar: prompt gönderme, canlı yanıt, oturum listesi/seçimi,
izin onayları ve bildirimler. **Mobil uygulama veya OpenTunnel çalışmasa bile**
bilgisayar açık ve internete bağlı olduğu sürece bu yol çalışır — bu yüzden
"yedek erişim"dir.

Kurulum (bir kez):

```powershell
cd W:\opencode-mobile\telegram-bridge
Copy-Item .env.example .env      # ilk kez
notepad .env                     # TELEGRAM_BOT_TOKEN + TELEGRAM_ALLOWED_IDS
npm install
npm start
```

- Bot token'ı BotFather'dan (`/newbot`) alınır.
- `TELEGRAM_ALLOWED_IDS` boşken bot **kimseye** izin vermez (fail-closed).
  Botu başlatıp Telegram'da `/start` yazın; size ID'nizi söyler, `.env`'e ekleyin.
- Köprü çalışırken `http://127.0.0.1:49375/health` durum ucunu açar (sır içermez).
  Aynı ağdaki telefondan kontrol için `.env` içinde `TELEGRAM_STATUS_HOST=0.0.0.0`.
- Uygulamada ✈ düğmesi → bot kullanıcı adını girin → **Telegram'da aç**.
  Aynı ağdaysanız durum adresini girip **Durumu kontrol et** ile canlı durum alın.

`.env` ve `node_modules/` git'e girmez (`telegram-bridge/.gitignore`).

## Mimari

```
ui/            ConnectScreen, SessionsScreen, SessionScreen, TelegramScreen (Compose)
net/Api.kt     HTTP API istemcisi + SSE olay akışı (OkHttp)
data/          DataStore ile bağlantı ayarları
AppViewModel   Durum yönetimi, canlı akış birleştirme
telegram-bridge/  Node/TypeScript Telegram köprüsü (yedek erişim) + /health ucu
```

OpenCode API'si hakkında: https://opencode.ai/v2/docs/api