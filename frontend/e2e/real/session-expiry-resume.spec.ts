import { expect, test } from '@playwright/test'
import { setupGame } from './helpers/multi-browser'
import type { RoleName } from './helpers/shell-runner'
import { hostStartsNight } from './helpers/night-driver'
import { cleanupRoomData } from './helpers/test-support'

// WITCH must stay in the list (setupGame role-toggle trap, see memory).
const ROLES = ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH'] as RoleName[]
const CANDIDATES = ['VILLAGER', 'SEER', 'WITCH'] as RoleName[]

test.describe('Login expires mid-game', () => {
  test.setTimeout(process.env.CI ? 300_000 : 180_000)

  test('the player is sent to log in, logs in again and is back in the game at the current stage', async ({
    browser,
  }) => {
    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ROLES,
      browserRoles: CANDIDATES,
    })
    try {
      // A bot-backed page (not the host's), so the host can still move the game on.
      const role = CANDIDATES.find((r) => ctx.bots.has(r))!
      const page = ctx.pages.get(role)!
      const nickname = ctx.bots.get(role)!.nick

      // The login goes bad: same token, broken signature → the backend answers 401.
      await page.evaluate(() => {
        const jwt = localStorage.getItem('jwt')!
        localStorage.setItem('jwt', `${jwt.slice(0, -4)}AAAA`)
      })

      // The game moves on (ROLE_REVEAL → NIGHT); the player's page refetches and gets 401.
      await hostStartsNight(ctx)

      await page.waitForURL((u) => new URL(u).pathname === '/', { timeout: 20_000 })
      await expect(page.getByTestId('resume-hint')).toBeVisible()
      await expect(page.getByTestId('nickname-input')).toHaveValue(nickname)

      await page.getByTestId('resume-login-btn').click()

      // Back in the same game, on the stage the game is at now.
      await page.waitForURL(new RegExp(`/game/${ctx.gameId}$`), { timeout: 20_000 })
      await expect(page.locator('[data-phase]')).toHaveAttribute('data-phase', 'NIGHT', {
        timeout: 20_000,
      })
      await expect(page.getByTestId('resume-hint')).toHaveCount(0)
    } finally {
      await cleanupRoomData(ctx.hostPage, ctx.roomCode)
      await ctx.cleanup()
    }
  })
})
