import { expect, test } from '@playwright/test'

// ── Helpers ───────────────────────────────────────────────────────────────────

async function goToCreateRoom(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('TestHost')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
}

async function goToGameViewNoSheriff(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('TestHost')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /Create Room/i }).click()
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.getByRole('button', { name: /Debug: Launch Game/i }).waitFor({ state: 'visible' })
  // Start game with sheriff disabled
  await page.evaluate(() => (window as any).__debug.gameStart({ hasSheriff: false }))
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  // Reveal role
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()
}

// ── Create-room screen ────────────────────────────────────────────────────────

test('sheriff toggle is visible on create-room screen', async ({ page }) => {
  await goToCreateRoom(page)
  await expect(page.getByText(/警长竞选 Sheriff Election/i)).toBeVisible()
})

test('sheriff toggle is ON by default', async ({ page }) => {
  await goToCreateRoom(page)
  // The toggle button for sheriff should have class toggle-on
  const sheriffToggle = page.locator('.toggle').last()
  await expect(sheriffToggle).toHaveClass(/toggle-on/)
})

test('sheriff toggle can be turned off', async ({ page }) => {
  await goToCreateRoom(page)
  const sheriffToggle = page.locator('.toggle').last()
  await sheriffToggle.click()
  await expect(sheriffToggle).toHaveClass(/toggle-off/)
})

test('sheriff toggle can be toggled back on', async ({ page }) => {
  await goToCreateRoom(page)
  const sheriffToggle = page.locator('.toggle').last()
  await sheriffToggle.click() // turn off
  await expect(sheriffToggle).toHaveClass(/toggle-off/)
  await sheriffToggle.click() // turn back on
  await expect(sheriffToggle).toHaveClass(/toggle-on/)
})

// ── No-sheriff game flow ──────────────────────────────────────────────────────

test('no-sheriff: confirming role shows waiting screen', async ({ page }) => {
  await goToGameViewNoSheriff(page)

  // goToGameViewNoSheriff ends with role revealed — just confirm directly
  await page.getByRole('button', { name: '知道了 / Got it' }).click()
  await page.waitForTimeout(100)

  await expect(page.getByText('等待其他玩家确认')).toBeVisible()
})

test('no-sheriff: Start Night button appears after all players confirm', async ({ page }) => {
  await goToGameViewNoSheriff(page)

  await page.getByRole('button', { name: '知道了 / Got it' }).click()
  await page.waitForTimeout(100)

  // Not all confirmed yet — button should not be visible
  await expect(page.getByRole('button', { name: /开始夜晚 \/ Start Night/i })).not.toBeVisible()

  // Simulate all players confirming via debug button
  await page.getByRole('button', { name: /All Confirmed/i }).click()
  await page.waitForTimeout(100)

  // Now "Start Night" button should be visible for the host
  await expect(page.getByRole('button', { name: /开始夜晚 \/ Start Night/i })).toBeVisible()
})

test('no-sheriff: host clicks Start Night shows WAITING countdown then werewolf phase', async ({ page }) => {
  await goToGameViewNoSheriff(page)

  await page.getByRole('button', { name: '知道了 / Got it' }).click()
  await page.waitForTimeout(100)

  await page.getByRole('button', { name: /All Confirmed/i }).click()
  await page.waitForTimeout(100)

  // Click "Start Night"
  await page.getByRole('button', { name: /开始夜晚 \/ Start Night/i }).click()
  await page.waitForTimeout(200)

  // Should show WAITING screen (night is beginning)
  await expect(page.getByText('夜晚即将开始')).toBeVisible({ timeout: 2000 })

  // Sheriff Election must NOT appear
  await expect(page.getByText(/Sign Up for Sheriff/i)).not.toBeVisible()

  // After 5 seconds, mock advances to WEREWOLF_PICK
  await page.waitForFunction(
    () => !document.body.textContent?.includes('夜晚即将开始'),
    { timeout: 8000 },
  )
  // Night phase should now show werewolf pick UI (or still night)
  await expect(page.getByText(/黑夜|夜晚降临|Night Falls/i).first()).toBeVisible()
})

test('no-sheriff: sheriff election screen does NOT appear after all confirm', async ({ page }) => {
  await goToGameViewNoSheriff(page)

  await page.getByRole('button', { name: '知道了 / Got it' }).click()
  await page.waitForTimeout(100)

  await page.getByRole('button', { name: /All Confirmed/i }).click()
  await page.waitForTimeout(100)

  // Sheriff Election must not appear
  await expect(page.getByText(/Sign Up for Sheriff/i)).not.toBeVisible()
})

// ── Sheriff-enabled flow (existing behavior unchanged) ────────────────────────

test('sheriff-enabled: skip to sheriff shows sheriff election', async ({ page }) => {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('TestHost')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /Create Room/i }).click()
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.getByRole('button', { name: /Debug: Launch Game/i }).waitFor({ state: 'visible' })
  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()

  await page.getByRole('button', { name: 'Skip → Sheriff' }).click()
  await page.waitForTimeout(100)

  await expect(page.getByText(/警长竞选|Sheriff Election|Sign Up/i).first()).toBeVisible()
})

test('no-sheriff: Skip → Night debug button shows WAITING screen', async ({ page }) => {
  await goToGameViewNoSheriff(page)

  await page.getByRole('button', { name: 'Skip → Night' }).click()
  await page.waitForTimeout(200)

  // Should show WAITING countdown screen
  await expect(page.getByText('夜晚即将开始')).toBeVisible({ timeout: 2000 })
  // Should not show sheriff election
  await expect(page.getByText(/警长竞选|Sheriff Election/i)).not.toBeVisible()
})
