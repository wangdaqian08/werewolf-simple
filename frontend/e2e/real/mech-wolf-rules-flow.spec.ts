/**
 * Mechanical wolf rules the learn→poison spec (mech-wolf-medium-flow) does
 * not reach — real-backend evidence. Rule ids are from
 * docs/superpowers/specs/2026-11-03-mech-wolf-medium-design.md.
 *
 * Boards are 9 players: a special wolf needs ≥ 9 players and wolfCount ≥
 * specials + 2 (RoomSetupPanel), so the pack is two wolves plus the mech.
 *
 * A. Lone kill + learned medium (9p: 2 wolves, mech, medium, witch, 4 villagers)
 *    N1  W1  the mech sees no kill UI while the pack is alive;
 *        W4  it may skip the learn — and still walks MECH_WOLF_RESULT;
 *        M3  the medium checking the unlearned mech sees MECHANICAL_WOLF;
 *            the witch poisons one pack wolf.
 *    D1      the village votes the other pack wolf out.
 *    N2  W6  the mech is the lone killer and kills from its own browser;
 *        W4  it learns the medium;
 *        M3  the medium now sees MEDIUM (learned earlier the same night).
 *    D2  W3  the pack is dead but the game goes on: the mech counts as a wolf.
 *    N3  W5  the learned medium skill: an exact-role check, from the next night.
 *    D3  W3  voting the mech out ends the game — villagers win.
 *
 * B. Seer board (9p: 2 wolves, mech, seer, witch, 4 villagers)
 *    N1  M4  the seer sees the unlearned mech as a wolf;
 *    N2  M4  …and still as a wolf once it has learned SEER;
 *    N3  W5  the learned seer skill: an alignment check reads the pack wolf as a wolf.
 *
 * CI-vs-local design (memory feedback_e2e_ci_vs_local): the roles under test
 * act through their own browsers (testids) once that browser renders the
 * sub-phase; the pack wolf and the witch act via act.sh (or as the host when
 * the host rolled that role); every step is gated on the backend sub-phase,
 * never on a sleep; kill targets are non-host villagers so the host page
 * always stays usable; timeouts scale 2× on CI; no action may be REJECTED.
 */
import { expect, type Page, test, type TestInfo } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { actName, type BotInfo, type RoleName } from './helpers/shell-runner'
import { assertNonNull, completeDay, tryAct, waitForSubPhase } from './helpers/flow-drivers'
import { fetchGameState, waitForPhaseIn, waitForUiSubPhase } from './helpers/state-polling'
import { hostStartsNight } from './helpers/night-driver'
import { readBackendLogLineCount, readBackendLogSince } from './helpers/backend-log'
import { findNightOrderViolations, type NightBoard } from './helpers/audio-manifest'
import { assertNoBrowserErrors } from './helpers/error-sentinel'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { cleanupRoomData } from './helpers/test-support'

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

/** The two pack wolves; the first is a bot when possible (it is poisoned / targeted by userId). */
function packWolves(ctx: GameContext): [BotInfo, BotInfo] {
  const wolves = [...(ctx.roleMap.WEREWOLF ?? [])].sort((a, b) =>
    Number(a.nick === 'Host') - Number(b.nick === 'Host'),
  )
  assertNonNull(wolves[1], 'board must have two pack wolves')
  return [wolves[0]!, wolves[1]]
}

/** Three non-host villagers: one wolf-kill target per night. */
function villagers(ctx: GameContext): [BotInfo, BotInfo, BotInfo] {
  const [v1, v2, v3] = (ctx.roleMap.VILLAGER ?? []).filter((v) => v.nick !== 'Host')
  assertNonNull(v3, 'need three non-host villagers')
  return [v1!, v2!, v3]
}

async function reach(ctx: GameContext, subPhase: string): Promise<void> {
  expect(
    await waitForSubPhase(ctx.hostPage, ctx.gameId, subPhase, 25_000),
    `night sub-phase ${subPhase} reached`,
  ).toBe(true)
}

function bot(ctx: GameContext, action: string, actor: BotInfo, opts: { target?: number; payload?: string }) {
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

async function witchPasses(ctx: GameContext, witch: BotInfo): Promise<void> {
  await reach(ctx, 'WITCH_ACT')
  bot(ctx, 'WITCH_ACT', witch, { payload: '{"useAntidote":false}' })
}

/** The mechanical wolf's own turn, driven on its browser, ending on the result card. */
async function mechTurn(
  ctx: GameContext,
  mechPage: Page,
  pick: { grid: 'learn' | 'skill'; targetSeat: number } | 'skip',
  expected: { kind: string; role?: string; wolf?: boolean },
): Promise<void> {
  await reach(ctx, 'MECH_WOLF_PICK')
  await waitForUiSubPhase(mechPage, 'MECH_WOLF_PICK')
  if (pick === 'skip') {
    await mechPage.getByTestId('mech-skip').click()
  } else {
    await mechPage.getByTestId(`mech-${pick.grid}-target-${pick.targetSeat}`).click()
    await mechPage.getByTestId(`mech-${pick.grid}-confirm`).click()
  }
  await reach(ctx, 'MECH_WOLF_RESULT')
  const result = mechPage.getByTestId('mech-result')
  await expect(result).toHaveAttribute('data-kind', expected.kind, { timeout: scale(10_000) })
  if (expected.role !== undefined) await expect(result).toHaveAttribute('data-role', expected.role)
  if (expected.wolf) await expect(result).toHaveClass(/mt-card-wolf/)
  await mechPage.getByTestId('mech-result-confirm').click()
}

/** W6: the lone mechanical wolf kills in the normal wolf turn, from its own browser. */
async function mechKills(ctx: GameContext, mechPage: Page, seat: number): Promise<void> {
  await reach(ctx, 'WEREWOLF_PICK')
  await waitForUiSubPhase(mechPage, 'WEREWOLF_PICK')
  await mechPage.locator(`.player-grid [data-seat="${seat}"]`).click()
  await mechPage.getByTestId('wolf-confirm-kill').click()
}

async function mediumChecks(
  ctx: GameContext,
  mediumPage: Page,
  targetSeat: number,
  expectedRole: string,
): Promise<void> {
  await reach(ctx, 'MEDIUM_PICK')
  await waitForUiSubPhase(mediumPage, 'MEDIUM_PICK')
  await mediumPage.getByTestId(`medium-target-${targetSeat}`).click()
  await mediumPage.getByTestId('medium-confirm').click()
  await reach(ctx, 'MEDIUM_RESULT')
  const seen = mediumPage.getByTestId('medium-result')
  await expect(seen).toHaveAttribute('data-checked-seat', String(targetSeat), {
    timeout: scale(10_000),
  })
  await expect(seen).toHaveAttribute('data-role', expectedRole)
  await mediumPage.getByTestId('medium-result-confirm').click()
}

async function seerChecks(
  ctx: GameContext,
  seerPage: Page,
  targetSeat: number,
  expectedAlignment: 'wolf' | 'village',
): Promise<void> {
  await reach(ctx, 'SEER_PICK')
  await waitForUiSubPhase(seerPage, 'SEER_PICK')
  await seerPage.locator(`.player-grid [data-seat="${targetSeat}"]`).click()
  await seerPage.getByTestId('seer-check').click()
  await reach(ctx, 'SEER_RESULT')
  const card = seerPage.getByTestId('seer-result-card')
  await expect(card).toHaveAttribute('data-checked-seat', String(targetSeat), {
    timeout: scale(10_000),
  })
  await expect(card).toHaveAttribute('data-alignment', expectedAlignment)
  await seerPage.getByTestId('seer-done').click()
}

async function dawn(ctx: GameContext): Promise<string> {
  return waitForPhaseIn(ctx.hostPage, ctx.gameId, ['DAY_DISCUSSION', 'GAME_OVER'])
}

async function assertBackendEvidence(
  ctx: GameContext,
  logStart: number,
  nights: number,
  board: NightBoard,
  testInfo: TestInfo,
): Promise<void> {
  const lines = readBackendLogSince(logStart)
  const gameTag = new RegExp(`game=${ctx.gameId}(?=[\\s:,])`)
  expect(
    lines.filter((l) => l.includes('-> REJECTED') && gameTag.test(l)),
    'no action was rejected',
  ).toEqual([])
  expect(findNightOrderViolations(lines, ctx.gameId, nights, board)).toEqual([])
  await assertNoBrowserErrors(ctx.errors, testInfo)
  await ctx.assertNoBackendErrors(testInfo)
}

test.describe('Mechanical wolf rules (real backend)', () => {
  let ctx: GameContext | undefined

  // eslint-disable-next-line no-empty-pattern
  test.afterEach(async ({}, testInfo) => {
    if (!ctx) return
    if (testInfo.status === 'failed') await attachCompositeOnFailure(ctx.pages, testInfo)
    await cleanupRoomData(ctx.hostPage, ctx.roomCode)
    await ctx.cleanup()
    ctx = undefined
  })

  test('lone kill, learned medium, and the mech counts as a wolf until it is voted out', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 600_000 : 300_000)

    // WITCH must stay in the list (setupGame role-toggle trap, see memory).
    ctx = await setupGame(browser, {
      totalPlayers: 9, // 3 wolf seats: two pack wolves + the mechanical wolf (W7)
      hasSheriff: false,
      roles: ['WEREWOLF', 'MECHANICAL_WOLF', 'VILLAGER', 'MEDIUM', 'WITCH'],
      browserRoles: ['MECHANICAL_WOLF', 'MEDIUM'],
    })
    const logStart = readBackendLogLineCount()
    ctx.markBackendLogPosition()

    const mechPage = page(ctx, 'MECHANICAL_WOLF')
    const mediumPage = page(ctx, 'MEDIUM')
    const mech = only(ctx, 'MECHANICAL_WOLF')
    const medium = only(ctx, 'MEDIUM')
    const [poisonedWolf, wolf] = packWolves(ctx)
    const witch = only(ctx, 'WITCH')
    const [v1, v2, v3] = villagers(ctx)
    console.warn(
      `[mech-rules A] game=${ctx.gameId} host=${ctx.hostRole} mech=${mech.seat} medium=${medium.seat} ` +
        `wolves=${wolf.seat},${poisonedWolf.seat} witch=${witch.seat} v=${v1.seat},${v2.seat},${v3.seat}`,
    )

    // ── Night 1: the pack kills; the mech only watches (W1) and skips (W4) ──
    await hostStartsNight(ctx)
    await reach(ctx, 'WEREWOLF_PICK')
    await waitForUiSubPhase(mechPage, 'WEREWOLF_PICK')
    await expect(mechPage.getByTestId('wolf-confirm-kill'), 'W1: no kill UI beside the pack').toHaveCount(0)
    bot(ctx, 'WOLF_KILL', wolf, { target: v1.seat })

    await reach(ctx, 'MECH_WOLF_PICK')
    await waitForUiSubPhase(mechPage, 'MECH_WOLF_PICK')
    await expect(mechPage.getByTestId(`mech-learn-target-${medium.seat}`), 'W4: learning is offered').toBeVisible({
      timeout: scale(10_000),
    })
    await mechTurn(ctx, mechPage, 'skip', { kind: 'SKIP' })
    await captureSnapshot(ctx.pages, testInfo, 'a-n1-mech-skipped')

    // The witch poisons one pack wolf; the other goes to the vote on D1.
    await reach(ctx, 'WITCH_ACT')
    bot(ctx, 'WITCH_ACT', witch, {
      payload: `{"useAntidote":false,"poisonTargetUserId":"${poisonedWolf.userId}"}`,
    })
    await mediumChecks(ctx, mediumPage, mech.seat, 'MECHANICAL_WOLF') // M3: not learned yet
    await captureSnapshot(ctx.pages, testInfo, 'a-n1-medium-sees-mech-wolf')

    // ── Day 1: the last pack wolf is voted out ────────────────────────────
    expect(await dawn(ctx)).toBe('DAY_DISCUSSION')
    expect(await completeDay(ctx, testInfo, { targetSeat: wolf.seat, label: 'a-d1' })).toBe('NIGHT')
    expect(await isAlive(ctx, wolf.seat), 'the pack wolf was voted out').toBe(false)
    expect(await isAlive(ctx, poisonedWolf.seat), 'the other pack wolf was poisoned').toBe(false)
    expect(await isAlive(ctx, v1.seat), 'the witch did not save V1').toBe(false)

    // ── Night 2: the lone mech kills (W6) and learns the medium (W4) ──────
    await mechKills(ctx, mechPage, v2.seat)
    await captureSnapshot(ctx.pages, testInfo, 'a-n2-lone-kill')
    await mechTurn(ctx, mechPage, { grid: 'learn', targetSeat: medium.seat }, { kind: 'LEARN', role: 'MEDIUM' })
    await witchPasses(ctx, witch)
    await mediumChecks(ctx, mediumPage, mech.seat, 'MEDIUM') // M3: learned earlier tonight
    await captureSnapshot(ctx.pages, testInfo, 'a-n2-medium-sees-learned-role')

    // ── Day 2: no pack wolf is left, yet the game goes on (W3) ────────────
    expect(await dawn(ctx), 'W3: the mech alone keeps the wolves alive').toBe('DAY_DISCUSSION')
    expect(await completeDay(ctx, testInfo, { targetSeat: null, label: 'a-d2' })).toBe('NIGHT')
    expect(await isAlive(ctx, v2.seat), 'the lone kill landed').toBe(false)

    // ── Night 3: the learned medium skill is usable from the next night (W5) ──
    await mechKills(ctx, mechPage, v3.seat)
    await reach(ctx, 'MECH_WOLF_PICK')
    await waitForUiSubPhase(mechPage, 'MECH_WOLF_PICK')
    await expect(mechPage.getByTestId(`mech-skill-target-${witch.seat}`)).toBeVisible({ timeout: scale(10_000) })
    await expect(mechPage.locator('[data-testid^="mech-learn-target-"]'), 'learning happens once').toHaveCount(0)
    await mechTurn(ctx, mechPage, { grid: 'skill', targetSeat: witch.seat }, { kind: 'CHECK', role: 'WITCH' })
    await captureSnapshot(ctx.pages, testInfo, 'a-n3-mech-checks-witch')
    await witchPasses(ctx, witch)
    await mediumChecks(ctx, mediumPage, witch.seat, 'WITCH')

    // ── Day 3: voting the mech out ends the game — villagers win (W3) ─────
    expect(await dawn(ctx)).toBe('DAY_DISCUSSION')
    expect(await completeDay(ctx, testInfo, { targetSeat: mech.seat, label: 'a-d3' })).toBe('GAME_OVER')
    await ctx.hostPage.waitForURL(/\/result\//, { timeout: scale(30_000) })
    await expect(ctx.hostPage.locator('.outcome-title')).toHaveText(/村民|好人|Villager|GOOD/i, {
      timeout: scale(10_000),
    })
    await captureSnapshot(ctx.pages, testInfo, 'a-d3-villagers-win')

    await assertBackendEvidence(ctx, logStart, 3, { mechanicalWolf: true, medium: true, guard: false }, testInfo)
  })

  test('the seer sees the mech as a wolf before and after it learns; a learned seer checks alignment', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 600_000 : 300_000)

    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ['WEREWOLF', 'MECHANICAL_WOLF', 'VILLAGER', 'SEER', 'WITCH'],
      browserRoles: ['MECHANICAL_WOLF', 'SEER'],
    })
    const logStart = readBackendLogLineCount()
    ctx.markBackendLogPosition()

    const mechPage = page(ctx, 'MECHANICAL_WOLF')
    const seerPage = page(ctx, 'SEER')
    const mech = only(ctx, 'MECHANICAL_WOLF')
    const seer = only(ctx, 'SEER')
    const [wolf] = packWolves(ctx)
    const witch = only(ctx, 'WITCH')
    const [v1, v2, v3] = villagers(ctx)
    console.warn(
      `[mech-rules B] game=${ctx.gameId} host=${ctx.hostRole} mech=${mech.seat} seer=${seer.seat} ` +
        `wolf=${wolf.seat} witch=${witch.seat} v=${v1.seat},${v2.seat},${v3.seat}`,
    )

    // ── Night 1: unlearned mech reads as a wolf (M4) ──────────────────────
    await hostStartsNight(ctx)
    await reach(ctx, 'WEREWOLF_PICK')
    bot(ctx, 'WOLF_KILL', wolf, { target: v1.seat })
    await mechTurn(ctx, mechPage, 'skip', { kind: 'SKIP' })
    await witchPasses(ctx, witch)
    await seerChecks(ctx, seerPage, mech.seat, 'wolf')
    await captureSnapshot(ctx.pages, testInfo, 'b-n1-seer-sees-wolf')
    expect(await dawn(ctx)).toBe('DAY_DISCUSSION')
    expect(await completeDay(ctx, testInfo, { targetSeat: null, label: 'b-d1' })).toBe('NIGHT')

    // ── Night 2: the mech learns SEER; the seer still reads it as a wolf (M4) ──
    await reach(ctx, 'WEREWOLF_PICK')
    bot(ctx, 'WOLF_KILL', wolf, { target: v2.seat })
    await mechTurn(ctx, mechPage, { grid: 'learn', targetSeat: seer.seat }, { kind: 'LEARN', role: 'SEER' })
    await witchPasses(ctx, witch)
    await seerChecks(ctx, seerPage, mech.seat, 'wolf')
    await captureSnapshot(ctx.pages, testInfo, 'b-n2-seer-still-sees-wolf')
    expect(await dawn(ctx)).toBe('DAY_DISCUSSION')
    expect(await completeDay(ctx, testInfo, { targetSeat: null, label: 'b-d2' })).toBe('NIGHT')

    // ── Night 3: the learned seer skill — an alignment check (W5) ─────────
    await reach(ctx, 'WEREWOLF_PICK')
    bot(ctx, 'WOLF_KILL', wolf, { target: v3.seat })
    await mechTurn(ctx, mechPage, { grid: 'skill', targetSeat: wolf.seat }, { kind: 'CHECK', wolf: true })
    await captureSnapshot(ctx.pages, testInfo, 'b-n3-mech-alignment-check')
    await witchPasses(ctx, witch)
    await seerChecks(ctx, seerPage, witch.seat, 'village')
    expect(await dawn(ctx)).toBe('DAY_DISCUSSION')

    await assertBackendEvidence(ctx, logStart, 3, { mechanicalWolf: true, guard: false }, testInfo)
  })
})
