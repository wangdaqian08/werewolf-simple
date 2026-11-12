/**
 * Gravekeeper (守墓人) — real-backend evidence.
 *
 *  N1: 首夜無信息 (FIRST_NIGHT).
 *  D1: everyone abstains, nobody is exiled.
 *  N2: 昨日無人被放逐 (NO_EXILE).
 *  D2: a werewolf is voted out.
 *  N3: the gravekeeper sees that seat as 狼人 (WOLF).
 *
 * The wolves 空刀 every night, so nobody dies at night and the game cannot end early.
 *
 * CI-vs-local design (memory feedback_e2e_ci_vs_local):
 *  - the gravekeeper acts through its own browser (DOM testids); every other
 *    role acts via act.sh — or as the host when the host rolled that role;
 *  - every action is gated on the backend sub-phase, never on a sleep;
 *  - the exiled wolf is a non-host wolf, so the host's page always stays usable;
 *  - timeouts scale 2× under CI; the backend log must show no REJECTED action.
 *
 * Leak scan: only the gravekeeper's page may ever receive `gravekeeperResult`.
 */
import { expect, type Page, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { actName, type BotInfo, type RoleName } from './helpers/shell-runner'
import { assertNonNull, completeDay, tryAct, waitForSubPhase } from './helpers/flow-drivers'
import { fetchGameState, waitForPhase } from './helpers/state-polling'
import { hostStartsNight } from './helpers/night-driver'
import { readBackendLogLineCount, readBackendLogSince } from './helpers/backend-log'
import { findNightOrderViolations } from './helpers/audio-manifest'
import { assertNoBrowserErrors } from './helpers/error-sentinel'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { cleanupRoomData } from './helpers/test-support'
import { findRoleLeaks, type PayloadLog, recordPayloads } from './helpers/role-leak'

// WITCH must stay in the list (setupGame role-toggle trap, see memory).
const ROLES: RoleName[] = ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD', 'GRAVEKEEPER']

const scale = (ms: number): number => (process.env.CI ? ms * 2 : ms)

function only(ctx: GameContext, role: RoleName): BotInfo {
  const holder = (ctx.roleMap[role] ?? [])[0]
  assertNonNull(holder, `board must have a ${role}`)
  return holder
}

async function reach(ctx: GameContext, subPhase: string): Promise<void> {
  expect(
    await waitForSubPhase(ctx.hostPage, ctx.gameId, subPhase, 25_000),
    `night sub-phase ${subPhase} reached`,
  ).toBe(true)
}

function bot(
  ctx: GameContext,
  action: string,
  actor: BotInfo,
  opts: { target?: number; payload?: string } = {},
) {
  const ok = tryAct(action, actName(actor), {
    target: opts.target === undefined ? undefined : String(opts.target),
    payload: opts.payload,
    room: ctx.roomCode,
  })
  expect(ok, `${action} by ${actor.nick} accepted`).toBe(true)
}

test.describe('Gravekeeper (real backend)', () => {
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

  test('gravekeeper sees 首夜無信息, then no exile, then the exiled wolf', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 600_000 : 300_000)

    const payloads = new Map<string, PayloadLog>()
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      noKillAllowed: true,
      roles: ROLES,
      browserRoles: ['GRAVEKEEPER'],
      onPage: (label, p) => payloads.set(label, recordPayloads(p)),
    })
    const game = ctx
    const logStart = readBackendLogLineCount()
    game.markBackendLogPosition()

    const gkPageOrNone = game.pages.get('GRAVEKEEPER')
    assertNonNull(gkPageOrNone, 'GRAVEKEEPER browser page required')
    const gkPage: Page = gkPageOrNone
    const seer = only(game, 'SEER')
    const witch = only(game, 'WITCH')
    const guard = only(game, 'GUARD')
    const gravekeeper = only(game, 'GRAVEKEEPER')
    const wolves = game.roleMap.WEREWOLF ?? []
    // The exiled wolf is never the host; another wolf keeps passing the knife on night 3.
    const exiled = wolves.find((w) => w.nick !== 'Host')
    assertNonNull(exiled, 'need a non-host werewolf')
    const knife = wolves.find((w) => w !== exiled)
    assertNonNull(knife, 'need a second werewolf')
    console.warn(
      `[gravekeeper] game=${game.gameId} host=${game.hostRole} gravekeeper=${gravekeeper.seat} ` +
        `exiled=${exiled.seat} knife=${knife.seat} seer=${seer.seat} witch=${witch.seat} guard=${guard.seat}`,
    )

    const result = gkPage.getByTestId('gravekeeper-result')

    /** One whole night; the gravekeeper's own step runs in its browser. */
    async function night(
      n: number,
      plan: { seerChecks: BotInfo; guardProtects: BotInfo; verdict: string; exiledSeat?: number },
    ): Promise<void> {
      await reach(game, 'WEREWOLF_PICK')
      bot(game, 'WOLF_SKIP', knife as BotInfo)

      await reach(game, 'WITCH_ACT')
      bot(game, 'WITCH_ACT', witch, { payload: '{"useAntidote":false}' })

      await reach(game, 'SEER_PICK')
      bot(game, 'SEER_CHECK', seer, { target: plan.seerChecks.seat })
      await reach(game, 'SEER_RESULT')
      bot(game, 'SEER_CONFIRM', seer)

      await reach(game, 'GRAVEKEEPER_RESULT')
      await expect(result).toHaveAttribute('data-verdict', plan.verdict, {
        timeout: scale(10_000),
      })
      if (plan.exiledSeat === undefined) {
        await expect(gkPage.getByTestId('gravekeeper-exiled')).toHaveCount(0)
      } else {
        await expect(result).toHaveAttribute('data-exiled-seat', String(plan.exiledSeat))
      }
      await captureSnapshot(game.pages, testInfo, `n${n}-gravekeeper-${plan.verdict}`)
      const state = await fetchGameState(game.hostPage, game.gameId)
      console.log(
        `[gravekeeper] night=${n} verdict=${plan.verdict} backend=${JSON.stringify({
          phase: state?.phase,
          subPhase: state?.nightPhase?.subPhase,
          aliveCount: state?.players?.filter((p) => p.isAlive).length,
        })}`,
      )
      await gkPage.getByTestId('gravekeeper-result-confirm').click()

      await reach(game, 'GUARD_PICK')
      bot(game, 'GUARD_PROTECT', guard, { target: plan.guardProtects.seat })
      expect(await waitForPhase(game.hostPage, game.gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)
    }

    // The guard never protects the same seat two nights running.
    await hostStartsNight(game)
    await night(1, { seerChecks: witch, guardProtects: guard, verdict: 'FIRST_NIGHT' })

    expect(await completeDay(game, testInfo, { targetSeat: null, label: 'gk-d1' })).toBe('NIGHT')
    await night(2, { seerChecks: guard, guardProtects: witch, verdict: 'NO_EXILE' })

    expect(await completeDay(game, testInfo, { targetSeat: exiled.seat, label: 'gk-d2' })).toBe(
      'NIGHT',
    )
    const afterVote = await fetchGameState(game.hostPage, game.gameId)
    expect(
      afterVote?.players?.find((p) => p.seatIndex === exiled.seat)?.isAlive,
      `the wolf at seat ${exiled.seat} was voted out`,
    ).toBe(false)
    await night(3, {
      seerChecks: gravekeeper,
      guardProtects: guard,
      verdict: 'WOLF',
      exiledSeat: exiled.seat,
    })

    // ── Leak scan: only the gravekeeper's page ever receives the result ──
    for (const [label, log] of payloads) {
      const role: string | null = label === 'HOST' ? game.hostRole : label
      assertNonNull(role, 'host role known')
      const pageOf: Page = label === 'HOST' ? game.hostPage : gkPage
      const me: string | null = await pageOf.evaluate(() => localStorage.getItem('userId'))
      assertNonNull(me, `${label} userId`)
      expect(log.bodies.length, `${label} recorded payloads`).toBeGreaterThan(0)
      expect(findRoleLeaks(log, me, role), `${label} (${role}) got a role`).toEqual([])
      const carriers = log.bodies.filter((b) => b.body.includes('"gravekeeperResult"'))
      if (role === 'GRAVEKEEPER') {
        expect(carriers.length, 'the gravekeeper received its result').toBeGreaterThan(0)
      } else {
        expect(
          carriers.map((b) => b.src),
          `${label} (${role}) must never receive gravekeeperResult`,
        ).toEqual([])
      }
      console.log(
        `[gravekeeper] leak-scan ${label}=${role} scanned=${log.bodies.length} carriers=${carriers.length}`,
      )
    }

    // ── Backend evidence ────────────────────────────────────────────────
    const lines = readBackendLogSince(logStart)
    const gameTag = new RegExp(`game=${game.gameId}(?=[\\s:,])`)
    expect(
      lines.filter((l) => l.includes('-> REJECTED') && gameTag.test(l)),
      'no action was rejected',
    ).toEqual([])
    // The step ran on all three nights, night 1 included, in handler order.
    expect(findNightOrderViolations(lines, game.gameId, 3, { gravekeeper: true })).toEqual([])
    await assertNoBrowserErrors(game.errors, testInfo)
    await game.assertNoBackendErrors(testInfo)
  })
})
