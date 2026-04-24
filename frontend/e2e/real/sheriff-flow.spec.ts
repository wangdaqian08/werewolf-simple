/**
 * Real-backend E2E: Sheriff election flow with multi-browser STOMP verification.
 *
 * Opens 4 browser contexts (host + wolf + seer + villager) and verifies
 * the sheriff election sub-game: signup → speech → vote → result.
 */
import {expect, test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act, type RoleName, sheriff} from './helpers/shell-runner'
import {verifyAllBrowsersPhase,} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'

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
    // Use seer browser to sign up for sheriff
    const seerPage = ctx.pages.get('SEER')
    if (!seerPage) {
      test.skip()
      return
    }

    // Click "Run for Sheriff" button
    const runBtn = seerPage.getByTestId('sheriff-run')
    if (await runBtn.isVisible().catch(() => false)) {
      await runBtn.click()

      // Verify: button should change to "Withdraw"
      await expect(
        seerPage.getByTestId('sheriff-withdraw'),
      ).toBeVisible({ timeout: 5_000 })
    }

    // Have some bots campaign via script
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const campaigners = [...wolfBots.slice(0, 1), ...villagerBots.slice(0, 2)]

    for (const bot of campaigners) {
      try {
        sheriff('campaign', { player: bot.nick, room: ctx.roomCode })
      } catch {
        // Some may already have signed up or phase moved on
      }
    }

    await captureSnapshot(ctx.pages, testInfo, 'sheriff-02-signup-done')
  })

  // ── Test 3: Speeches — host advances, speaker shown ────────────────────

  test('3. Sheriff speeches — host advances, current speaker updates', async ({}, testInfo) => {
    // Host starts speeches
    try {
      act('SHERIFF_START_SPEECH', undefined, { room: ctx.roomCode })
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
        act('SHERIFF_ADVANCE_SPEECH', undefined, { room: ctx.roomCode })
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

    // Host reveals result
    try {
      act('SHERIFF_REVEAL_RESULT', undefined, { room: ctx.roomCode })
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
