/**
 * Cross-browser assertion helpers for real E2E tests.
 *
 * Detects P0 bugs:
 *   - Button clicked but UI unchanged
 *   - STOMP event sent but UI not updated
 *   - Phase transition not reflected in a browser
 */
import { type Page } from '@playwright/test'

// Phase transitions occasionally take 2-3× longer on GH ubuntu-latest runners
// than on local dev hardware. Rather than hard-code larger waits at every call
// site, scale caller-supplied timeouts up when running under CI. Local runs
// keep the original tight budgets so genuine regressions still surface fast.
const TIMEOUT_SCALE = process.env.CI ? 2 : 1
const scale = (ms: number) => Math.round(ms * TIMEOUT_SCALE)

/**
 * Post-#1 mapping: spec-friendly phase label → set of authoritative
 * `data-phase` values that GameView writes onto its root element.
 *
 * Multiple values map to one label because the spec abstracts day/night
 * cycle states the way humans do (DAY = "discussion-or-pending"), while
 * the backend distinguishes DAY_PENDING / DAY_DISCUSSION precisely.
 *
 * Used by verifyAllBrowsersPhase below — the data-attribute selector
 * makes phase delivery testable cross-browser without inferring from
 * CSS classes (which would mask, e.g., a spec calling 'NIGHT' on a
 * browser whose store is still on DAY but whose body has the night
 * class from a stale render).
 */
export const PHASE_DATA_VALUES: Readonly<Record<string, readonly string[]>> = Object.freeze({
  ROLE_REVEAL: ['ROLE_REVEAL', 'WAITING'],
  SHERIFF_ELECTION: ['SHERIFF_ELECTION'],
  NIGHT: ['NIGHT'],
  DAY: ['DAY_PENDING', 'DAY_DISCUSSION'],
  VOTING: ['DAY_VOTING'],
  GAME_OVER: ['GAME_OVER'],
})

// ── Cross-browser helpers ────────────────────────────────────────────────────

/**
 * Ask the backend for the current game state via /api/game/{id}/state, using
 * the host page's JWT. Used for diagnostics when a phase transition stalls —
 * knowing whether the backend is already in the expected phase tells us if
 * the stall is a broadcast/UI bug (backend advanced, frontend didn't receive)
 * vs. a coroutine/rejection bug (backend never advanced).
 */
async function snapshotBackendState(page: Page): Promise<Record<string, unknown> | null> {
  const gameIdMatch = page.url().match(/\/game\/(\d+)/)
  const gameId = gameIdMatch?.[1]
  if (!gameId) return null
  try {
    return await page.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      if (!token) return { error: 'no jwt in localStorage' }
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return { error: `state fetch ${res.status}`, body: await res.text() }
      const body = (await res.json()) as Record<string, unknown>
      // Trim big arrays so the error log stays readable
      const trimmed: Record<string, unknown> = {
        phase: body.phase,
        subPhase: body.subPhase,
        dayNumber: body.dayNumber,
        aliveCount: Array.isArray(body.players)
          ? (body.players as { isAlive?: boolean }[]).filter((p) => p.isAlive).length
          : undefined,
        totalPlayers: Array.isArray(body.players) ? (body.players as unknown[]).length : undefined,
        nightPhase: body.nightPhase,
        dayPhase: body.dayPhase,
        votingPhase: body.votingPhase,
      }
      return trimmed
    }, gameId)
  } catch (e) {
    return { error: `snapshotBackendState threw: ${(e as Error).message}` }
  }
}

export async function verifyAllBrowsersPhase(
  pages: Map<string, Page>,
  phase: string,
  timeout = 30_000, // Increased from 15s to 30s to accommodate audio processing delays
): Promise<void> {
  const dataValues = PHASE_DATA_VALUES[phase]
  if (!dataValues) throw new Error(`Unknown phase: ${phase}`)
  const effective = scale(timeout)

  // Build a CSS selector that matches the .game-wrap root only when its
  // data-phase is one of the expected backend values. This is the
  // authoritative phase source — backed by gameStore.state.phase, the
  // same field every other component reads — so a STOMP delivery
  // regression to a single browser fails this assertion immediately
  // even if the legacy component-class selector would still match.
  const selector = dataValues.map((v) => `.game-wrap[data-phase="${v}"]`).join(', ')

  const results = await Promise.allSettled(
    Array.from(pages.entries()).map(async ([role, page]) => {
      try {
        await page.locator(selector).first().waitFor({ state: 'visible', timeout: effective })
      } catch {
        // Diagnostic: read the actual data-phase / data-phase-sub the
        // browser is showing so the failure message says exactly what
        // value was observed instead of a CSS-class inference.
        const currentPhase = await page.evaluate(() => {
          const wrap = document.querySelector('.game-wrap') as HTMLElement | null
          return {
            dataPhase: wrap?.dataset.phase ?? null,
            dataPhaseSub: wrap?.dataset.phaseSub ?? null,
            dataDayNumber: wrap?.dataset.dayNumber ?? null,
            // CSS-class fallback for diagnostics only — if data-phase is
            // empty (component hasn't mounted yet, store not populated),
            // these still tell us how far the page got.
            hasNightWrap: !!document.querySelector('.game-wrap.night-mode'),
            hasDayWrap: !!document.querySelector('.day-wrap'),
            hasVotingWrap: !!document.querySelector('.voting-wrap'),
            hasSheriffWrap: !!document.querySelector('.sheriff-wrap'),
            hasRevealWrap: !!document.querySelector('.reveal-wrap'),
            url: window.location.href,
          }
        })

        throw new Error(
          `P0: Browser [${role}] stuck — expected phase ${phase} ` +
            `(data-phase ∈ {${dataValues.join(',')}}) ` +
            `but not visible after ${effective}ms. Current state: ${JSON.stringify(currentPhase)}`,
        )
      }
    }),
  )

  const failures = results.filter((r) => r.status === 'rejected')
  if (failures.length > 0) {
    const msgs = failures.map((r) => (r as PromiseRejectedResult).reason.message)
    // On stall, dump backend's own view of the game state using the first
    // available page's JWT. If the backend thinks it's already in DAY while
    // every browser is stuck on NIGHT, the bug is in broadcast/UI. If the
    // backend is still on NIGHT too, the coroutine is waiting for an action
    // that was rejected or never sent.
    const anyPage = pages.values().next().value as Page | undefined
    let backendNote = ''
    if (anyPage) {
      const backend = await snapshotBackendState(anyPage)
      backendNote = `\nBackend /state snapshot: ${JSON.stringify(backend)}`
    }
    throw new Error(msgs.join('\n') + backendNote)
  }
}
