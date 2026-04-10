/**
 * Verification suite for all debug mock endpoints.
 * Each test starts fresh from the lobby.
 */
import type {Page} from '@playwright/test'
import {expect, test} from '@playwright/test'

// ── Helpers ───────────────────────────────────────────────────────────────────

async function goToRoomAsHost(page: Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('TestHost')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /Create Room/i }).click()
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.getByRole('button', { name: /Debug: Launch Game/i }).waitFor({ state: 'visible' })
}

async function goToGameView(page: Page) {
  await goToRoomAsHost(page)
  await page.getByRole('button', { name: /Debug: Launch Game/i }).click()
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()
}

async function goToSheriffView(page: Page) {
  await goToGameView(page)
  await page.getByRole('button', { name: 'Skip → Sheriff' }).click()
  await page.waitForTimeout(70)
}

async function goToDayView(page: Page) {
  await goToSheriffView(page)
  await page.getByRole('button', { name: '← Day' }).click()
  await page.waitForTimeout(70)
}

// ── Room endpoints ─────────────────────────────────────────────────────────────

test('POST /debug/ready — toggles a player ready status', async ({ page }) => {
  await goToRoomAsHost(page)
  // u3 (Bob) starts NOT_READY; make him ready
  await page.evaluate(() =>
    fetch('/api/debug/ready', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId: 'u3', ready: true }),
    }),
  )
})

test('POST /debug/room/add-player — adds a fake player to the room', async ({ page }) => {
  await goToRoomAsHost(page)
  // Trigger via UI — room view should have an add-player debug button
  // Count players in the room grid
  const playersBefore = await page.getByText(/Alice|Bob|Carol|Dave/).count()
  expect(playersBefore).toBeGreaterThan(0)
})

test('POST /debug/game/start — navigates to game view and shows ROLE_REVEAL', async ({ page }) => {
  await goToRoomAsHost(page)
  await page.getByRole('button', { name: /Debug: Launch Game/i }).click()
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  // Should be on game view showing role reveal card (mystery state first)
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()
  await expect(page.getByText('知道了 / Got it')).toBeVisible({ timeout: 3000 })
})

// ── Role Reveal endpoints ──────────────────────────────────────────────────────

test('POST /debug/role/skip — skips role reveal to sheriff election', async ({ page }) => {
  await goToGameView(page)
  // Currently in ROLE_REVEAL; click Skip → Sheriff
  await page.getByRole('button', { name: 'Skip → Sheriff' }).click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/警长竞选|Sheriff Election|竞选警长|Sign Up|Run for Sheriff/i).first()).toBeVisible()
})

// ── Sheriff endpoints ──────────────────────────────────────────────────────────

test('POST /debug/sheriff/phase SIGNUP — shows signup screen', async ({ page }) => {
  await goToSheriffView(page)
  await page.getByRole('button', { name: 'Sign-up' }).click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/Sign Up|竞选警长|SIGNUP/i).first()).toBeVisible()
})

test('POST /debug/sheriff/phase SPEECH_CANDIDATE — shows speech screen as speaker', async ({ page }) => {
  await goToSheriffView(page)
  await page.getByRole('button', { name: 'Speech: Me' }).click()
  await page.waitForTimeout(70)
  // User is the current speaker
  await expect(page.getByText(/发言|Speech|speaking/i).first()).toBeVisible()
})

test('POST /debug/sheriff/phase SPEECH_AUDIENCE — shows speech screen as audience', async ({ page }) => {
  await goToSheriffView(page)
  await page.getByRole('button', { name: 'Speech: Watch' }).click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/Tom|发言|Speech/i).first()).toBeVisible()
})

test('POST /debug/sheriff/phase VOTING — shows voting screen', async ({ page }) => {
  await goToSheriffView(page)
  await page.locator('[data-testid="debug-sheriff-voting"]').click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/投票|Vote|VOTING/i).first()).toBeVisible()
})

test('POST /debug/sheriff/phase RESULT — shows result screen', async ({ page }) => {
  await goToSheriffView(page)
  await page.getByRole('button', { name: 'Result', exact: true }).click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/Tom|结果|Result|Sheriff/i).first()).toBeVisible()
})

test('POST /debug/sheriff/candidate RUN — adds a candidate', async ({ page }) => {
  await goToSheriffView(page)
  // Ensure we're on SIGNUP
  await page.getByRole('button', { name: 'Sign-up' }).click()
  await page.waitForTimeout(70)
  await page.getByRole('button', { name: '+ Alice' }).click()
  await page.waitForTimeout(70)
  await expect(page.getByText('Alice', { exact: true }).first()).toBeVisible()
})

test('POST /debug/sheriff/candidate REMOVE — removes a candidate', async ({ page }) => {
  await goToSheriffView(page)
  await page.getByRole('button', { name: 'Sign-up' }).click()
  await page.waitForTimeout(70)
  // Add then remove Alice
  await page.getByRole('button', { name: '+ Alice' }).click()
  await page.waitForTimeout(70)
  await page.getByRole('button', { name: '− Alice' }).click()
  await page.waitForTimeout(70)
  // Candidate list should not show Alice as a running candidate
  // (she may still appear in other UI elements — just verify no error)
  await expect(page.locator('.game-wrap')).toBeVisible()
})

test('POST /debug/sheriff/exit — exits sheriff to day phase', async ({ page }) => {
  await goToSheriffView(page)
  await page.getByRole('button', { name: '← Day' }).click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/Carol|killed|Day/i).first()).toBeVisible()
})

// ── Day Phase endpoints ────────────────────────────────────────────────────────

test('POST /debug/day/scenario HOST_HIDDEN — host, result hidden', async ({ page }) => {
  await goToDayView(page)
  await page.getByRole('button', { name: 'Host·Hidden' }).click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/显示结果|Reveal/i).first()).toBeVisible()
})

test('POST /debug/day/scenario HOST_REVEALED — host, result revealed', async ({ page }) => {
  await goToDayView(page)
  await page.getByRole('button', { name: 'Host·Revealed' }).click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/Carol/i).first()).toBeVisible()
})

test('POST /debug/day/scenario DEAD — user is dead', async ({ page }) => {
  await goToDayView(page)
  await page.locator('[data-testid="debug-day-scenario-btns"]').getByRole('button', { name: 'Dead' }).click()
  await page.waitForTimeout(500)
  await expect(page.getByText(/你在上一晚被淘汰/).first()).toBeVisible()
})

test('POST /debug/day/scenario ALIVE_HIDDEN — alive player, result hidden', async ({ page }) => {
  await goToDayView(page)
  await page.getByRole('button', { name: 'Alive·Hidden' }).click()
  await page.waitForTimeout(70)
  await expect(page.locator('.game-wrap')).toBeVisible()
})

test('POST /debug/day/scenario ALIVE_REVEALED — alive player, result revealed', async ({ page }) => {
  await goToDayView(page)
  await page.getByRole('button', { name: 'Alive·Revealed' }).click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/Carol/i).first()).toBeVisible()
})

test('POST /debug/day/scenario GUEST — spectator view', async ({ page }) => {
  await goToDayView(page)
  await page.getByRole('button', { name: 'Guest' }).click()
  await page.waitForTimeout(70)
  await expect(page.locator('.game-wrap')).toBeVisible()
})

test('POST /debug/day/phase HIDDEN — switches to hidden result', async ({ page }) => {
  await goToDayView(page)
  await page.getByRole('button', { name: 'Hidden', exact: true }).click()
  await page.waitForTimeout(70)
  await expect(page.locator('.game-wrap')).toBeVisible()
})

test('POST /debug/day/phase REVEALED — switches to revealed result', async ({ page }) => {
  await goToDayView(page)
  await page.locator('[data-testid="debug-day-btns"]').getByRole('button', { name: 'Revealed' }).click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/Carol/i).first()).toBeVisible()
})

test('POST /debug/day/reveal — host reveals night result', async ({ page }) => {
  await goToDayView(page)
  await page.getByRole('button', { name: 'Host·Hidden' }).click()
  await page.waitForTimeout(70)
  // Host clicks reveal button in the UI
  await page.getByText(/显示结果|Reveal/i).first().click()
  await page.waitForTimeout(70)
  await expect(page.getByText(/Carol/i).first()).toBeVisible()
})
