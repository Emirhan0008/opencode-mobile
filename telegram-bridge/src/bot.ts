import { Bot, InlineKeyboard, GrammyError, type Api } from "grammy"
import type { BridgeConfig } from "./config.js"
import { log } from "./config.js"
import type { Client } from "./opencode.js"
import type { Outcome, PermissionInfo, RelayEvents } from "./relay.js"

/** Telegram callback_data is limited to 64 bytes, so long IDs are swapped for short tokens. */
class TokenStore {
  private map = new Map<string, CallbackTarget>()

  make(target: CallbackTarget): string {
    const id = `t${this.map.size}_${Math.random().toString(36).slice(2, 7)}`
    this.map.set(id, target)
    if (this.map.size > 500) {
      const oldest = this.map.keys().next().value
      if (oldest) this.map.delete(oldest)
    }
    return id
  }

  get(id: string): CallbackTarget | undefined {
    return this.map.get(id)
  }
}

type CallbackTarget =
  | { kind: "select"; sessionID: string }
  | { kind: "permission"; sessionID: string; requestID: string; decision: "once" | "always" | "reject" }

const HELP = `*OpenCode ⇄ Telegram köprüsü*

*Düz mesaj* → seçili session'a prompt gönderilir, yanıt canlı akar.
*/sessions* → son session'ları listeler, düğmeyle seçersiniz
*/use <no>* → listeden session seçer
*/new \[dizin\]* → yeni session açar (örn. \`/new C:\\projeler\\api\`)
*/current* → seçili session'ın bilgisi
*/last* → seçili session'ın son yanıtı
*/model \[sağlayıcı/model\]* → modeli gösterir / değiştirir
*/perm* → bekleyen izin istekleri
*/stop* → seçili session'ı durdurur
*/help* → bu mesaj`

interface LiveTarget {
  chatId: number
  messageId: number
}

type SendExtra = Parameters<Api["sendMessage"]>[2]
type EditExtra = Parameters<Api["editMessageText"]>[3]

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const isRateLimit = (err: unknown) => err instanceof GrammyError && err.error_code === 429

export function createBot(cfg: BridgeConfig, client: Client): { bot: Bot; relayEvents: RelayEvents } {
  const bot = new Bot(cfg.token)

  const tokens = new TokenStore()
  /** chatId -> selected sessionID */
  const selected = new Map<number, string>()
  /** sessionID -> telegram message being updated with live output */
  const liveTarget = new Map<string, LiveTarget>()
  /** sessionID -> chatIds that should receive notifications */
  const watchers = new Map<string, Set<number>>()
  /** requestID -> telegram messages showing the permission request */
  const permissionMessages = new Map<string, LiveTarget[]>()
  /** chatId -> last /sessions list (index -> sessionID) */
  const lastList = new Map<number, string[]>()

  const chatsToNotify = () => [...cfg.allowedIds]

  const safeSend = async (chatId: number, text: string, extra?: SendExtra) => {
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        return await bot.api.sendMessage(chatId, text.slice(0, 4096), extra)
      } catch (err) {
        if (isRateLimit(err) && attempt === 0) {
          await sleep(1500)
          continue
        }
        log.warn("sendMessage failed:", describe(err))
        return undefined
      }
    }
    return undefined
  }

  const safeEdit = async (chatId: number, messageId: number, text: string, extra?: EditExtra) => {
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        await bot.api.editMessageText(chatId, messageId, text.slice(0, 4096), extra)
        return
      } catch (err) {
        const msg = describe(err)
        if (/not modified/i.test(msg)) return
        if (isRateLimit(err) && attempt === 0) {
          await sleep(1500)
          continue
        }
        log.warn("editMessageText failed:", msg)
        return
      }
    }
  }

  const registerWatch = (sessionID: string, chatId: number) => {
    let set = watchers.get(sessionID)
    if (!set) {
      set = new Set()
      watchers.set(sessionID, set)
    }
    set.add(chatId)
  }

  const sessionTitle = async (sessionID: string): Promise<string> => {
    try {
      const info = await client.session.get({ sessionID })
      return info.title?.trim() || info.location?.directory || sessionID
    } catch {
      return sessionID
    }
  }

  // ---------------------------------------------------------------- relay glue

  const relayEvents: RelayEvents = {
    async onSessionStart(sessionID) {
      const target = liveTarget.get(sessionID)
      if (target) return // we already have a placeholder message to update
      for (const chatId of chatsToNotify()) {
        const title = await sessionTitle(sessionID)
        const sent = await safeSend(chatId, `▶️ *${escapeMd(title)}* çalışıyor…`, { parse_mode: "Markdown" })
        if (sent) {
          liveTarget.set(sessionID, { chatId, messageId: sent.message_id })
          registerWatch(sessionID, chatId)
          break // one live view is enough; the same message is edited for everyone
        }
      }
    },

    onLive(sessionID, render) {
      const target = liveTarget.get(sessionID)
      if (!target) return
      void safeEdit(target.chatId, target.messageId, render)
    },

    onDone(sessionID, outcome: Outcome, render) {
      const target = liveTarget.get(sessionID)
      if (target) {
        void safeEdit(target.chatId, target.messageId, render)
        liveTarget.delete(sessionID)
        return
      }
      const targets = watchers.get(sessionID)
      const chats = targets && targets.size > 0 ? [...targets] : chatsToNotify()
      for (const chatId of chats) {
        void safeSend(chatId, render)
      }
    },

    onPermissionAsked(req: PermissionInfo) {
      const text = permissionText(req)
      const kb = new InlineKeyboard()
        .text("✅ Bir kez", tokens.make({ kind: "permission", sessionID: req.sessionID, requestID: req.id, decision: "once" }))
        .text("🔄 Her zaman", tokens.make({ kind: "permission", sessionID: req.sessionID, requestID: req.id, decision: "always" }))
        .row()
        .text("❌ Reddet", tokens.make({ kind: "permission", sessionID: req.sessionID, requestID: req.id, decision: "reject" }))

      void (async () => {
        const refs: LiveTarget[] = []
        for (const chatId of chatsToNotify()) {
          const sent = await safeSend(chatId, text, { reply_markup: kb })
          if (sent) refs.push({ chatId, messageId: sent.message_id })
        }
        permissionMessages.set(req.id, refs)
      })()
    },

    onPermissionReplied(_sessionID, requestID, reply) {
      const refs = permissionMessages.get(requestID) ?? []
      const label =
        reply === "always" ? "🔄 Her zaman izin verildi" : reply === "once" ? "✅ İzin verildi" : "❌ Reddedildi"
      for (const ref of refs) void safeEdit(ref.chatId, ref.messageId, `İzin isteği: ${label}`)
      permissionMessages.delete(requestID)
    },
  }

  // ------------------------------------------------------------------ commands

  bot.use(async (ctx, next) => {
    const id = ctx.from?.id
    const text = (ctx.message as { text?: string } | undefined)?.text ?? ""
    log.info(`mesaj: user id=${id} text=${JSON.stringify(text.slice(0, 60))}`)
    if (id && cfg.allowedIds.has(id)) return next()
    log.info(`yetkisiz istek: user id=${id} chat id=${ctx.chat?.id}`)
    if (text.startsWith("/start") && id) {
      await ctx.reply(
        `Bu botu kullanma yetkiniz yok.\nTelegram kullanıcı ID'niz: ${id}\nBu ID'yi .env dosyasındaki TELEGRAM_ALLOWED_IDS listesine ekleyip botu yeniden başlatın.`,
      )
    }
  })

  bot.command(["start", "help"], (ctx) => ctx.reply(HELP, { parse_mode: "Markdown" }))

  bot.command("sessions", async (ctx) => {
    const res = await client.session.list({ limit: 15, order: "desc" })
    const sessions = res.data
    if (!sessions.length) return ctx.reply("Hiç session yok. `/new` ile bir tane açın.", { parse_mode: "Markdown" })

    const ids: string[] = []
    const kb = new InlineKeyboard()
    const lines = sessions.map((s, i) => {
      ids.push(s.id)
      const title = s.title?.trim() || "(başlıksız)"
      const when = new Date(s.time?.updated ?? s.time?.created ?? 0).toLocaleString("tr-TR")
      kb.text(`${i + 1} · ${title.slice(0, 28)}`, tokens.make({ kind: "select", sessionID: s.id })).row()
      return `*${i + 1}.* ${escapeMd(title)}\n     📁 ${escapeMd(s.location?.directory ?? "?")} · ${when}`
    })

    lastList.set(ctx.chat.id, ids)
    await ctx.reply(`*Son session'lar*\n\n${lines.join("\n")}\n\nSeçmek için düğmeye basın veya \`/use <no>\` yazın.`, {
      parse_mode: "Markdown",
      reply_markup: kb,
    })
  })

  bot.command("use", async (ctx) => {
    const arg = ctx.match?.trim()
    if (!arg) return ctx.reply("Kullanım: `/use <no>` (liste için `/sessions`)", { parse_mode: "Markdown" })
    const ids = lastList.get(ctx.chat.id) ?? []
    const byIndex = ids[Number(arg) - 1]
    const sessionID = byIndex ?? (/^ses/.test(arg) ? arg : undefined)
    if (!sessionID) return ctx.reply("Geçersiz numara. Önce `/sessions` çalıştırın.", { parse_mode: "Markdown" })
    selected.set(ctx.chat.id, sessionID)
    const title = await sessionTitle(sessionID)
    await ctx.reply(`Seçildi: *${escapeMd(title)}*`, { parse_mode: "Markdown" })
  })

  bot.command("new", async (ctx) => {
    const dir = ctx.match?.trim() || undefined
    const session = await client.session.create(dir ? { location: { directory: dir } } : {})
    selected.set(ctx.chat.id, session.id)
    registerWatch(session.id, ctx.chat.id)
    await ctx.reply(
      `Yeni session açıldı: *${escapeMd(session.title ?? session.id)}*\n📁 ${escapeMd(session.location?.directory ?? "?")}\n\nŞimdi doğrudan yazabilirsiniz.`,
      { parse_mode: "Markdown" },
    )
  })

  bot.command("current", async (ctx) => {
    const sessionID = selected.get(ctx.chat.id)
    if (!sessionID) return ctx.reply("Önce bir session seçin: `/sessions`", { parse_mode: "Markdown" })
    const s = await client.session.get({ sessionID })
    const model = s.model ? `${s.model.providerID}/${s.model.id}` : "?"
    await ctx.reply(
      [
        `*${escapeMd(s.title ?? s.id)}*`,
        `🆔 \`${s.id}\``,
        `📁 ${escapeMd(s.location?.directory ?? "?")}`,
        `🤖 ${escapeMd(model)} · 🧩 ${escapeMd(s.agent ?? "?")}`,
        `💰 $${(s.cost ?? 0).toFixed(4)}`,
      ].join("\n"),
      { parse_mode: "Markdown" },
    )
  })

  bot.command("last", async (ctx) => {
    const sessionID = selected.get(ctx.chat.id)
    if (!sessionID) return ctx.reply("Önce bir session seçin: `/sessions`", { parse_mode: "Markdown" })
    const res = await client.message.list({ sessionID, limit: 30, order: "desc" })
    const assistant = res.data.find((m) => m.type === "assistant") as
      | { type: "assistant"; content?: Array<{ type: string; text?: string; name?: string }> }
      | undefined
    if (!assistant) return ctx.reply("Bu session'da henüz yanıt yok.")
    const text = renderAssistant(assistant.content ?? [])
    await ctx.reply(text.slice(0, 4096))
  })

  bot.command("model", async (ctx) => {
    const sessionID = selected.get(ctx.chat.id)
    if (!sessionID) return ctx.reply("Önce bir session seçin: `/sessions`", { parse_mode: "Markdown" })
    const arg = ctx.match?.trim()
    if (!arg) {
      const s = await client.session.get({ sessionID })
      const model = s.model ? `${s.model.providerID}/${s.model.id}` : "?"
      return ctx.reply(`Aktif model: *${escapeMd(model)}*\nDeğiştirmek için: \`/model anthropic/claude-sonnet-4-5\``, {
        parse_mode: "Markdown",
      })
    }
    const [providerID, ...rest] = arg.split("/")
    const id = rest.join("/")
    if (!providerID || !id) return ctx.reply("Biçim: `sağlayıcı/model` (örn. `anthropic/claude-sonnet-4-5`)", {
      parse_mode: "Markdown",
    })
    await client.session.switchModel({ sessionID, model: { providerID, id } })
    await ctx.reply(`Model değiştirildi: *${escapeMd(arg)}*`, { parse_mode: "Markdown" })
  })

  bot.command("perm", async (ctx) => {
    let requests: Array<{ id: string; sessionID: string; action: string; resources: string[]; message?: string }> = []
    try {
      const res = await client.permission.request.list({})
      requests = res.data
    } catch (err) {
      log.warn("permission list failed:", describe(err))
    }
    if (!requests.length) return ctx.reply("Bekleyen izin isteği yok. ✅")
    for (const req of requests) {
      const kb = new InlineKeyboard()
        .text("✅ Bir kez", tokens.make({ kind: "permission", sessionID: req.sessionID, requestID: req.id, decision: "once" }))
        .text("🔄 Her zaman", tokens.make({ kind: "permission", sessionID: req.sessionID, requestID: req.id, decision: "always" }))
        .row()
        .text("❌ Reddet", tokens.make({ kind: "permission", sessionID: req.sessionID, requestID: req.id, decision: "reject" }))
      await safeSend(ctx.chat.id, permissionText({ ...req, action: req.action }), { reply_markup: kb })
    }
  })

  bot.command("stop", async (ctx) => {
    const sessionID = selected.get(ctx.chat.id)
    if (!sessionID) return ctx.reply("Önce bir session seçin: `/sessions`", { parse_mode: "Markdown" })
    const res = await client.session.interrupt({ sessionID })
    await ctx.reply(res.interrupted ? "⏹ Durduruldu." : "Durdurulamadı (çalışmıyor olabilir).")
  })

  bot.on("callback_query:data", async (ctx) => {
    const target = tokens.get(ctx.callbackQuery.data)
    if (!target) {
      return ctx.answerCallbackQuery({ text: "Bu düğmenin süresi doldu. /perm veya /sessions ile yenileyin." })
    }

    if (target.kind === "select") {
      selected.set(ctx.chat!.id, target.sessionID)
      registerWatch(target.sessionID, ctx.chat!.id)
      const title = await sessionTitle(target.sessionID)
      await ctx.answerCallbackQuery({ text: `Seçildi: ${title}` })
      await safeEdit(ctx.chat!.id, ctx.callbackQuery.message!.message_id, `✅ Seçildi: ${title}`)
      return
    }

    // permission decision
    try {
      await client.permission.reply({
        sessionID: target.sessionID,
        requestID: target.requestID,
        decision: target.decision,
      })
      const label =
        target.decision === "always" ? "🔄 Her zaman izin verildi" : target.decision === "once" ? "✅ İzin verildi" : "❌ Reddedildi"
      await ctx.answerCallbackQuery({ text: label })
      await safeEdit(ctx.chat!.id, ctx.callbackQuery.message!.message_id, `İzin isteği: ${label}`)
      permissionMessages.delete(target.requestID)
    } catch (err) {
      await ctx.answerCallbackQuery({ text: `Hata: ${describe(err)}` })
    }
  })

  bot.on("message:text", async (ctx) => {
    const text = ctx.message.text
    if (text.startsWith("/")) return // unknown command

    let sessionID = selected.get(ctx.chat.id)
    if (!sessionID) {
      const session = await client.session.create({ title: text.slice(0, 60) })
      sessionID = session.id
      selected.set(ctx.chat.id, sessionID)
    }
    registerWatch(sessionID, ctx.chat.id)

    const targetTitle = await sessionTitle(sessionID)

    // Never steer a busy work session: queue the message instead and say so.
    let busy = false
    try {
      const active = await client.session.active()
      busy = Boolean((active as Record<string, unknown>)[sessionID])
    } catch {
      busy = false
    }

    const placeholder = await safeSend(
      ctx.chat.id,
      busy
        ? `⏳ Kuyruğa eklendi → *${escapeMd(targetTitle)}* (mevcut görev bitince işlenecek)`
        : `⏳ Gönderildi → *${escapeMd(targetTitle)}*`,
      { parse_mode: "Markdown" },
    )
    if (placeholder) liveTarget.set(sessionID, { chatId: ctx.chat.id, messageId: placeholder.message_id })

    try {
      await client.session.prompt({ sessionID, text, ...(busy ? { delivery: "queue" as const } : {}) })
    } catch (err) {
      log.warn("prompt failed:", describe(err))
      if (placeholder) await safeEdit(ctx.chat.id, placeholder.message_id, `❌ Gönderilemedi: ${describe(err)}`)
    }
  })

  bot.catch((err) => log.error("bot error:", describe(err.error)))

  return { bot, relayEvents }
}

function permissionText(req: PermissionInfo): string {
  const resources = req.resources.slice(0, 8).join("\n   • ")
  return [
    `🔐 *İzin isteği*`,
    `Eylem: \`${escapeMd(req.action)}\``,
    req.message ? `Mesaj: ${escapeMd(req.message)}` : "",
    resources ? `Kaynaklar:\n   • ${escapeMd(resources)}` : "",
    `Session: \`${req.sessionID}\``,
  ]
    .filter(Boolean)
    .join("\n")
}

function renderAssistant(content: Array<{ type: string; text?: string; name?: string }>): string {
  const parts: string[] = []
  for (const part of content) {
    if (part.type === "text" && part.text) parts.push(part.text)
    else if (part.type === "tool") parts.push(`🔧 ${part.name ?? "tool"}`)
  }
  return parts.join("\n\n").trim() || "…"
}

function escapeMd(text: string): string {
  return text.replace(/([_*\[\]()~`>#+\-=|{}.!\\])/g, "\\$1")
}

function describe(err: unknown): string {
  if (err instanceof GrammyError) return `${err.description} (${err.error_code})`
  if (err instanceof Error) return err.message
  return String(err)
}