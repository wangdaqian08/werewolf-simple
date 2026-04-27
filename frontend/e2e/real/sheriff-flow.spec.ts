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

let ctx: GameContext

test.describe('Sheriff election — multi-browser STOMP verification', () => {
  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000) // setup can take a while with shell scripts
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: true,
      browserRoles: ['WEREWOLF', 'SEER', 'VILLAGER'] as RoleName[],
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

  // ── Test 1: Sheriff signup ─────────────────────────────────────────────

  test('1. Sheriff signup — all browsers show SHERIFF_ELECTION phase', async ({}, testInfo) => {
    // After role reveal with hasSheriff=true, phase should go to SHERIFF_ELECTION
    // The host may need to wait for automatic transition, or the election starts automatically
    await verifyAllBrowsersPhase(ctx.pages, 'SHERIFF_ELECTION', 20_000)

    // Verify the signup sub-phase UI is shown
    for (const [role, page] of Array.from(ctx.pages.entries())) {
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

    // Candidate count on the seer's UI = 1 (self) + campaigners.length,
    // propagated via STOMP. toHaveCount retries until the count matches
    // or the timeout expires, so this naturally waits for STOMP delivery.
    await expect(seerPage.locator('.cand-row-running')).toHaveCount(
      1 + campaigners.length,
      { timeout: 10_000 },
    )

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
    // Host starts speeches via the dedicated UI button (DOM-driven, no act.sh
    // fan-out). The button is visible only in SIGNUP — wait for that
    // sub-phase before clicking so the action lands cleanly.
    await waitForCondition(
      async () => (await readSheriffSubPhase(ctx.hostPage, ctx.gameId)) === 'SIGNUP',
      'sheriff election to reach SIGNUP before starting speeches',
      15_000,
    )
    const startBtn = ctx.hostPage.getByTestId('sheriff-start-campaign')
    await expect(startBtn).toBeVisible({ timeout: 10_000 })
    await expect(startBtn).toBeEnabled({ timeout: 10_000 })
    await startBtn.click()

    // Backend transitions SIGNUP → SPEECH. Assert it.
    await waitForCondition(
      async () => (await readSheriffSubPhase(ctx.hostPage, ctx.gameId)) === 'SPEECH',
      'sheriff election to reach SPEECH after sheriff-start-campaign click',
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

  // ── Test 5: Sheriff → Night transition ─────────────────────────────────

  test('5. Sheriff result → Night transition', async ({}, testInfo) => {
    // After sheriff RESULT, the host has a "start-night" button on the
    // result screen. The button is the contract — fail loudly if it
    // doesn't render in 10s rather than silently fall back to a script
    // that may also fail.
    const startNightBtn = ctx.hostPage.getByTestId('start-night')
    await startNightBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await startNightBtn.click()

    // All browsers transition to NIGHT.
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)

    await captureSnapshot(ctx.pages, testInfo, 'sheriff-05-night-transition')
  })
})
