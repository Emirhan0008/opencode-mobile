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
  bu oturum kalıcıdır; yoksa eski plan hedefi) ve bilgisayar
  tarafında yürütülür: aktiflikler boşalana kadar bekler, sonra kapatma komutunu
  (`shutdown.exe /s /t 120`, istenirse `/f` ile programları zorla kapatarak) **yalnızca bir kez**
  çalıştırır. Telefon kapalı olsa da uygulama kapalı olsa da sürer; iptal edilebilir
  (gözlemci oturuma iptal mesajı + kesme gönderilir). Görev kuralları global `kapatma`
  skill'inde + `scripts/` yerine `~/.config/opencode/skills/kapatma/shutdown-watch.ps1`
  betiğindedir.
  - Gözcü oturumunun **kalıcı sistem talimatı** vardır: OpenCode v2'nin oturum
    `instruction entry` deposuna (`core` anahtarı, `/api/experimental/session/{id}/instructions/entries`)
    yazılır. Sohbet mesajı değildir — geçmişte görünmez, compaction/temizlikten
    etkilenmez, her turda sistem bağlamına enjekte edilir. Böylece gözcü oturumu
    ne yapacağını sohbetten değil çekirdekten bilir; görev mesajı yalnızca tek
    komut satırı taşır. Talimat metni `scripts/seed-shutdown-session.mjs` ile
    aynıdır; elle tohumlamak için `node scripts/seed-shutdown-session.mjs`.
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

## Sesli komut (ekran kilitli · Bluetooth kulaklık)

Telefon cebinizdeyken ve ekran kilitliyken **kulaklığın orta tuşuyla** çalışır:

1. Tuşa bas → bip → konuş. Sessizlik algılanınca kayıt kendiliğinden biter.
2. Uygulama duyduğunu **sesli okur** ("Şunu mu söyledin: …") ve **kendiliğinden dinlemeye
   geçer**. Tuşa basmak gerekmez: **"evet"** de (onayla/tamam/doğru da olur) ya da
   **"hayır"** de (tekrar/yanlış) — yeniden söylemek için. "İptal" akışı bitirir.
3. En yeni 5 üst düzey oturum sesli okunur, ardından mikrofon açılır; **numarayı söyle**
   ("bir"…"beş"). Anlaşılmazsa bir kez daha sorar, yine olmazsa en yeni oturuma gider.
4. Prompt gönderilir, tur bitene kadar beklenir.
5. Modelin **size yazdığı yanıt** (yalnızca `type=assistant` mesajın metin parçaları —
   kabuk/araç çıktıları, düşünme ve alt ajanlar hariç) kısa bir özete çevrilip sesli okunur.

Tuş yalnızca **başlatmak** için gerekir; onay ve oturum seçimi tamamen konuşarak yapılır.
Konuşma sırasında tuş = sus; uzun bas = son yanıtı yeniden özetle.

Medya tuşunun uygulamaya ulaşması için iki şey gerekir:

1. **Ses odağı** (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, `USAGE_ASSISTANT`) — alınmazsa
   tuş başka uygulamaya/Google Asistan'a gider.
2. **Gerçek bir ses çalımı** — Android 13 `MediaSessionService`, medya tuşunu yalnızca
   "son ses çalan" uygulamalar arasından seçer (`getSortedAudioPlaybackClientUids`).
   Servis açılışında ve ekran her kapandığında ~60 ms'lik **sessiz** bir PCM çalınır;
   bu, oturumu medya tuşu oturumu yapar. Aksi hâlde oturum aktif ve odak sahibi olsa
   bile tuş "son bilinen alıcıya" (YouTube/Spotify) gider — telefonda tam olarak bu
   yaşandı.

Oturum ayrıca `setMediaButtonReceiver` ile kendi `VoiceButtonReceiver`'ını bildirir;
sistem bunu `secure.media_button_receiver` ayarına yazar, böylece oturum seçilemese
bile tuş yedeği bize döner.

Ses/LLM işleri **OpenCode'dan bağımsızdır**; OpenCode yalnızca prompt hedefi ve yanıt
kaynağıdır. Tek sağlayıcı **EVREN** (`https://evren-llmapi.ssyz.org.tr`), üç ayrı ve
tek modele kısıtlı anahtar:

| İş | Model | Uç |
|---|---|---|
| Konuşma → metin | `qwen3-asr-1.7b` | `POST /v1/audio/transcriptions` (16 kHz WAV) |
| Metin → ses | `voxcpm2` | `POST /v1/audio/speech` (mp3, ses: deniz/ece/emre/kaan) |
| Sesli özet | `qwen3.8-flash-next` | `POST /v1/chat/completions` (`reasoning_effort: none`) |

Anahtarlar **koda gömülmez**; Ayarlar → *SESLİ KOMUT* bölümünden girilir ve
**AES-GCM + Android Keystore** ile şifreli saklanır (`data/SecurePrefs.kt`). Anahtar
cihazdan dışarı çıkmaz; bu yüzden yedekle taşınmaz, yeni cihazda yeniden girilir.

Özet çağrısında `reasoning_effort: "none"` zorunludur: düşünme açıkken ~5,3 sn,
kapalıyken ~2,3 sn (ölçüldü) — sesli akışta fark belirgindir.

Kayıt Bluetooth **SCO** üzerinden yapılır, okuma **A2DP** ile; ikisi aynı anda
güvenilir çalışmadığı için sırayla kullanılır (`voice/AudioEngine.kt`). Ekran kilitli
çalışma için `RECORD_AUDIO`, `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE_MICROPHONE`
ve `POST_NOTIFICATIONS` gerekir; anahtar açılınca uygulama bunları ister. MIUI/HyperOS'ta
ayrıca **Otomatik başlatma** izni ve **pil kısıtlaması yok** ayarı önerilir.

Tuş iki yoldan dinlenir: etkin `MediaSession` (`HEADSETHOOK` / `MEDIA_PLAY_PAUSE`) ve
yedek olarak `ACTION_MEDIA_BUTTON` yayın alıcısı (`voice/VoiceButtonReceiver.kt`).
Bazı OEM'ler tuşu oturuma değil yayına gönderir; aynı jest iki yoldan da gelirse
servis 400 ms içindeki ikinciyi yutar. Telefonun **varsayılan asistanı** kulaklık
tuşuna atanmışsa (Ayarlar → Asistan ve ses → kulaklık tuşu) tek basış asistanı
açabilir; bu durumda asistandan bu kısayolu kaldırın veya uygulamayı asistan olarak
seçmeyin.

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