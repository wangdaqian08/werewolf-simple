import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

/**
 * 玩家视角复盘 and the 积分规则 table, driven through the real app against the mock
 * backend: the route, the fetch, the rendering and the back navigation. The payloads
 * come from MOCK_PLAYER_REVIEW / MOCK_GAME_REVIEW / MOCK_REWARD_RULES in src/mocks.
 */

async function startGame(page: Page) {
  await page.goto('/')
  await page.evaluate(() => localStorage.clear())
  await page.goto('/')
  await page.getByPlaceholder('Enter your nickname').fill('Test')
  await page.getByRole('button', { name: /Create Room/i }).first().click()
  await page.waitForURL(/\/create-room/, { timeout: 5000 })
  await page.getByRole('button', { name: /Create Room/i }).click()
  await page.getByTestId('room-setup-done').click()
  await page.waitForURL(/\/room\//, { timeout: 5000 })
  await page.waitForTimeout(70)
  await page.evaluate(() =>
    (window as unknown as { __debug: { gameStart: () => void } }).__debug.gameStart(),
  )
  await page.waitForURL(/\/game\//, { timeout: 5000 })
  await page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i }).click()
}

async function goToResult(page: Page) {
  await page.evaluate(
    (w) => (window as unknown as { __debug: { gameOver: (w: string) => void } }).__debug.gameOver(w),
    'VILLAGER',
  )
  await page.waitForURL(/\/result\//, { timeout: 5000 })
}

test('result screen offers both replays side by side', async ({ page }) => {
  await startGame(page)
  await goToResult(page)

  await expect(page.getByTestId('open-review')).toBeVisible()
  await expect(page.getByTestId('open-player-review')).toBeVisible()
})

test('玩家视角复盘 shows my own turn and stops where my game ended', async ({ page }) => {
  await startGame(page)
  await goToResult(page)
  await page.getByTestId('open-player-review').click()
  await page.waitForURL(/\/review\/.+\/me/, { timeout: 5000 })

  const review = page.getByTestId('player-review')
  await expect(review).toBeVisible()
  // The mock viewer is the seer: his own checks are shown, and the replay stops at his death.
  await expect(review).toContainText('我的身份')
  await expect(review).toContainText('我查验')
  await expect(review).toContainText('复盘到此结束')
  // Dawn names the dead without the cause the god replay gives.
  await expect(review).not.toContainText('被刀出局')
})

test('玩家视角复盘 itemises my points and marks the actions that earned them', async ({ page }) => {
  await startGame(page)
  await goToResult(page)
  await page.getByTestId('open-player-review').click()
  await page.waitForURL(/\/review\/.+\/me/, { timeout: 5000 })

  const points = page.getByTestId('my-reward')
  await expect(points).toBeVisible()
  await expect(points).toContainText('合计')
  // The mock viewer won, wore the badge and found a wolf on day 1: 20 + 5 + 3.
  await expect(points).toContainText('+28')
  // Those credits are marked on the very events that earned them.
  await expect(page.getByTestId('player-review')).toContainText('+5')
  await expect(page.getByTestId('player-review')).toContainText('+3')
})

test('上帝视角复盘 lists what every player earned', async ({ page }) => {
  await startGame(page)
  await goToResult(page)
  await page.getByTestId('open-review').click()
  await page.waitForURL(/\/review\/[^/]+$/, { timeout: 5000 })

  await expect(page.getByTestId('game-review')).toBeVisible()
  await expect(page.getByTestId('review-rewards')).toBeVisible()
})

test('‹ 返回 goes back to the result screen of the same game', async ({ page }) => {
  await startGame(page)
  await goToResult(page)
  const resultUrl = page.url()
  await page.getByTestId('open-player-review').click()
  await page.waitForURL(/\/review\/.+\/me/, { timeout: 5000 })

  await page.getByTestId('player-review-back').click()
  await page.waitForURL(/\/result\//, { timeout: 5000 })
  expect(page.url()).toBe(resultUrl)
})

test('积分规则 separates the one-of base rules from what stacks on them', async ({ page }) => {
  await startGame(page)
  await page.evaluate(() =>
    (window as unknown as { __debug: { dayScenario: (s: string) => void } }).__debug.dayScenario(
      'ALIVE_REVEALED',
    ),
  )
  await page.getByTestId('settings-fab').click()

  const rules = page.getByTestId('settings-rewards')
  await expect(rules).toBeVisible()
  await expect(page.getByTestId('settings-reward-group-BASE')).toContainText('基础分')
  await expect(page.getByTestId('settings-reward-group-BONUS')).toContainText('加分项')
  // A per-day rate and a per-occurrence one read differently.
  await expect(rules).toContainText('+4 / 天')
  await expect(rules).toContainText('+3 / 次')
  // Nothing costs points yet, so neither the penalty group nor the floor note appears.
  await expect(page.getByTestId('settings-reward-group-PENALTY')).toHaveCount(0)
  await expect(rules).not.toContainText('本局积分最低')
})
