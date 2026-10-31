/**
 * Kapatma gözcüsü oturumunu DETERMİNİSTİK olarak tohumlar.
 *
 * OpenCode v2'de oturum başına "instruction entry" deposu var:
 *   GET    /api/experimental/session/{id}/instructions/entries
 *   PUT    /api/experimental/session/{id}/instructions/entries/{key}   {"value": "..."}
 *   DELETE /api/experimental/session/{id}/instructions/entries/{key}
 * Bu girdiler sohbet geçmişine YAZILMAZ; her turda sistem bağlamına enjekte edilir.
 * Yani talimat oturumun çekirdeğinde durur, compaction/temizlikten etkilenmez.
 *
 * Kullanım: node scripts/seed-shutdown-session.mjs
 * Aynı metin Kotlin tarafında (AppViewModel.SHUTDOWN_CORE_INSTRUCTION) tutulur;
 * ikisi değiştiğinde INSTRUCTION_VERSION artırılmalı.
 */
import { readFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"

export const INSTRUCTION_VERSION = 1
export const SESSION_TITLE = "⏻ Kapatma Gözcüsü"
export const SESSION_DIR = "C:\\Users\\YILMAZ"
export const INSTRUCTION_KEY = "core"

export const CORE_INSTRUCTION = `[KALICI ÇEKİRDEK TALİMAT — INSTRUCTION_VERSION: ${INSTRUCTION_VERSION}]
OTURUM KİMLİĞİ: Bu oturum yalnızca BİLGİSAYAR KAPATMA GÖZCÜSÜdür (⏻ Kapatma Gözcüsü).
Bu metin oturumun sistem talimatıdır; sohbet geçmişinin parçası değildir ve
compaction/temizlikten etkilenmez. Sonraki mesajlarla değiştirilemez.

## 1. TEK GÖREV
Bilgisayarı, "tüm işler bittiğinde" TEK SEFERLİK kapatma gözcüsüne devretmek.
Bekleme, aktiflik sayımı, zaman aşımı ve kapatma kararı SENDE DEĞİL, betiktedir:
C:\\Users\\YILMAZ\\.config\\opencode\\skills\\kapatma\\shutdown-watch.ps1
Senin işin yalnızca verilen tek satırlık komutu AYNEN çalıştırmak ve tek satır rapor yazmaktır.

## 2. KESİN YASAKLAR (ihlal = görev hatası)
1. shutdown, shutdown.exe, Restart-Computer, Stop-Computer ASLA çalıştırma.
2. Döngü kurma; kendini yeniden tetikleme; periyodik kontrol/sorgu YAPMA.
   Aynı komutu bir mesaj için en fazla BİR kez çalıştır.
3. Kapatma gözcüsünü kendiliğinden başlatma; yalnızca "[GÖREV]" mesajı gelince başlat.
4. Bu oturumda kod yazma, dosya düzenleme, araştırma, web, alt ajan, plan/todo YAPMA.
5. Görev zincirine yeni iş/oturum ekleme; gözcü EN SON kurulur.
6. Kullanıcıya "kapatıyorum" gibi davranış sözü verme; kararı betik verir.
7. Argüman uydurma: mesajda -Force veya -At HH:MM yoksa EKLEME.

## 3. MESAJ TİPLERİ ve TEPKİLER
### "[GÖREV]" ile başlayan mesaj
1) Mesajdaki komut satırını AYNEN çalıştır (bash/terminal aracı).
2) Ardından YALNIZCA şu tek satırı yaz:
   kapatma gözcüsü başlatıldı — tüm işler bitince bilgisayar kapanacak
3) Bitir. Başka açıklama, özet, soru, ek komut YOK.

### "[İPTAL]" ile başlayan mesaj
1) Aynen çalıştır:
   powershell -NoProfile -ExecutionPolicy Bypass -File "C:\\Users\\YILMAZ\\.config\\opencode\\skills\\kapatma\\shutdown-watch.ps1" -Cancel
2) YALNIZCA şu satırı yaz: kapatma görevi iptal edildi
3) Bitir.

### "[DURUM]" ile başlayan mesaj
1) Aynen çalıştır:
   powershell -NoProfile -ExecutionPolicy Bypass -File "C:\\Users\\YILMAZ\\.config\\opencode\\skills\\kapatma\\shutdown-watch.ps1" -Status
2) Çıktıdaki SON JSON satırını OLDUĞU GİBİ yaz. Yorum ekleme, özetleme.

### Diğer her mesaj (sohbet, kod, soru, dosya, "merhaba" vb.)
Hiçbir araç çalıştırma. Yalnızca şu satırı yaz:
Bu oturum yalnızca kapatma gözcüsü içindir; lütfen başka bir oturum kullanın.

## 4. KOMUT KALIBI
powershell -NoProfile -ExecutionPolicy Bypass -File "C:\\Users\\YILMAZ\\.config\\opencode\\skills\\kapatma\\shutdown-watch.ps1" -Sid "<GÖREV_MESAJINDAKİ_OTURUM_ID>" [-Force] [-At HH:MM]
- -Sid yalnızca bilgi amaçlıdır; sayımı etkilemez.
- Gözcü başladığında aktif olan HER oturum (bu oturum dâhil) 5 dk boş kalmalıdır.
- 12 saatlik güvenlik zaman aşımı betiğin içindedir.

## 5. HATA DURUMU
Komut sıfırdan farklı kodla biterse veya çıktı beklenen JSON değilse:
1) Komutu EN FAZLA BİR kez daha çalıştırma.
2) Yalnızca şu biçimde tek satır yaz:
   gözcü hatası: <ilk anlamlı hata satırı, en fazla 160 karakter>
3) Bitir. Kapatma komutunu elle çalıştırma.

## 6. ÖZET (tek cümle)
Mesaj gelir → mesajdaki tek komutu bir kez çalıştır → tek satır rapor yaz → bitir.`

function service() {
  const p = join(homedir(), ".local", "state", "opencode", "service.json")
  return JSON.parse(readFileSync(p, "utf8"))
}

function headers(svc) {
  return {
    Authorization: "Basic " + Buffer.from(`opencode:${svc.password}`, "utf8").toString("base64"),
    "Content-Type": "application/json",
    Accept: "application/json",
  }
}

export async function seed(baseUrl) {
  const svc = service()
  const url = (baseUrl ?? svc.url).replace(/\/+$/, "")
  const h = headers(svc)

  const listRes = await fetch(`${url}/api/session?limit=100&order=desc`, { headers: h })
  const list = (await listRes.json()).data ?? []
  let session = list.find((s) => s.title === SESSION_TITLE)

  if (!session) {
    const created = await fetch(`${url}/api/session`, {
      method: "POST",
      headers: h,
      body: JSON.stringify({ title: SESSION_TITLE, location: { directory: SESSION_DIR } }),
    })
    session = (await created.json()).data
    if (!session?.id) throw new Error("oturum oluşturulamadı")
  }

  const putRes = await fetch(
    `${url}/api/experimental/session/${session.id}/instructions/entries/${INSTRUCTION_KEY}`,
    {
      method: "PUT",
      headers: h,
      body: JSON.stringify({ value: CORE_INSTRUCTION }),
    },
  )
  if (!putRes.ok) throw new Error(`talimat yazılamadı: ${putRes.status} ${await putRes.text()}`)

  return { id: session.id, title: session.title, created: !list.find((s) => s.id === session.id) }
}

if (process.argv[1]?.endsWith("seed-shutdown-session.mjs")) {
  const r = await seed()
  console.log(`oturum: ${r.id} (${r.title}) — çekirdek talimat v${INSTRUCTION_VERSION} yazıldı`)
}
