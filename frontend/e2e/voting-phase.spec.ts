import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function goToGame(page: Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('Tester')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.getByRole('button', { name: /Create Room/i }).click()
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.getByRole('button', { name: /Debug: Launch Game/i }).waitFor({ state: 'visible' })
  await page.getByRole('button', { name: /Debug: Launch Game/i }).click()
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /知道了 \/ Got it/i }).waitFor({ state: 'visible', timeout: 3000 })
}

// Scoped to the Day Scenarios debug section to avoid label collisions
async function loadDayScenario(page: Page, label: string) {
  await page.locator('[data-testid="debug-day-scenario-btns"]').getByRole('button', { name: label }).click()
  await page.waitForTimeout(70)
}

// Scoped to the Voting Screens debug section to avoid label collisions
async function loadVotingScenario(page: Page, label: string) {
  await page.locator('[data-testid="debug-voting-btns"]').getByRole('button', { name: label }).click()
  await page.waitForTimeout(70)
}

// ── Host perspective ──────────────────────────────────────────────────────────

test('host sees Reveal button on voting screen', async ({ page }) => {
  await goToGame(page)
  // HOST_HIDDEN keeps hostId: 'u1' (logged-in user is host)
  await loadDayScenario(page, 'Host·Hidden')
  await loadVotingScenario(page, 'Voting')

  await expect(page.getByRole('button', { name: /公布结果.*Reveal/i })).toBeVisible()
})

test('host Reveal button is disabled while votes are still coming in', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Host·Hidden')
  await loadVotingScenario(page, 'Voting')

  // 3/8 voted — not all in yet
  await expect(page.getByRole('button', { name: /公布结果.*Reveal/i })).toBeDisabled()
})

test('host sees countdown timer after reveal', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Host·Hidden')
  await loadVotingScenario(page, 'Revealed')

  await expect(page.locator('.reveal-countdown')).toBeVisible()
  await expect(page.getByRole('button', { name: /继续.*Continue/i })).toBeVisible()
})

test('host does not see Reveal button after tally is revealed', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Host·Hidden')
  await loadVotingScenario(page, 'Revealed')

  await expect(page.getByRole('button', { name: /公布结果.*Reveal/i })).not.toBeVisible()
})

// ── Guest / alive-player perspective ─────────────────────────────────────────

test('alive player does NOT see Reveal button', async ({ page }) => {
  await goToGame(page)
  // Alive·Revealed sets hostId: 'u2' (u1 is a regular alive player)
  await loadDayScenario(page, 'Alive·Revealed')
  await loadVotingScenario(page, 'Voting')

  await expect(page.getByRole('button', { name: /公布结果.*Reveal/i })).not.toBeVisible()
})

test('alive player sees vote and skip buttons', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Alive·Revealed')
  await loadVotingScenario(page, 'Voting')

  await expect(page.getByRole('button', { name: /投票.*Vote/i })).toBeVisible()
  await expect(page.getByRole('button', { name: /弃权/i })).toBeVisible()
})

test('dead player sees voting-disabled button', async ({ page }) => {
  await goToGame(page)
  // Dead sets hostId: 'u2', u1 is dead
  await loadDayScenario(page, 'Dead')
  await loadVotingScenario(page, 'Voting')

  await expect(page.getByRole('button', { name: /投票已禁用.*Voting disabled/i })).toBeVisible()
  await expect(page.getByRole('button', { name: /^投票.*Vote$/i })).not.toBeVisible()
})

test('alive player does NOT see countdown timer after reveal', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Alive·Revealed')
  await loadVotingScenario(page, 'Revealed')

  await expect(page.locator('.reveal-countdown')).not.toBeVisible()
  await expect(page.getByText(/等待房主继续/)).toBeVisible()
})

// ── Voted state ───────────────────────────────────────────────────────────────

test('voted player sees Unvote button', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Alive·Revealed')
  await loadVotingScenario(page, 'Voted')

  await expect(page.getByRole('button', { name: /取消投票.*Unvote/i })).toBeVisible()
})

test('voted player does not see vote/skip buttons', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Alive·Revealed')
  await loadVotingScenario(page, 'Voted')

  await expect(page.getByRole('button', { name: /^投票.*Vote$/i })).not.toBeVisible()
  await expect(page.getByRole('button', { name: /^弃权$/i })).not.toBeVisible()
})

test('voted players show green cards', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Alive·Revealed')
  await loadVotingScenario(page, 'Voting')

  // u3, u5, u7 are in votedPlayerIds → slot-ready class
  await expect(page.locator('.slot-ready')).toHaveCount(3)
})

// ── Tally reveal ──────────────────────────────────────────────────────────────

test('tally shows vote count before reveal', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Host·Hidden')
  await loadVotingScenario(page, 'Voting')

  await expect(page.locator('.tally-chip-count')).toBeVisible()
  await expect(page.locator('.tally-chip-count')).toContainText('3')
})

test('tally shows per-player breakdown after reveal', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Host·Hidden')
  await loadVotingScenario(page, 'Revealed')

  await expect(page.locator('.tally-chip-top')).toBeVisible()
  await expect(page.locator('.tally-chip-count')).not.toBeVisible()
})

test('eliminated player banner shown after reveal', async ({ page }) => {
  await goToGame(page)
  await loadDayScenario(page, 'Host·Hidden')
  await loadVotingScenario(page, 'Revealed')

  await expect(page.locator('.elim-banner-body')).toBeVisible()
  await expect(page.getByText(/ELIMINATED/i)).toBeVisible()
})
