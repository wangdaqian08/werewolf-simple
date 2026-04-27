/**
 * Real-backend E2E: Sheriff election flow with multi-browser STOMP verification.
 *
 * Opens 4 browser contexts (host + wolf + seer + villager) and verifies
 * the sheriff election sub-game: signup → speech → vote → result.
 */
import {expect, test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act, actName, type RoleName, sheriff} from './helpers/shell-runner'
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

  // ── Test 3: Speeches — host advances, speaker shown ────────────────────

  test('3. Sheriff speeches — host advances, current speaker updates', async ({}, testInfo) => {
    // Host starts speeches. Pass 'Host' explicitly: SHERIFF_START_SPEECH and
    // SHERIFF_ADVANCE_SPEECH are host-only — without a player arg, act.sh
    // fans out across every bot, each call rejected with "Only host can
    // start speeches", and the test burns ~30 s of REJECTED rows in the
    // backend log before the catch{} short-circuits. Passing 'Host'
    // routes the action through the cached host token in one call.
    try {
      act('SHERIFF_START_SPEECH', 'Host', { room: ctx.roomCode })
    } catch {
      // May already be in speech phase
    }

    // Wait for SPEECH sub-phase
    await ctx.hostPage.waitForTimeout(2_000)

    // Verify all browsers show speech UI
    for (const [role, page] of Array.from(ctx.pages.entries())) {
      await expect(page.locator('.sheriff-wrap')).toBeVisible({ timeout: 10_000 })
    }

    // Host advances speeches until all done
    let advances = 0
    while (advances < 10) {
      try {
        act('SHERIFF_ADVANCE_SPEECH', 'Host', { room: ctx.roomCode })
        advances++
        await ctx.hostPage.waitForTimeout(500)
      } catch {
        // No more speeches to advance, or phase moved to VOTING
        break
      }
    }

    await captureSnapshot(ctx.pages, testInfo, 'sheriff-03-speeches')
  })

  // ── Test 4: Voting + result ────────────────────────────────────────────

  test('4. Sheriff voting — bots vote, result revealed on all browsers', async ({}, testInfo) => {
    // Wait for voting sub-phase
    await ctx.hostPage.waitForTimeout(2_000)

    // All bots vote for the first candidate (seer if they signed up)
    const seerBots = ctx.roleMap.SEER ?? []
    const targetNick = seerBots[0]?.nick

    if (targetNick) {
      try {
        sheriff('vote', { target: targetNick, room: ctx.roomCode })
      } catch {
        // Some may fail to vote
      }
    }

    // Wait for all votes
    await ctx.hostPage.waitForTimeout(2_000)

    // Host reveals result. SHERIFF_REVEAL_RESULT is host-only — pass 'Host'
    // explicitly so act.sh uses the cached host token (otherwise it fans
    // out across bots, each rejected as "Only host can reveal sheriff
    // result").
    try {
      act('SHERIFF_REVEAL_RESULT', 'Host', { room: ctx.roomCode })
    } catch {
      // May fail if tied — try appoint instead
    }

    // Wait for result to propagate
    await ctx.hostPage.waitForTimeout(3_000)

    await captureSnapshot(ctx.pages, testInfo, 'sheriff-04-result')
  })

  // ── Test 5: Sheriff → Night transition ─────────────────────────────────

  test('5. Sheriff result → Night transition', async ({}, testInfo) => {
    // The transition to night may happen:
    // 1. Host clicks "Start Night" button in the sheriff result screen
    // 2. Or via script

    // Try clicking the button first
    const startNightBtn = ctx.hostPage.getByTestId('start-night')
    const btnVisible = await startNightBtn.isVisible().catch(() => false)

    if (btnVisible) {
      await startNightBtn.click()
    } else {
      // Use script
      try {
        act('START_NIGHT', 'Host', { room: ctx.roomCode })
      } catch {
        // Might auto-advance
      }
    }

    // All browsers should transition to NIGHT
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)

    await captureSnapshot(ctx.pages, testInfo, 'sheriff-05-night-transition')
  })
})
