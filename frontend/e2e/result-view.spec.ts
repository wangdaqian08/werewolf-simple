/**
 * E2E spec: visual capture of the redesigned gameover page.
 *
 * Captures full-page screenshots for both winner variants at the
 * design-target iPhone 14 viewport (390 × 844) and a narrow 280 × 600
 * viewport to verify the responsive grid floors at 2 columns.
 *
 * Outputs go to e2e/screenshots/result-{light,dark}-{390,280}.png.
 * Reviewers should compare these against the user's reference images
 * `image-cache/.../3.png` (light) and `4.png` (dark).
 */

import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function setup(page: Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('Test')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /Create Room/i }).click()
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.waitForTimeout(70)
  await page.evaluate(
    () => (window as unknown as { __debug: { gameStart: () => void } }).__debug.gameStart(),
  )
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page
    .getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i })
    .waitFor({ state: 'visible', timeout: 3000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()
}

async function goToResult(page: Page, winner: 'VILLAGER' | 'WEREWOLF') {
  await page.evaluate(
    (w) =>
      (window as unknown as { __debug: { gameOver: (w: string) => void } }).__debug.gameOver(w),
    winner,
  )
  await page.waitForURL(/\/result\//, { timeout: 5000 })
  await expect(page.locator('.reveal-card').first()).toBeVisible()
}

test('result-view: light variant — VILLAGE WINS at 390x844', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await setup(page)
  await goToResult(page, 'VILLAGER')
  await page.screenshot({
    path: 'e2e/screenshots/result-light-390.png',
    fullPage: true,
  })
  await expect(page.getByText('好人胜')).toBeVisible()
  await expect(page.getByText('VILLAGE WINS')).toBeVisible()
  await expect(page.locator('[data-testid="play-again"]')).toBeVisible()
})

test('result-view: dark variant — WOLVES WIN at 390x844', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await setup(page)
  await goToResult(page, 'WEREWOLF')
  await page.screenshot({
    path: 'e2e/screenshots/result-dark-390.png',
    fullPage: true,
  })
  await expect(page.getByText('狼人胜')).toBeVisible()
  await expect(page.getByText('WOLVES WIN')).toBeVisible()
  await expect(page.locator('.result-wolves')).toBeVisible()
})

test('result-view: narrow 280x600 collapses to 2-column grid', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')
  await page.setViewportSize({ width: 280, height: 600 })
  await page.waitForTimeout(150)
  const cols = await page.evaluate(() => {
    const cells = Array.from(document.querySelectorAll('.reveal-grid > .reveal-card'))
    if (cells.length === 0) return 0
    const ys = cells.map((c) => c.getBoundingClientRect().y)
    const firstY = ys[0]
    return ys.filter((y) => Math.abs(y - firstY) < 1).length
  })
  expect(cols).toBe(2)
  await page.screenshot({
    path: 'e2e/screenshots/result-light-280.png',
    fullPage: true,
  })
})

test('result-view: cards expose [data-testid="role-reveal-N"] in seat order', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')
  // 9 mock players seated 1..9
  for (let seat = 1; seat <= 9; seat++) {
    await expect(page.locator(`[data-testid="role-reveal-${seat}"]`)).toBeVisible()
  }
})
