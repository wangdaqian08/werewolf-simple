/**
 * Mechanical wolf + medium — real-backend evidence.
 *
 *  N1: the mechanical wolf learns the WITCH (its result card says WITCH), and
 *      the medium, checking the mechanical wolf, sees WITCH too.
 *  N2: the learned poison is offered (skill grid, no learn grid); the
 *      mechanical wolf poisons villager V2, who is dead at dawn.
 *
 * The pack wolves attack V1 both nights; the witch saves V1 on N1 and the
 * guard protects V1 on N2, so the poison is the only death across both nights.
 *
 * CI-vs-local design (memory feedback_e2e_ci_vs_local):
 *  - the two roles under test act through their own browsers (DOM testids);
 *    the pack wolves, witch and guard act via act.sh — or as the host when the
 *    host rolled that role (roleMap carries the host as 'Host' → actName HOST);
 *  - every action is gated on the backend sub-phase, never on a sleep;
 *  - V1/V2 are non-host villagers, so the host's page always stays usable;
 *  - timeouts scale 2× under CI; the backend log must show no REJECTED action.
 *
 * Role-leak scan: every browser records what the server sends it; after N2 no
 * page holds another player's role except the owner's own medium/mech result.
 */
import { expect, type Page, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { actName, type BotInfo, type RoleName } from './helpers/shell-runner'
import { assertNonNull, completeDay, tryAct, waitForSubPhase } from './helpers/flow-drivers'
import { fetchGameState, waitForCondition, waitForPhase } from './helpers/state-polling'
import { hostStartsNight } from './helpers/night-driver'
import { readBackendLogLineCount, readBackendLogSince } from './helpers/backend-log'
import { findNightOrderViolations } from './helpers/audio-manifest'
import { assertNoBrowserErrors } from './helpers/error-sentinel'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { cleanupRoomData } from './helpers/test-support'
import { findRoleLeaks, type PayloadLog, recordPayloads } from './helpers/role-leak'

// WITCH must stay in the list (setupGame role-toggle trap, see memory).
const ROLES: RoleName[] = ['WEREWOLF', 'MECHANICAL_WOLF', 'VILLAGER', 'MEDIUM', 'WITCH', 'GUARD']

const scale = (ms: number): number => (process.env.CI ? ms * 2 : ms)

function only(ctx: GameContext, role: RoleName): BotInfo {
  const holder = (ctx.roleMap[role] ?? [])[0]
  assertNonNull(holder, `board must have a ${role}`)
  return holder
}

function page(ctx: GameContext, role: RoleName): Page {
  const p = ctx.pages.get(role)
  assertNonNull(p, `${role} browser page required`)
  return p
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
  opts: { target?: number; payload?: string },
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

test.describe('Mechanical wolf and medium (real backend)', () => {
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

  test('mech wolf learns witch and poisons; the medium sees the learned role', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 480_000 : 240_000)

    const payloads = new Map<string, PayloadLog>()
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ROLES,
      browserRoles: ['MECHANICAL_WOLF', 'MEDIUM'],
      onPage: (label, p) => payloads.set(label, recordPayloads(p)),
    })
    const logStart = readBackendLogLineCount()
    ctx.markBackendLogPosition()

    const mechPage = page(ctx, 'MECHANICAL_WOLF')
    const mediumPage = page(ctx, 'MEDIUM')
    const mech = only(ctx, 'MECHANICAL_WOLF')
    const wolf = only(ctx, 'WEREWOLF')
    const witch = only(ctx, 'WITCH')
    const guard = only(ctx, 'GUARD')
    const [v1, v2] = (ctx.roleMap.VILLAGER ?? []).filter((v) => v.nick !== 'Host')
    assertNonNull(v2, 'need two non-host villagers')
    console.warn(
      `[mech-medium] game=${ctx.gameId} host=${ctx.hostRole} mech=${mech.seat} wolf=${wolf.seat} ` +
        `witch=${witch.seat} guard=${guard.seat} v1=${v1.seat} v2=${v2.seat}`,
    )

    // ── Night 1 ──────────────────────────────────────────────────────────
    await hostStartsNight(ctx)
    await reach(ctx, 'WEREWOLF_PICK')
    bot(ctx, 'WOLF_KILL', wolf, { target: v1.seat })

    await reach(ctx, 'MECH_WOLF_PICK')
    const learnTarget = mechPage.getByTestId(`mech-learn-target-${witch.seat}`)
    await expect(learnTarget).toBeVisible({ timeout: scale(10_000) })
    await learnTarget.click()
    await mechPage.getByTestId('mech-learn-confirm').click()

    await reach(ctx, 'MECH_WOLF_RESULT')
    const learned = mechPage.getByTestId('mech-result')
    await expect(learned).toHaveAttribute('data-kind', 'LEARN', { timeout: scale(10_000) })
    await expect(learned).toHaveAttribute('data-role', 'WITCH')
    await captureSnapshot(ctx.pages, testInfo, 'n1-mech-learned-witch')
    await mechPage.getByTestId('mech-result-confirm').click()

    await reach(ctx, 'WITCH_ACT')
    bot(ctx, 'WITCH_ACT', witch, { payload: '{"useAntidote":true}' })

    await reach(ctx, 'MEDIUM_PICK')
    const checkTarget = mediumPage.getByTestId(`medium-target-${mech.seat}`)
    await expect(checkTarget).toBeVisible({ timeout: scale(10_000) })
    await checkTarget.click()
    await mediumPage.getByTestId('medium-confirm').click()

    await reach(ctx, 'MEDIUM_RESULT')
    const seen = mediumPage.getByTestId('medium-result')
    await expect(seen).toHaveAttribute('data-checked-seat', String(mech.seat), {
      timeout: scale(10_000),
    })
    await expect(seen).toHaveAttribute('data-role', 'WITCH')
    await captureSnapshot(ctx.pages, testInfo, 'n1-medium-sees-witch')
    await mediumPage.getByTestId('medium-result-confirm').click()

    await reach(ctx, 'GUARD_PICK')
    bot(ctx, 'GUARD_PROTECT', guard, { target: guard.seat })

    // ── Day 1: peaceful (V1 saved); everyone abstains → night 2 ──────────
    expect(await waitForPhase(ctx.hostPage, ctx.gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)
    expect(await completeDay(ctx, testInfo, { targetSeat: null, label: 'mech-medium-d1' })).toBe(
      'NIGHT',
    )
    expect(await isAlive(ctx, v1.seat), 'the witch saved V1 on N1').toBe(true)

    // ── Night 2 ──────────────────────────────────────────────────────────
    await reach(ctx, 'WEREWOLF_PICK')
    bot(ctx, 'WOLF_KILL', wolf, { target: v1.seat })

    await reach(ctx, 'MECH_WOLF_PICK')
    const poisonTarget = mechPage.getByTestId(`mech-skill-target-${v2.seat}`)
    await expect(poisonTarget, 'the learned poison is offered').toBeVisible({
      timeout: scale(10_000),
    })
    await expect(mechPage.locator('[data-testid^="mech-learn-target-"]')).toHaveCount(0)
    await captureSnapshot(ctx.pages, testInfo, 'n2-mech-poison-offered')
    await poisonTarget.click()
    await mechPage.getByTestId('mech-skill-confirm').click()

    await reach(ctx, 'MECH_WOLF_RESULT')
    await expect(mechPage.getByTestId('mech-result')).toHaveAttribute('data-kind', 'POISON', {
      timeout: scale(10_000),
    })
    await mechPage.getByTestId('mech-result-confirm').click()

    await reach(ctx, 'WITCH_ACT')
    bot(ctx, 'WITCH_ACT', witch, { payload: '{"useAntidote":false}' })

    await reach(ctx, 'MEDIUM_PICK')
    const recheck = mediumPage.getByTestId(`medium-target-${mech.seat}`)
    await expect(recheck).toBeVisible({ timeout: scale(10_000) })
    await recheck.click()
    await mediumPage.getByTestId('medium-confirm').click()
    await reach(ctx, 'MEDIUM_RESULT')
    const reseen = mediumPage.getByTestId('medium-result')
    await expect(reseen).toHaveAttribute('data-checked-seat', String(mech.seat), {
      timeout: scale(10_000),
    })
    await expect(reseen).toHaveAttribute('data-role', 'WITCH')
    await mediumPage.getByTestId('medium-result-confirm').click()

    await reach(ctx, 'GUARD_PICK')
    bot(ctx, 'GUARD_PROTECT', guard, { target: v1.seat })

    // ── Dawn 2: the poisoned villager is dead, the guarded one alive ─────
    expect(await waitForPhase(ctx.hostPage, ctx.gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)
    const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
    await expect(revealBtn).toBeVisible({ timeout: scale(15_000) })
    await revealBtn.click()
    await waitForCondition(
      async () => (await isAlive(ctx!, v2.seat)) === false,
      `poisoned villager (seat ${v2.seat}) is dead at dawn`,
      15_000,
    )
    expect(await isAlive(ctx, v1.seat), 'the guard protected V1 on N2').toBe(true)
    await captureSnapshot(ctx.pages, testInfo, 'd2-poisoned-villager-dead')

    // ── Role-leak scan: each page against its own player ────────────────
    for (const [label, log] of payloads) {
      const role: string | null = label === 'HOST' ? ctx.hostRole : label
      assertNonNull(role, 'host role known')
      const me: string | null = await (
        label === 'HOST' ? ctx.hostPage : page(ctx, label as RoleName)
      ).evaluate(() => localStorage.getItem('userId'))
      assertNonNull(me, `${label} userId`)
      expect(log.bodies.length, `${label} recorded payloads`).toBeGreaterThan(0)
      expect(findRoleLeaks(log, me, role), `${label} (${role}) got a role`).toEqual([])
      console.warn(`[mech-medium] leak-scan ${label}=${role} scanned=${log.bodies.length} leaks=0`)
    }
    // The exemption is exercised: each owner did receive its own result.
    const own = (role: RoleName, key: string) =>
      payloads.get(ctx!.hostRole === role ? 'HOST' : role)?.bodies.some((b) => b.body.includes(key))
    expect(own('MEDIUM', '"mediumResult"'), 'medium received mediumResult').toBe(true)
    expect(own('MECHANICAL_WOLF', '"mechWolf"'), 'mech wolf received mechWolf').toBe(true)

    // ── Backend evidence ────────────────────────────────────────────────
    const lines = readBackendLogSince(logStart)
    const gameTag = new RegExp(`game=${ctx.gameId}(?=[\\s:,])`)
    expect(
      lines.filter((l) => l.includes('-> REJECTED') && gameTag.test(l)),
      'no action was rejected',
    ).toEqual([])
    expect(
      findNightOrderViolations(lines, ctx.gameId, 2, { mechanicalWolf: true, medium: true }),
    ).toEqual([])
    await assertNoBrowserErrors(ctx.errors, testInfo)
    await ctx.assertNoBackendErrors(testInfo)
  })
})
