/**
 * Cross-browser assertion helpers for real E2E tests.
 *
 * Detects P0 bugs:
 *   - Button clicked but UI unchanged
 *   - STOMP event sent but UI not updated
 *   - Phase transition not reflected in a browser
 */
import { expect, type Page } from '@playwright/test'

// Phase transitions occasionally take 2-3× longer on GH ubuntu-latest runners
// than on local dev hardware. Rather than hard-code larger waits at every call
// site, scale caller-supplied timeouts up when running under CI. Local runs
// keep the original tight budgets so genuine regressions still surface fast.
const TIMEOUT_SCALE = process.env.CI ? 2 : 1
const scale = (ms: number) => Math.round(ms * TIMEOUT_SCALE)

// ── Phase selectors (derived from actual component root classes) ─────────────

/**
 * Pre-#1 selectors: inferred phase from per-component CSS classes. Kept
 * as a documented fallback for waitForPhase (single-page helper) since
 * some specs use it before GameView mounts (e.g. waiting-screen).
 */
export const PHASE_SELECTORS: Record<string, string> = {
  ROLE_REVEAL: '.reveal-wrap, .waiting-screen',
  SHERIFF_ELECTION: '.sheriff-wrap',
  NIGHT: '.game-wrap.night-mode',
  DAY: '.day-wrap',
  VOTING: '.voting-wrap',
}

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

// ── Single-page helpers ──────────────────────────────────────────────────────

/**
 * Wait until a page shows the expected game phase.
 * Throws (P0) if the phase doesn't appear within timeout.
 */
export async function waitForPhase(page: Page, phase: string, timeout = 15_000): Promise<void> {
  const selector = PHASE_SELECTORS[phase]
  if (!selector) throw new Error(`Unknown phase: ${phase}`)
  await page
    .locator(selector)
    .first()
    .waitFor({ state: 'visible', timeout: scale(timeout) })
}

/**
 * Click a button and verify the UI changes.
 *
 * P0 detection: if the button remains visible+enabled AND no changeIndicator
 * appears within timeout, this is a P0 bug.
 *
 * @param page - the page to act on
 * @param buttonText - button text (regex or string) to find via getByRole
 * @param changeIndicator - selector or text that should appear/change after click
 * @param timeout - max wait time in ms (default 8s)
 */
export async function clickAndVerify(
  page: Page,
  buttonText: string | RegExp,
  changeIndicator: string,
  timeout = 8_000,
): Promise<void> {
  const button = page.getByRole('button', { name: buttonText })
  await button.click()

  // Wait for either: the indicator appears, or the button disappears/disables
  const indicatorVisible = page
    .locator(changeIndicator)
    .first()
    .waitFor({ state: 'visible', timeout })
    .then(() => 'indicator')
    .catch(() => null)

  const buttonGone = button
    .waitFor({ state: 'hidden', timeout })
    .then(() => 'button-hidden')
    .catch(() => null)

  const result = await Promise.race([indicatorVisible, buttonGone])
  if (!result) {
    throw new Error(
      `P0: Button "${buttonText}" clicked but UI unchanged after ${timeout}ms. ` +
        `Expected "${changeIndicator}" to appear or button to disappear.`,
    )
  }
}

// ── Cross-browser helpers ────────────────────────────────────────────────────

/**
 * Verify ALL open browser pages transition to the expected phase.
 * Any browser that doesn't transition within timeout is a P0 bug.
 */
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
/**
 * Perform an action and verify that an observer page reflects the change.
 *
 * Sets up the wait BEFORE running the action (avoids race conditions).
 * P0 if the expected selector doesn't appear within timeout.
 *
 * @param observerPage - the page that should reflect the change
 * @param actionFn - async function that triggers the change (click in another browser, or shell script)
 * @param expectedSelector - CSS selector that should become visible on observerPage
 * @param timeout - max wait in ms
 */
export async function verifyStompUpdate(
  observerPage: Page,
  actionFn: () => Promise<void>,
  expectedSelector: string,
  timeout = 10_000,
): Promise<void> {
  // Start waiting BEFORE the action
  const waitPromise = observerPage
    .locator(expectedSelector)
    .first()
    .waitFor({ state: 'visible', timeout })

  // Perform the action
  await actionFn()

  // Await the UI update
  try {
    await waitPromise
  } catch {
    throw new Error(
      `P0: Expected "${expectedSelector}" on observer page after STOMP action ` +
        `but not visible after ${timeout}ms`,
    )
  }
}

/**
 * Wait for text to appear on a page (useful for dynamic content like vote counts).
 */
export async function waitForText(
  page: Page,
  text: string | RegExp,
  timeout = 10_000,
): Promise<void> {
  if (typeof text === 'string') {
    await expect(page.getByText(text).first()).toBeVisible({ timeout })
  } else {
    await expect(page.getByText(text).first()).toBeVisible({ timeout })
  }
}

/**
 * Verify a page does NOT show a specific selector (e.g., dead player should not see vote buttons).
 */
export async function verifyNotVisible(
  page: Page,
  selector: string,
  timeout = 2_000,
): Promise<void> {
  // Give a brief window for any pending updates, then assert hidden
  await page.waitForTimeout(timeout)
  await expect(page.locator(selector).first()).not.toBeVisible()
}
