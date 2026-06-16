// E2E: SIGNUP sub-phase hides candidate identities and shows only the
// decision-progress count.
//
// Behaviour pinned 2026-05-11. The screenshots produced here under
// frontend/e2e/screenshots/ are the visual evidence that:
//   • candidate identities (names/avatars) are NOT exposed during SIGNUP, and
//   • only `decisionProgress` (X / Y decided) is surfaced — no candidate count
//     and no explanatory hint text.
//
// We use mock mode (no backend), which mirrors the shape of the new
// /api/game/state contract: SHERIFF_ELECTION/SIGNUP returns only the viewer's
// own candidate row plus `decisionProgress`.
import { expect, test } from '@playwright/test'

async function goToSheriffSignup(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('TestHost')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /Create Room/i }).click()
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page
    .getByRole('button', { name: /Debug: Launch Game/i })
    .waitFor({ state: 'visible' })
  await page.evaluate(() => (window as { __debug?: { gameStart: () => unknown } }).__debug?.gameStart())
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page
    .getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i })
    .waitFor({ state: 'visible', timeout: 3000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()
  await page.getByRole('button', { name: 'Skip → Sheriff' }).click()
  await page.getByTestId('sheriff-decision-progress').waitFor({ state: 'visible' })
}

test('SIGNUP shows only decision progress and hides candidate identities', async ({ page }) => {
  await goToSheriffSignup(page)

  const progressLocator = page.getByTestId('sheriff-decision-progress')
  await expect(progressLocator).toBeVisible()
  // Mock SIGNUP fixture: 5 of 8 players decided
  await expect(progressLocator).toHaveText(/5\s*\/\s*8/)

  // No per-candidate rows leak through during SIGNUP — identities are hidden
  await expect(page.locator('.cand-row-running')).toHaveCount(0)
  // No aggregate candidate count is rendered either — only progress
  await expect(page.getByTestId('sheriff-candidate-count')).toHaveCount(0)
  // The host "Start Campaign" button is gone — auto-transition handles it
  await expect(page.getByTestId('sheriff-start-campaign')).toHaveCount(0)

  // Screenshot 1: SIGNUP before I decide
  await page.screenshot({
    path: 'e2e/screenshots/sheriff-signup-hidden-before.png',
    fullPage: true,
  })

  // After clicking "Run for Sheriff", decision progress advances 5/8 → 6/8.
  await page.getByRole('button', { name: /参选 \/ Run for Sheriff/i }).click()
  await expect(page.getByRole('button', { name: /撤回 \/ Withdraw/i })).toBeVisible()
  await expect(progressLocator).toHaveText(/6\s*\/\s*8/)
  // Still no per-candidate rows visible: even my own decision doesn't expose
  // anybody else.
  await expect(page.locator('.cand-row-running')).toHaveCount(0)

  // Screenshot 2: SIGNUP after I sign up (progress incremented, identities still hidden)
  await page.screenshot({
    path: 'e2e/screenshots/sheriff-signup-hidden-after-run.png',
    fullPage: true,
  })
})
