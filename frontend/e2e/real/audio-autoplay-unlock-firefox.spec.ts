import { expect, test } from '@playwright/test'
import { countIn, installTrigger, NIGHT1_SEQUENCE, snapshot } from './helpers/autoplay-unlock'

/**
 * Narration autoplay unlock — Firefox half (see
 * audio-autoplay-unlock.spec.ts for the Chromium half and the full
 * rationale). LOCAL-ONLY, skipped in CI.
 *
 * Firefox media.autoplay.blocking_policy=2 requires play() to run inside
 * an input handler — the same shape as iOS Safari's per-element gesture
 * rule, and immune to Playwright's activation grant (unlike Chromium's
 * sticky-activation policy). It exercises the NotAllowedError
 * park-and-retry path against a real browser denial end to end.
 */

test.skip(
  !!process.env.CI,
  'needs a headed browser with a real autoplay policy; headless-shell pages are pre-activated',
)

test.use({
  browserName: 'firefox',
  headless: false,
  launchOptions: {
    firefoxUserPrefs: {
      'media.autoplay.default': 1,
      'media.autoplay.blocking_policy': 2,
    },
  },
})

test('denied head is parked with the full sequence; a tap plays it in-gesture and the tail re-parks', async ({
  page,
}) => {
  const lines: string[] = []
  page.on('console', (msg) => lines.push(msg.text()))
  await installTrigger(page, 'flagged')
  await page.goto('/#unlock-repro')

  // Head attempted (flag true), denied by the browser, and PARKED — the
  // old code consumed all four files here ('Failed to play' ×4).
  await expect
    .poll(() => countIn(lines, 'Autoplay prevented by browser policy'), { timeout: 20_000 })
    .toBeGreaterThan(0)
  const parked = await snapshot(page)
  console.log(
    `[unlock:firefox] parked queueLen=${parked.queueLen} ` +
      `failedLogs=${countIn(lines, 'Failed to play')}`,
  )
  expect(parked.userInteracted).toBe(true)
  expect(parked.queueLen).toBe(NIGHT1_SEQUENCE.length)
  expect(countIn(lines, 'Failed to play')).toBe(0)
  expect(parked.elements.every((e) => e.paused && e.currentTime === 0)).toBe(true)

  // A real tap resumes the head synchronously inside the input handler —
  // the only context this policy (and iOS) accepts.
  const marker = lines.length
  await page.locator('body').click({ position: { x: 10, y: 10 }, force: true })
  await expect
    .poll(
      async () => {
        const s = await snapshot(page)
        const head = s.elements.find((e) => e.file === 'goes_dark_close_eyes.mp3')
        return head ? head.currentTime > 0 : false
      },
      { timeout: 10_000 },
    )
    .toBe(true)

  // When the head ends, the tail's un-gestured play is denied again under
  // blocking_policy=2 → it re-parks with the remaining cues instead of
  // draining. (iOS differs here: pool priming blesses the elements, so the
  // chain continues — covered by the unit suite.)
  await expect
    .poll(() => countIn(lines, 'Narration parked (autoplay blocked)', marker), {
      timeout: 20_000,
    })
    .toBeGreaterThan(0)
  const reparked = await snapshot(page)
  console.log(`[unlock:firefox] re-parked queueLen=${reparked.queueLen}`)
  expect(reparked.queueLen).toBe(NIGHT1_SEQUENCE.length - 1)
  expect(countIn(lines, 'Failed to play')).toBe(0)
})
