/**
 * Real-backend E2E: Sheriff election flow with multi-browser STOMP verification.
 *
 * Opens 4 browser contexts (host + wolf + seer + villager) and verifies
 * the sheriff election sub-game: signup → speech → vote → result.
 */
import {expect, type Page, test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {actName, type RoleName, sheriff} from './helpers/shell-runner'
import {verifyAllBrowsersPhase,} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'
import {waitForCondition} from './helpers/state-polling'
import {driveMinimalNight1ViaDom} from './helpers/night-driver'

let ctx: GameContext

// Tests within this describe run serially (Playwright config workers:1) and
// share game state. Test 2 records which userIds entered the campaign so
// test 3 can drive every other alive player to pass. The SIGNUP state
// response deliberately hides other candidates' identities (2026-05-11),
// so we can't recover this from the state itself.
let test2CampaignerUserIds = new Set<string>()

test.describe('Sheriff election — multi-browser STOMP verification', () => {
  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000) // setup can take a while with shell scripts
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: true,
      // Variant B: each role's Night 1 action is DOM-driven from its own
      // browser context. driveMinimalNight1ViaDom drives WEREWOLF_PICK →
      // WITCH_ACT → SEER_PICK → SEER_RESULT → GUARD_PICK in order, so the
      // kit MUST include all four special roles; otherwise the missing
      // role's sub-phase never fires and the wait times out. Explicit
      // roles also bypass the default 9p kit which doesn't guarantee
      // GUARD presence.
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[],
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

  // ── Test 1: N1 + reveal → SHERIFF_ELECTION ────────────────────────────

  test('1. Day 1 morning — sheriff election opens at end-of-night (deferred kills)', async ({}, testInfo) => {
    // Variant B (correct order): setup leaves us in ROLE_REVEAL. Drive
    // Night 1 via DOM clicks on each role's browser. The backend
    // automatically opens SHERIFF_ELECTION at end-of-night when Day 1 +
    // hasSheriff + no sheriff yet — host does NOT click reveal yet
    // (kills are deferred until after the sheriff election so N1 victims
    // can still 上警 / use 挡刀 tactics).
    //
    // Wolves can target any non-host villager — even the seer's would-be
    // running mate works, because deferred kills mean no one is dead in
    // DB yet during sheriff signup.
    const villagerSeats = (ctx.roleMap.VILLAGER ?? [])
      .filter((b) => b.nick !== 'Host')
      .map((b) => b.seat)
    const wolfTargetSeat = villagerSeats[0]
    expect(
      wolfTargetSeat,
      'kit must include at least one non-host VILLAGER for the wolf to target',
    ).toBeDefined()

    // driveMinimalNight1ViaDom drives the 4 night sub-phases via DOM and
    // then waits for either SHERIFF_ELECTION/SIGNUP (Day 1 + hasSheriff)
    // or DAY_DISCUSSION/RESULT_HIDDEN (other days). Here we expect SHERIFF.
    await driveMinimalNight1ViaDom(ctx, { wolfTargetSeat: wolfTargetSeat! })

    // After the auto-transition, all browsers must be in SHERIFF_ELECTION.
    await verifyAllBrowsersPhase(ctx.pages, 'SHERIFF_ELECTION', 20_000)

    // Verify the signup sub-phase UI is shown on every browser.
    for (const [, page] of Array.from(ctx.pages.entries())) {
      await expect(page.locator('.sheriff-wrap')).toBeVisible({ timeout: 10_000 })
    }

    await captureSnapshot(ctx.pages, testInfo, 'sheriff-01-signup')
  })

  // ── Test 2: Players sign up, button changes ────────────────────────────

  test('2. Sheriff signup — browser player signs up, button changes', async ({}, testInfo) => {
    // Strong contract — three things this test exercises that no other
    // sheriff-flow test does:
    //   (a) The seer's browser shows `sheriff-run` while in SIGNUP.
    //   (b) Clicking it round-trips through STOMP and the same browser
    //       sees `sheriff-withdraw` (proves signup registered + state
    //       broadcast back to the originating browser).
    //   (c) Bot campaigns submitted via the REST script appear in the
    //       seer's candidate list (cross-browser STOMP fan-out).
    //
    // Earlier versions wrapped (b) in `if (await runBtn.isVisible())` and
    // (c) in `try { ... } catch {}`, so the test passed silently even when
    // the signup feature was completely broken. Same anti-pattern as
    // `test.skip()` — replaced with positive assertions.

    const seerPage = ctx.pages.get('SEER')
    if (!seerPage) {
      throw new Error(
        `ctx.pages.get('SEER') is undefined — setupGame did not assign a SEER page. ` +
          `Check beforeAll's browserRoles (must include 'SEER') and the role kit ` +
          `(room.hasSeer must be true). hostRole=${ctx.hostRole}.`,
      )
    }

    // Wait for SHERIFF_ELECTION/SIGNUP — only sub-phase where `sheriff-run`
    // renders. Backend may take a moment after role-reveal-end to enter it.
    await waitForCondition(
      async () => {
        const state = await seerPage.evaluate(async (id: string) => {
          const token = localStorage.getItem('jwt')
          const res = await fetch(`/api/game/${id}/state`, {
            headers: { Authorization: `Bearer ${token}` },
          })
          return res.ok ? res.json() : null
        }, ctx.gameId)
        return state?.sheriffElection?.subPhase === 'SIGNUP'
      },
      'sheriffElection.subPhase to reach SIGNUP',
      15_000,
    )

    // (a) sheriff-run must render. If it doesn't, that's a regression in
    //     SheriffElection.vue's signup template (or an aliveness bug).
    const runBtn = seerPage.getByTestId('sheriff-run')
    await expect(runBtn).toBeVisible({ timeout: 10_000 })

    // (b) Click → button toggles to "Withdraw" (signup confirmed).
    await runBtn.click()
    await expect(seerPage.getByTestId('sheriff-withdraw')).toBeVisible({
      timeout: 10_000,
    })

    // (c) Drive 3 bot campaigns via script and assert all 3 appear in the
    //     seer's candidate list (1 self-signup + 3 bots = 4 candidates).
    //     Filter Host out: the seer just signed up via the seerPage click;
    //     if host rolled SEER, seerPage IS hostPage and the host is already
    //     in the list — feeding host into `sheriff campaign` here would be
    //     a duplicate signup that the backend rejects.
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const campaigners = [...wolfBots.slice(0, 1), ...villagerBots.slice(0, 2)].filter(
      (b) => b.nick !== 'Host',
    )
    expect(campaigners.length, 'need at least one non-host bot to campaign').toBeGreaterThan(0)
    for (const bot of campaigners) {
      sheriff('campaign', { player: actName(bot), room: ctx.roomCode })
    }

    // 2026-05-11 behaviour: SIGNUP no longer renders per-candidate rows
    // (identities are hidden). The observable signal that the bot fan-out
    // landed is the decision-progress counter on the seer's UI: it must
    // reflect the count of decided alive players. The seer signed up via
    // (b) above, so the expected `decided` is 1 (seer) + campaigners.length.
    await expect(seerPage.getByTestId('sheriff-decision-progress')).toContainText(
      new RegExp(`${1 + campaigners.length}\\s*/`),
      { timeout: 10_000 },
    )

    // Record campaigner userIds so test 3 can leave them alone when driving
    // remaining alive players to pass. The seer's own userId comes from
    // ctx.roleMap.SEER[0] (the same browser that just clicked sheriff-run).
    const seerForCtx = ctx.roleMap.SEER?.[0]
    test2CampaignerUserIds = new Set<string>([
      ...(seerForCtx?.userId ? [seerForCtx.userId] : []),
      ...campaigners.map((b) => b.userId),
    ])

    await captureSnapshot(ctx.pages, testInfo, 'sheriff-02-signup-done')
  })

  // Helper: read the sheriff election sub-phase from /api/game/{id}/state.
  // Returns null if the response shape is missing the field (e.g. the
  // election already wrapped). Used to gate every host-only action so the
  // backend isn't spammed with "Not in <X> sub-phase" rejections.
  const readSheriffSubPhase = async (page: Page, gameId: string): Promise<string | null> =>
    page.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? ((await res.json())?.sheriffElection?.subPhase ?? null) : null
    }, gameId)

  // ── Test 3: Speeches — host advances, speaker shown ────────────────────

  test('3. Sheriff speeches — host advances, current speaker updates', async ({}, testInfo) => {
    // 2026-05-11: SIGNUP→SPEECH is now backend-auto-triggered when every
    // alive player has decided (signed up or passed). The host's manual
    // `sheriff-start-campaign` button is gone. Drive every remaining
    // undecided alive player to pass via sheriff.sh, using test 2's
    // recorded campaigner list as ground truth (the SIGNUP state response
    // hides other candidates' identities, so we can't recover the set from
    // a state poll).
    await waitForCondition(
      async () => (await readSheriffSubPhase(ctx.hostPage, ctx.gameId)) === 'SIGNUP',
      'sheriff election to reach SIGNUP before driving remaining passes',
      15_000,
    )
    expect(
      test2CampaignerUserIds.size,
      'test 2 must have populated test2CampaignerUserIds before test 3 runs',
    ).toBeGreaterThan(0)

    const hostUserId = await ctx.hostPage.evaluate(() => localStorage.getItem('userId'))
    const aliveIds = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return [] as string[]
      const state = await res.json()
      return ((state?.players ?? []) as Array<{ isAlive: boolean; userId: string }>)
        .filter((p) => p.isAlive)
        .map((p) => p.userId)
    }, ctx.gameId)
    const allBots = Object.values(ctx.roleMap).flatMap((b) => b ?? [])
    for (const userId of aliveIds) {
      if (test2CampaignerUserIds.has(userId)) continue // campaigner — leave RUNNING
      const selector =
        userId === hostUserId ? 'HOST' : allBots.find((b) => b.userId === userId)?.nick
      if (!selector) continue
      try {
        sheriff('pass', { player: selector, room: ctx.roomCode })
      } catch (e) {
        // eslint-disable-next-line no-console
        console.warn(`[sheriff] pass ${selector} threw: ${(e as Error).message}`)
      }
    }

    // Backend auto-transitions SIGNUP → SPEECH once all alive players have decided.
    await waitForCondition(
      async () => (await readSheriffSubPhase(ctx.hostPage, ctx.gameId)) === 'SPEECH',
      'sheriff election to auto-transition to SPEECH after every alive player decides',
      15_000,
    )

    // Verify all browsers show the speech UI.
    for (const [, page] of Array.from(ctx.pages.entries())) {
      await expect(page.locator('.sheriff-wrap')).toBeVisible({ timeout: 10_000 })
    }

    // Advance every speech via the DOM `sheriff-advance-speech` button. Loop
    // until the sub-phase auto-transitions out of SPEECH (backend advances
    // to VOTING after the last speaker) — NOT a fixed iteration count.
    // SheriffElection.vue:115,128,182 only renders the button while in
    // SPEECH, so its disappearance is the same signal as the sub-phase
    // change. 20 is just a safety cap.
    const advanceBtn = ctx.hostPage.getByTestId('sheriff-advance-speech')
    for (let i = 0; i < 20; i++) {
      const sub = await readSheriffSubPhase(ctx.hostPage, ctx.gameId)
      if (sub !== 'SPEECH') break
      const reappeared = await advanceBtn
        .waitFor({ state: 'visible', timeout: 5_000 })
        .then(() => true)
        .catch(() => false)
      if (!reappeared) continue
      await advanceBtn.click()
    }

    // After all speeches, backend should be in VOTING. Assert it — if not,
    // the test fails loudly instead of silently leaving the phase wrong.
    await waitForCondition(
      async () => (await readSheriffSubPhase(ctx.hostPage, ctx.gameId)) === 'VOTING',
      'sheriff election to reach VOTING after last speaker',
      15_000,
    )

    await captureSnapshot(ctx.pages, testInfo, 'sheriff-03-speeches')
  })

  // ── Test 4: Voting + result ────────────────────────────────────────────

  test('4. Sheriff voting — bots vote, result revealed on all browsers', async ({}, testInfo) => {
    // Test 3 left us in VOTING. Assert that explicitly.
    await waitForCondition(
      async () => (await readSheriffSubPhase(ctx.hostPage, ctx.gameId)) === 'VOTING',
      'sheriff election VOTING sub-phase reached after test 3',
      15_000,
    )

    // All non-host non-candidate bots vote for the seer (the candidate
    // signed up in test 2). Candidates can't vote for themselves
    // (SheriffService.kt:385), so they abstain instead.
    const seerBots = ctx.roleMap.SEER ?? []
    const targetNick = seerBots[0]?.nick
    expect(targetNick, 'kit must include a SEER candidate to vote for').toBeDefined()
    sheriff('vote', { target: targetNick!, room: ctx.roomCode })
    sheriff('abstain', { player: targetNick!, room: ctx.roomCode })

    // Host abstains via DOM so allVoted flips true (host is never a
    // candidate; sheriff.sh's bot fan-out doesn't include the host).
    const abstainBtn = ctx.hostPage.getByTestId('sheriff-abstain')
    await abstainBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await abstainBtn.click()

    // Host reveals result via DOM. Wait for the button to enable so we
    // never click before the backend's allVoted gate flips.
    const revealBtn = ctx.hostPage.getByTestId('sheriff-reveal-result')
    await revealBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await expect(revealBtn).toBeEnabled({ timeout: 15_000 })
    await revealBtn.click()

    // Backend transitions VOTING → RESULT (or TIED). Assert it.
    await waitForCondition(
      async () => {
        const sub = await readSheriffSubPhase(ctx.hostPage, ctx.gameId)
        return sub === 'RESULT' || sub === 'TIED'
      },
      'sheriff election RESULT/TIED reached after sheriff-reveal-result',
      15_000,
    )

    await captureSnapshot(ctx.pages, testInfo, 'sheriff-04-result')
  })

  // ── Test 5: Sheriff result → host clicks 显示结果 → DAY_DISCUSSION/RESULT_HIDDEN ──

  test('5. Sheriff result → host clicks 显示结果 → DAY_DISCUSSION/RESULT_HIDDEN', async ({}, testInfo) => {
    // Variant B (correct order): after sheriff RESULT the host has the camera
    // and decides when to dismiss the screen. The 显示结果 button (testid
    // "sheriff-end-result") fires SHERIFF_END_RESULT, transitioning the game
    // to DAY_DISCUSSION/RESULT_HIDDEN. The host then clicks REVEAL_NIGHT_RESULT
    // separately to apply pending kills and flip to RESULT_REVEALED.
    //
    // Replaces the old 60s auto-timer (SheriffService.scheduleAutoAdvance...)
    // — that timer was removed so the host stays in control of pacing.
    const endBtn = ctx.hostPage.getByTestId('sheriff-end-result')
    await endBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await expect(endBtn).toBeEnabled({ timeout: 10_000 })
    await endBtn.click()

    await waitForCondition(
      async () => {
        const state = await ctx.hostPage.evaluate(async (id: string) => {
          const token = localStorage.getItem('jwt')
          const res = await fetch(`/api/game/${id}/state`, {
            headers: { Authorization: `Bearer ${token}` },
          })
          return res.ok ? await res.json() : null
        }, ctx.gameId)
        return state?.phase === 'DAY_DISCUSSION' && state?.subPhase === 'RESULT_HIDDEN'
      },
      'SHERIFF_END_RESULT click landed on DAY_DISCUSSION/RESULT_HIDDEN',
      15_000,
    )

    // All browsers transition to DAY (PHASE_DATA_VALUES maps "DAY" to
    // {DAY_PENDING, DAY_DISCUSSION} on the data-phase attribute).
    await verifyAllBrowsersPhase(ctx.pages, 'DAY', 15_000)

    await captureSnapshot(ctx.pages, testInfo, 'sheriff-05-day-discussion')
  })
})
