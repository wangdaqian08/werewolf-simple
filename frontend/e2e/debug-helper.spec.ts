import { expect, test } from '@playwright/test'

test('__debug helper is exposed on window in mock mode', async ({ page }) => {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder(/輸入暱稱|Enter your nickname/).fill('Test')
  await page.getByRole('button', { name: /創建房間|Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /創建房間|Create Room/i }).click()
  await page.getByTestId('room-setup-done').click() // players & roles: keep the default board
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.waitForTimeout(70)

  // __debug should be available after setupMocks() runs
  const keys = await page.evaluate(() => Object.keys((window as any).__debug ?? {}))
  expect(keys).toContain('gameStart')
  expect(keys).toContain('roleSkip')
  expect(keys).toContain('sheriffPhase')
  expect(keys).toContain('sheriffCandidate')
  expect(keys).toContain('sheriffRemove')
  expect(keys).toContain('sheriffExit')
  expect(keys).toContain('dayScenario')
  expect(keys).toContain('dayPhase')
  expect(keys).toContain('dayReveal')
  expect(keys).toContain('ready')
  expect(keys).toContain('addPlayer')
})

test('__debug.gameStart() navigates to game view', async ({ page }) => {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder(/輸入暱稱|Enter your nickname/).fill('Test')
  await page.getByRole('button', { name: /創建房間|Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /創建房間|Create Room/i }).click()
  await page.getByTestId('room-setup-done').click() // players & roles: keep the default board
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.waitForTimeout(70)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份|Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份|Reveal Role/i }).click()

  await expect(page.getByText(/知道了|Got it/)).toBeVisible()
})

test('__debug.roleSkip() advances to Sheriff Election', async ({ page }) => {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder(/輸入暱稱|Enter your nickname/).fill('Test')
  await page.getByRole('button', { name: /創建房間|Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /創建房間|Create Room/i }).click()
  await page.getByTestId('room-setup-done').click() // players & roles: keep the default board
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.waitForTimeout(70)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份|Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份|Reveal Role/i }).click()

  await page.evaluate(() => (window as any).__debug.roleSkip())
  await page.waitForTimeout(70)

  await expect(page.getByText(/警長競選|Sheriff Election|Sign Up|Run for Sheriff/i).first()).toBeVisible()
})

test('__debug.dayScenario() loads day phase', async ({ page }) => {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder(/輸入暱稱|Enter your nickname/).fill('Test')
  await page.getByRole('button', { name: /創建房間|Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /創建房間|Create Room/i }).click()
  await page.getByTestId('room-setup-done').click() // players & roles: keep the default board
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.waitForTimeout(70)

  await page.evaluate(() => (window as any).__debug.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份|Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })

  await page.getByRole('button', { name: /揭示我的身份|Reveal Role/i }).click()

  await page.evaluate(() => (window as any).__debug.dayScenario('HOST_REVEALED'))
  await page.waitForTimeout(70)

  await expect(page.getByText(/Carol/i).first()).toBeVisible()
})
