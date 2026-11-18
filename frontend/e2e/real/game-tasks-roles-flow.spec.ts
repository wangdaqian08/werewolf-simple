/**
 * 单局任务 batch 2 — real-backend evidence for three role tasks
 * (docs/superpowers/specs/2026-11-18-tasks-batch2-design.md): the game records the
 * events, settlement judges them, the result page and the review show them.
 *
 *  9 seats: 3 wolves, witch, hunter, knight, idiot, 2 villagers (the host is one);
 *  no sheriff. 9 seats because the board allows at most wolves + 1 gods, and these
 *  four gods need 3 wolves.
 *
 *  N1: the wolves knife the witch (she passes).
 *  D1: everyone votes the idiot out → the idiot reveals and stays alive.
 *  N2: the wolves knife the hunter.
 *  D2: at the reveal the hunter shoots wolf A; then the knight duels wolf B → B dies,
 *      no vote today, the host starts the night.
 *  N3: wolf C knifes the other villager.
 *  D3: wolf C is voted out → no wolves left, the village wins.
 *
 *  Why not a voted-out hunter's shot at the end: the game ends the moment a side
 *  wins, before any shot (VotingPipeline), so exiling the last god is 屠邊 for the
 *  wolves. The hunter shoots at its night-death reveal instead.
 *
 *  Pinned tasks and what each must end as:
 *   host (villager)  活到遊戲結束且好人獲勝     done
 *   hunter           開槍帶走一隻狼人           done, +10
 *   knight           決鬥翻出狼人               done
 *   idiot            被放逐翻牌後，好人陣營獲勝 done
 *   wolf C           狼人獲勝且自己存活         not done
 *
 * CI-vs-local (memory feedback_e2e_ci_vs_local): bots act through act.sh, every action
 * is gated on the backend phase, never a sleep; the host is never knifed or voted out;
 * the backend log must show no REJECTED action for this game.
 */
import { expect, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { actName, type BotInfo, getRoles, type RoleName } from './helpers/shell-runner'
import { assertNonNull, completeDay, completeNight, tryAct } from './helpers/flow-drivers'
import { hostStartsNight } from './helpers/night-driver'
import {
  fetchGameState,
  readHostUserId,
  waitForCondition,
  waitForDaySkipVoting,
  waitForDaySubPhase,
  waitForPhase,
} from './helpers/state-polling'
import { readBackendLogLineCount, readBackendLogSince } from './helpers/backend-log'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { cleanupRoomData, setTask, swapRoles } from './helpers/test-support'
import { assertNoBrowserErrors } from './helpers/error-sentinel'

// WITCH must stay in the list (setupGame role-toggle trap, see memory).
const ROLES: RoleName[] = ['WEREWOLF', 'VILLAGER', 'WITCH', 'HUNTER', 'KNIGHT', 'IDIOT']

const nonHost = (ctx: GameContext, role: RoleName): BotInfo[] =>
  (ctx.roleMap[role] ?? []).filter((b) => b.nick !== 'Host')

function only(ctx: GameContext, role: RoleName): BotInfo {
  const holder = nonHost(ctx, role)[0]
  assertNonNull(holder, `a ${role} bot`)
  return holder
}

async function isAlive(ctx: GameContext, seat: number): Promise<boolean | undefined> {
  const state = await fetchGameState(ctx.hostPage, ctx.gameId)
  return state?.players?.find((p) => p.seatIndex === seat)?.isAlive
}

test.describe('Game tasks batch 2 (real backend)', () => {
  let ctx: GameContext | undefined

  // eslint-disable-next-line no-empty-pattern
  test.afterEach(async ({}, testInfo) => {
    if (ctx && testInfo.status === 'failed') await attachCompositeOnFailure(ctx.pages, testInfo)
  })
  test.afterAll(async () => {
    if (!ctx) return
    await cleanupRoomData(ctx.hostPage, ctx.roomCode)
    await ctx.cleanup()
  })

  test('hunter shot, knight duel and idiot reveal are judged from the real game', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 600_000 : 300_000)

    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ROLES,
      browserRoles: [],
    })
    const game = ctx
    const gameId = Number(game.gameId)
    const logStart = readBackendLogLineCount()
    game.markBackendLogPosition()
    const hostId = await readHostUserId(game.hostPage)
    assertNonNull(hostId, 'host userId')

    // ── The host is a villager: never knifed, never voted out, always has a page ──
    if (game.hostRole !== 'VILLAGER') {
      const villagerBot = nonHost(game, 'VILLAGER')[0]
      assertNonNull(villagerBot, 'a dealt villager to swap with')
      await swapRoles(game.hostPage, gameId, hostId, villagerBot.userId)
      game.roleMap = getRoles(game.roomCode)
      game.hostRole = 'VILLAGER'
      game.isHostRole = (role) => role === 'VILLAGER'
    }
    const witch = only(game, 'WITCH')
    const hunter = only(game, 'HUNTER')
    const knight = only(game, 'KNIGHT')
    const idiot = only(game, 'IDIOT')
    const villager = only(game, 'VILLAGER')
    const [wolfA, wolfB, wolfC] = nonHost(game, 'WEREWOLF')
    assertNonNull(wolfA, 'wolf A')
    assertNonNull(wolfB, 'wolf B')
    assertNonNull(wolfC, 'wolf C')
    const hostSeat = (game.roleMap.VILLAGER ?? []).find((b) => b.userId === hostId)?.seat
    assertNonNull(hostSeat, 'host seat')
    console.warn(
      `[tasks2] game=${gameId} host=${hostSeat} witch=${witch.seat} hunter=${hunter.seat} ` +
        `knight=${knight.seat} idiot=${idiot.seat} villager=${villager.seat} ` +
        `wolves=${wolfA.seat},${wolfB.seat},${wolfC.seat}`,
    )

    // ── Pin every task whose outcome this game decides ──
    await setTask(game.hostPage, gameId, hostId, 'VILLAGER_SURVIVE_WIN')
    await setTask(game.hostPage, gameId, hunter.userId, 'HUNTER_SHOOT_WOLF')
    await setTask(game.hostPage, gameId, knight.userId, 'KNIGHT_DUEL_WOLF')
    await setTask(game.hostPage, gameId, idiot.userId, 'IDIOT_REVEAL_WIN')
    await setTask(game.hostPage, gameId, wolfC.userId, 'WOLF_WIN_ALIVE')

    await hostStartsNight(game)

    // ── N1 + D1: the witch falls; the idiot is voted out and reveals ──
    await completeNight(game, { wolfKillSeat: witch.seat, witch: { mode: 'pass' } })
    expect(await completeDay(game, testInfo, { targetSeat: idiot.seat, label: 'tasks2-d1' })).toBe(
      'NIGHT',
    )
    expect(await isAlive(game, witch.seat), 'the witch was knifed on N1').toBe(false)
    expect(await isAlive(game, idiot.seat), 'the revealed idiot stays alive').toBe(true)

    // ── N2 + D2: the knifed hunter shoots wolf A; the knight duels wolf B ──
    await completeNight(game, { wolfKillSeat: hunter.seat })
    expect(await waitForPhase(game.hostPage, game.gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)
    const revealBtn = game.hostPage.getByTestId('day-reveal-result')
    await expect(revealBtn).toBeVisible({ timeout: 15_000 })
    await revealBtn.click()
    // The reveal applies the knife and opens the hunter's private shot.
    expect(await waitForDaySubPhase(game.hostPage, game.gameId, 'RESULT_REVEALED', 15_000)).toBe(
      true,
    )
    expect(await isAlive(game, hunter.seat), 'the hunter was knifed on N2').toBe(false)
    expect(
      tryAct('HUNTER_SHOOT', actName(hunter), { target: String(wolfA.seat), room: game.roomCode }),
      'HUNTER_SHOOT accepted',
    ).toBe(true)
    await waitForCondition(
      async () => (await isAlive(game, wolfA.seat)) === false,
      'the hunter shot wolf A',
    )
    // The shot returns the day to RESULT_REVEALED: the duel window.
    expect(await waitForDaySubPhase(game.hostPage, game.gameId, 'RESULT_REVEALED', 15_000)).toBe(
      true,
    )
    expect(
      tryAct('KNIGHT_DUEL', actName(knight), { target: String(wolfB.seat), room: game.roomCode }),
      'KNIGHT_DUEL accepted',
    ).toBe(true)
    expect(await waitForDaySkipVoting(game.hostPage, game.gameId, 10_000), 'no vote today').toBe(
      true,
    )
    expect(await isAlive(game, wolfB.seat), 'the duel killed wolf B').toBe(false)
    await captureSnapshot(game.pages, testInfo, 'tasks2-d2-shot-and-duel')
    await game.hostPage.getByTestId('day-enter-night').click()
    expect(await waitForPhase(game.hostPage, game.gameId, 'NIGHT', 15_000)).toBe(true)

    // ── N3 + D3: wolf C knifes the villager, then is voted out → the village wins ──
    await completeNight(game, { wolfKillSeat: villager.seat })
    expect(await completeDay(game, testInfo, { targetSeat: wolfC.seat, label: 'tasks2-d3' })).toBe(
      'GAME_OVER',
    )
    const end = await fetchGameState(game.hostPage, game.gameId)
    expect(end?.phase).toBe('GAME_OVER')
    expect((end as { winner?: string } | null)?.winner).toBe('VILLAGER')

    // ── Result page: the host's own task is done ──
    await game.hostPage.waitForURL(/\/result\//, { timeout: 30_000 })
    const resultTask = game.hostPage.getByTestId('settlement').getByTestId('task-line')
    await expect(resultTask).toHaveAttribute('data-task', 'VILLAGER_SURVIVE_WIN')
    await expect(resultTask).toHaveAttribute('data-done', 'true')
    await captureSnapshot(new Map([['HOST', game.hostPage]]), testInfo, 'tasks2-result')

    // ── God's-eye review: every pinned task with its outcome ──
    await game.hostPage.goto(`/review/${gameId}`)
    await expect(game.hostPage.getByTestId('review-tasks')).toBeVisible({ timeout: 15_000 })
    const line = (seat: number) =>
      game.hostPage
        .getByTestId('review-tasks')
        .locator(`[data-testid="review-task"][data-seat="${seat}"]`)
        .getByTestId('task-line')
    const expectRow = async (seat: number, task: string, done: boolean, score?: string) => {
      await expect(line(seat), `seat ${seat} ${task}`).toHaveAttribute('data-task', task)
      await expect(line(seat), `seat ${seat} ${task}`).toHaveAttribute('data-done', String(done))
      if (score) await expect(line(seat).getByTestId('task-score')).toHaveText(score)
    }
    await expectRow(hostSeat, 'VILLAGER_SURVIVE_WIN', true)
    await expectRow(hunter.seat, 'HUNTER_SHOOT_WOLF', true, '+10 戰績分')
    await expectRow(knight.seat, 'KNIGHT_DUEL_WOLF', true)
    await expectRow(idiot.seat, 'IDIOT_REVEAL_WIN', true)
    await expectRow(wolfC.seat, 'WOLF_WIN_ALIVE', false)
    await expect(line(hunter.seat), 'the zh task name renders').toContainText('開槍帶走一隻狼人')
    await captureSnapshot(new Map([['HOST', game.hostPage]]), testInfo, 'tasks2-review')

    // ── Backend evidence ──
    const gameTag = new RegExp(`game=${game.gameId}(?=[\\s:,])`)
    expect(
      readBackendLogSince(logStart).filter((l) => l.includes('-> REJECTED') && gameTag.test(l)),
      'no action was rejected',
    ).toEqual([])
    await assertNoBrowserErrors(game.errors, testInfo)
    await game.assertNoBackendErrors(testInfo)
  })
})
