/**
 * 单局任务 — real-backend evidence, with every task pinned so the outcome is known.
 *
 *  9 seats: 3 wolves (the host is one), seer, witch, guard, 3 villagers; no sheriff.
 *  The wolves knife a villager each night; the seer checks a wolf each night; the
 *  witch and guard pass; every day the table abstains. Night 3 takes the last
 *  villager: 屠邊, the wolves win.
 *
 *  Pinned tasks and what each must end as:
 *   host (wolf)  狼人獲勝且自己存活      done, +20
 *   wolf bot     整局不被放逐出局        done
 *   seer         活著的每一晚都查驗到狼人 done, 3 nights × 10 = +30
 *   villager     活到遊戲結束且好人獲勝  not done
 *   witch        用解藥救下一名好人      not done
 *
 *  Also checked in the browser:
 *   - the host's role reveal card and in-game role card show its task;
 *   - until the game ends, nothing the host's page receives names another seat's task.
 *
 * CI-vs-local (memory feedback_e2e_ci_vs_local): bots act through act.sh, every step is
 * gated on the backend phase, never a sleep; the host is never knifed or voted out.
 */
import { expect, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { type BotInfo, getRoles, type RoleName } from './helpers/shell-runner'
import { assertNonNull, completeDay, completeNight } from './helpers/flow-drivers'
import { hostStartsNight } from './helpers/night-driver'
import { fetchGameState, readHostUserId, waitForPhaseIn } from './helpers/state-polling'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { cleanupRoomData, setTask, swapRoles } from './helpers/test-support'
import { type PayloadLog, recordPayloads } from './helpers/role-leak'
import { assertNoBrowserErrors } from './helpers/error-sentinel'

// WITCH must stay in the list (setupGame role-toggle trap, see memory).
const ROLES: RoleName[] = ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD']

const nonHost = (ctx: GameContext, role: RoleName): BotInfo[] =>
  (ctx.roleMap[role] ?? []).filter((b) => b.nick !== 'Host')

test.describe('Game tasks (real backend)', () => {
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

  test('pinned tasks are private while playing and judged as expected at the end', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 600_000 : 300_000)

    let hostLog: PayloadLog | undefined
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      guardSkipAllowed: true,
      roles: ROLES,
      browserRoles: [],
      onPage: (label, p) => {
        if (label === 'HOST') hostLog = recordPayloads(p)
      },
    })
    const game = ctx
    const gameId = Number(game.gameId)
    const hostId = await readHostUserId(game.hostPage)
    assertNonNull(hostId, 'host userId')

    // ── The host is a wolf, so it is never knifed and always has a page ──
    if (game.hostRole !== 'WEREWOLF') {
      const wolfBot = nonHost(game, 'WEREWOLF')[0]
      assertNonNull(wolfBot, 'a dealt wolf to swap with')
      await swapRoles(game.hostPage, gameId, hostId, wolfBot.userId)
      game.roleMap = getRoles(game.roomCode)
      game.hostRole = 'WEREWOLF'
      game.isHostRole = (role) => role === 'WEREWOLF'
    }
    const wolfBot = nonHost(game, 'WEREWOLF')[0]
    const seer = nonHost(game, 'SEER')[0]
    const witch = nonHost(game, 'WITCH')[0]
    const villagers = nonHost(game, 'VILLAGER')
    assertNonNull(wolfBot, 'a second wolf')
    assertNonNull(seer, 'a seer bot')
    assertNonNull(witch, 'a witch bot')
    expect(villagers, 'three villagers').toHaveLength(3)
    // completeNight resolves a seer target among the bots, never the host, so check bot wolves.
    const wolfBotSeats = nonHost(game, 'WEREWOLF').map((b) => b.seat)
    const hostSeat = (game.roleMap.WEREWOLF ?? []).find((b) => b.userId === hostId)?.seat
    assertNonNull(hostSeat, 'host seat')

    // ── Pin every task whose outcome this game decides ──
    await setTask(game.hostPage, gameId, hostId, 'WOLF_WIN_ALIVE')
    await setTask(game.hostPage, gameId, wolfBot.userId, 'WOLF_NEVER_EXILED')
    await setTask(game.hostPage, gameId, seer.userId, 'SEER_WOLF_EVERY_NIGHT')
    await setTask(game.hostPage, gameId, villagers[0]!.userId, 'VILLAGER_SURVIVE_WIN')
    await setTask(game.hostPage, gameId, witch.userId, 'WITCH_SAVE_GOOD')
    const othersTasks = [
      'WOLF_NEVER_EXILED',
      'SEER_WOLF_EVERY_NIGHT',
      'VILLAGER_SURVIVE_WIN',
      'WITCH_SAVE_GOOD',
    ]
    console.warn(
      `[tasks] game=${gameId} host=${hostSeat} wolf=${wolfBot.seat} seer=${seer.seat} witch=${witch.seat} ` +
        `villagers=${villagers.map((v) => v.seat).join(',')}`,
    )

    // Scan from here: before pinning, the host still held its randomly dealt task, which
    // may share a code with a task pinned on another seat.
    assertNonNull(hostLog, 'host payloads recorded')
    const scanFrom = hostLog.bodies.length

    // ── Role reveal: the card shows the host's own task, still to be played ──
    // GameView's confirmed flag is local, so a reload brings the card back.
    await game.hostPage.reload()
    await game.hostPage.getByTestId('reveal-role-btn').click()
    await expect(game.hostPage.getByTestId('role-reveal-card')).toHaveAttribute(
      'data-role',
      'WEREWOLF',
    )
    const revealTask = game.hostPage.getByTestId('task-line')
    await expect(revealTask).toHaveAttribute('data-task', 'WOLF_WIN_ALIVE')
    await expect(revealTask).toHaveAttribute('data-done', 'pending')
    await expect(revealTask.getByTestId('task-score')).toHaveText('+20 戰績分')
    await captureSnapshot(new Map([['HOST', game.hostPage]]), testInfo, '01-reveal-task')
    await game.hostPage.getByTestId('confirm-role-btn').click()

    await hostStartsNight(game)

    // ── Three nights: a villager falls each night, the seer finds a wolf each night ──
    for (let n = 1; n <= 3; n++) {
      await completeNight(game, {
        wolfKillSeat: villagers[n - 1]!.seat,
        seerCheckSeat: wolfBotSeats[(n - 1) % wolfBotSeats.length]!,
        witch: { mode: 'pass' },
        guard: { mode: 'skip' },
      })
      const phase = await waitForPhaseIn(game.hostPage, game.gameId, [
        'DAY_DISCUSSION',
        'GAME_OVER',
      ])
      console.log(`[tasks] night=${n} → ${phase}`)
      if (phase === 'GAME_OVER') break
      if (n === 1) {
        // The in-game role card carries the same task.
        await game.hostPage.getByTestId('my-role-chip').click()
        const cardTask = game.hostPage.getByTestId('role-card-sheet').getByTestId('task-line')
        await expect(cardTask).toHaveAttribute('data-task', 'WOLF_WIN_ALIVE')
        await expect(cardTask).toHaveAttribute('data-done', 'pending')
        await game.hostPage.getByTestId('role-card-close').click()
      }
      expect(await completeDay(game, testInfo, { targetSeat: null, label: `tasks-d${n}` })).toBe(
        'NIGHT',
      )
    }

    const end = await fetchGameState(game.hostPage, game.gameId)
    expect(end?.phase).toBe('GAME_OVER')
    expect((end as { winner?: string } | null)?.winner).toBe('WEREWOLF')

    // ── Privacy: until now the host's page never received another seat's task ──
    const scanned = hostLog.bodies.slice(scanFrom)
    expect(scanned.length, 'host payloads after pinning').toBeGreaterThan(0)
    const leaks = scanned.filter((b) => othersTasks.some((t) => b.body.includes(`"${t}"`)))
    expect(
      leaks.map((b) => b.src),
      "no other seat's task reached the host",
    ).toEqual([])

    // ── Result page: the host's task is done and paid in 戰績分 only ──
    await game.hostPage.waitForURL(/\/result\//, { timeout: 30_000 })
    const settlement = game.hostPage.getByTestId('settlement')
    const resultTask = settlement.getByTestId('task-line')
    await expect(resultTask).toHaveAttribute('data-task', 'WOLF_WIN_ALIVE')
    await expect(resultTask).toHaveAttribute('data-done', 'true')
    await expect(resultTask.getByTestId('task-score')).toHaveText('+20 戰績分')
    const digits = async (id: string) =>
      Number(((await settlement.getByTestId(id).textContent()) ?? '').replace(/\D/g, ''))
    const coins = await digits('my-coins')
    expect(await digits('my-earned'), '戰績分 = coins + 20').toBe(coins + 20)
    await captureSnapshot(new Map([['HOST', game.hostPage]]), testInfo, '02-result-task')

    // ── God's-eye review: every pinned task with its outcome ──
    await game.hostPage.goto(`/review/${gameId}`)
    await expect(game.hostPage.getByTestId('review-tasks')).toBeVisible({ timeout: 15_000 })
    const row = (seat: number) =>
      game.hostPage
        .getByTestId('review-tasks')
        .locator(`[data-testid="review-task"][data-seat="${seat}"]`)
    const expectRow = async (seat: number, task: string, done: boolean, score?: string) => {
      const line = row(seat).getByTestId('task-line')
      await expect(line, `seat ${seat} ${task}`).toHaveAttribute('data-task', task)
      await expect(line, `seat ${seat} ${task}`).toHaveAttribute('data-done', String(done))
      if (score) await expect(line.getByTestId('task-score')).toHaveText(score)
    }
    await expectRow(hostSeat, 'WOLF_WIN_ALIVE', true, '+20 戰績分')
    await expectRow(wolfBot.seat, 'WOLF_NEVER_EXILED', true)
    await expectRow(seer.seat, 'SEER_WOLF_EVERY_NIGHT', true, '+30 戰績分')
    await expectRow(villagers[0]!.seat, 'VILLAGER_SURVIVE_WIN', false)
    await expectRow(witch.seat, 'WITCH_SAVE_GOOD', false)
    await captureSnapshot(new Map([['HOST', game.hostPage]]), testInfo, '03-review-tasks')

    await assertNoBrowserErrors(game.errors, testInfo)
    await game.assertNoBackendErrors(testInfo)
  })
})
