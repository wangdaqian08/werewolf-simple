/**
 * Verifies every flow described in docs/debug-endpoints.md.
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
}

// ── Flow 1: Full game flow from lobby ─────────────────────────────────────────

test('flow: gameStart → roleSkip → Sheriff SIGNUP', async ({ page }) => {
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()

  // Should see Role Reveal card
  await expect(page.getByText('知道了 / Got it')).toBeVisible()

  await page.evaluate(() => (window as any).__debug.roleSkip())
  await page.waitForTimeout(70)

  // Should be on Sheriff Election SIGNUP
  await expect(page.getByText(/竞选警长|Sign Up|Run for Sheriff|警长竞选/i).first()).toBeVisible()
})

// ── Flow 2: Jump straight to a day scenario ───────────────────────────────────

test('flow: gameStart → roleSkip → sheriffExit → dayScenario HOST_REVEALED', async ({ page }) => {
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()

  await page.evaluate(() => (window as any).__debug.roleSkip())
  await page.waitForTimeout(70)

  await page.evaluate(() => (window as any).__debug.sheriffExit())
  await page.waitForTimeout(70)

  // After sheriffExit we're in DAY HIDDEN — confirm we're not on sheriff screen
  await expect(page.getByText(/竞选警长|Sign Up/i)).not.toBeVisible()

  await page.evaluate(() => (window as any).__debug.dayScenario('HOST_REVEALED'))
  await page.waitForTimeout(70)

  // HOST_REVEALED: Carol was killed, kill banner visible
  await expect(page.getByText(/Carol/i).first()).toBeVisible()
})

// ── Flow 3: Test candidate controls ──────────────────────────────────────────

test('flow: roleSkip lands on SIGNUP showing decision progress', async ({ page }) => {
  // 2026-05-11: SIGNUP no longer exposes candidate identities, so the
  // previous "Alice+Tom seeded as visible candidates" claim no longer
  // applies. The observable signal is the decision-progress counter.
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()

  await page.evaluate(() => (window as any).__debug.roleSkip())

  // Mock SIGNUP fixture lands at 5 of 8 alive players decided
  await expect(page.getByTestId('sheriff-decision-progress')).toContainText(/5\s*\/\s*8/)
})

test('flow: sheriffCandidate is a no-op when candidate already exists', async ({ page }) => {
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()

  await page.evaluate(() => (window as any).__debug.roleSkip())

  const progress = page.getByTestId('sheriff-decision-progress')
  // First sheriffCandidate(u2) is a real add: counter advances 5/8 → 6/8.
  await page.evaluate(() => (window as any).__debug.sheriffCandidate('u2', 'Alice', '😊'))
  await expect(progress).toContainText(/6\s*\/\s*8/)
  // Adding Alice again should be a no-op: counter stays at 6/8.
  await page.evaluate(() => (window as any).__debug.sheriffCandidate('u2', 'Alice', '😊'))
  await page.waitForTimeout(70)
  await expect(progress).toContainText(/6\s*\/\s*8/)
})

test('flow: correct candidate controls sequence — remove then re-add', async ({ page }) => {
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()

  await page.evaluate(() => (window as any).__debug.roleSkip())

  const progress = page.getByTestId('sheriff-decision-progress')
  // Add Alice: 5/8 → 6/8
  await page.evaluate(() => (window as any).__debug.sheriffCandidate('u2', 'Alice', '😊'))
  await expect(progress).toContainText(/6\s*\/\s*8/)
  // Remove Alice: 6/8 → 5/8
  await page.evaluate(() => (window as any).__debug.sheriffRemove('u2'))
  await expect(progress).toContainText(/5\s*\/\s*8/)
  // Re-add Alice: 5/8 → 6/8
  await page.evaluate(() => (window as any).__debug.sheriffCandidate('u2', 'Alice', '😊'))
  await expect(progress).toContainText(/6\s*\/\s*8/)
})

test('flow: sheriffPhase SPEECH_CANDIDATE after roleSkip works', async ({ page }) => {
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()

  await page.evaluate(() => (window as any).__debug.roleSkip())
  await page.waitForTimeout(70)

  await page.evaluate(() => (window as any).__debug.sheriffPhase('SPEECH_CANDIDATE'))
  await page.waitForTimeout(70)

  // Should now be on speech phase
  await expect(page.getByText(/发言|Speech|speaking/i).first()).toBeVisible()
})
