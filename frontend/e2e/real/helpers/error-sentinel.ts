/**
 * Per-browser console + network error sentinels.
 *
 * Wires `pageerror` (uncaught JS exceptions) and `response` (HTTP 5xx)
 * listeners on every browser page in the multi-browser fixture. Errors
 * are pushed into a shared array and `assertNoBrowserErrors()` fails the
 * test in afterEach if anything was recorded during the test window.
 *
 * Why: a JS error on the seer's screen — or a 5xx from a STOMP-triggered
 * REST call — does not by itself fail the existing assertions, because
 * the spec only checks "did the phase advance?". This sentinel turns
 * those silent regressions into hard test failures and attaches the
 * error trace to the Playwright report.
 *
 * Usage:
 *   const errors: BrowserError[] = []
 *   attachErrorListeners('HOST', hostPage, errors)
 *   // ... after each test:
 *   assertNoBrowserErrors(errors, testInfo)
 */
import type { Page, TestInfo } from '@playwright/test'

export interface BrowserError {
  role: string
  type: 'pageerror' | 'response-5xx'
  url?: string
  status?: number
  message: string
  stack?: string
  /** ISO timestamp of capture, for ordering vs backend log */
  capturedAt: string
}

/**
 * Attach error listeners to a page. Listeners stay attached for the
 * lifetime of the page; the shared `errors` array is mutated in place.
 * Caller resets the array between tests.
 */
export function attachErrorListeners(
  role: string,
  page: Page,
  errors: BrowserError[],
): void {
  page.on('pageerror', (err) => {
    errors.push({
      role,
      type: 'pageerror',
      message: err.message,
      stack: err.stack,
      capturedAt: new Date().toISOString(),
    })
  })
  page.on('response', (resp) => {
    const status = resp.status()
    if (status >= 500) {
      errors.push({
        role,
        type: 'response-5xx',
        url: resp.url(),
        status,
        message: `${resp.request().method()} ${resp.url()} → ${status}`,
        capturedAt: new Date().toISOString(),
      })
    }
  })
}

/**
 * Fail the current test if any browser error was recorded since the
 * last reset. Attaches the full error list to the Playwright report
 * before throwing so CI logs include the JS stack / 5xx URL.
 *
 * Call this in afterEach AFTER any failure-only attachments (composite
 * screenshot, backend log) so they still run if the test already failed.
 *
 * Pass `expected` to allow specific URLs/messages — useful when a test
 * intentionally exercises a 5xx path. Default: every recorded error
 * fails the test.
 */
export async function assertNoBrowserErrors(
  errors: BrowserError[],
  testInfo: TestInfo,
  expected?: { urlIncludes?: string[]; messageIncludes?: string[] },
): Promise<void> {
  const filtered = errors.filter((e) => {
    if (!expected) return true
    if (expected.urlIncludes?.some((s) => e.url?.includes(s))) return false
    if (expected.messageIncludes?.some((s) => e.message.includes(s))) return false
    return true
  })
  if (filtered.length === 0) return

  const summary = filtered
    .map(
      (e, i) =>
        `[${i + 1}] [${e.role}] ${e.type} @ ${e.capturedAt}\n` +
        (e.url ? `  url: ${e.url}\n` : '') +
        (e.status ? `  status: ${e.status}\n` : '') +
        `  message: ${e.message}` +
        (e.stack ? `\n  stack:\n${e.stack.split('\n').slice(0, 8).join('\n')}` : ''),
    )
    .join('\n\n')

  await testInfo.attach('browser-errors', {
    body: `Browser sentinel recorded ${filtered.length} error(s) during this test.\n\n${summary}`,
    contentType: 'text/plain',
  })

  throw new Error(
    `Browser sentinel: ${filtered.length} JS / 5xx error(s) recorded — see browser-errors attachment. ` +
      `First: [${filtered[0].role}] ${filtered[0].message.slice(0, 200)}`,
  )
}

/**
 * Reset the shared errors array. Call in beforeEach so each test gets a
 * clean window — without this, a failure in test N leaks into test N+1.
 */
export function resetBrowserErrors(errors: BrowserError[]): void {
  errors.length = 0
}
