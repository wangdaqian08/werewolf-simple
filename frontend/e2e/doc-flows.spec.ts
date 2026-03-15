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
  await page.getByRole('button', { name: /Create Room/i }).click()
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.waitForTimeout(300)
}

// ── Flow 1: Full game flow from lobby ─────────────────────────────────────────

test('flow: gameStart → roleSkip → Sheriff SIGNUP', async ({ page }) => {
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.waitForTimeout(500)

  // Should see Role Reveal card
  await expect(page.getByText('知道了 / Got it')).toBeVisible()

  await page.evaluate(() => (window as any).__debug.roleSkip())
  await page.waitForTimeout(400)

  // Should be on Sheriff Election SIGNUP
  await expect(page.getByText(/竞选警长|Sign Up|Run for Sheriff|警长竞选/i).first()).toBeVisible()
})

// ── Flow 2: Jump straight to a day scenario ───────────────────────────────────

test('flow: gameStart → roleSkip → sheriffExit → dayScenario HOST_REVEALED', async ({ page }) => {
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.waitForTimeout(500)

  await page.evaluate(() => (window as any).__debug.roleSkip())
  await page.waitForTimeout(400)

  await page.evaluate(() => (window as any).__debug.sheriffExit())
  await page.waitForTimeout(400)

  // After sheriffExit we're in DAY HIDDEN — confirm we're not on sheriff screen
  await expect(page.getByText(/竞选警长|Sign Up/i)).not.toBeVisible()

  await page.evaluate(() => (window as any).__debug.dayScenario('HOST_REVEALED'))
  await page.waitForTimeout(400)

  // HOST_REVEALED: Carol was killed, kill banner visible
  await expect(page.getByText(/Carol/i).first()).toBeVisible()
})

// ── Flow 3: Test candidate controls ──────────────────────────────────────────

test('flow: roleSkip already seeds Alice+Tom as candidates', async ({ page }) => {
  // Verify that MOCK_SHERIFF_SIGNUP (used by roleSkip) already contains Alice and Tom.
  // This means the doc's sheriffCandidate calls in "Test candidate controls" are no-ops.
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.waitForTimeout(500)

  await page.evaluate(() => (window as any).__debug.roleSkip())
  await page.waitForTimeout(400)

  // Alice and Tom should already be candidates without any sheriffCandidate() call
  await expect(page.getByText('Alice', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('Tom', { exact: true }).first()).toBeVisible()
})

test('flow: sheriffCandidate is a no-op when candidate already exists', async ({ page }) => {
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.waitForTimeout(500)

  await page.evaluate(() => (window as any).__debug.roleSkip())
  await page.waitForTimeout(400)

  // Count candidates before
  const before = await page.evaluate(() => {
    // The mock exposes mockGameState indirectly; read from DOM
    return document.querySelectorAll('.cand-name').length
  })

  // Adding Alice again should be a no-op
  await page.evaluate(() => (window as any).__debug.sheriffCandidate('u2', 'Alice', '😊'))
  await page.waitForTimeout(300)

  const after = await page.evaluate(() => document.querySelectorAll('.cand-name').length)
  expect(after).toBe(before) // count unchanged — no-op confirmed
})

test('flow: correct candidate controls sequence — remove then re-add', async ({ page }) => {
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.waitForTimeout(500)

  await page.evaluate(() => (window as any).__debug.roleSkip())
  await page.waitForTimeout(400)

  // Remove Alice first, then re-add to actually see the add take effect
  await page.evaluate(() => (window as any).__debug.sheriffRemove('u2'))
  await page.waitForTimeout(300)

  await page.evaluate(() => (window as any).__debug.sheriffCandidate('u2', 'Alice', '😊'))
  await page.waitForTimeout(300)

  await expect(page.getByText('Alice', { exact: true }).first()).toBeVisible()
})

test('flow: sheriffPhase SPEECH_CANDIDATE after roleSkip works', async ({ page }) => {
  await setup(page)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.waitForTimeout(500)

  await page.evaluate(() => (window as any).__debug.roleSkip())
  await page.waitForTimeout(400)

  await page.evaluate(() => (window as any).__debug.sheriffPhase('SPEECH_CANDIDATE'))
  await page.waitForTimeout(400)

  // Should now be on speech phase
  await expect(page.getByText(/发言|Speech|speaking/i).first()).toBeVisible()
})
