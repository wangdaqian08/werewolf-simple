import { expect, test } from '@playwright/test'

async function goToGameView(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('TestHost')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /Create Room/i }).click()

  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.getByRole('button', { name: /Debug: Launch Game/i }).waitFor({ state: 'visible' })

  await page.getByRole('button', { name: /Debug: Launch Game/i }).click()
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).waitFor({ state: 'visible', timeout: 3000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()
}

test('game start shows RoleRevealCard with SEER role', async ({ page }) => {
  await goToGameView(page)

  // Click "Role Reveal" debug button to trigger ROLE_REVEAL phase
  await page.getByRole('button', { name: 'Role Reveal' }).click()
  await page.waitForTimeout(70)

  // Should see role card with Seer content
  await expect(page.getByText('预言家', { exact: true })).toBeVisible()
  await expect(page.getByText('SEER', { exact: true })).toBeVisible()
  await expect(page.getByText('知道了 / Got it')).toBeVisible()
})

test('confirming role shows waiting screen', async ({ page }) => {
  await goToGameView(page)

  await page.getByRole('button', { name: 'Role Reveal' }).click()
  await page.waitForTimeout(70)

  // Confirm role
  await page.getByRole('button', { name: '知道了 / Got it' }).click()
  await page.waitForTimeout(70)

  // Should switch to waiting screen
  await expect(page.getByText('等待其他玩家确认')).toBeVisible()
  await expect(page.getByText(/confirmed/)).toBeVisible()
})

test('skip button advances to Sheriff Election', async ({ page }) => {
  await goToGameView(page)

  await page.getByRole('button', { name: 'Role Reveal' }).click()
  await page.waitForTimeout(70)

  // Click "Skip → Sheriff" debug button
  await page.getByRole('button', { name: 'Skip → Sheriff' }).click()
  await page.waitForTimeout(70)

  // Should now show Sheriff Election UI
  await expect(page.getByText(/警长竞选|Sheriff Election|Sign Up/i).first()).toBeVisible()
})

test('debug panel is visible in ROLE_REVEAL phase', async ({ page }) => {
  await goToGameView(page)

  await page.getByRole('button', { name: 'Role Reveal' }).click()
  await page.waitForTimeout(70)

  // Debug panel should still be accessible even in role reveal phase
  await expect(page.getByRole('button', { name: 'Skip → Sheriff' })).toBeVisible()
})
