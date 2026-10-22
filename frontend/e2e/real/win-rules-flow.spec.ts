import { expect, type Page, test } from '@playwright/test'
import { setupGame, type GameContext } from './helpers/multi-browser'
import { type RoleName } from './helpers/shell-runner'
import { captureSnapshot } from './helpers/composite-screenshot'
import {
  assertNonNull,
  completeDay,
  completeNight,
  resolveRolePlayer,
} from './helpers/flow-drivers'
import { waitForCondition } from './helpers/state-polling'
import { cleanupRoomData } from './helpers/test-support'
import { hostStartsNight } from './helpers/night-driver'

async function readState(hostPage: Page, gameId: string) {
  return hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    return res.ok ? res.json() : null
  }, gameId)
}

async function waitForTopPhaseIn(
  hostPage: Page,
  gameId: string,
  phases: string[],
  timeoutMs = 45_000,
) {
  let last: string | null = null
  await waitForCondition(
    async () => {
      last = (await readState(hostPage, gameId))?.phase ?? null
      return last !== null && phases.includes(last)
    },
    `phase in ${phases.join('|')}`,
    timeoutMs,
    300,
  ).catch(() => {})
  return last
}

async function startNight(ctx: GameContext) {
  await hostStartsNight(ctx)
}

function nonHostVillagers(ctx: GameContext) {
  return (ctx.roleMap.VILLAGER ?? []).filter((b) => b.nick !== 'Host')
}

test.describe('Win rules — 屠边 and "the game ends before the hunter shoots"', () => {
  test.setTimeout(process.env.CI ? 360_000 : 240_000)

  test('屠边: the wolves kill the only god while outnumbered — wolves win at dawn', async ({
    browser,
  }, testInfo) => {
    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER'] as RoleName[],
    })
    try {
      const seer = await resolveRolePlayer(ctx, 'SEER')
      assertNonNull(seer, 'kit must have a SEER')
      const villager = nonHostVillagers(ctx)[0]
      assertNonNull(villager, 'need a non-host villager for the seer check')

      await startNight(ctx)
      await completeNight(ctx, { wolfKillSeat: seer.seat, seerCheckSeat: villager.seat })

      const phase = await waitForTopPhaseIn(ctx.hostPage, ctx.gameId, [
        'GAME_OVER',
        'DAY_DISCUSSION',
      ])
      await captureSnapshot(ctx.pages, testInfo, 'tubian-gods-wiped')
      const state = await readState(ctx.hostPage, ctx.gameId)
      expect(phase, 'all gods dead (2 wolves vs 3 villagers) must end the game').toBe('GAME_OVER')
      expect(state?.winner).toBe('WEREWOLF')
    } finally {
      await cleanupRoomData(ctx.hostPage, ctx.roomCode)
      await ctx.cleanup()
    }
  })

  test('屠边: wolves reach parity while a god and a villager live — the game continues', async ({
    browser,
  }, testInfo) => {
    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
    })
    try {
      const seer = await resolveRolePlayer(ctx, 'SEER')
      assertNonNull(seer, 'kit must have a SEER')
      const [v1, v2] = nonHostVillagers(ctx)
      assertNonNull(v1, 'need a non-host villager as the wolf target')
      const checkSeat = v2?.seat ?? v1.seat

      await startNight(ctx)
      await completeNight(ctx, {
        wolfKillSeat: v1.seat,
        witch: { mode: 'poison', targetSeat: seer.seat },
        seerCheckSeat: checkSeat,
      })

      const phase = await waitForTopPhaseIn(ctx.hostPage, ctx.gameId, [
        'GAME_OVER',
        'DAY_DISCUSSION',
      ])
      await captureSnapshot(ctx.pages, testInfo, 'tubian-parity-continues')
      expect(phase, '2 wolves vs witch + villager is parity but no side is wiped').toBe(
        'DAY_DISCUSSION',
      )
    } finally {
      await cleanupRoomData(ctx.hostPage, ctx.roomCode)
      await ctx.cleanup()
    }
  })

  test('the hunter voted out as the last god ends the game — no shot', async ({
    browser,
  }, testInfo) => {
    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'HUNTER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'HUNTER'] as RoleName[],
    })
    try {
      const hunter = await resolveRolePlayer(ctx, 'HUNTER')
      assertNonNull(hunter, 'kit must have a HUNTER')
      const victim = nonHostVillagers(ctx)[0]
      assertNonNull(victim, 'need a non-host villager as the wolf target')

      await startNight(ctx)
      await completeNight(ctx, { wolfKillSeat: victim.seat })
      expect(await waitForTopPhaseIn(ctx.hostPage, ctx.gameId, ['DAY_DISCUSSION'])).toBe(
        'DAY_DISCUSSION',
      )

      await completeDay(ctx, testInfo, { targetSeat: hunter.seat, label: 'hunter-last-god' })
      const phase = await waitForTopPhaseIn(ctx.hostPage, ctx.gameId, ['GAME_OVER'], 15_000)
      const state = await readState(ctx.hostPage, ctx.gameId)
      await captureSnapshot(ctx.pages, testInfo, 'hunter-last-god-game-over')
      expect(state?.votingPhase?.subPhase ?? state?.subPhase, 'no HUNTER_SHOOT step').not.toBe(
        'HUNTER_SHOOT',
      )
      expect(phase, 'the vote wiped the god side').toBe('GAME_OVER')
      expect(state?.winner).toBe('WEREWOLF')
    } finally {
      await cleanupRoomData(ctx.hostPage, ctx.roomCode)
      await ctx.cleanup()
    }
  })

  test('game review: the result page opens a God-view replay of the night and the vote', async ({
    browser,
  }, testInfo) => {
    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER'] as RoleName[],
    })
    try {
      const seer = await resolveRolePlayer(ctx, 'SEER')
      assertNonNull(seer, 'kit must have a SEER')
      const victim = nonHostVillagers(ctx)[0]
      assertNonNull(victim, 'need a non-host villager as the wolf target')
      const wolves = [...(ctx.roleMap.WEREWOLF ?? [])].sort((a, b) => a.seat - b.seat)
      const wolf = wolves[0]
      assertNonNull(wolf, 'kit must have a wolf')
      const wolfSeats = `${wolves.map((w) => w.seat).join('·')}号`

      await startNight(ctx)
      await completeNight(ctx, { wolfKillSeat: victim.seat, seerCheckSeat: wolf.seat })
      expect(await waitForTopPhaseIn(ctx.hostPage, ctx.gameId, ['DAY_DISCUSSION'])).toBe(
        'DAY_DISCUSSION',
      )

      await completeDay(ctx, testInfo, { targetSeat: seer.seat, label: 'review-seer-out' })
      expect(await waitForTopPhaseIn(ctx.hostPage, ctx.gameId, ['GAME_OVER'], 15_000)).toBe(
        'GAME_OVER',
      )

      await ctx.hostPage.waitForURL(/\/result\//, { timeout: 15_000 })
      await ctx.hostPage.getByTestId('open-review').click()
      await ctx.hostPage.waitForURL(/\/review\//)
      const lines = ctx.hostPage.locator('.rv-line')
      await expect(lines).toContainText([
        '第1夜',
        new RegExp(`🐺 狼人（${wolfSeats}） 刀 ${victim.seat}号 .*〔村民〕`),
        new RegExp(`🔮 预言家 ${seer.seat}号 验 ${wolf.seat}号 .* → 狼人`),
        new RegExp(`☀ 天亮 ${victim.seat}号 .*〔村民〕 被刀出局`),
        '第1天',
        new RegExp(`放逐${seer.seat}号 .*〔预言家〕 被投出（狼人冲票）`),
      ])
      // Every wolf voted the seer out: the seer's row shows their seats as red badges.
      await expect(
        ctx.hostPage.locator('.rv-tally-row').first().locator('.rv-seat-wolf'),
      ).toHaveText(wolves.map((w) => String(w.seat)))
      await captureSnapshot(ctx.pages, testInfo, 'game-review')
    } finally {
      await cleanupRoomData(ctx.hostPage, ctx.roomCode)
      await ctx.cleanup()
    }
  })
})
