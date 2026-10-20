# OpenCode ⇄ Telegram köprüsü

Bilgisayarınızda açık duran [OpenCode](https://opencode.ai) oturumlarını Telegram üzerinden kullanmanızı sağlar:

- Seçili bir session'a prompt gönderir, yanıtı **canlı akış** halinde Telegram'da görürsünüz
- Session listesi, aralarında geçiş, yeni session açma
- Agent'ın bekleyen **izin (permission)** isteklerini Telegram düğmeleriyle onaylama / reddetme
- Görev bitince, hata alınca veya izin istenince **bildirim**

Her şey bilgisayarınızda çalışır; OpenCode servisi yerel HTTP API üzerinden konuşulur (`@opencode/client`).

## Kurulum

1. **Bağımlılıklar** (bir kez):

   ```powershell
   cd C:\Users\YILMAZ\opencode-telegram-bridge
   npm install
   ```

2. **Telegram bot'u oluşturun**: Telegram'da [@BotFather](https://t.me/BotFather) → `/newbot` → ad verin. Size bir **bot token** verilir (`123456789:AA...`).

3. **`.env` dosyasını doldurun**:

   ```powershell
   Copy-Item .env.example .env
   notepad .env
   ```

   ```
   TELEGRAM_BOT_TOKEN=123456789:AA...   # BotFather token'ı
   TELEGRAM_ALLOWED_IDS=123456789       # Kendi Telegram kullanıcı ID'niz
   ```

   Kullanıcı ID'nizi bilmiyorsanız `TELEGRAM_ALLOWED_IDS` boşken botu başlatıp Telegram'da bot'a `/start` yazın; bot ID'nizi söyler. Onu listeye ekleyip botu yeniden başlatın.

4. **Çalıştırın**:

   ```powershell
   npm start
   ```

   OpenCode arka plan servisi açık değilse köprü onu otomatik başlatır. Açık olan TUI'niz köprünün çalışmasını engellemez — ikisi aynı servisi paylaşır.

## Kullanım

| Komut | Açıklama |
| --- | --- |
| *(düz mesaj)* | Seçili session'a prompt gider, yanıt canlı akar |
| `/sessions` | Son session'ları listeler, düğmeyle seçersiniz |
| `/use <no>` | Listedeki sıradan session seçer |
| `/new [dizin]` | Yeni session açar (örn. `/new C:\projeler\api`) |
| `/current` | Seçili session'ın bilgisi (model, dizin, maliyet) |
| `/last` | Seçili session'ın son yanıtı |
| `/model [sağlayıcı/model]` | Modeli gösterir / değiştirir |
| `/perm` | Bekleyen izin isteklerini düğmelerle listeler |
| `/stop` | Seçili session'ı durdurur (interrupt) |
| `/help` | Yardım |

İzin isteği geldiğinde üç düğme görünür: **Bir kez** (`once`), **Her zaman** (`always`), **Reddet** (`reject`).

## Güvenlik

- Bot yalnızca `TELEGRAM_ALLOWED_IDS` listesindeki kullanıcılara yanıt verir; diğerleri komut çalıştıramaz.
- Bot token'ı `.env` içindedir ve git'e eklenmemelidir (`.gitignore` bu dosyayı dışlar).
- Telegram hesabı ele geçirilirse bu bot, o kullanıcının bilgisayarında kod çalıştırma yetkisi verir. Kullanıcı listesini kısa tutun.

## Bilinen sınırlar

- Telegram mesaj sınırı 4096 karakter; uzun yanıtların sadece son bölümü gösterilir.
- Streaming sırasında mesaj düzenleme Telegram'ın hız sınırlarına takılmamak için ~1,5 saniyede bir yapılır.
- Dosya eki (attachment) gönderimi henüz desteklenmez; yalnızca metin prompt gider.
- Köprü kapanıp açıldığında bekleyen izin düğmelerinin ömrü biter; `/perm` ile yeniden listeleyebilirsiniz.

## Mimari

```
Telegram ──(long polling)──> grammy bot (src/bot.ts)
                                 │
                                 ▼
                     @opencode/client (src/opencode.ts)
                                 │  yerel HTTP + SSE
                                 ▼
                     OpenCode arka plan servisi
                                 ▲
                     src/relay.ts ── olay akışını canlı görünüme çevirir
```

- `src/config.ts` — `.env` okuma
- `src/opencode.ts` — servis keşfi ve API istemcisi
- `src/relay.ts` — `session.text.*`, `session.tool.*`, `session.idle`, `permission.*` olaylarını işler
- `src/bot.ts` — Telegram komutları ve düğmeleri
- `src/status.ts` — salt-okunur `/health` durum ucu (uygulama durum kontrolü)

## Durum ucu (uygulama entegrasyonu)

Köprü çalışırken küçük bir HTTP ucu açar:

```bash
curl http://127.0.0.1:49375/health
```

```json
{
  "ok": true,
  "bot": { "username": "ornek_bot", "id": 123456789 },
  "allowed": 1,
  "opencode": "http://127.0.0.1:49374",
  "uptimeSeconds": 42,
  "version": "0.2.0"
}
```

- Yanıt **sır içermez**: token, parola ve kullanıcı ID'leri paylaşılmaz.
- Varsayılan olarak yalnızca `127.0.0.1` dinlenir. Aynı ağdaki telefondan
  kontrol için `.env` içinde `TELEGRAM_STATUS_HOST=0.0.0.0` yapın.
- Uygulama bu ucu "Telegram → Durumu kontrol et" ekranından sorgular; botun
  kullanıcı adını da buradan alır (`t.me/<username>` linki için).

> Mobil uygulama uzaktan (mobil veri) bağlanıyorsa bu yerel uca erişemez;
> bu durumda uygulama yalnızca `t.me` linkini açar, canlı durum göstermez.
> Telegram'ın kendisi zaten yedek erişim yoludur: uygulama/tünel kapalıyken
> bot, bilgisayar açık ve internete bağlı olduğu sürece çalışmaya devam eder.