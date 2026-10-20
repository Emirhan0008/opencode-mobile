import { loadConfig, log } from "./config.js"
import { connect } from "./opencode.js"
import { Relay } from "./relay.js"
import { createBot } from "./bot.js"
import { startStatusServer } from "./status.js"

async function main(): Promise<void> {
  const cfg = loadConfig()
  const startedAt = Date.now()

  if (cfg.allowedIds.size === 0) {
    log.warn("TELEGRAM_ALLOWED_IDS boş — kimse botu kullanamaz. .env dosyasını doldurun.")
  }

  const { client, url: opencodeUrl } = await connect(cfg.baseUrl)
  const info = await client.server.info().catch(() => undefined)
  if (info) log.info("opencode server:", JSON.stringify(info))

  const { bot, relayEvents } = createBot(cfg, client)
  const relay = new Relay(client, relayEvents)
  relay.start()

  // Botun kullanıcı adı (t.me linki + uygulama durum ekranı için).
  const me = await bot.api.getMe().catch((err: unknown) => {
    log.warn("getMe başarısız:", err instanceof Error ? err.message : err)
    return undefined
  })

  const status = startStatusServer({
    host: cfg.statusHost,
    port: cfg.statusPort,
    getInfo: () => ({
      ok: true,
      bot: { username: me?.username ?? null, id: me?.id ?? null },
      allowed: cfg.allowedIds.size,
      opencode: opencodeUrl,
      uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
      version: "0.2.0",
    }),
  })

  if (me?.username) {
    log.info(`telegram botu: @${me.username} — https://t.me/${me.username}`)
  } else {
    log.info("telegram botu: kullanıcı adı alınamadı")
  }
  log.info("telegram botu başlatılıyor… (durdurmak için Ctrl+C)")
  await bot.start()

  // bot.start() normalde dönmez; yine de kapanışta durum sunucusunu kapat.
  status.close()
}

main().catch((err) => {
  log.error("fatal:", err)
  process.exit(1)
})
