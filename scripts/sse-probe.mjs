/**
 * Ham SSE karelerini yakalar (parser tasarımını doğrulamak için).
 * Kullanım: node scripts/sse-probe.mjs [saniye]
 */
import fs from "node:fs"

const info = JSON.parse(fs.readFileSync("C:/Users/YILMAZ/.local/state/opencode/service.json", "utf8"))
const auth = "Basic " + Buffer.from("opencode:" + info.password).toString("base64")
const seconds = Number(process.argv[2] ?? 20)

const res = await fetch(info.url + "/api/event", { headers: { authorization: auth, accept: "text/event-stream" } })
console.log("status:", res.status, "content-type:", res.headers.get("content-type"))

const reader = res.body.getReader()
const decoder = new TextDecoder()
const deadline = Date.now() + seconds * 1000
let buf = ""
let frames = 0

while (Date.now() < deadline) {
  const { value, done } = await reader.read().catch(() => ({ done: true }))
  if (done) break
  buf += decoder.decode(value, { stream: true })
  let idx
  while ((idx = buf.indexOf("\n\n")) >= 0) {
    const frame = buf.slice(0, idx)
    buf = buf.slice(idx + 2)
    if (!frame.trim() || frame.startsWith(":")) continue
    frames++
    if (frames <= 6) console.log("---- frame ----\n" + frame.slice(0, 500))
  }
}
console.log("toplam kare:", frames)
process.exit(0)