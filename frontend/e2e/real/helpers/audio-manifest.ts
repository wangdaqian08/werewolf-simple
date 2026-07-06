/**
 * Expected-audio manifest for full-game flow specs.
 *
 * The audio chain is asserted at two layers (the pattern proven in
 * full-game-audio.spec.ts):
 *
 *   1. Backend publish — `[broadcastAudio] game=N file=<x>.mp3` (one line per
 *      role open/close frame, NightOrchestrator.kt:874) and
 *      `[broadcastNightInit] game=N … audioFiles=[…]` (night-entry cue,
 *      NightOrchestrator.kt:804). Asserted with EXACT counts, filtered by
 *      gameId so parallel/prior games in the shared log can't skew them.
 *   2. Frontend STOMP receipt — `[stomp] received AudioSequence [a.mp3, …]`
 *      console lines (GameView.vue). Asserted with AT-LEAST counts because
 *      the reconnect replay buffer may legitimately re-deliver frames.
 *
 * `[AudioService] Starting playback:` lines are captured for diagnostics but
 * never asserted: under the e2e profile (cooldown 200ms / gap 500ms)
 * broadcasts outpace real-time headless playback and the queue legitimately
 * backs up — playback counts are not a stable signal.
 *
 * Day cues (rooster_crowing + day_time) have no dedicated backend log line —
 * they ride the NIGHT→day PhaseChanged AudioSequence (including the day-1
 * NIGHT→SHERIFF_ELECTION morning cue) — so they are asserted at the STOMP
 * layer only.
 */
import type { Page } from '@playwright/test'

export const NIGHT_ENTRY_FILES = ['goes_dark_close_eyes.mp3', 'wolf_howl.mp3'] as const

/**
 * Per-night role broadcast order. Werewolf → Witch → Seer → Guard is pinned
 * by the handlers' Spring @Order(1..4); each role emits open then close,
 * dead or alive (dead-role masking).
 */
export const NIGHT_ROLE_ORDER = [
  'wolf_open_eyes.mp3',
  'wolf_close_eyes.mp3',
  'witch_open_eyes.mp3',
  'witch_close_eyes.mp3',
  'seer_open_eyes.mp3',
  'seer_close_eyes.mp3',
  'guard_open_eyes.mp3',
  'guard_close_eyes.mp3',
] as const

export const DAY_CUE_FILES = ['rooster_crowing.mp3', 'day_time.mp3'] as const

export interface GameAudioManifest {
  /** Exact per-file counts expected in `[broadcastAudio]` backend lines. */
  backendRolePerFile: Record<string, number>
  /** Exact per-file counts expected in `[broadcastNightInit]` backend lines. */
  backendNightInitPerFile: Record<string, number>
  /** Minimum per-file counts expected in STOMP AudioSequence receipts. */
  stompPerFile: Record<string, number>
  /** Total expected audio files across the game (all three groups). */
  totalFiles: number
}

/**
 * Build the manifest for a game with `nights` completed role-loop nights and
 * `dayCues` NIGHT→day transitions that broadcast the morning cue (the day-1
 * sheriff-election morning cue counts as one; a game-over at night broadcasts
 * none).
 */
export function buildGameAudioManifest(opts: {
  nights: number
  dayCues: number
}): GameAudioManifest {
  const { nights, dayCues } = opts
  const backendRolePerFile: Record<string, number> = {}
  for (const f of NIGHT_ROLE_ORDER) backendRolePerFile[f] = nights
  const backendNightInitPerFile: Record<string, number> = {}
  for (const f of NIGHT_ENTRY_FILES) backendNightInitPerFile[f] = nights
  const dayCuePerFile: Record<string, number> = {}
  for (const f of DAY_CUE_FILES) dayCuePerFile[f] = dayCues
  const stompPerFile: Record<string, number> = {
    ...backendRolePerFile,
    ...backendNightInitPerFile,
    ...dayCuePerFile,
  }
  return {
    backendRolePerFile,
    backendNightInitPerFile,
    stompPerFile,
    totalFiles: nights * (NIGHT_ROLE_ORDER.length + NIGHT_ENTRY_FILES.length) + dayCues * 2,
  }
}

// ── Console capture ──────────────────────────────────────────────────────────

export interface AudioCapture {
  /** `[AudioService]` / `[useAudioService]` lines (diagnostics only). */
  audioEvents: string[]
  /** `[stomp] received …` lines (assertion layer 2). */
  stompFrames: string[]
}

/**
 * Attach a console listener collecting both capture buffers. Must be called
 * BEFORE the first state-changing click (start-night) or early frames are
 * lost.
 */
export function attachAudioCapture(page: Page): AudioCapture {
  const capture: AudioCapture = { audioEvents: [], stompFrames: [] }
  page.on('console', (msg: { text: () => string }) => {
    const text = msg.text()
    if (text.includes('[stomp] received')) capture.stompFrames.push(text)
    if (text.includes('[AudioService]') || text.includes('[useAudioService]')) {
      capture.audioEvents.push(text)
    }
  })
  return capture
}

// ── Pure line counters (unit-tested in src/__tests__/audioManifest.test.ts) ──

/** True when a backend log line belongs to `gameId` (game=N followed by a delimiter). */
function belongsToGame(line: string, gameId: string): boolean {
  return new RegExp(`game=${gameId}(?=[\\s:,])`).test(line)
}

export function countBackendBroadcasts(lines: string[], gameId?: string): Record<string, number> {
  const perFile: Record<string, number> = {}
  for (const line of lines) {
    if (!line.includes('[broadcastAudio]')) continue
    if (gameId !== undefined && !belongsToGame(line, gameId)) continue
    const file = line.match(/file=([^\s]+\.mp3)/)?.[1]
    if (file) perFile[file] = (perFile[file] ?? 0) + 1
  }
  return perFile
}

export function countBackendNightInits(lines: string[], gameId?: string): Record<string, number> {
  const perFile: Record<string, number> = {}
  for (const line of lines) {
    if (!line.includes('[broadcastNightInit]')) continue
    if (gameId !== undefined && !belongsToGame(line, gameId)) continue
    const group = line.match(/audioFiles=\[([^\]]+)\]/)?.[1]
    if (!group) continue
    for (const f of group.split(',').map((s) => s.trim())) {
      if (f.endsWith('.mp3')) perFile[f] = (perFile[f] ?? 0) + 1
    }
  }
  return perFile
}

export function countStompAudioFiles(stompFrames: string[]): Record<string, number> {
  const perFile: Record<string, number> = {}
  for (const frame of stompFrames) {
    if (!frame.includes('AudioSequence')) continue
    // Format: "[stomp] received AudioSequence [a.mp3, b.mp3]"
    const bracket = frame.match(/AudioSequence \[([^\]]+)\]/)?.[1]
    if (!bracket) continue
    for (const f of bracket.split(',').map((s) => s.trim())) {
      if (f.endsWith('.mp3')) perFile[f] = (perFile[f] ?? 0) + 1
    }
  }
  return perFile
}

/** Diagnostics only — never assert on playback counts (see file header). */
export function countAudioPlaybacks(audioEvents: string[]): Record<string, number> {
  const perFile: Record<string, number> = {}
  for (const e of audioEvents) {
    if (!e.includes('Starting playback')) continue
    const file = e.match(/Starting playback: ([^\s]+\.mp3)/)?.[1]
    if (file) perFile[file] = (perFile[file] ?? 0) + 1
  }
  return perFile
}

/**
 * Split the backend log into per-night blocks (each starts at the
 * `[nightRoleLoop] game=N: roles=[…]` header) and return the ordered
 * `[broadcastAudio]` filenames inside each block.
 */
export function extractPerNightRoleAudio(lines: string[], gameId: string): string[][] {
  const nights: string[][] = []
  let current: string[] | null = null
  for (const line of lines) {
    if (!belongsToGame(line, gameId)) continue
    if (line.includes('[nightRoleLoop]') && line.includes('roles=')) {
      if (current) nights.push(current)
      current = []
      continue
    }
    if (current && line.includes('[broadcastAudio]')) {
      const file = line.match(/file=([^\s]+\.mp3)/)?.[1]
      if (file) current.push(file)
    }
  }
  if (current) nights.push(current)
  return nights
}

/**
 * Count the `[nightRoleLoop] game=N: role=R alive=BOOL` lines per role —
 * the backend-side proof of dead-role masking (a dead role's night phase
 * still runs, so its open/close audio still broadcasts).
 */
export function countRoleAliveLines(
  lines: string[],
  gameId: string,
): Record<string, { alive: number; dead: number }> {
  const perRole: Record<string, { alive: number; dead: number }> = {}
  for (const line of lines) {
    if (!line.includes('[nightRoleLoop]')) continue
    if (!belongsToGame(line, gameId)) continue
    const m = line.match(/role=(\w+) alive=(true|false)/)
    const role = m?.[1]
    if (!role) continue
    const entry = (perRole[role] ??= { alive: 0, dead: 0 })
    if (m?.[2] === 'true') entry.alive++
    else entry.dead++
  }
  return perRole
}

// ── Violation finders (pure — specs assert `toEqual([])`) ───────────────────

/**
 * Diff the captured backend log + STOMP frames against the manifest.
 * Returns human-readable violation strings; empty array = manifest satisfied.
 * Backend counts are EXACT (both short and excess are violations — excess
 * would be a double-broadcast regression); STOMP counts are AT-LEAST.
 */
export function findAudioManifestViolations(input: {
  backendLines: string[]
  stompFrames: string[]
  manifest: GameAudioManifest
  gameId: string
}): string[] {
  const { backendLines, stompFrames, manifest, gameId } = input
  const violations: string[] = []

  const roleCounts = countBackendBroadcasts(backendLines, gameId)
  for (const [file, expected] of Object.entries(manifest.backendRolePerFile)) {
    const actual = roleCounts[file] ?? 0
    if (actual !== expected) {
      violations.push(`[broadcastAudio] ${file}: expected exactly ${expected}, got ${actual}`)
    }
  }

  const initCounts = countBackendNightInits(backendLines, gameId)
  for (const [file, expected] of Object.entries(manifest.backendNightInitPerFile)) {
    const actual = initCounts[file] ?? 0
    if (actual !== expected) {
      violations.push(`[broadcastNightInit] ${file}: expected exactly ${expected}, got ${actual}`)
    }
  }

  const stompCounts = countStompAudioFiles(stompFrames)
  for (const [file, expected] of Object.entries(manifest.stompPerFile)) {
    const actual = stompCounts[file] ?? 0
    if (actual < expected) {
      violations.push(`STOMP ${file}: expected >= ${expected}, got ${actual}`)
    }
  }

  return violations
}

/**
 * Assert every completed night broadcast its role audio in the pinned
 * Werewolf → Witch → Seer → Guard open/close order. Returns violation
 * strings; empty = order held for all `expectedNights` nights.
 */
export function findNightOrderViolations(
  backendLines: string[],
  gameId: string,
  expectedNights: number,
): string[] {
  const violations: string[] = []
  const nights = extractPerNightRoleAudio(backendLines, gameId)
  if (nights.length !== expectedNights) {
    violations.push(
      `night blocks: expected ${expectedNights} [nightRoleLoop] headers, got ${nights.length}`,
    )
  }
  const expected = NIGHT_ROLE_ORDER.join(' → ')
  nights.forEach((files, i) => {
    const actual = files.join(' → ')
    if (actual !== expected) {
      violations.push(
        `night ${i + 1} role-audio order:\n  expected ${expected}\n  actual   ${actual}`,
      )
    }
  })
  return violations
}
