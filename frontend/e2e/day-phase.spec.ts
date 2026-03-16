import { expect, test } from '@playwright/test'

// Navigate to game view with a specific day phase scenario loaded
async function loadScenario(
  page: import('@playwright/test').Page,
  scenario: string,
) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('TestHost')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.getByRole('button', { name: /Create Room/i }).click()

  // Wait for room view and STOMP subscription to be ready
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.waitForTimeout(200)

  // Start game via debug button (uses mocked axios, not raw fetch)
  await page.getByRole('button', { name: /Debug: Launch Game/i }).click()
  await page.waitForURL(/\/game\//, { timeout: 5000 })

  // Wait for game view to fully initialize: getState (300ms mock delay) + STOMP connect (50ms)
  await page.waitForTimeout(500)

  // Load the scenario via debug panel button — STOMP subscription is now ready
  await page.getByRole('button', { name: new RegExp(scenarioLabel(scenario)) }).click()
  await page.waitForTimeout(400)
}

function scenarioLabel(scenario: string): string {
  const labels: Record<string, string> = {
    HOST_HIDDEN: 'Host·Hidden',
    HOST_REVEALED: 'Host·Revealed',
    DEAD: 'Dead',
    ALIVE_HIDDEN: 'Alive·Hidden',
    ALIVE_REVEALED: 'Alive·Revealed',
    GUEST: 'Guest',
  }
  return labels[scenario] ?? scenario
}

// ── HOST view ─────────────────────────────────────────────────────────────────

test('host sees 显示结果 button when result is hidden', async ({ page }) => {
  await loadScenario(page, 'HOST_HIDDEN')
  await expect(page.getByRole('button', { name: /显示结果/ })).toBeVisible()
})

test('host does not see kill banner before revealing result', async ({ page }) => {
  await loadScenario(page, 'HOST_HIDDEN')
  await expect(page.locator('.banner-kill')).not.toBeVisible()
})

test('host sees kill banner after revealing result', async ({ page }) => {
  await loadScenario(page, 'HOST_REVEALED')
  await expect(page.locator('.banner-kill')).toBeVisible()
})

test('host sees Vote and 弃权 buttons after revealing result', async ({ page }) => {
  await loadScenario(page, 'HOST_REVEALED')
  await expect(page.getByRole('button', { name: /投票/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /弃权/ })).toBeVisible()
})

test('host Vote button is disabled until a player is selected', async ({ page }) => {
  await loadScenario(page, 'HOST_REVEALED')
  await expect(page.getByRole('button', { name: /投票/ })).toBeDisabled()
})

// ── DEAD view ─────────────────────────────────────────────────────────────────

test('dead player sees elimination banner', async ({ page }) => {
  await loadScenario(page, 'DEAD')
  await expect(page.locator('.banner-info')).toBeVisible()
})

test('dead player sees kill banner after result is revealed', async ({ page }) => {
  await loadScenario(page, 'DEAD')
  await expect(page.locator('.banner-kill')).toBeVisible()
})

test('dead player sees voting disabled button', async ({ page }) => {
  await loadScenario(page, 'DEAD')
  await expect(page.getByRole('button', { name: /投票已禁用/ })).toBeDisabled()
})

// ── ALIVE view ────────────────────────────────────────────────────────────────

test('alive player sees waiting hint before result is revealed', async ({ page }) => {
  await loadScenario(page, 'ALIVE_HIDDEN')
  await expect(page.getByText(/等待房主公布结果/)).toBeVisible()
  await expect(page.getByRole('button', { name: /投票/ })).not.toBeVisible()
})

test('alive player sees kill banner after result is revealed', async ({ page }) => {
  await loadScenario(page, 'ALIVE_REVEALED')
  await expect(page.locator('.banner-kill')).toBeVisible()
})

test('alive player sees Vote and 弃权 buttons after result is revealed', async ({ page }) => {
  await loadScenario(page, 'ALIVE_REVEALED')
  await expect(page.getByRole('button', { name: /投票/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /弃权/ })).toBeVisible()
})

test('alive player Vote button is disabled until a player is selected', async ({ page }) => {
  await loadScenario(page, 'ALIVE_REVEALED')
  await expect(page.getByRole('button', { name: /投票/ })).toBeDisabled()
})

// ── REGRESSION: guest joining via room join should not see host UI ─────────────

async function joinAsGuestAndStartGame(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('GuestUser')
  await page.getByRole('textbox', { name: 'Room code' }).fill('XYZ789')
  await page.getByRole('button', { name: /Join/i }).click()

  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.waitForTimeout(200)

  await page.getByRole('button', { name: /Debug: Launch Game/i }).click()
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.waitForTimeout(500)
}

test('guest joined via room join does not see 显示结果 on day hidden', async ({ page }) => {
  await joinAsGuestAndStartGame(page)
  await page.getByRole('button', { name: /^Hidden$/ }).click()
  await page.waitForTimeout(400)
  await expect(page.getByRole('button', { name: /显示结果/ })).not.toBeVisible()
  await expect(page.getByText(/等待房主公布结果/)).toBeVisible()
})

test('guest joined via room join does not see 显示结果 on day revealed', async ({ page }) => {
  await joinAsGuestAndStartGame(page)
  await page.locator('[data-testid="debug-day-btns"]').getByRole('button', { name: 'Revealed' }).click()
  await page.waitForTimeout(400)
  await expect(page.getByRole('button', { name: /显示结果/ })).not.toBeVisible()
  await expect(page.getByRole('button', { name: /投票/ })).toBeVisible()
})

// ── GUEST view ────────────────────────────────────────────────────────────────

test('guest sees waiting hint', async ({ page }) => {
  await loadScenario(page, 'GUEST')
  await expect(page.getByText(/等待房主公布结果/)).toBeVisible()
})

test('guest sees no action buttons', async ({ page }) => {
  await loadScenario(page, 'GUEST')
  await expect(page.getByRole('button', { name: /投票/ })).not.toBeVisible()
  await expect(page.getByRole('button', { name: /显示结果/ })).not.toBeVisible()
})
