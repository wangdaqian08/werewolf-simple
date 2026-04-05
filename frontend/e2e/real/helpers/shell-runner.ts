/**
 * Shell script wrappers for game automation.
 *
 * Calls existing scripts via child_process.execSync:
 *   - join-room.sh  — add bots to a room
 *   - act.sh        — perform game actions
 *   - roles.sh      — discover role assignments
 *   - sheriff.sh    — sheriff election helpers
 */
import {execSync} from 'child_process'
import {readFileSync} from 'fs'
import path from 'path'

// Resolve project root from the frontend working directory
const PROJECT_ROOT = path.resolve(process.cwd(), '..')
const SCRIPTS_DIR = path.join(PROJECT_ROOT, 'scripts')
const STATE_DIR = '/tmp'

// ── Types ────────────────────────────────────────────────────────────────────

export interface BotInfo {
  nick: string
  token: string
  seat: number
  userId: string
}

export interface StateFile {
  roomCode: string
  roomId: number
  gameId?: number
  bots: BotInfo[]
  users?: BotInfo[]
  hostToken: string
  hostNick?: string
}

export type RoleName =
  | 'WEREWOLF'
  | 'SEER'
  | 'WITCH'
  | 'GUARD'
  | 'HUNTER'
  | 'IDIOT'
  | 'VILLAGER'

export type RoleMap = Partial<Record<RoleName, BotInfo[]>>

// ── Helpers ──────────────────────────────────────────────────────────────────

function run(command: string, timeoutMs = 60_000): string {
  try {
    return execSync(command, {
      cwd: PROJECT_ROOT,
      encoding: 'utf-8',
      timeout: timeoutMs,
      stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, TERM: 'dumb' }, // suppress colour codes
    }).trim()
  } catch (err: unknown) {
    const e = err as { stderr?: string; stdout?: string; message?: string }
    const stderr = e.stderr?.trim() ?? ''
    const stdout = e.stdout?.trim() ?? ''
    throw new Error(
      `Script failed: ${command}\n` +
        (stderr ? `stderr: ${stderr}\n` : '') +
        (stdout ? `stdout: ${stdout}\n` : '') +
        (e.message ?? ''),
    )
  }
}

function stripAnsi(str: string): string {
  // eslint-disable-next-line no-control-regex
  return str.replace(/\x1B\[[0-9;]*[A-Za-z]/g, '')
}

// ── Public API ───────────────────────────────────────────────────────────────

/** Join N bots to the room and optionally mark them ready. */
export function joinBots(
  roomCode: string,
  count: number,
  ready = true,
): void {
  const readyFlag = ready ? '--ready' : ''
  // Use --room-code to avoid ambiguity when the code looks like a number
  run(`${SCRIPTS_DIR}/join-room.sh --room-code ${roomCode} --player-num ${count} ${readyFlag}`)
}

/** Read the state file written by join-room.sh. */
export function readStateFile(roomCode: string): StateFile {
  const filePath = path.join(STATE_DIR, `werewolf-${roomCode.toUpperCase()}.json`)
  return JSON.parse(readFileSync(filePath, 'utf-8'))
}

/**
 * Run act.sh with the given action.
 *
 * @returns raw stdout (stripped of ANSI codes)
 */
export function act(
  action: string,
  player?: string,
  opts?: { target?: string; payload?: string; room?: string },
): string {
  const parts = [`${SCRIPTS_DIR}/act.sh`, action]
  if (player) parts.push(player)
  if (opts?.target) parts.push('--target', opts.target)
  if (opts?.payload) parts.push('--payload', `'${opts.payload}'`)
  if (opts?.room) parts.push('--room', opts.room)
  return stripAnsi(run(parts.join(' ')))
}

/**
 * Parse CONSOLE_LOGIN output to extract JWT, nickname, and userId.
 *
 * The script outputs JS like:
 *   localStorage.setItem("jwt", "eyJ..."); localStorage.setItem("nickname", "Bot1"); ...
 */
export function getConsoleLogin(
  player: string,
  roomCode: string,
): { jwt: string; nickname: string; userId: string } {
  const output = act('CONSOLE_LOGIN', player, { room: roomCode })

  const extract = (key: string): string => {
    // Match: localStorage.setItem("key", "value")
    const re = new RegExp(`localStorage\\.setItem\\("${key}",\\s*"([^"]*)"\\)`)
    const m = output.match(re)
    if (!m) throw new Error(`Could not parse ${key} from CONSOLE_LOGIN output:\n${output}`)
    return m[1]
  }

  return {
    jwt: extract('jwt'),
    nickname: extract('nickname'),
    userId: extract('userId'),
  }
}

/**
 * Discover role assignments by parsing roles.sh output.
 *
 * Output format:
 *   seat  1  Bot1-abc            WEREWOLF      ← use for WOLF_KILL
 *   seat  3  Bot2-abc            SEER          ← use for SEER_CHECK
 */
export function getRoles(roomCode: string): RoleMap {
  const output = stripAnsi(run(`${SCRIPTS_DIR}/roles.sh --room ${roomCode}`))
  const roleMap: RoleMap = {}
  const state = readStateFile(roomCode)
  const botsByNick = new Map(state.bots.map((b) => [b.nick, b]))
  // Also include manual users
  for (const u of state.users ?? []) {
    botsByNick.set(u.nick, u)
  }

  // Parse each "seat  N  NICK  ROLE" line
  for (const line of output.split('\n')) {
    const m = line.match(/seat\s+(\d+)\s+(\S+)\s+(WEREWOLF|SEER|WITCH|GUARD|HUNTER|IDIOT|VILLAGER)/)
    if (!m) continue
    const [, seatStr, nick, role] = m
    const seat = parseInt(seatStr, 10)
    const bot = botsByNick.get(nick)
    if (!bot) continue

    const roleName = role as RoleName
    if (!roleMap[roleName]) roleMap[roleName] = []
    roleMap[roleName]!.push({ ...bot, seat })
  }

  return roleMap
}

/** Run sheriff.sh with the given action. */
export function sheriff(
  action: string,
  opts?: { player?: string; target?: string; room?: string },
): string {
  const parts = [`${SCRIPTS_DIR}/sheriff.sh`, action]
  if (opts?.player) parts.push('--player', opts.player)
  if (opts?.target) parts.push('--target', opts.target)
  if (opts?.room) parts.push('--room', opts.room)
  return stripAnsi(run(parts.join(' ')))
}
