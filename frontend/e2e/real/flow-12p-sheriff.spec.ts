/**
 * 12-player full-game flow evidence — host drives every UI decision via
 * real clicks; bots sign in and act through REST (via scripts/act.sh), the
 * same way a second device would. No shortcuts via test-only endpoints.
 *
 * Two scenarios:
 *   1. CLASSIC (easy) — villagers win by voting out every wolf.
 *   2. HARD_MODE — wolves win; day-1 voting sends the elected sheriff
 *      (seer) to elimination so the badge-handover sub-phase fires and is
 *      captured in a screenshot.
 *
 * Each test step attaches a composite screenshot (host + per-role pages) to
 * the Playwright report so the evidence is viewable at:
 *   docs/e2e-evidence/<run>/index.html  (after npx playwright show-report)
 */
import { expect, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { type RoleName } from './helpers/shell-runner'
import { driveMinimalNight1ViaDom, waitForDayDiscussionAfterSheriff } from './helpers/night-driver'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { readHostSeat, waitForCondition } from './helpers/state-polling'
import {
  assertNonNull,
  completeDay,
  completeNight,
  resolveRolePlayer,
  runSheriffElection,
} from './helpers/flow-drivers'

const BROWSER_ROLES: RoleName[] = ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER']

// ───────────────────────────────────────────────────────────────────────────────
// Scenario 1 — 12 players, CLASSIC, sheriff election, VILLAGERS win
// ───────────────────────────────────────────────────────────────────────────────

test.describe('12p sheriff — CLASSIC villager win', () => {
  test.setTimeout(600_000) // 10 min max

  let ctx: GameContext

  test.beforeAll(async ({ browser }, testInfo) => {
    // CI scales ~2× slower; 180s is tight for a 12p 6-round classic game on
    // ubuntu-latest. Bump to 360s under CI only.
    testInfo.setTimeout(process.env.CI ? 360_000 : 180_000)
    ctx = await setupGame(browser, {
      totalPlayers: 12,
      hasSheriff: true,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
      browserRoles: BROWSER_ROLES,
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

  // UN-SKIP attempt 2026-04-27: workers:1 + the rewritten 6-design-points
  // diagnostics (browser sentinels, backend log scan, invariants, action
  // observability, sub-phase gating) should make this both bearable on the
  // shared backend AND debuggable when it fails.
  test('phase: role-reveal + N1 + sheriff election + village votes out wolves', async ({}, testInfo) => {
    await captureSnapshot(ctx.pages, testInfo, 'classic-01-role-reveal')

    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    // Prefer non-host candidates so the badge lives on a bot we can script later
    const candidates = [seerBots[0], wolfBots[0]]
      .filter((b): b is NonNullable<typeof b> => !!b && b.nick !== 'Host')
      .map((b) => b.nick)

    // Variant B Day 1: drive Night 1 (DOM-clicks) → reveal night result →
    // sheriff election. The wolf must avoid the seer (so the seer can run
    // and win Test 1's election). Guard must NOT protect the wolf-target
    // seat (UI has no skip — guard always protects someone; protecting
    // the kill target would block the kill and stall the planned outcome).
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const villagerSeats = villagerBots.filter((b) => b.nick !== 'Host').map((b) => b.seat)
    expect(
      villagerSeats.length,
      'classic kit must include at least 2 non-host VILLAGER seats so wolf and guard can target distinct seats',
    ).toBeGreaterThanOrEqual(2)
    const wolfTargetSeatN1 = villagerSeats[0]
    const guardTargetSeatN1 = villagerSeats[1]

    // Variant B (correct): driveMinimalNight1ViaDom drives N1 actions and
    // waits for the auto-transition into SHERIFF_ELECTION/SIGNUP (Day 1 +
    // hasSheriff). Kills are still deferred at this point — N1 victims
    // are alive in DB and could 上警 if the test wanted them to.
    await driveMinimalNight1ViaDom(ctx, {
      wolfTargetSeat: wolfTargetSeatN1,
      guardTargetSeat: guardTargetSeatN1,
    })
    await captureSnapshot(ctx.pages, testInfo, 'classic-02-night-1-done-sheriff-opened')

    await runSheriffElection(ctx, candidates)
    await captureSnapshot(ctx.pages, testInfo, 'classic-03-sheriff-elected')

    // After sheriff RESULT, backend auto-advances to DAY_DISCUSSION/RESULT_HIDDEN
    // (kills still deferred; host clicks reveal in completeDay below).
    await waitForDayDiscussionAfterSheriff(ctx)
    await captureSnapshot(ctx.pages, testInfo, 'classic-04-day-result-hidden')

    // Wolves will be voted out every day. No village player dies at night if
    // we can avoid it — wolves hit a villager target we'll vote no-one for.
    // Alive wolves-to-eliminate order: all wolves including host (if host
    // rolled WEREWOLF). The earlier "exclude host" filter was wrong: with
    // host filtered, a host-wolf survives the entire spec, the loop exits
    // with 1 wolf still alive, and `/result/` is never reached. The host
    // can be voted out and still drive the post-elimination UI (host-only
    // buttons check hostUserId, not alive status). Include host with their
    // real seat from the API — the shell state file's seat=0 is stale.
    const hostSeat = await readHostSeat(ctx.hostPage, ctx.gameId)
    const wolvesToEliminate = (ctx.roleMap.WEREWOLF ?? []).map((b) => ({
      nick: b.nick,
      userId: b.userId,
      seat: b.nick === 'Host' && hostSeat != null ? hostSeat : b.seat,
    }))

    let round = 0
    const maxRounds = 6
    while (round < maxRounds && wolvesToEliminate.length > 0) {
      // Skip night on round 0 — Night 1 was already driven before sheriff
      // election. Subsequent rounds drive Night N>=2 via the existing
      // completeNight helper (which handles dead actors + seerCheckSeat
      // logic for nights with prior eliminations).
      if (round > 0) {
        const killSeat = villagerSeats[round % villagerSeats.length] ?? 1
        const checkSeat = wolvesToEliminate[0]?.seat ?? 1
        await completeNight(ctx, killSeat, checkSeat)
        await ctx.hostPage.waitForTimeout(3_000)
        if (ctx.hostPage.url().includes('/result/')) break
        await captureSnapshot(ctx.pages, testInfo, `classic-06-night-${round + 1}-actions`)
      }

      // Day: vote out the front wolf
      const targetWolfSeat = wolvesToEliminate[0].seat
      await completeDay(ctx, testInfo, targetWolfSeat, `classic-07-day-${round + 1}`)
      await ctx.hostPage.waitForTimeout(2_500)
      if (ctx.hostPage.url().includes('/result/')) break

      wolvesToEliminate.shift()
      round++
    }

    // Expect GAME_OVER — assert via authoritative API state, not URL.
    // STOMP-driven router.push to /result lags backend commit by 200-500ms
    // (longer on CI), so URL-only detection is racy.
    const finalPhase = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    expect(
      finalPhase,
      `villager-win plan must reach GAME_OVER within ${maxRounds} rounds — actual phase=${finalPhase}`,
    ).toBe('GAME_OVER')

    // Wait for the result-screen redirect so the outcome title renders.
    await ctx.hostPage.waitForURL(/\/result\//, { timeout: 60_000 })
    await captureSnapshot(ctx.pages, testInfo, 'classic-99-result-screen')

    await expect(ctx.hostPage.locator('.outcome-title')).toBeVisible({ timeout: 10_000 })
    const winner = (await ctx.hostPage.locator('.outcome-title').textContent()) ?? ''
    expect(winner).toMatch(/村民|好人|Villager|GOOD/i)
  })
})

// ───────────────────────────────────────────────────────────────────────────────
// Scenario 2 — 12 players, HARD_MODE, sheriff election, WOLVES win
// Day 1: village votes out the elected sheriff → BADGE_HANDOVER fires.
// Subsequent nights: wolves kill; wolves-win rule triggers when counterplay
// (witch potions + guard + hunter-if-any) is exhausted.
// ───────────────────────────────────────────────────────────────────────────────

test.describe('12p sheriff — HARD_MODE wolf win with badge passover', () => {
  test.setTimeout(600_000)

  let ctx: GameContext

  test.beforeAll(async ({ browser }, testInfo) => {
    // CI scales ~2× slower; 180s is tight for a 12p sheriff-elect + 2-night
    // game on ubuntu-latest. Bump to 360s under CI only.
    testInfo.setTimeout(process.env.CI ? 360_000 : 180_000)
    ctx = await setupGame(browser, {
      totalPlayers: 12,
      hasSheriff: true,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
      browserRoles: BROWSER_ROLES,
      winCondition: 'HARD_MODE',
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

  test('phase: role-reveal + sheriff-elect (seer) + D1 vote out sheriff → badge passover → wolves win', async ({}, testInfo) => {
    // eslint-disable-next-line no-console
    console.warn(`[hard-mode test] starting with hostRole=${ctx.hostRole}`)

    await captureSnapshot(ctx.pages, testInfo, 'hard-01-role-reveal')

    // For each special role: resolve to bot OR host (host takes the role on
    // ~25% of rolls in this 12p kit). Read the host's actual seat from the
    // live game state — the shell state file's seat=0 for the host is stale
    // (multi-browser.ts:227 writes it once before seat-claim and never
    // updates it).
    const seer = await resolveRolePlayer(ctx, 'SEER')
    const guard = await resolveRolePlayer(ctx, 'GUARD')
    const witch = await resolveRolePlayer(ctx, 'WITCH')
    assertNonNull(seer, `kit must have a SEER (bot or host=${ctx.hostRole})`)
    assertNonNull(guard, `kit must have a GUARD (bot or host=${ctx.hostRole})`)
    assertNonNull(witch, `kit must have a WITCH (bot or host=${ctx.hostRole})`)
    // eslint-disable-next-line no-console
    console.warn(
      `[hard-mode test] resolved roles — seer=${seer.nick}(seat=${seer.seat},host=${seer.isHost}) ` +
        `guard=${guard.nick}(seat=${guard.seat},host=${guard.isHost}) ` +
        `witch=${witch.nick}(seat=${witch.seat},host=${witch.isHost})`,
    )

    const wolfSeats = new Set((ctx.roleMap.WEREWOLF ?? []).map((b) => b.seat))
    const villagerSeats = (ctx.roleMap.VILLAGER ?? [])
      .filter((b) => b.nick !== 'Host')
      .map((b) => b.seat)
      .filter((s) => !wolfSeats.has(s))
    expect(
      villagerSeats.length,
      `D2 vote-out plan needs at least one non-host non-wolf villager seat`,
    ).toBeGreaterThanOrEqual(1)

    // Variant B Day 1: drive Night 1 first (DOM-clicks on every special
    // role browser), then reveal night result → sheriff election.
    //
    // N1 plan: wolves kill the GUARD. Witch passes on the antidote (per
    // helper default), so the kill stands. Seer checks a wolf so the
    // SEER_RESULT sub-phase has a real check to render. Guard MUST NOT
    // protect themselves (the UI has no skip; if the guard protects
    // guard.seat, the wolf kill is blocked and the test plan fails).
    // Park the guard's protect on a wolf — wolves don't kill each other,
    // so the protection is wasted but doesn't block the planned death.
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const wolfSeatForGuardProtect =
      wolfBots.find((b) => b.nick !== 'Host')?.seat ?? wolfBots[0]?.seat
    expect(
      wolfSeatForGuardProtect,
      'HARD_MODE kit needs at least one wolf so guard can protect a non-target seat',
    ).toBeDefined()

    // Variant B: drives N1 then waits for end-of-night auto-transition into
    // SHERIFF_ELECTION/SIGNUP. Guard is the wolf-target but kills are
    // deferred — guard is still alive in DB during sheriff election (and
    // could 上警 if the test wanted them to).
    //
    // seerCheckSeat: prefer a non-host wolf so we exercise the seer's
    // cross-browser action against a bot — and guard against a stale
    // host seat (the placeholder seat=0 in the state file is now updated
    // post-game-start, but staying explicit keeps test intent clear).
    const seerCheckWolfSeat =
      wolfBots.find((b) => b.nick !== 'Host')?.seat ?? wolfBots[0]?.seat ?? guard.seat
    await driveMinimalNight1ViaDom(ctx, {
      wolfTargetSeat: guard.seat,
      seerCheckSeat: seerCheckWolfSeat,
      guardTargetSeat: wolfSeatForGuardProtect!,
    })
    await captureSnapshot(ctx.pages, testInfo, 'hard-02-night-1-done-sheriff-opened')

    // Sheriff election — only the seer campaigns. After speeches + votes
    // the seer holds the badge.
    await runSheriffElection(ctx, [seer.nick])
    await captureSnapshot(ctx.pages, testInfo, 'hard-03-sheriff-elected-is-seer')

    // After sheriff RESULT, backend auto-advances to DAY_DISCUSSION/RESULT_HIDDEN.
    // Host clicks reveal in completeDay below to apply the deferred guard kill.
    await waitForDayDiscussionAfterSheriff(ctx)
    await captureSnapshot(ctx.pages, testInfo, 'hard-04-day-result-hidden')

    // D1: village votes out the seer (sheriff). Backend transitions to
    // BADGE_HANDOVER — only the seer's browser page sees the pass-badge
    // button (isEliminatedSheriff is true there only). When the host is
    // the seer, ctx.pages.get('SEER') === ctx.hostPage by setupGame's
    // mapping (multi-browser.ts:347), so the host's own page renders the
    // badge UI. Park the badge on a non-host wolf so the sheriff never
    // moves again (wolves don't get voted, don't kill themselves).
    const seerPage = ctx.pages.get('SEER')
    assertNonNull(
      seerPage,
      'badge-handover needs the SEER browser page — only the eliminated sheriff sees the pass-badge UI',
    )
    const badgeWolfBot = wolfBots.find((b) => b.nick !== 'Host')
    assertNonNull(
      badgeWolfBot,
      'a non-host wolf is needed as the badge recipient so the badge stays put for the rest of the test',
    )
    await completeDay(ctx, testInfo, seer.seat, 'hard-05-day-1', seerPage, badgeWolfBot.seat)
    await ctx.hostPage.waitForTimeout(2_000)

    // Plan needs 2 nights: D1 vote-out fires BADGE_HANDOVER + post-vote win
    // check, but counterplay (witch + alive humans) keeps it from ending.
    // Use API state, not URL — STOMP /result/ redirect lags backend commit.
    const phaseAfterD1 = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    expect(
      phaseAfterD1,
      'wolf-win plan needs 2 nights — D1 vote-out should NOT end the game',
    ).not.toBe('GAME_OVER')

    // N2: wolves kill the WITCH. Witch sees herself as the wolves' target
    // during WITCH_ACT but the `useAntidote: false` payload (in
    // completeNight) forces the witch to decline self-heal — she dies. After:
    // 9 alive (4W/0S/0Wi/0G/5V), no remaining counterplay tokens.
    await completeNight(ctx, witch.seat)
    await ctx.hostPage.waitForTimeout(2_500)
    await captureSnapshot(ctx.pages, testInfo, 'hard-06-night-2-done')

    // Edge: if N2 already wrapped the game (wolves got parity via cascading
    // death), capture and assert wolf-win here. Use API state — STOMP
    // /result/ redirect lags backend GAME_OVER commit.
    const phaseAfterN2 = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    if (phaseAfterN2 === 'GAME_OVER') {
      await ctx.hostPage.waitForURL(/\/result\//, { timeout: 30_000 })
      await captureSnapshot(ctx.pages, testInfo, 'hard-99-result-screen')
      await expect(ctx.hostPage.locator('.outcome-title')).toBeVisible({ timeout: 10_000 })
      const winnerEarly = (await ctx.hostPage.locator('.outcome-title').textContent()) ?? ''
      expect(winnerEarly).toMatch(/狼人|Werewolf|WOLF/i)
      return
    }

    // D2: village votes out any non-host non-wolf villager. The vote
    // produces an elimination, which fires the POST_VOTE win check — and
    // with hasGuard=false, hasWitch=false, hasHunter=N/A (HUNTER not in role
    // kit), counterplay.any=false. Wolves at parity (4W vs 4 humans
    // remaining: host + 3 villagers) → HARD_MODE wolf-win logical branch.
    await completeDay(ctx, testInfo, villagerSeats[0], 'hard-07-day-2')
    await ctx.hostPage.waitForTimeout(2_000)

    // Authoritative GAME_OVER assertion via API, not URL.
    const phaseAfterD2 = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    expect(
      phaseAfterD2,
      `HARD_MODE plan must reach GAME_OVER after N1(guard)+D1(seer)+N2(witch)+D2(villager) — actual=${phaseAfterD2}`,
    ).toBe('GAME_OVER')

    await ctx.hostPage.waitForURL(/\/result\//, { timeout: 60_000 })
    await captureSnapshot(ctx.pages, testInfo, 'hard-99-result-screen')

    await expect(ctx.hostPage.locator('.outcome-title')).toBeVisible({ timeout: 10_000 })
    const winner = (await ctx.hostPage.locator('.outcome-title').textContent()) ?? ''
    expect(winner).toMatch(/狼人|Werewolf|WOLF/i)
  })
})

// ───────────────────────────────────────────────────────────────────────────────
// Scenario 3 — 12 players, sheriff killed AT NIGHT on N2.
// New contract (2026-05-22): night-killed sheriff gets one last action — pass
// the badge to an heir OR destroy it — landed in the new
// DaySubPhase.BADGE_HANDOVER value at day reveal, BEFORE discussion. This
// exercises the new flow end-to-end: real STOMP broadcasts, REST endpoints,
// DAY_DISCUSSION-side badge UI in DayPhase.vue (separate from VotingPhase's
// badge UI which covers vote-out elimination).
// ───────────────────────────────────────────────────────────────────────────────
test.describe('12p sheriff — night-killed sheriff hands over badge at day reveal', () => {
  test.setTimeout(600_000)

  let ctx: GameContext

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(process.env.CI ? 360_000 : 180_000)
    ctx = await setupGame(browser, {
      totalPlayers: 12,
      hasSheriff: true,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
      browserRoles: BROWSER_ROLES,
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

  test('N1 normal → sheriff elected → D1 vote-out wolf → N2 wolves kill sheriff → BADGE_HANDOVER at reveal → pass to heir', async ({}, testInfo) => {
    // Resolve roles. Seer becomes sheriff (matches HARD_MODE pattern).
    const seer = await resolveRolePlayer(ctx, 'SEER')
    const guard = await resolveRolePlayer(ctx, 'GUARD')
    assertNonNull(seer, `kit must have a SEER (bot or host=${ctx.hostRole})`)
    assertNonNull(guard, `kit must have a GUARD (bot or host=${ctx.hostRole})`)

    const wolfSeats = new Set((ctx.roleMap.WEREWOLF ?? []).map((b) => b.seat))
    const villagerSeats = (ctx.roleMap.VILLAGER ?? [])
      .filter((b) => b.nick !== 'Host')
      .map((b) => b.seat)
      .filter((s) => !wolfSeats.has(s))
    expect(
      villagerSeats.length,
      'plan needs 2 non-host non-wolf villager seats: villagerSeats[0] dies to the N1 kill, ' +
        'villagerSeats[1] must be a LIVING badge heir at the day-2 handover',
    ).toBeGreaterThanOrEqual(2)

    // ── N1: wolves kill a villager (not the seer — seer needs to become sheriff)
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const guardSafeWolfSeat = wolfBots.find((b) => b.nick !== 'Host')?.seat ?? wolfBots[0]?.seat
    expect(
      guardSafeWolfSeat,
      'need a wolf seat so guard can protect a wolf without blocking the planned kill',
    ).toBeDefined()

    await driveMinimalNight1ViaDom(ctx, {
      wolfTargetSeat: villagerSeats[0],
      seerCheckSeat: guardSafeWolfSeat,
      guardTargetSeat: guardSafeWolfSeat!,
    })
    await captureSnapshot(ctx.pages, testInfo, 'sndh-01-n1-done')

    // ── Sheriff election: seer campaigns and wins
    await runSheriffElection(ctx, [seer.nick])
    await captureSnapshot(ctx.pages, testInfo, 'sndh-02-sheriff-elected-is-seer')
    await waitForDayDiscussionAfterSheriff(ctx)

    // Confirm: seer is now sheriffUserId.
    const sheriffAfterElection = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.sheriffUserId : null
    }, ctx.gameId)
    expect(sheriffAfterElection, 'seer should hold the badge after election').toBe(seer.userId)

    // ── D1: vote out any non-sheriff wolf (keep sheriff alive for the N2 plan)
    const wolfToKill = wolfBots.find((b) => b.nick !== 'Host') ?? wolfBots[0]
    assertNonNull(wolfToKill, 'need a wolf to vote out on D1')
    await completeDay(ctx, testInfo, wolfToKill.seat, 'sndh-03-d1')
    await ctx.hostPage.waitForTimeout(2_000)

    // ── N2: wolves target the SHERIFF (the seer). Witch+guard pass via
    //         completeNight defaults (witch declines antidote → kill stands;
    //         guard skips).
    await completeNight(ctx, seer.seat)
    await captureSnapshot(ctx.pages, testInfo, 'sndh-04-n2-done')

    // ── Day 2 reveal: backend should transition to DAY_DISCUSSION/BADGE_HANDOVER
    //         because the sheriff died at night.
    const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
    await revealBtn.waitFor({ state: 'visible', timeout: 30_000 })
    await revealBtn.click()

    // Authoritative subPhase check via API (not DOM): the backend should be
    // in BADGE_HANDOVER, not RESULT_REVEALED.
    await waitForCondition(
      async () => {
        const state = await ctx.hostPage.evaluate(async (id: string) => {
          const token = localStorage.getItem('jwt')
          const res = await fetch(`/api/game/${id}/state`, {
            headers: { Authorization: `Bearer ${token}` },
          })
          return res.ok ? await res.json() : null
        }, ctx.gameId)
        return state?.phase === 'DAY_DISCUSSION' && state?.subPhase === 'BADGE_HANDOVER'
      },
      'BADGE_HANDOVER at day-2 reveal (night-killed sheriff)',
      15_000,
    )
    await captureSnapshot(ctx.pages, testInfo, 'sndh-05-badge-handover-fired')

    // ── Sheriff's browser must render the dying-sheriff banner + pass button.
    //         When host is the seer, ctx.pages.get('SEER') === hostPage by
    //         setupGame's mapping — host's own page renders the badge UI.
    const seerPage = ctx.pages.get('SEER')
    assertNonNull(
      seerPage,
      'badge-handover needs the SEER browser page — only the night-killed sheriff sees the pass-badge UI',
    )
    await expect(seerPage.getByTestId('day-badge-eliminated-banner')).toBeVisible({
      timeout: 10_000,
    })
    const passBtn = seerPage.getByTestId('day-badge-pass')
    await expect(passBtn).toBeVisible({ timeout: 5_000 })
    // Disabled until an heir is selected.
    await expect(passBtn).toBeDisabled()

    // ── Non-sheriff browsers see the waiting banner, not the pass UI.
    const witchPage = ctx.pages.get('WITCH')
    if (witchPage) {
      await expect(witchPage.getByTestId('day-badge-wait-banner')).toBeVisible({ timeout: 10_000 })
      expect(await witchPage.getByTestId('day-badge-pass').count()).toBe(0)
    }

    // ── Pass the badge to an alive non-host non-wolf villager. The sheriff
    //         page's player grid is tap-to-select; click the heir then click pass.
    // The heir must be ALIVE: villagerSeats[0] is the N1 wolf victim (dead
    // since the D1 reveal), and the badge picker renders dead seats
    // unclickable — tapping one leaves day-badge-pass disabled forever.
    const heirSeat = villagerSeats.find((s) => s !== villagerSeats[0] && s !== seer.seat)
    assertNonNull(heirSeat, 'need a living non-sheriff villager seat as badge heir')
    const heirSlot = seerPage.locator(`.player-grid [data-seat="${heirSeat}"]`)
    await heirSlot.click()
    await expect(passBtn).toBeEnabled({ timeout: 5_000 })
    await passBtn.click()

    // ── Backend transitions to DAY_DISCUSSION/RESULT_REVEALED with the new
    //         sheriff in place.
    await waitForCondition(
      async () => {
        const state = await ctx.hostPage.evaluate(async (id: string) => {
          const token = localStorage.getItem('jwt')
          const res = await fetch(`/api/game/${id}/state`, {
            headers: { Authorization: `Bearer ${token}` },
          })
          return res.ok ? await res.json() : null
        }, ctx.gameId)
        return (
          state?.phase === 'DAY_DISCUSSION' &&
          state?.subPhase === 'RESULT_REVEALED' &&
          state?.sheriffUserId !== seer.userId &&
          state?.sheriffUserId != null
        )
      },
      'badge transferred → DAY_DISCUSSION/RESULT_REVEALED with new sheriff',
      15_000,
    )
    await captureSnapshot(ctx.pages, testInfo, 'sndh-06-badge-passed-result-revealed')
  })
})
