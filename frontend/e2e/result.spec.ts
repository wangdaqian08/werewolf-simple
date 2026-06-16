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
  await page.evaluate(() => (window as unknown as { __debug: { gameStart: () => void } }).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()
}

async function goToResult(page: Page, winner: 'VILLAGER' | 'WEREWOLF') {
  await page.evaluate(
    (w) => (window as unknown as { __debug: { gameOver: (w: string) => void } }).__debug.gameOver(w),
    winner,
  )
  await page.waitForURL(/\/result\//, { timeout: 5000 })
}

// ── Village Wins ──────────────────────────────────────────────────────────────

test('result: Village Wins — title and subtitle visible', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')

  await expect(page.getByRole('heading', { name: '好人胜' })).toBeVisible()
  await expect(page.getByText('VILLAGE WINS')).toBeVisible()
  await expect(page.getByText('GAME OVER')).toBeVisible()
})

test('result: Village Wins — reveal cards show all players', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')

  const cards = page.locator('.reveal-card')
  await expect(cards).toHaveCount(9)
})

test('result: Village Wins — only wolves carry .reveal-wolf class', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')

  // Per MOCK_ROLE_ASSIGNMENTS: u2 + u6 are WEREWOLF → 2 wolves out of 9 players
  const wolves = page.locator('.reveal-card.reveal-wolf')
  await expect(wolves).toHaveCount(2)
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

  await expect(page.getByRole('heading', { name: '狼人胜' })).toBeVisible()
  await expect(page.getByText('WOLVES WIN')).toBeVisible()
})

// ── Sort + format ─────────────────────────────────────────────────────────────

test('result: cards rendered in seat-index order', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')

  // MOCK_GAME_STATE has 9 players seats 1..9. Cards must render in 1..9 order.
  const seats = await page
    .locator('.reveal-card')
    .evaluateAll((els) =>
      els.map((el) => Number(el.getAttribute('data-testid')?.split('-').pop())),
    )
  expect(seats).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9])
})

// ── Navigation ────────────────────────────────────────────────────────────────

test('result: Play Again — navigates to lobby', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')

  await page.locator('[data-testid="play-again"]').click()
  await page.waitForURL(/\/$/, { timeout: 5000 })
})

// ── Dead players are greyed out (2026-05-11) ─────────────────────────────────
//
// Killed players' role cards must render with `.reveal-dead` (desaturated /
// strikethrough role text) so survivors are visually distinct from casualties.

test('result: dead players role cards carry .reveal-dead', async ({ page }) => {
  await setup(page)
  await goToResult(page, 'VILLAGER')

  // MOCK_GAME_RESULT marks seats 2, 4, 7 as dead.
  const deadSeats = [2, 4, 7]
  for (const seat of deadSeats) {
    const card = page.locator(`[data-testid="role-reveal-${seat}"]`)
    await expect(card).toHaveClass(/reveal-dead/)
  }
  // Alive players must NOT carry the class.
  const aliveSeats = [1, 3, 5, 6, 8, 9]
  for (const seat of aliveSeats) {
    const card = page.locator(`[data-testid="role-reveal-${seat}"]`)
    await expect(card).not.toHaveClass(/reveal-dead/)
  }

  // Visual evidence: capture the result screen so reviewers can see the
  // grey-out + strikethrough effect on the dead players' cards.
  await page
    .locator('.result-card')
    .screenshot({ path: 'e2e/screenshots/result-dead-greyed.png' })
})
