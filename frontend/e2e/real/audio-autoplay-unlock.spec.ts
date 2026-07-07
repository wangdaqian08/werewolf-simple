import { expect, test } from '@playwright/test'
import { countIn, installTrigger, NIGHT1_SEQUENCE, snapshot } from './helpers/autoplay-unlock'

/**
 * Narration autoplay unlock — browser-level regression net for the mobile
 * "first night has no sound" bug, Chromium half (Firefox half:
 * audio-autoplay-unlock-firefox.spec.ts; queue logic unit-pinned in
 * src/__tests__/audioServiceAutoplayUnlock.test.ts).
 *
 * LOCAL-ONLY (skipped in CI): it needs a headed browser with a strict
 * autoplay policy. CI shards run Playwright's headless-shell, which starts
 * every page pre-activated (navigator.userActivation.hasBeenActive === true
 * within ~500ms of commit, verified even on a plain JSON page) — the exact
 * condition that masked this bug in every e2e run. Run locally:
 *
 *   npx playwright test --config=playwright.real.config.ts audio-autoplay-unlock
 *
 * This half exercises OUR pre-interaction gate under
 * --autoplay-policy=user-gesture-required: cues queued before any
 * interaction must be PARKED (not consumed) and must play after the first
 * real tap. (page.evaluate grants Chromium sticky activation, so the
 * browser-side NotAllowedError path can't be tested here — that's what the
 * Firefox half is for.)
 */

test.skip(
  !!process.env.CI,
  'needs a headed browser with a real autoplay policy; headless-shell pages are pre-activated',
)

test.use({
  headless: false,
  launchOptions: { args: ['--autoplay-policy=user-gesture-required'] },
})

test('night sequence parks untouched, then plays through after one tap', async ({ page }) => {
  const lines: string[] = []
  page.on('console', (msg) => lines.push(msg.text()))
  await installTrigger(page, 'pristine')
  await page.goto('/#unlock-repro')

  await expect.poll(() => countIn(lines, 'playSequential fired'), { timeout: 15_000 }).toBe(1)
  await expect
    .poll(() => countIn(lines, 'Narration parked (autoplay blocked)'), { timeout: 10_000 })
    .toBeGreaterThan(0)

  // Parked, not consumed: whole sequence still queued, nothing started.
  const parked = await snapshot(page)
  console.log(`[unlock:chromium] parked queueLen=${parked.queueLen}`)
  expect(parked.queueLen).toBe(NIGHT1_SEQUENCE.length)
  expect(parked.isPlayingQueue).toBe(false)
  expect(countIn(lines, 'Starting playback')).toBe(0)
  expect(parked.elements.every((e) => e.paused && e.currentTime === 0)).toBe(true)

  // First real tap → pool primes + queue resumes audibly.
  await page.locator('body').click({ position: { x: 10, y: 10 }, force: true })

  await expect
    .poll(() => countIn(lines, 'Starting playback: goes_dark_close_eyes.mp3'), {
      timeout: 10_000,
    })
    .toBe(1)
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
  const resumed = await snapshot(page)
  console.log(
    `[unlock:chromium] resumed queueLen=${resumed.queueLen} ` +
      `primedLog=${countIn(lines, 'narration file(s) during user gesture')}`,
  )
  expect(resumed.queueLen).toBe(NIGHT1_SEQUENCE.length - 1)
  expect(countIn(lines, 'narration file(s) during user gesture')).toBeGreaterThan(0)
})
