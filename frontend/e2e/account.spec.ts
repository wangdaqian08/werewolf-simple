import { expect, test } from '@playwright/test'

// Mock-mode account page flow: log in (guest login mints the mock u1 session,
// which is a non-guest userId so the payments section renders orders), return
// to the lobby, open the ☰ account page and assert all three sections render
// rows from the axios-mock fixtures.
test('account page renders wallet, perk history and payment history', async ({ page }) => {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')

  // Login via the guest flow (same as the other mock specs): typing a
  // nickname and clicking Create Room mints the session, then we return
  // to the lobby as a logged-in user.
  await page.getByTestId('nickname-input').fill('AccountTester')
  await page.getByTestId('create-room-btn').click()
  await expect(page).toHaveURL(/\/create-room/)
  await page.goto('/')

  const menuBtn = page.getByTestId('account-menu-btn')
  await expect(menuBtn).toBeVisible()
  await menuBtn.click()
  await expect(page).toHaveURL(/\/account/)

  await expect(page.getByTestId('account-wallet')).toBeVisible()
  await expect(page.getByTestId('account-perks')).toBeVisible()
  await expect(page.getByTestId('account-payments')).toBeVisible()
  await expect(page.getByTestId('account-balance')).toContainText('250')

  // Fixture-backed rows: ledger, all four perk statuses, two orders.
  await expect(page.getByTestId('ledger-row').first()).toBeVisible()
  await expect(page.getByTestId('perk-row')).toHaveCount(4)
  await expect(page.getByTestId('order-row')).toHaveCount(2)

  // Row content assertions (testid-only, no text selectors).
  // MOCK_MY_PERKS[2] is VOID, MOCK_MY_PERKS[0] is ACTIVE.
  await expect(page.getByTestId('perk-row').nth(0)).toContainText('启用中')
  await expect(page.getByTestId('perk-row').nth(2)).toContainText('未生效')
  // First order row has its fixture amount and 已完成 status.
  await expect(page.getByTestId('order-row').first()).toContainText('$9.99')
  await expect(page.getByTestId('order-row').first()).toContainText('已完成')

  // Back returns to the lobby.
  await page.getByTestId('account-back-btn').click()
  await expect(page.getByTestId('account-menu-btn')).toBeVisible()
})

test('account page shows login prompt when not logged in', async ({ page }) => {
  // Navigate directly to /account without logging in.
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/account')

  await expect(page.getByTestId('account-login-prompt')).toBeVisible()
  await expect(page.getByTestId('account-wallet')).not.toBeVisible()
})
