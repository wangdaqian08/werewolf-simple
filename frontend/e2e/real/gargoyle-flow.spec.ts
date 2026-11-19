/**
 * Gargoyle (石像鬼) — real-backend evidence for docs/superpowers/specs/2026-11-17-gargoyle-design.md.
 *
 *  N1: the gargoyle checks the seer and sees 預言家 (G3); its turn opens the night (G9).
 *  D1: pack wolf A is voted out.
 *  N2: the seer is greyed out (G4, whole game); it checks pack wolf B and sees 狼人,
 *      but B never becomes its teammate (G13).
 *  D2: pack wolf B is voted out.
 *  N3: it checks a villager, then — the pack gone — makes the kill itself (G8).
 *  D3: the gargoyle is voted out; the review lists its checks (G15).
 *
 *
 * CI-vs-local design (memory feedback_e2e_ci_vs_local):
 *  - the gargoyle acts through its own browser (DOM testids); every other role acts
 *    via act.sh — or as the host when the host rolled that role;
 *  - the host is never a pack wolf (swapped to a villager), so it is never voted out
 *    before the end; if the host rolled the gargoyle, the host page is the gargoyle page;
 *  - every action is gated on the backend sub-phase, never on a sleep;
 *  - timeouts scale 2× under CI; the backend log must show no REJECTED action.
 *
 * Leak scan (before game over): only the gargoyle's page may receive `gargoyle`.
 */
import { expect, type Page, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { actName, type BotInfo, getRoles, type RoleName } from './helpers/shell-runner'
import { assertNonNull, completeDay, tryAct, waitForSubPhase } from './helpers/flow-drivers'
import { fetchGameState, waitForPhase } from './helpers/state-polling'
import { hostStartsNight } from './helpers/night-driver'
import { readBackendLogLineCount, readBackendLogSince } from './helpers/backend-log'
import { findNightOrderViolations } from './helpers/audio-manifest'
import { assertNoBrowserErrors } from './helpers/error-sentinel'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { cleanupRoomData, swapRoles } from './helpers/test-support'
import { findRoleLeaks, type PayloadLog, recordPayloads } from './helpers/role-leak'

// WITCH must stay in the list (setupGame role-toggle trap, see memory).
const ROLES: RoleName[] = ['WEREWOLF', 'GARGOYLE', 'VILLAGER', 'SEER', 'WITCH', 'GUARD']

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

async function isAlive(ctx: GameContext, seat: number): Promise<boolean | undefined> {
  const state = await fetchGameState(ctx.hostPage, ctx.gameId)
  return state?.players?.find((p) => p.seatIndex === seat)?.isAlive
}

test.describe('Gargoyle (real backend)', () => {
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

  test('gargoyle checks exact roles, never twice, and makes the kill once the pack is out', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 720_000 : 360_000)

    const payloads = new Map<string, PayloadLog>()
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      noKillAllowed: true,
      roles: ROLES,
      browserRoles: ['GARGOYLE'],
      onPage: (label, p) => payloads.set(label, recordPayloads(p)),
    })
    const game = ctx
    const logStart = readBackendLogLineCount()
    game.markBackendLogPosition()

    // ── The host is never a pack wolf: both pack wolves are voted out mid-game ──
    if (game.hostRole === 'WEREWOLF') {
      const hostId = await game.hostPage.evaluate(() => localStorage.getItem('userId'))
      assertNonNull(hostId, 'host userId')
      const villagerBot = (game.roleMap.VILLAGER ?? []).find((v) => v.nick !== 'Host')
      assertNonNull(villagerBot, 'a dealt villager to swap with')
      await swapRoles(game.hostPage, Number(game.gameId), hostId, villagerBot.userId)
      game.roleMap = getRoles(game.roomCode)
      game.hostRole = 'VILLAGER'
      game.isHostRole = (role) => role === 'VILLAGER'
    }

    const gPageOrNone = game.pages.get('GARGOYLE')
    assertNonNull(gPageOrNone, 'GARGOYLE browser page required')
    const gPage: Page = gPageOrNone
    const gargoyle = only(game, 'GARGOYLE')
    const seer = only(game, 'SEER')
    const witch = only(game, 'WITCH')
    const guard = only(game, 'GUARD')
    const [wolfA, wolfB] = game.roleMap.WEREWOLF ?? []
    assertNonNull(wolfB, 'two pack wolves')
    const villagers = (game.roleMap.VILLAGER ?? []).filter((v) => v.nick !== 'Host')
    const [checked, victim] = villagers
    assertNonNull(victim, 'two non-host villagers')
    console.warn(
      `[gargoyle] game=${game.gameId} host=${game.hostRole} gargoyle=${gargoyle.seat} ` +
        `wolfA=${wolfA?.seat} wolfB=${wolfB.seat} seer=${seer.seat} witch=${witch.seat} ` +
        `guard=${guard.seat} checked=${checked?.seat} victim=${victim.seat}`,
    )

    const turn = gPage.getByTestId('gargoyle-turn')
    const result = gPage.getByTestId('gargoyle-result')

    /** The gargoyle's own turn (first of the night): check [target], expect [role]. */
    async function gargoyleChecks(n: number, target: BotInfo, role: string, greyed: number[]) {
      await reach(game, 'GARGOYLE_PICK')
      await expect(turn).toBeVisible({ timeout: scale(10_000) })
      for (const seat of greyed) {
        await expect(
          gPage.getByTestId(`gargoyle-target-${seat}`),
          `seat ${seat} was checked before (G4)`,
        ).toBeDisabled()
      }
      await expect(gPage.getByTestId(`gargoyle-target-${gargoyle.seat}`), 'no self').toBeDisabled()
      await gPage.getByTestId(`gargoyle-target-${target.seat}`).click()
      await gPage.getByTestId('gargoyle-confirm').click()
      await reach(game, 'GARGOYLE_RESULT')
      await expect(result).toHaveAttribute('data-kind', 'CHECK', { timeout: scale(10_000) })
      await expect(result).toHaveAttribute('data-role', role)
      await expect(result).toHaveAttribute('data-checked-seat', String(target.seat))
      await captureSnapshot(game.pages, testInfo, `n${n}-gargoyle-${role}`)
      await gPage.getByTestId('gargoyle-result-confirm').click()
    }

    /** The rest of a night by bots; the wolf turn is passed in. */
    async function restOfNight(plan: { seerChecks: BotInfo; guardProtects: BotInfo }) {
      await reach(game, 'WITCH_ACT')
      bot(game, 'WITCH_ACT', witch, { payload: '{"useAntidote":false}' })
      await reach(game, 'SEER_PICK')
      bot(game, 'SEER_CHECK', seer, { target: plan.seerChecks.seat })
      await reach(game, 'SEER_RESULT')
      bot(game, 'SEER_CONFIRM', seer)
      await reach(game, 'GUARD_PICK')
      bot(game, 'GUARD_PROTECT', guard, { target: plan.guardProtects.seat })
      expect(await waitForPhase(game.hostPage, game.gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)
    }

    // ── N1 ──
    await hostStartsNight(game)
    await gargoyleChecks(1, seer, 'SEER', [])
    await reach(game, 'WEREWOLF_PICK')
    bot(game, 'WOLF_SKIP', wolfA as BotInfo)
    await restOfNight({ seerChecks: witch, guardProtects: guard })

    expect(await completeDay(game, testInfo, { targetSeat: wolfA!.seat, label: 'g-d1' })).toBe(
      'NIGHT',
    )
    expect(await isAlive(game, wolfA!.seat), 'pack wolf A voted out').toBe(false)

    // ── N2: the seer stays greyed; checking a pack wolf adds no teammate ──
    await gargoyleChecks(2, wolfB, 'WEREWOLF', [seer.seat])
    const mine = await fetchGameState(gPage, game.gameId)
    expect(mine?.wolfTeammates ?? [], 'G13: a checked wolf is not a teammate').toEqual([])
    await reach(game, 'WEREWOLF_PICK')
    bot(game, 'WOLF_SKIP', wolfB)
    await restOfNight({ seerChecks: guard, guardProtects: witch })

    expect(await completeDay(game, testInfo, { targetSeat: wolfB.seat, label: 'g-d2' })).toBe(
      'NIGHT',
    )
    expect(await isAlive(game, wolfB.seat), 'pack wolf B voted out').toBe(false)

    // ── N3: the pack is gone; the gargoyle checks first, then kills (G8, 先驗後刀) ──
    await gargoyleChecks(3, checked as BotInfo, 'VILLAGER', [seer.seat, wolfB.seat])
    await reach(game, 'WEREWOLF_PICK')
    const victimSlot = gPage.locator(`.player-grid [data-seat="${victim.seat}"]`)
    await expect(victimSlot, 'the lone gargoyle gets the kill grid').toBeVisible({
      timeout: scale(10_000),
    })
    await victimSlot.click()
    await gPage.getByTestId('wolf-confirm-kill').click()
    await captureSnapshot(game.pages, testInfo, 'n3-gargoyle-kill')
    await restOfNight({ seerChecks: checked as BotInfo, guardProtects: guard })
    // Night deaths apply at the host's reveal, which completeDay (D3) performs.

    // ── Leak scan before game over: only the gargoyle's page gets `gargoyle` ──
    for (const [label, log] of payloads) {
      const role: string | null = label === 'HOST' ? game.hostRole : label
      assertNonNull(role, 'host role known')
      const pageOf: Page = label === 'HOST' ? game.hostPage : gPage
      const me: string | null = await pageOf.evaluate(() => localStorage.getItem('userId'))
      assertNonNull(me, `${label} userId`)
      expect(log.bodies.length, `${label} recorded payloads`).toBeGreaterThan(0)
      expect(findRoleLeaks(log, me, role), `${label} (${role}) got a role`).toEqual([])
      const carriers = log.bodies.filter((b) => b.body.includes('"gargoyle":{'))
      if (role === 'GARGOYLE') {
        expect(carriers.length, 'the gargoyle received its state').toBeGreaterThan(0)
        // The exemption is exercised: scanned as a villager, its own results are role leaks.
        expect(findRoleLeaks(log, me, 'VILLAGER').length, 'own results recorded').toBeGreaterThan(0)
      } else {
        expect(
          carriers.map((b) => b.src),
          `${label} (${role}) must never receive the gargoyle state`,
        ).toEqual([])
      }
      console.log(
        `[gargoyle] leak-scan ${label}=${role} scanned=${log.bodies.length} carriers=${carriers.length}`,
      )
    }

    // ── D3: the gargoyle is voted out → good wins; the review lists its checks ──
    await completeDay(game, testInfo, { targetSeat: gargoyle.seat, label: 'g-d3' })
    expect(await waitForPhase(game.hostPage, game.gameId, 'GAME_OVER', 30_000), 'game over').toBe(
      true,
    )
    expect(await isAlive(game, victim.seat), 'G8: the lone gargoyle killed the victim on N3').toBe(
      false,
    )
    await game.hostPage.goto(`/review/${game.gameId}`)
    const review = game.hostPage.getByTestId('game-review')
    await expect(review).toBeVisible({ timeout: scale(10_000) })
    // G15: one row per night, "🗿 石像鬼 N號 驗 M號 暱稱〔身份〕", with the exact role it was told.
    const row = (seat: number, role: string) =>
      new RegExp(
        `🗿\\s*石像鬼\\s*(?<!\\d)${gargoyle.seat}號\\s*驗\\s*(?<!\\d)${seat}號[^〔\\n]*〔${role}〕`,
      )
    await expect(review, 'N1: the seer').toContainText(row(seer.seat, '預言家'))
    await expect(review, 'N2: pack wolf B').toContainText(row(wolfB.seat, '狼人'))
    await expect(review, 'N3: a villager').toContainText(row((checked as BotInfo).seat, '村民'))
    await captureSnapshot(game.pages, testInfo, 'review')

    // ── Backend evidence ──
    const lines = readBackendLogSince(logStart)
    const gameTag = new RegExp(`game=${game.gameId}(?=[\\s:,])`)
    expect(
      lines.filter((l) => l.includes('-> REJECTED') && gameTag.test(l)),
      'no action was rejected',
    ).toEqual([])
    // G9 / G11: the gargoyle's cues open every night, in handler order.
    expect(findNightOrderViolations(lines, game.gameId, 3, { gargoyle: true })).toEqual([])
    await assertNoBrowserErrors(game.errors, testInfo)
    await game.assertNoBackendErrors(testInfo)
  })
})
