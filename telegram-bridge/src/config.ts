import fs from "node:fs"
import path from "node:path"

export interface BridgeConfig {
  /** Telegram bot token (BotFather) */
  token: string
  /** Telegram user IDs allowed to use the bot */
  allowedIds: Set<number>
  /** Optional explicit OpenCode server URL; otherwise the background service is discovered */
  baseUrl?: string
  /** Yerel durum ucu (`/health`) — yalnızca bilgi döner, sır içermez. */
  statusHost: string
  statusPort: number
  logLevel: "debug" | "info" | "warn"
}

function loadDotEnv(): void {
  const file = path.join(process.cwd(), ".env")
  if (!fs.existsSync(file)) return
  try {
    process.loadEnvFile(file)
  } catch {
    // Fall back to a minimal parser for older Node versions
    for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
      const m = /^\s*([A-Za-z0-9_]+)\s*=\s*(.*)\s*$/.exec(line)
      if (!m) continue
      const value = m[2].replace(/^["']|["']$/g, "")
      if (process.env[m[1]] === undefined) process.env[m[1]] = value
    }
  }
}

export function loadConfig(): BridgeConfig {
  loadDotEnv()

  const token = (process.env.TELEGRAM_BOT_TOKEN ?? "").trim()
  if (!token) throw new Error("TELEGRAM_BOT_TOKEN is missing. Copy .env.example to .env and fill it in.")

  const allowedIds = new Set<number>()
  for (const part of (process.env.TELEGRAM_ALLOWED_IDS ?? "").split(",")) {
    const id = Number(part.trim())
    if (Number.isFinite(id) && id > 0) allowedIds.add(id)
  }

  const statusHost = (process.env.TELEGRAM_STATUS_HOST ?? "127.0.0.1").trim() || "127.0.0.1"
  const rawStatusPort = Number((process.env.TELEGRAM_STATUS_PORT ?? "49375").trim())
  const statusPort =
    Number.isFinite(rawStatusPort) && rawStatusPort > 0 && rawStatusPort < 65536 ? rawStatusPort : 49375

  const logLevel = (process.env.LOG_LEVEL ?? "info") as BridgeConfig["logLevel"]

  return {
    token,
    allowedIds,
    baseUrl: process.env.OPENCODE_URL?.trim() || undefined,
    statusHost,
    statusPort,
    logLevel: ["debug", "info", "warn"].includes(logLevel) ? logLevel : "info",
  }
}

export const log = {
  debug: (...args: unknown[]) => console.log("[debug]", ...args),
  info: (...args: unknown[]) => console.log("[info]", ...args),
  warn: (...args: unknown[]) => console.warn("[warn]", ...args),
  error: (...args: unknown[]) => console.error("[error]", ...args),
}
