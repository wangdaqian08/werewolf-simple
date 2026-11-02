import { expect, type Page, test } from '@playwright/test'
import { setupGame } from './helpers/multi-browser'
import type { RoleName } from './helpers/shell-runner'
import { captureSnapshot } from './helpers/composite-screenshot'
import { completeDay, completeNight } from './helpers/flow-drivers'
import { waitForCondition, waitForDaySubPhase } from './helpers/state-polling'
import { cleanupRoomData } from './helpers/test-support'
import { hostStartsNight } from './helpers/night-driver'

// WITCH must stay in the list (setupGame role-toggle trap, see memory).
const ROLES = ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH'] as RoleName[]

async function readState(page: Page, gameId: string) {
  return page.evaluate(async (id: string) => {
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${localStorage.getItem('jwt')}` },
    })
    return res.ok ? res.json() : null
  }, gameId)
}

type StatePlayer = { userId: string; isAlive: boolean; removedByHost?: boolean }

test.describe('Host declares a player out', () => {
  test.setTimeout(process.env.CI ? 360_000 : 240_000)

  test('a wolf who left is declared out in the day; the day votes and the game reaches night 2', async ({
    browser,
  }, testInfo) => {
    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ROLES,
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
    })
    try {
      const wolves = ctx.roleMap.WEREWOLF ?? []
      expect(wolves.length, 'kit must have 2 wolves').toBe(2)
      // Declaring one of two wolves out cannot end the game (屠边 needs a whole side dead).
      const target = wolves.find((w) => w.nick !== 'Host')!
      // The WEREWOLF page is that wolf's unless the host rolled a wolf.
      const targetPage = ctx.hostRole === 'WEREWOLF' ? null : (ctx.pages.get('WEREWOLF') ?? null)
      const victim = (ctx.roleMap.VILLAGER ?? []).find((v) => v.nick !== 'Host')!

      await hostStartsNight(ctx)
      await completeNight(ctx, { wolfKillSeat: victim.seat, witch: { mode: 'pass' } })

      const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
      await revealBtn.waitFor({ state: 'visible', timeout: 30_000 })
      await revealBtn.click()
      expect(await waitForDaySubPhase(ctx.hostPage, ctx.gameId, 'RESULT_REVEALED', 15_000)).toBe(
        true,
      )

      // Host: ⚡ → 宣布玩家出局 → pick the wolf → confirm.
      await ctx.hostPage.getByTestId('action-menu-btn').click()
      await ctx.hostPage.getByTestId('action-menu-declare-out').click()
      await ctx.hostPage.getByTestId(`action-menu-declare-${target.userId}`).click()
      await ctx.hostPage.getByTestId('action-menu-declare-confirm').click()

      await waitForCondition(
        async () => {
          const s = await readState(ctx.hostPage, ctx.gameId)
          const p = (s?.players as StatePlayer[] | undefined)?.find(
            (x) => x.userId === target.userId,
          )
          return p?.isAlive === false && p?.removedByHost === true
        },
        'target declared out',
        15_000,
      )
      if (targetPage) {
        await expect(targetPage.getByTestId('you-declared-out')).toBeVisible({ timeout: 10_000 })
      }
      await captureSnapshot(ctx.pages, testInfo, 'host-declared-out')

      const afterDeclare = await readState(ctx.hostPage, ctx.gameId)
      expect(afterDeclare?.phase).toBe('DAY_DISCUSSION')
      expect(afterDeclare?.subPhase).toBe('RESULT_REVEALED')

      // The day goes on: everyone abstains (the declared player is not awaited) → night 2.
      await completeDay(ctx, testInfo, { targetSeat: -1, label: 'declare-out-day' })
      await waitForCondition(
        async () => (await readState(ctx.hostPage, ctx.gameId))?.phase === 'NIGHT',
        'NIGHT 2',
        30_000,
      )
    } finally {
      await cleanupRoomData(ctx.hostPage, ctx.roomCode)
      await ctx.cleanup()
    }
  })
})
