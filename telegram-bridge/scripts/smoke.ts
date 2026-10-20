/**
 * Smoke test: verifies the OpenCode side of the bridge without Telegram.
 * Run with: npx tsx scripts/smoke.ts
 */
import { connect } from "../src/opencode.js"
import { Relay } from "../src/relay.js"

const client = await connect(process.env.OPENCODE_URL)

const info = await client.server.info()
console.log("server info:", JSON.stringify(info))

const sessions = await client.session.list({ limit: 5, order: "desc" })
console.log("sessions:", sessions.data.map((s) => `${s.id} | ${s.title ?? "(untitled)"} | ${s.location?.directory}`).join("\n"))

const perms = await client.permission.request.list({}).catch((e) => ({ data: [], error: String(e) }))
console.log("pending permissions:", JSON.stringify((perms as { data: unknown[] }).data))

console.log("subscribing to events for 8s…")
const relay = new Relay(client, {
  onSessionStart: (id) => console.log("  [event] start", id),
  onLive: (id, render) => console.log("  [event] live", id, JSON.stringify(render.slice(0, 80))),
  onDone: (id, outcome, render) => console.log("  [event] done", id, outcome, JSON.stringify(render.slice(0, 80))),
  onPermissionAsked: (r) => console.log("  [event] permission", r.action, r.resources),
  onPermissionReplied: (id, rid, reply) => console.log("  [event] permission replied", id, reply),
})
relay.start()
await new Promise((r) => setTimeout(r, 8000))
relay.stop()
console.log("smoke test OK")
process.exit(0)