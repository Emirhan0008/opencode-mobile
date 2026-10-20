import { OpenCode, type OpenCodeClient } from "@opencode/client"
import { Service } from "@opencode/client/service"
import { log } from "./config.js"

export type Client = OpenCodeClient

export interface Connection {
  client: Client
  /** Sunucunun gerçekte dinlediği taban URL (durum ucu için). */
  url: string
}

/**
 * Connect to the local OpenCode server. When OPENCODE_URL is set it is used
 * directly; otherwise the managed background service is discovered (and
 * started when needed) and its auth headers are applied automatically.
 */
export async function connect(baseUrl?: string): Promise<Connection> {
  if (baseUrl) {
    log.info("connecting to explicit server:", baseUrl)
    return {
      client: OpenCode.make({ baseUrl }),
      url: baseUrl.replace(/\/+$/, ""),
    }
  }
  const endpoint = await Service.ensure()
  log.info("connected to background service:", endpoint.url)
  return {
    client: OpenCode.make({
      baseUrl: endpoint.url,
      headers: Service.headers(endpoint),
    }),
    url: endpoint.url,
  }
}
