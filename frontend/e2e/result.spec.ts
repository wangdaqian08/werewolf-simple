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
  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /知道了 \/ Got it/i }).waitFor({ state: 'visible', timeout: 3000 })
}

async function goToResult(page: Page, winner: 'VILLAGER' | 'WEREWOLF') {
  await page.evaluate((w) => (window as any).__debug.gameOver(w), winner)
  await page.waitForURL(/\/result\//, { timeout: 5000 })
}

// ── Village Wins ──────────────────────────────────────────────────────────────

test('result: Village Wins — title and subtitle visible', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')

  await expect(page.getByRole('heading', { name: '村民胜利' })).toBeVisible()
  await expect(page.getByText('Village Wins')).toBeVisible()
})

test('result: Village Wins — role pills show all players', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')

  const pills = page.locator('.role-pill')
  await expect(pills).toHaveCount(9)
})

test('result: Village Wins — wolf roles styled as red pills', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')

  const wolfPills = page.locator('.rp-wolf')
  await expect(wolfPills).toHaveCount(2)
})

// ── Wolves Win ────────────────────────────────────────────────────────────────

test('result: Wolves Win — dark theme active', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'WEREWOLF')

  await expect(page.locator('.result-wolves')).toBeVisible()
})

test('result: Wolves Win — title and subtitle visible', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'WEREWOLF')

  await expect(page.getByRole('heading', { name: '狼人胜利' })).toBeVisible()
  await expect(page.getByText('Wolves Win')).toBeVisible()
})

// ── Navigation ────────────────────────────────────────────────────────────────

test('result: Play Again — navigates to lobby', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')

  await page.getByRole('button', { name: /再来一局.*Play Again/i }).click()
  await page.waitForURL(/\/$/, { timeout: 5000 })
})
