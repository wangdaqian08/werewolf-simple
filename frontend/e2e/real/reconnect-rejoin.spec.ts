/**
 * Real-backend E2E: quick rejoin after leaving an in-progress game (#7).
 *
 * A player who closes/backgrounds the app and lands back on the lobby must be
 * able to jump straight back into their active game. This drives the real
 * GET /api/room/active membership lookup + the lobby's 继续游戏 / Rejoin button:
 *
 *   1. Host is in an in-progress game (ctx.gameId).
 *   2. Host navigates back to the lobby '/' (simulates reopening the app).
 *   3. The lobby surfaces a Rejoin button carrying the room code.
 *   4. Clicking it routes to /room/:id which auto-redirects into /game/:id.
 *
 * Designed per memory `e2e-ci-vs-local-env-differences`: testid locators,
 * URL-gated waits (no fixed sleeps), no backend mocking.
 */
import { expect, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { type RoleName } from './helpers/shell-runner'
import { attachCompositeOnFailure } from './helpers/composite-screenshot'

let ctx: GameContext

test.describe('Quick rejoin into an in-progress game — real-backend flow', () => {
  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    // Minimal browser footprint — only the host page is exercised here.
    // Include WITCH in the role set: setupGame's role-toggle filter for WITCH
    // ambiguously matches the "女巫自救 Witch Self-Save" settings row, so a spec
    // that omits WITCH trips a strict-mode violation when it tries to toggle it
    // off. Keeping WITCH enabled side-steps that pre-existing harness quirk.
    ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH'] as RoleName[],
      browserRoles: ['VILLAGER'] as RoleName[],
    })
  })

  test.afterAll(async () => {
    await ctx?.cleanup()
  })

  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status === 'failed' && ctx?.pages) {
      await attachCompositeOnFailure(ctx.pages, testInfo)
    }
  })

  test('host returns to lobby → sees Rejoin → lands back in the game', async () => {
    // Sanity: the host starts on the game view of the active game.
    await expect(ctx.hostPage).toHaveURL(new RegExp(`/game/${ctx.gameId}`))

    // Simulate reopening the app: navigate back to the lobby. The session
    // (JWT) persists in localStorage, so the lobby's onMounted lookup runs.
    await ctx.hostPage.goto('http://localhost:5174/')

    // The active-room lookup surfaces the Rejoin button with the room code.
    const rejoin = ctx.hostPage.getByTestId('rejoin-room-btn')
    await expect(rejoin).toBeVisible({ timeout: 15_000 })
    await expect(rejoin).toContainText(ctx.roomCode)

    // Clicking it routes through /room/:id and auto-redirects into the game.
    await rejoin.click()
    await ctx.hostPage.waitForURL(new RegExp(`/game/${ctx.gameId}`), { timeout: 15_000 })
    await expect(ctx.hostPage.locator('.game-wrap')).toBeVisible({ timeout: 10_000 })
  })
})
