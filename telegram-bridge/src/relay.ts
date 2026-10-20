import type { Client } from "./opencode.js"
import { log } from "./config.js"

export type Outcome = "ok" | "error" | "interrupted"

export interface PermissionInfo {
  id: string
  sessionID: string
  action: string
  resources: string[]
  message?: string
}

export interface RelayEvents {
  /** A session started executing (sent by us or from the TUI). */
  onSessionStart(sessionID: string): void
  /** Live rendering of the current assistant output. */
  onLive(sessionID: string, render: string): void
  /** The session finished; render is the final output. */
  onDone(sessionID: string, outcome: Outcome, render: string): void
  onPermissionAsked(req: PermissionInfo): void
  onPermissionReplied(sessionID: string, requestID: string, reply: string): void
}

interface ToolEntry {
  name: string
  status: "running" | "ok" | "error"
}

interface LiveState {
  text: string
  /** Length of `text` when the current text part started, so `text.ended` can splice the final version in. */
  partStart: number
  tools: Map<string, ToolEntry>
  toolOrder: string[]
  running: boolean
  lastEmit: number
}

const LIVE_THROTTLE_MS = 1500
const MAX_TEXT = 3500

export class Relay {
  private live = new Map<string, LiveState>()
  private toolNames = new Map<string, string>()
  private stopped = false

  constructor(
    private client: Client,
    private events: RelayEvents,
  ) {}

  start(): void {
    void this.loop()
  }

  stop(): void {
    this.stopped = true
  }

  private async loop(): Promise<void> {
    while (!this.stopped) {
      try {
        for await (const ev of this.client.event.subscribe()) {
          if (this.stopped) break
          this.handle(ev as { type: string; data?: Record<string, unknown> })
        }
      } catch (err) {
        log.warn("event stream failed, reconnecting in 3s:", err)
      }
      await new Promise((r) => setTimeout(r, 3000))
    }
  }

  private handle(ev: { type: string; data?: Record<string, unknown> }): void {
    const d = ev.data ?? {}
    const sessionID = d.sessionID as string | undefined

    switch (ev.type) {
      case "session.execution.started": {
        if (!sessionID) return
        this.live.set(sessionID, {
          text: "",
          partStart: 0,
          tools: new Map(),
          toolOrder: [],
          running: true,
          lastEmit: 0,
        })
        this.events.onSessionStart(sessionID)
        return
      }

      case "session.text.started": {
        const st = this.getOrCreate(sessionID)
        st.partStart = st.text.length
        this.emit(sessionID, st, false)
        return
      }

      case "session.text.delta": {
        const st = this.getOrCreate(sessionID)
        st.text += (d.delta as string) ?? ""
        this.emit(sessionID, st, false)
        return
      }

      case "session.text.ended": {
        // The final text is authoritative for this part
        const st = this.getOrCreate(sessionID)
        const final = d.text as string | undefined
        if (final !== undefined) st.text = st.text.slice(0, st.partStart) + final
        this.emit(sessionID, st, false)
        return
      }

      case "session.tool.input.started": {
        const id = d.id as string
        this.toolNames.set(`${sessionID}:${id}`, (d.name as string) ?? "tool")
        return
      }

      case "session.tool.called": {
        const st = this.getOrCreate(sessionID)
        const id = d.id as string
        const key = `${sessionID}:${id}`
        const name = this.toolNames.get(key) ?? "tool"
        if (!st.tools.has(id)) st.toolOrder.push(id)
        st.tools.set(id, { name, status: "running" })
        this.emit(sessionID, st, false)
        return
      }

      case "session.tool.success": {
        const st = this.getOrCreate(sessionID)
        const id = d.id as string
        const entry = st.tools.get(id)
        if (entry) entry.status = "ok"
        this.emit(sessionID, st, false)
        return
      }

      case "session.tool.failed": {
        const st = this.getOrCreate(sessionID)
        const id = d.id as string
        const entry = st.tools.get(id)
        if (entry) entry.status = "error"
        this.emit(sessionID, st, false)
        return
      }

      case "session.execution.succeeded":
      case "session.idle": {
        if (!sessionID) return
        const st = this.live.get(sessionID)
        if (!st) {
          // Idle for a session we never saw start: nothing to report.
          return
        }
        st.running = false
        this.events.onDone(sessionID, "ok", this.render(st, "ok"))
        this.live.delete(sessionID)
        return
      }

      case "session.execution.failed": {
        if (!sessionID) return
        const st = this.live.get(sessionID) ?? this.getOrCreate(sessionID)
        st.running = false
        const err = d.error as { name?: string; data?: { message?: string } } | undefined
        const detail = err?.data?.message ?? err?.name ?? "bilinmeyen hata"
        this.events.onDone(sessionID, "error", `${this.render(st, "error")}\n\n❌ ${detail}`)
        this.live.delete(sessionID)
        return
      }

      case "session.execution.interrupted": {
        if (!sessionID) return
        const st = this.live.get(sessionID)
        if (!st) return
        st.running = false
        this.events.onDone(sessionID, "interrupted", this.render(st, "interrupted"))
        this.live.delete(sessionID)
        return
      }

      case "permission.asked": {
        this.events.onPermissionAsked({
          id: d.id as string,
          sessionID: sessionID ?? "",
          action: (d.action as string) ?? "",
          resources: (d.resources as string[]) ?? [],
          message: d.message as string | undefined,
        })
        return
      }

      case "permission.replied": {
        this.events.onPermissionReplied(
          sessionID ?? "",
          d.requestID as string,
          d.reply as string,
        )
        return
      }

      default:
        return
    }
  }

  private getOrCreate(sessionID: string | undefined): LiveState {
    if (!sessionID) sessionID = "_"
    let st = this.live.get(sessionID)
    if (!st) {
      st = { text: "", partStart: 0, tools: new Map(), toolOrder: [], running: true, lastEmit: 0 }
      this.live.set(sessionID, st)
    }
    return st
  }

  private emit(sessionID: string | undefined, st: LiveState, force: boolean): void {
    if (!sessionID || sessionID === "_") return
    const now = Date.now()
    if (!force && now - st.lastEmit < LIVE_THROTTLE_MS) return
    st.lastEmit = now
    this.events.onLive(sessionID, this.render(st, null))
  }

  render(st: LiveState, outcome: Outcome | null): string {
    let text = st.text.trim()
    if (text.length > MAX_TEXT) {
      text = `… (eski kısım kırpıldı) …\n${text.slice(-MAX_TEXT)}`
    }

    const tools = st.toolOrder.map((id) => {
      const t = st.tools.get(id)
      if (!t) return ""
      const icon = t.status === "running" ? "⏳" : t.status === "ok" ? "✅" : "❌"
      return `${icon} ${t.name}`
    })

    const lines: string[] = []
    lines.push(text || "…")
    if (tools.length) lines.push("\n🔧 " + tools.join("  ·  "))
    if (outcome === "ok") lines.push("\n✅ Tamamlandı")
    else if (outcome === "interrupted") lines.push("\n⏹ Durduruldu")
    else if (outcome === "error") lines.push("\n⛔ Hata")
    else if (st.running) lines.push("\n⏳ çalışıyor…")

    return lines.join("\n")
  }
}