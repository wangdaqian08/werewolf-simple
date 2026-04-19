/**
 * Cross-browser assertion helpers for real E2E tests.
 *
 * Detects P0 bugs:
 *   - Button clicked but UI unchanged
 *   - STOMP event sent but UI not updated
 *   - Phase transition not reflected in a browser
 */
import {expect, type Page} from '@playwright/test'

// Phase transitions occasionally take 2-3× longer on GH ubuntu-latest runners
// than on local dev hardware. Rather than hard-code larger waits at every call
// site, scale caller-supplied timeouts up when running under CI. Local runs
// keep the original tight budgets so genuine regressions still surface fast.
const TIMEOUT_SCALE = process.env.CI ? 2 : 1
const scale = (ms: number) => Math.round(ms * TIMEOUT_SCALE)

// ── Phase selectors (derived from actual component root classes) ─────────────

export const PHASE_SELECTORS: Record<string, string> = {
  ROLE_REVEAL: '.reveal-wrap, .waiting-screen',
  SHERIFF_ELECTION: '.sheriff-wrap',
  NIGHT: '.game-wrap.night-mode',
  DAY: '.day-wrap',
  VOTING: '.voting-wrap',
}

// ── Single-page helpers ──────────────────────────────────────────────────────

/**
 * Wait until a page shows the expected game phase.
 * Throws (P0) if the phase doesn't appear within timeout.
 */
export async function waitForPhase(
  page: Page,
  phase: string,
  timeout = 15_000,
): Promise<void> {
  const selector = PHASE_SELECTORS[phase]
  if (!selector) throw new Error(`Unknown phase: ${phase}`)
  await page.locator(selector).first().waitFor({ state: 'visible', timeout: scale(timeout) })
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
export async function verifyAllBrowsersPhase(
  pages: Map<string, Page>,
  phase: string,
  timeout = 30_000, // Increased from 15s to 30s to accommodate audio processing delays
): Promise<void> {
  const selector = PHASE_SELECTORS[phase]
  if (!selector) throw new Error(`Unknown phase: ${phase}`)
  const effective = scale(timeout)

  const results = await Promise.allSettled(
    Array.from(pages.entries()).map(async ([role, page]) => {
      try {
        await page.locator(selector).first().waitFor({ state: 'visible', timeout: effective })
      } catch {
        // Get current page state for better error reporting
        const currentPhase = await page.evaluate(() => {
          const body = document.body
          const hasNightWrap = !!document.querySelector('.game-wrap.night-mode')
          const hasDayWrap = !!document.querySelector('.day-wrap')
          const hasVotingWrap = !!document.querySelector('.voting-wrap')
          const hasSheriffWrap = !!document.querySelector('.sheriff-wrap')
          const hasRevealWrap = !!document.querySelector('.reveal-wrap')
          
          let detectedPhase = 'UNKNOWN'
          if (hasNightWrap) detectedPhase = 'NIGHT'
          else if (hasDayWrap) detectedPhase = 'DAY'
          else if (hasVotingWrap) detectedPhase = 'VOTING'
          else if (hasSheriffWrap) detectedPhase = 'SHERIFF_ELECTION'
          else if (hasRevealWrap) detectedPhase = 'ROLE_REVEAL'
          
          return {
            detectedPhase,
            bodyClasses: body.className,
            url: window.location.href
          }
        })
        
        throw new Error(
          `P0: Browser [${role}] stuck — expected phase ${phase} (selector: ${selector}) ` +
          `but not visible after ${effective}ms. Current state: ${JSON.stringify(currentPhase)}`
        )
      }
    }),
  )

  const failures = results.filter((r) => r.status === 'rejected')
  if (failures.length > 0) {
    const msgs = failures.map((r) => (r as PromiseRejectedResult).reason.message)
    throw new Error(msgs.join('\n'))
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
