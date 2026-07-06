/**
 * 5-night HARD_MODE spine — the corrected master game script, end to end.
 *
 * 12 players, all roles (4 WEREWOLF + SEER + WITCH + HUNTER + GUARD + IDIOT +
 * 3 VILLAGER), sheriff enabled, witch self-save allowed (room default).
 * This is the executable form of docs/scenarios/scenario-10; it exists to
 * cover the behaviors no other spec exercises, all of which need ONE
 * continuous multi-night game:
 *
 *   1. witch ANTIDOTE save → 0-kill 平安夜 reveal (N2/D2)
 *   2. witch POISON → 2-death reveal + the DOM use-poison path (N1/D1)
 *   3. potion exhaustion → potion-less witch-skip UI on a later night (N3)
 *   4. guard save cancels the kill + a legal consecutive-target chain
 *      (self → wolfA → seer → wolfA → wolfB) across five nights
 *   5. double tie / mass abstain → no elimination → AUTO-advance to night (D2)
 *   6. sheriff-game day-1 audio: morning cue rides NIGHT→SHERIFF_ELECTION
 *      exactly once (election→day rooster suppressed)
 *   7. TWO dead roles masked in the same night (N5: seer + witch)
 *   8. HARD_MODE suppresses the POST_NIGHT parity check (N4 ends 3W/3H and
 *      the game continues; CLASSIC would end there)
 *
 * Sheriff/badge/hunter beats appear because the script is one continuous
 * game — they are smoke-asserted only (flow-12p-sheriff and
 * game-flow-d1-outcomes own that coverage).
 *
 * Alive audit (12 → GAME_OVER): N1 kills V1+V2 · D1 executes HUNTER, hunter
 * shoots wolfD · N2 witch self-saves (0) · D2 double-tie (0) · N3 guard saves
 * seer (0) · D3 revote executes SEER/sheriff → badge to wolfA · N4 kills
 * WITCH (3W/3H, POST_NIGHT suppressed) · D4 executes V3 (guard alive ⇒
 * counterplay blocks POST_VOTE) · N5 kills GUARD · D5 executes wolfB →
 * 2W vs 1H, counterplay gone → WEREWOLF win.
 */
import { expect, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { type RoleName } from './helpers/shell-runner'
import { driveMinimalNight1ViaDom, waitForDayDiscussionAfterSheriff } from './helpers/night-driver'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import {
  assertNonNull,
  completeDay,
  completeNight,
  readTopPhase,
  resolveRolePlayer,
  runSheriffElection,
} from './helpers/flow-drivers'
import {
  attachAudioCapture,
  buildGameAudioManifest,
  countRoleAliveLines,
  findAudioManifestViolations,
  findNightOrderViolations,
} from './helpers/audio-manifest'
import { readBackendLogSince } from './helpers/backend-log'
import { cleanupRoomData } from './helpers/test-support'
import {
  assertGameInvariants,
  newInvariantState,
  type GameInvariantState,
} from './helpers/invariants'

const BROWSER_ROLES: RoleName[] = ['WEREWOLF', 'SEER', 'WITCH', 'GUARD']

test.describe('5-night HARD_MODE spine — corrected master script', () => {
  test.setTimeout(process.env.CI ? 900_000 : 480_000)

  let ctx: GameContext

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(process.env.CI ? 360_000 : 180_000)
    ctx = await setupGame(browser, {
      totalPlayers: 12,
      hasSheriff: true,
      winCondition: 'HARD_MODE',
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT'] as RoleName[],
      browserRoles: BROWSER_ROLES,
    })
  })

  test.afterAll(async () => {
    if (ctx) await cleanupRoomData(ctx.hostPage, ctx.roomCode)
    await ctx?.cleanup()
  })

  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status === 'failed' && ctx?.pages) {
      await attachCompositeOnFailure(ctx.pages, testInfo)
    }
  })

  test('N1..N5 scripted game: audio manifest, masking, 平安夜 ×2, double-tie, badge, wolf win', async ({}, testInfo) => {
    const hostPage = ctx.hostPage
    const gameId = ctx.gameId
    let invariants: GameInvariantState = newInvariantState()
    const step = async (label: string): Promise<void> => {
      invariants = await assertGameInvariants(hostPage, gameId, invariants, label)
      await captureSnapshot(ctx.pages, testInfo, `spine-${label}`)
    }

    // ── Cast resolution (roles are server-shuffled; resolve at runtime) ──
    const seer = await resolveRolePlayer(ctx, 'SEER')
    const witch = await resolveRolePlayer(ctx, 'WITCH')
    const guard = await resolveRolePlayer(ctx, 'GUARD')
    const hunter = await resolveRolePlayer(ctx, 'HUNTER')
    const idiot = await resolveRolePlayer(ctx, 'IDIOT')
    assertNonNull(seer, `kit must have a SEER (bot or host=${ctx.hostRole})`)
    assertNonNull(witch, `kit must have a WITCH (bot or host=${ctx.hostRole})`)
    assertNonNull(guard, `kit must have a GUARD (bot or host=${ctx.hostRole})`)
    assertNonNull(hunter, `kit must have a HUNTER (bot or host=${ctx.hostRole})`)
    assertNonNull(idiot, `kit must have an IDIOT (bot or host=${ctx.hostRole})`)

    const wolves = ctx.roleMap.WEREWOLF ?? []
    expect(wolves.length, '12p all-roles kit assigns 4 wolves').toBe(4)
    // wolfA carries the badge from D3 to the end; wolfB is executed on D5;
    // wolfD dies to the hunter's D1 shot; wolfC just survives. Prefer
    // non-host wolves for A/B (their pages/tokens drive badge + vote UI),
    // though every path also works host-side.
    const sortedWolves = [...wolves].sort((a, b) =>
      a.nick === 'Host' ? 1 : b.nick === 'Host' ? -1 : 0,
    )
    const [wolfA, wolfB, wolfC, wolfD] = sortedWolves
    assertNonNull(wolfA, 'wolfA')
    assertNonNull(wolfB, 'wolfB')
    assertNonNull(wolfC, 'wolfC')
    assertNonNull(wolfD, 'wolfD')

    const villagers = ctx.roleMap.VILLAGER ?? []
    const nonHostVillagers = villagers.filter((b) => b.nick !== 'Host')
    expect(
      nonHostVillagers.length,
      'plan needs 2 non-host villagers (N1 kill + N1 poison targets)',
    ).toBeGreaterThanOrEqual(2)
    const v1 = nonHostVillagers[0]
    const v2 = nonHostVillagers[1]
    assertNonNull(v1, 'v1')
    assertNonNull(v2, 'v2')
    const v3 = villagers.find((b) => b.userId !== v1.userId && b.userId !== v2.userId)
    assertNonNull(v3, 'plan needs a third villager (D4 vote-out target)')

    // eslint-disable-next-line no-console
    console.warn(
      `[spine] cast — seer=${seer.nick}@${seer.seat} witch=${witch.nick}@${witch.seat} ` +
        `guard=${guard.nick}@${guard.seat} hunter=${hunter.nick}@${hunter.seat} ` +
        `idiot=${idiot.nick}@${idiot.seat} wolves=[${sortedWolves.map((w) => `${w.nick}@${w.seat}`).join(', ')}] ` +
        `villagers=[${villagers.map((v) => `${v.nick}@${v.seat}`).join(', ')}] hostRole=${ctx.hostRole}`,
    )

    // ── Audio capture must be attached BEFORE the first state-changing click ──
    const capture = attachAudioCapture(hostPage)

    // ══ NIGHT 1 (DOM: full real-UI pass incl. the poison picker) ══════════
    // wolf → V1; witch poisons V2; seer checks wolfA; guard SELF-protects.
    await driveMinimalNight1ViaDom(ctx, {
      wolfTargetSeat: v1.seat,
      seerCheckSeat: wolfA.seat,
      guardTargetSeat: guard.seat,
      witch: { mode: 'poison', targetSeat: v2.seat },
    })
    await step('01-n1-done')

    // ══ SHERIFF ELECTION (day 1, before the death reveal) ═════════════════
    // The seer campaigns alone and wins the badge.
    await runSheriffElection(ctx, [seer.nick])
    const sheriffAfterElection = await hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.sheriffUserId : null
    }, gameId)
    expect(sheriffAfterElection, 'seer must hold the badge after the election').toBe(seer.userId)
    await waitForDayDiscussionAfterSheriff(ctx)
    await step('02-sheriff-elected')

    // ══ DAY 1: reveal 2 dead (wolf kill + poison) → vote HUNTER → shoot ══
    const revealBtnD1 = hostPage.getByTestId('day-reveal-result')
    await revealBtnD1.waitFor({ state: 'visible', timeout: 30_000 })
    await revealBtnD1.click()
    await expect(hostPage.getByTestId('day-banner-kill')).toBeVisible({ timeout: 10_000 })
    await expect(hostPage.getByTestId(`day-killed-seat-${v1.seat}`)).toBeVisible({
      timeout: 5_000,
    })
    await expect(hostPage.getByTestId(`day-killed-seat-${v2.seat}`)).toBeVisible({
      timeout: 5_000,
    })
    await step('03-d1-two-dead-revealed')

    const d1Outcome = await completeDay(ctx, testInfo, {
      targetSeat: hunter.seat,
      label: 'spine-d1',
      hunterShootTargetSeat: wolfD.seat,
    })
    expect(d1Outcome, 'D1 (hunter executed + wolfD shot) must not end the game').not.toBe(
      'GAME_OVER',
    )
    await step('04-d1-done')

    // ══ NIGHT 2 (API): wolves hit the WITCH; she SELF-SAVES (toggle default
    // allows it); guard parks protection on wolfA; seer checks wolfB. ══════
    await completeNight(ctx, {
      wolfKillSeat: witch.seat,
      witch: { mode: 'save' },
      guard: { mode: 'protect', targetSeat: wolfA.seat },
      seerCheckSeat: wolfB.seat,
    })
    await step('05-n2-done')

    // ══ DAY 2: 平安夜 via antidote → mass abstain ×2 → AUTO-night ═════════
    const revealBtnD2 = hostPage.getByTestId('day-reveal-result')
    await revealBtnD2.waitFor({ state: 'visible', timeout: 30_000 })
    await revealBtnD2.click()
    await expect(
      hostPage.getByTestId('day-banner-peaceful'),
      'witch antidote must cancel the kill → 平安夜 banner',
    ).toBeVisible({ timeout: 10_000 })
    await step('06-d2-peaceful-antidote')

    const d2Outcome = await completeDay(ctx, testInfo, { targetSeat: null, label: 'spine-d2' })
    expect(
      d2Outcome,
      'double tie (mass abstain ×2) must AUTO-advance to NIGHT with no elimination and no host click',
    ).toBe('NIGHT')
    await step('07-d2-double-tie-auto-night')

    // ══ NIGHT 3 (API + witch DOM): wolves hit the SEER; guard saves him
    // (prev target was wolfA — consecutive rule satisfied); the potion-less
    // witch acknowledges via the witch-skip UI. ═══════════════════════════
    await completeNight(ctx, {
      wolfKillSeat: seer.seat,
      witch: { mode: 'confirmNoItems' },
      guard: { mode: 'protect', targetSeat: seer.seat },
      seerCheckSeat: wolfA.seat,
    })
    await step('08-n3-done')

    // ══ DAY 3: 平安夜 via guard → engineered 1:1 tie (sheriff abstains) →
    // revote piles onto the SEER/sheriff → BADGE_HANDOVER → badge to wolfA ═
    const revealBtnD3 = hostPage.getByTestId('day-reveal-result')
    await revealBtnD3.waitFor({ state: 'visible', timeout: 30_000 })
    await revealBtnD3.click()
    await expect(
      hostPage.getByTestId('day-banner-peaceful'),
      'guard protection must cancel the kill → second 平安夜',
    ).toBeVisible({ timeout: 10_000 })
    await step('09-d3-peaceful-guard')

    const seerPage = ctx.pages.get('SEER')
    assertNonNull(seerPage, 'badge handover needs the SEER browser page')
    const d3Outcome = await completeDay(ctx, testInfo, {
      targetSeat: seer.seat,
      label: 'spine-d3',
      sheriffPage: seerPage,
      badgeRecipientSeat: wolfA.seat,
      round1Votes: [
        { voterNick: witch.nick, targetSeat: wolfB.seat },
        { voterNick: guard.nick, targetSeat: idiot.seat },
      ],
    })
    expect(d3Outcome, 'D3 (sheriff executed) must not end the game').not.toBe('GAME_OVER')
    const sheriffAfterD3 = await hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.sheriffUserId : null
    }, gameId)
    expect(sheriffAfterD3, 'badge must land on wolfA after the handover').toBe(wolfA.userId)
    await step('10-d3-badge-on-wolfA')

    // ══ NIGHT 4 (API): wolves kill the potion-less WITCH (she still acts —
    // kills are deferred); dead SEER is masked; guard back to wolfA. ═══════
    await completeNight(ctx, {
      wolfKillSeat: witch.seat,
      witch: { mode: 'pass' },
      guard: { mode: 'protect', targetSeat: wolfA.seat },
    })
    // HARD_MODE divergence pin: 3W/3H at the end of N4 would end a CLASSIC
    // game at the POST_NIGHT check; HARD_MODE suppresses parity at night.
    const phaseAfterN4 = await readTopPhase(hostPage, gameId)
    expect(
      phaseAfterN4,
      'HARD_MODE must suppress the POST_NIGHT parity check at 3W/3H — game continues to D4',
    ).not.toBe('GAME_OVER')
    await step('11-n4-done-parity-suppressed')

    // ══ DAY 4: reveal witch dead → vote out V3 (guard alive ⇒ counterplay
    // blocks the POST_VOTE wolf win at 3W/2H) ═════════════════════════════
    const revealBtnD4 = hostPage.getByTestId('day-reveal-result')
    await revealBtnD4.waitFor({ state: 'visible', timeout: 30_000 })
    await revealBtnD4.click()
    await expect(hostPage.getByTestId('day-banner-kill')).toBeVisible({ timeout: 10_000 })
    await expect(hostPage.getByTestId(`day-killed-seat-${witch.seat}`)).toBeVisible({
      timeout: 5_000,
    })
    const d4Outcome = await completeDay(ctx, testInfo, { targetSeat: v3.seat, label: 'spine-d4' })
    expect(
      d4Outcome,
      'D4 must not end the game: 3W/2H at POST_VOTE but the guard is alive (counterplay)',
    ).not.toBe('GAME_OVER')
    await step('12-d4-done-counterplay-held')

    // ══ NIGHT 5 (API): wolves kill the GUARD — he still acts first
    // (protect wolfB; not self, not prev wolfA). Dead SEER + dead WITCH are
    // BOTH masked this night. ═════════════════════════════════════════════
    await completeNight(ctx, {
      wolfKillSeat: guard.seat,
      guard: { mode: 'protect', targetSeat: wolfB.seat },
    })
    await step('13-n5-done')

    // ══ DAY 5: reveal guard dead → vote wolfB → 2W vs 1H, counterplay gone
    // → HARD_MODE werewolf win ════════════════════════════════════════════
    const revealBtnD5 = hostPage.getByTestId('day-reveal-result')
    await revealBtnD5.waitFor({ state: 'visible', timeout: 30_000 })
    await revealBtnD5.click()
    await expect(hostPage.getByTestId('day-banner-kill')).toBeVisible({ timeout: 10_000 })
    await expect(hostPage.getByTestId(`day-killed-seat-${guard.seat}`)).toBeVisible({
      timeout: 5_000,
    })
    await completeDay(ctx, testInfo, { targetSeat: wolfB.seat, label: 'spine-d5' })

    const finalPhase = await readTopPhase(hostPage, gameId)
    expect(
      finalPhase,
      'D5 wolf-elimination leaves 2W vs 1H with no counterplay → HARD_MODE wolf win',
    ).toBe('GAME_OVER')
    const winner = await hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.winner : null
    }, gameId)
    expect(winner).toBe('WEREWOLF')

    // Result view: outcome + settlement present.
    await hostPage.waitForURL(/\/result\//, { timeout: 60_000 })
    await expect(hostPage.locator('.outcome-title')).toBeVisible({ timeout: 10_000 })
    expect((await hostPage.locator('.outcome-title').textContent()) ?? '').toMatch(
      /狼人|Werewolf|WOLF/i,
    )
    await expect(hostPage.getByTestId('settlement')).toBeVisible({ timeout: 10_000 })
    await expect(hostPage.getByTestId('play-again')).toBeVisible({ timeout: 5_000 })
    await captureSnapshot(ctx.pages, testInfo, 'spine-99-result')

    // ══ AUDIO MANIFEST: 5 nights × (2 entry + 8 role) + 5 day cues = 60 ═══
    // Let the last STOMP frames settle: the D5 morning cue precedes the vote
    // so everything should already be in, but slow frames get a short grace.
    const manifest = buildGameAudioManifest({ nights: 5, dayCues: 5 })
    const settleDeadline = Date.now() + 30_000
    while (Date.now() < settleDeadline) {
      const roosters = capture.stompFrames.filter(
        (f) => f.includes('AudioSequence') && f.includes('rooster_crowing.mp3'),
      ).length
      if (roosters >= 5) break
      await hostPage.waitForTimeout(500)
    }

    const backendLines = readBackendLogSince(0)
    const violations = findAudioManifestViolations({
      backendLines,
      stompFrames: capture.stompFrames,
      manifest,
      gameId,
    })
    expect(
      violations,
      `audio manifest must hold (60 files: 5 nights + 5 day cues).\n${violations.join('\n')}`,
    ).toEqual([])

    const orderViolations = findNightOrderViolations(backendLines, gameId, 5)
    expect(
      orderViolations,
      `per-night role audio must broadcast in Werewolf→Witch→Seer→Guard open/close order.\n${orderViolations.join('\n')}`,
    ).toEqual([])

    // Masking pins: the seer is dead for N4+N5, the witch for N5 — their
    // night slots still run (alive=false lines) and their audio still
    // broadcast (already counted exactly above).
    const aliveLines = countRoleAliveLines(backendLines, gameId)
    expect(aliveLines.SEER, 'seer: alive N1-N3, masked N4-N5').toEqual({ alive: 3, dead: 2 })
    expect(aliveLines.WITCH, 'witch: alive N1-N4, masked N5').toEqual({ alive: 4, dead: 1 })
    expect(aliveLines.GUARD, 'guard acts every night (dies at the N5 reveal)').toEqual({
      alive: 5,
      dead: 0,
    })
    expect(aliveLines.WEREWOLF, 'a living wolf acts every night').toEqual({ alive: 5, dead: 0 })

    // Sheriff-game D1 audio: exactly one morning cue per day when no replay
    // re-delivery occurred (the NIGHT→SHERIFF_ELECTION cue rides once; the
    // election→day transition suppresses its rooster).
    const replayFrames = capture.audioEvents.filter((e) => e.includes('AudioSequence (replay)'))
    if (replayFrames.length === 0) {
      const roosterCount = capture.stompFrames.filter(
        (f) => f.includes('AudioSequence') && f.includes('rooster_crowing.mp3'),
      ).length
      expect(
        roosterCount,
        'exactly 5 rooster cues: one per day, D1 election morning not double-played',
      ).toBe(5)
    }

    // Backend log must be ERROR-free for the whole run.
    await ctx.assertNoBackendErrors(testInfo)
  })
})
