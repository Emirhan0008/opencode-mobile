import http from "node:http"
import { log } from "./config.js"

/** Uygulamanın (veya tarayıcının) okuyabileceği, SIR İÇERMEYEN durum bilgisi. */
export interface StatusInfo {
  ok: boolean
  bot: { username: string | null; id: number | null }
  /** Yetkili Telegram kullanıcı sayısı (ID'ler paylaşılmaz). */
  allowed: number
  /** Bağlanılan OpenCode sunucusu (yerel adres). */
  opencode: string | null
  uptimeSeconds: number
  version: string
}

export interface StatusServerOptions {
  host: string
  port: number
  getInfo: () => StatusInfo
}

/**
 * Küçük, salt-okunur durum ucu:
 *   GET /health (veya /) → JSON
 *
 * Varsayılan olarak 127.0.0.1'e bağlanır; böylece ağa açılmaz. Aynı ağdaki
 * telefondan kontrol için `.env` içinde TELEGRAM_STATUS_HOST=0.0.0.0 yapılabilir.
 * Yanıtta token, parola veya kullanıcı ID'si YOKTUR.
 */
export function startStatusServer(opts: StatusServerOptions): http.Server {
  const server = http.createServer((req, res) => {
    const path = (req.url ?? "/").split("?")[0]
    if (req.method !== "GET" || (path !== "/health" && path !== "/")) {
      res.writeHead(404, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" })
      res.end(JSON.stringify({ ok: false, error: "not_found" }))
      return
    }

    let body: string
    try {
      body = JSON.stringify({ ...opts.getInfo(), ok: true })
    } catch (err) {
      log.warn("status: yanıt üretilemedi:", err)
      res.writeHead(500, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" })
      res.end(JSON.stringify({ ok: false, error: "internal" }))
      return
    }

    res.writeHead(200, {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
    })
    res.end(body)
  })

  server.on("error", (err) => {
    // Port dolu olsa bile bot çalışmaya devam etmeli.
    log.warn(`status sunucusu başlatılamadı (${opts.host}:${opts.port}):`, err)
  })

  server.listen(opts.port, opts.host, () => {
    log.info(`status: http://${opts.host}:${opts.port}/health`)
  })

  return server
}
