/**
 * E2E spec: the in-game player dashboard grid must adapt to viewport
 * width and never collapse below 2 columns.
 *
 * Currently `.player-grid` is hard-coded to `repeat(4, 1fr)` everywhere
 * (game.css, DayPhase.vue, NightPhase.vue), so the column count never
 * changes regardless of viewport. The fix is to use
 * `repeat(auto-fit, minmax(min(85px, 47%), 1fr))` so the column count
 * derives from container width, with a 2-col floor for narrow screens.
 *
 * Targets at common mobile widths:
 *   480 px → 4 columns
 *   340 px → 3 columns
 *   260 px → 2 columns
 */

import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function bootMockDay(page: Page) {
  await page.goto('/')
  await page.evaluate(() => {
    localStorage.setItem('jwt', 'mock-jwt-token-abc123')
    localStorage.setItem('userId', 'u1')
    localStorage.setItem('nickname', 'You')
  })
  await page.goto('/game/game-001')
  await page.waitForTimeout(300)
  await page.locator('[data-testid="debug-scenario-host-revealed"]').click()
  await expect(page.locator('.day-wrap')).toBeVisible({ timeout: 3000 })
  await expect(page.locator('.player-grid > .player-slot').first()).toBeVisible()
}

/**
 * Counts distinct x-coordinates of player cells (within 1px tolerance) to
 * derive the rendered column count. Uses the first row of cards because
 * grid auto-fit with a partial last row could mislead a naive count.
 */
async function countColumns(page: Page): Promise<number> {
  return await page.evaluate(() => {
    const cells = Array.from(document.querySelectorAll('.player-grid > .player-slot'))
    if (cells.length === 0) return 0
    const rects = cells.map((c) => c.getBoundingClientRect())
    const firstRowY = rects[0].y
    const firstRow = rects.filter((r) => Math.abs(r.y - firstRowY) < 1)
    return firstRow.length
  })
}

test('grid renders 4 columns at 480px viewport', async ({ page }) => {
  // Boot at default viewport so the debug button is reachable, then resize.
  await bootMockDay(page)
  await page.setViewportSize({ width: 480, height: 800 })
  await page.waitForTimeout(150) // allow grid to relayout
  const cols = await countColumns(page)
  expect(cols, 'columns at 480px viewport').toBe(4)
  await page.screenshot({
    path: 'e2e/screenshots/responsive-grid-480.png',
    fullPage: false,
  })
})

test('grid renders 3 columns at 340px viewport', async ({ page }) => {
  await bootMockDay(page)
  await page.setViewportSize({ width: 340, height: 800 })
  await page.waitForTimeout(150)
  const cols = await countColumns(page)
  expect(cols, 'columns at 340px viewport').toBe(3)
  await page.screenshot({
    path: 'e2e/screenshots/responsive-grid-340.png',
    fullPage: false,
  })
})

test('grid renders 2 columns at 260px viewport', async ({ page }) => {
  await bootMockDay(page)
  await page.setViewportSize({ width: 260, height: 800 })
  await page.waitForTimeout(150)
  const cols = await countColumns(page)
  expect(cols, 'columns at 260px viewport').toBe(2)
  await page.screenshot({
    path: 'e2e/screenshots/responsive-grid-260.png',
    fullPage: false,
  })
})
