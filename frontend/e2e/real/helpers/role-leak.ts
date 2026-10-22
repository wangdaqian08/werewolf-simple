/**
 * Role-leak scan: what a player's browser actually receives from the server.
 *
 * Records every STOMP frame and every /api/game/* JSON response on one page,
 * then finds any object that pairs another player's id with a role. The UI
 * may hide a role, but if the payload carries it, devtools shows it.
 */
import type { Page } from '@playwright/test'

const PLAYER_ROLES = new Set([
  'WEREWOLF',
  'VILLAGER',
  'SEER',
  'WITCH',
  'HUNTER',
  'GUARD',
  'IDIOT',
  'WHITE_WOLF_KING',
  'BLACK_WOLF_KING',
])
const ID_KEY = /(user|player)Id$/i
const ROLE_KEY = /role$/i

export interface PayloadLog {
  bodies: { src: string; body: string }[]
}

/** Attach before the page opens its STOMP socket (i.e. before goto). */
export function recordPayloads(page: Page): PayloadLog {
  const log: PayloadLog = { bodies: [] }
  page.on('websocket', (ws) =>
    ws.on('framereceived', (f) => {
      // SockJS wraps STOMP frames in a JSON array: a["MESSAGE\n…\n\n{body}\u0000"]
      if (typeof f.payload !== 'string' || !f.payload.startsWith('a[')) return
      for (const frame of JSON.parse(f.payload.slice(1)) as string[]) {
        const at = frame.indexOf('\n\n') // STOMP: headers, blank line, body
        if (frame.startsWith('MESSAGE') && at >= 0) {
          log.bodies.push({ src: 'stomp', body: frame.slice(at + 2).replace(/\0$/, '') })
        }
      }
    }),
  )
  page.on('response', async (r) => {
    if (!/\/api\/game\//.test(r.url())) return
    try {
      log.bodies.push({ src: new URL(r.url()).pathname, body: await r.text() })
    } catch {
      // body gone (navigation) — nothing to scan
    }
  })
  return log
}

export interface RoleLeak {
  src: string
  id: string
  role: string
  key: string
}

/** Objects (also JSON nested in string fields) pairing a non-[me] id with a role, in bodies [from, to). */
export function findRoleLeaks(log: PayloadLog, me: string, from = 0, to?: number): RoleLeak[] {
  const leaks: RoleLeak[] = []
  const walk = (src: string, v: unknown): void => {
    if (typeof v === 'string') {
      if (v.startsWith('{') || v.startsWith('[')) {
        try {
          walk(src, JSON.parse(v))
        } catch {
          // not JSON
        }
      }
      return
    }
    if (Array.isArray(v)) return v.forEach((x) => walk(src, x))
    if (!v || typeof v !== 'object') return
    const o = v as Record<string, unknown>
    const ids = Object.entries(o).filter(
      ([k, x]) => ID_KEY.test(k) && typeof x === 'string' && x !== me,
    )
    for (const [key, role] of Object.entries(o)) {
      if (
        key === 'myRole' ||
        !ROLE_KEY.test(key) ||
        typeof role !== 'string' ||
        !PLAYER_ROLES.has(role)
      )
        continue
      for (const [, id] of ids) leaks.push({ src, id: id as string, role, key })
    }
    Object.values(o).forEach((x) => walk(src, x))
  }
  for (const { src, body } of log.bodies.slice(from, to)) {
    try {
      walk(src, JSON.parse(body))
    } catch {
      // non-JSON body
    }
  }
  return leaks
}
