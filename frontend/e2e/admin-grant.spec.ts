import { expect, test } from '@playwright/test'

/**
 * Admin credit grant page against the mock backend (src/mocks): the mock user
 * is the admin and the mock PIN is 1234. Real admin / PIN / amount / player
 * checks live in the backend (AdminControllerTest).
 */

async function loginAndOpenAdmin(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder(/輸入暱稱|Enter your nickname/).fill('Ray')
  await page
    .getByRole('button', { name: /創建房間|Create Room/i })
    .first()
    .click()
  await page.waitForURL(/\/create-room/)
  await page.goto('/account')
  await page.getByTestId('account-admin-link').click()
  await expect(page).toHaveURL(/\/admin$/)
}

test('admin finds a player and grants credits; the new balance comes back', async ({ page }) => {
  await loginAndOpenAdmin(page)
  await page.getByTestId('admin-search-input').fill('ali')
  await page.getByTestId('admin-search-btn').click()
  await expect(page.getByTestId('admin-player-guest:alice')).toBeVisible()
  await expect(page.getByTestId('admin-player-guest:alina')).toBeVisible()

  await page.getByTestId('admin-player-guest:alice').click()
  await page.getByTestId('admin-amount-input').fill('50')
  await page.getByTestId('admin-pin-input').fill('1234')
  await page.getByTestId('admin-grant-btn').click()

  await expect(page.getByTestId('admin-success')).toContainText('Alice')
  await expect(page.getByTestId('admin-success')).toContainText('90')
  await expect(page.getByTestId('admin-player-guest:alice')).toContainText('90')
})

test('a wrong PIN is refused with a message', async ({ page }) => {
  await loginAndOpenAdmin(page)
  await page.getByTestId('admin-search-input').fill('bob')
  await page.getByTestId('admin-search-btn').click()
  await page.getByTestId('admin-player-google:1093').click()
  await page.getByTestId('admin-amount-input').fill('10')
  await page.getByTestId('admin-pin-input').fill('0000')
  await page.getByTestId('admin-grant-btn').click()

  await expect(page.getByTestId('admin-error')).toContainText(/密碼錯誤|Wrong PIN/)
  await expect(page.getByTestId('admin-success')).not.toBeVisible()
})
