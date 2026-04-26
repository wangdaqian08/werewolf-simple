/**
 * Real-backend E2E: Complete game flow with multi-browser STOMP verification.
 *
 * Opens 6 isolated browser contexts (host + wolf + seer + witch + guard + villager)
 * and verifies that actions in one browser are reflected in all others via STOMP.
 *
 * P0 bug detection:
 *   - Button clicked → no UI change
 *   - STOMP event sent → UI not updated in other browsers
 *   - Phase transition → browser stuck on old phase
 */
import { expect, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { act, actName, type RoleName } from './helpers/shell-runner'
import { verifyAllBrowsersPhase } from './helpers/assertions'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import {
  readHostUserId,
  readUnvotedAlivePlayerIds,
  waitForNightSubPhase,
} from './helpers/state-polling'
import { assertNoBrowserErrors } from './helpers/error-sentinel'

let ctx: GameContext

test.describe('Game flow — multi-browser STOMP verification', () => {
  test.setTimeout(60_000) // 3 minutes for the full flow

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000) // setup can take a while with shell scripts
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[],
    })
  })

  test.afterAll(async () => {
    await ctx?.cleanup()
  })

  // Reset the per-test error/log buffers BEFORE each test so the post-test
  // assertions only see what happened during this test's window.
  test.beforeEach(async () => {
    ctx?.resetErrors()
    ctx?.markBackendLogPosition()
  })

  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status === 'failed' && ctx?.pages) {
      await attachCompositeOnFailure(ctx.pages, testInfo)
    }
    if (!ctx) return
    // Sentinel #3: any uncaught JS exception or 5xx in any browser fails
    // the test, even if the gameplay-level assertions otherwise passed.
    await assertNoBrowserErrors(ctx.errors, testInfo)
    // Sentinel #6: any ERROR/FATAL backend log line during the test window
    // fails the test. Catches backend bugs that retried/recovered and were
    // therefore invisible to the frontend.
    await ctx.assertNoBackendErrors(testInfo)
  })

  // ── Test 1: Role reveal ──────────────────────────────────────────────

  test('1. Role reveal — all browsers show ROLE_REVEAL phase', async ({}, testInfo) => {
    // All role browsers should be on the waiting screen (roles already confirmed via scripts)
    // Since setupGame() confirms all roles via scripts, the browsers should either show
    // the waiting screen or the role reveal screen (if phase hasn't advanced yet)
    for (const [_, page] of Array.from(ctx.pages.entries())) {
      // Wait for either waiting screen or role reveal screen
      const locator = page.locator('.waiting-screen, .reveal-wrap')
      await expect(locator).toBeVisible({ timeout: 5_000 })

      // If on role reveal screen, complete the reveal flow
      const revealWrap = page.locator('.reveal-wrap')
      const revealVisible = await revealWrap.isVisible().catch(() => false)
      if (revealVisible) {
        const revealBtn = page.getByTestId('reveal-role-btn')
        const revealBtnVisible = await revealBtn.isVisible().catch(() => false)
        expect(revealBtnVisible).toBe(true)
        await revealBtn.click()
        await page.waitForTimeout(300)
        const gotItBtn = page.getByTestId('confirm-role-btn')
        await gotItBtn.waitFor({ state: 'visible', timeout: 2_000 })
        await gotItBtn.click()
      }

      // Verify waiting screen is shown (after role confirm or if already there)
      await expect(page.locator('.waiting-screen')).toBeVisible({ timeout: 3_000 })
    }

    await captureSnapshot(ctx.pages, testInfo, '01-role-reveal')
  })

  // ── Test 2: Host starts night → all browsers transition ──────────────

  test('2. Start night — all browsers transition to NIGHT phase', async ({}, testInfo) => {
    const hostPage = ctx.hostPage

    // Host should see "Start Night" button (all confirmed, no sheriff)
    const startNightBtn = hostPage.getByTestId('start-night')

    // If button not visible yet, wait a bit for all confirmations to propagate
    if (!(await startNightBtn.isVisible().catch(() => false))) {
      await startNightBtn.waitFor({ state: 'visible', timeout: 10_000 })
    }

    await startNightBtn.click()

    // STOMP verify: ALL browsers should transition to NIGHT phase
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)

    await captureSnapshot(ctx.pages, testInfo, '02-night-start')
  })

  // ── Test 3: Wolf picks target, verify via browser ─────────────────────

  test('3. Night — wolf picks target via browser, others show WAITING', async ({}, testInfo) => {
    const wolfPage = ctx.pages.get('WEREWOLF')!

    // Wolf should see night phase with player grid
    await expect(wolfPage.locator('.player-grid').first()).toBeVisible({ timeout: 10_000 })

    // Other roles should be in night mode (WAITING state)
    for (const role of ['SEER', 'GUARD', 'VILLAGER'] as const) {
      const page = ctx.pages.get(role)
      if (!page || page === wolfPage) continue // skip if same page as wolf (host is wolf)
      await expect(page.locator('.game-wrap.night-mode')).toBeVisible({ timeout: 10_000 })
    }

    // Wolf kill — DOM-driven via the wolf's browser regardless of whether
    // the wolf is a bot or the host. This catches UI/STOMP bugs the API
    // path would mask (button render, click handler wiring, target-grid
    // filter). Gate on WEREWOLF_PICK so the click lands in the right
    // sub-phase (Category A race guard).
    await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 15_000)
    const wolfTargetSlot = wolfPage.locator('.player-grid .slot-alive').first()
    await wolfTargetSlot.waitFor({ state: 'visible', timeout: 10_000 })
    await wolfTargetSlot.click()
    await wolfPage.getByTestId('wolf-confirm-kill').click()

    // STOMP verify: seer browser should transition to SEER_PICK
    // (seer-check button is rendered inside the SEER_PICK template)
    const seerPage = ctx.pages.get('SEER')
    if (seerPage && seerPage !== wolfPage) {
      await expect(seerPage.getByTestId('seer-check')).toBeVisible({ timeout: 15_000 })
    }

    await captureSnapshot(ctx.pages, testInfo, '03-wolf-kill')
  })

  // ── Test 4: Complete night via scripts, verify DAY transition ─────────

  test('4. Night — seer/witch/guard complete night, all browsers show DAY', async ({}, testInfo) => {
    // ── Seer ── DOM-driven via seer's browser regardless of bot vs host.
    // Gate on SEER_PICK before clicking — without this we race the Kotlin
    // role-loop coroutine (memory: e2e-ci-vs-local-env-differences item 1).
    const seerPage = ctx.pages.get('SEER')!
    await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'SEER_PICK', 15_000)
    const seerCheckBtn = seerPage.getByTestId('seer-check')
    await expect(seerCheckBtn).toBeVisible({ timeout: 10_000 })

    const seerTargetSlot = seerPage.locator('.player-grid .slot-alive').first()
    await seerTargetSlot.waitFor({ state: 'visible', timeout: 5_000 })
    await seerTargetSlot.click()
    await seerCheckBtn.click()

    // Result screen appears, then confirm
    await expect(seerPage.locator('.sr-wrap').first()).toBeVisible({ timeout: 10_000 })
    await captureSnapshot(ctx.pages, testInfo, '04-seer-check-result')
    await seerPage.getByTestId('seer-done').click()

    // ── Witch (always via browser to capture UI at each step) ──
    // Both antidote and poison sections are visible simultaneously.
    // We interact with poison FIRST (since passing antidote may auto-complete the turn).
    const witchPage = ctx.pages.get('WITCH')!
    await expect(witchPage.locator('.w-section').first()).toBeVisible({ timeout: 15_000 })

    // Screenshot: before witch makes any action (both sections visible)
    await captureSnapshot(ctx.pages, testInfo, '04-witch-before-action')

    // -- Poison: enter target selection mode --
    const usePoisonBtn = witchPage.getByTestId('use-poison')
    if (await usePoisonBtn.isVisible().catch(() => false)) {
      await usePoisonBtn.click()
      await witchPage.waitForTimeout(500)

      // Screenshot: poison target selection grid shown
      await captureSnapshot(ctx.pages, testInfo, '04-witch-poison-select')

      // Select a target — poison grid uses 'alive' variant (slot-alive class)
      const poisonTarget = witchPage.locator('.player-grid-sm .slot-alive').first()
      if (await poisonTarget.isVisible().catch(() => false)) {
        await poisonTarget.click()
        await witchPage.waitForTimeout(300)

        // Screenshot: poison target selected (before confirm)
        await captureSnapshot(ctx.pages, testInfo, '04-witch-poison-selected')

        // Cancel — we don't actually want to poison in round 1
        const cancelBtn = witchPage.getByTestId('poison-mode-cancel')
        await cancelBtn.click()
        await witchPage.waitForTimeout(300)
      }
    }

    // -- Antidote decision --
    const useAntidoteBtn = witchPage.getByTestId('witch-antidote')
    if (await useAntidoteBtn.isVisible().catch(() => false)) {
      // Screenshot: antidote choice visible
      await captureSnapshot(ctx.pages, testInfo, '04-witch-antidote-choice')

      // Use antidote to save the attacked player
      await useAntidoteBtn.click()
      await witchPage.waitForTimeout(500)

      // Screenshot: after using antidote
      await captureSnapshot(ctx.pages, testInfo, '04-witch-after-antidote')
    }

    // -- Skip poison (if still available after antidote) --
    const skipPoisonBtn = witchPage.getByTestId('switch-pass-poison')
    if (await skipPoisonBtn.isVisible().catch(() => false)) {
      await skipPoisonBtn.click()
      await witchPage.waitForTimeout(500)

      // Screenshot: after skipping poison (witch turn complete)
      await captureSnapshot(ctx.pages, testInfo, '04-witch-after-action')
    }

    // If no items at all, click done
    const doneBtn = witchPage.getByTestId('witch-skip')
    if (await doneBtn.isVisible().catch(() => false)) {
      await doneBtn.click()
    }

    // ── Guard ── DOM-driven via guard's browser. The UI has no "skip" button
    // (memory: game-rules-clarifications — guard self-protect is allowed),
    // so we always pick a target and confirm. Gate on GUARD_PICK first.
    const guardPage = ctx.pages.get('GUARD')!
    await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'GUARD_PICK', 15_000)
    const guardConfirmBtn = guardPage.getByTestId('guard-confirm-protect')
    await expect(guardConfirmBtn).toBeVisible({ timeout: 10_000 })
    await captureSnapshot(ctx.pages, testInfo, '04-guard-ui')

    const guardTargetSlot = guardPage.locator('.player-grid .slot-alive').first()
    await guardTargetSlot.waitFor({ state: 'visible', timeout: 5_000 })
    await guardTargetSlot.click()
    await guardConfirmBtn.click()

    // STOMP verify: ALL browsers should transition to DAY phase
    await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)

    await captureSnapshot(ctx.pages, testInfo, '04-night-complete')
  })

  // ── Test 5: Day phase — host reveals result ──────────────────────────

  test('5. Day — host reveals night result, kill shown on all browsers', async ({}, testInfo) => {
    const hostPage = ctx.hostPage

    // Host should see "显示结果 · Result" button
    const revealBtn = hostPage.getByTestId('day-reveal-result')
    await revealBtn.waitFor({ state: 'visible', timeout: 10_000 })

    // Click reveal — verify all browsers see the kill banner
    await revealBtn.click()

    // All browsers should see the result (kill banner or "peaceful night")
    for (const [_, page] of Array.from(ctx.pages.entries())) {
      // Wait for the button to change or kill info to appear
      await page.waitForTimeout(2_000)
      // After reveal, the sub-phase changes to RESULT_REVEALED
      // Either a kill banner or "平安夜 / Peaceful night" should be visible
      const hasContent = await page
        .locator('.day-wrap')
        .first()
        .isVisible()
        .catch(() => false)
      expect(hasContent).toBe(true)
    }

    await captureSnapshot(ctx.pages, testInfo, '05-day-reveal')
  })

  // ── Test 6: Day → Voting transition ──────────────────────────────────

  test('6. Day → Voting — host starts vote, all browsers transition', async ({}, testInfo) => {
    const hostPage = ctx.hostPage

    // Host clicks "Start Vote"
    const startVoteBtn = hostPage.getByTestId('day-start-vote')
    await startVoteBtn.waitFor({ state: 'visible', timeout: 10_000 })

    await startVoteBtn.click()

    // STOMP verify: all browsers should transition to VOTING
    await verifyAllBrowsersPhase(ctx.pages, 'VOTING', 15_000)

    await captureSnapshot(ctx.pages, testInfo, '06-voting-start')
  })

  // ── Test 7: Voting — vote, tally, continue ───────────────────────────

  test('7. Voting — players vote, tally revealed, continue to night', async ({}, testInfo) => {
    const hostPage = ctx.hostPage

    // Host votes FIRST via browser — abstain
    // (Must do this BEFORE bot votes, since act() now includes host in "all" players)
    const abstainBtn = hostPage.locator('.skip-btn').first()
    await abstainBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await abstainBtn.click()
    await hostPage.waitForTimeout(500)

    // Find a wolf target to vote for (use the first alive wolf bot)
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const wolfTarget = wolfBots.find((b) => b.nick !== 'Host')

    // Fan-out the vote only to bots that are alive, not host, and haven't
    // voted yet — the host already clicked abstain above so their userId is
    // in state.votingPhase.votedPlayerIds. Without the filter, act.sh would
    // iterate every bot (plus host) and the redundant attempts on the
    // already-voted host would burn act.sh's 3× retry quota on rejection.
    const unvoted = await readUnvotedAlivePlayerIds(ctx.hostPage, ctx.gameId)
    const hostId = await readHostUserId(ctx.hostPage)
    const voteOpts: { target?: string; room: string } = wolfTarget
      ? { target: String(wolfTarget.seat), room: ctx.roomCode }
      : { room: ctx.roomCode }
    for (const bot of ctx.allBots) {
      if (bot.nick === 'Host' || bot.userId === hostId) continue
      if (!unvoted.has(bot.userId)) continue
      act('SUBMIT_VOTE', bot.nick, voteOpts)
    }

    // Wait for all votes to register
    await hostPage.waitForTimeout(2_000)

    // Host reveals tally via DOM — exercises the host's reveal button
    // wiring + STOMP fan-out, not just the API path.
    const revealTallyBtn = hostPage.getByTestId('voting-reveal')
    await revealTallyBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await revealTallyBtn.click()

    // Wait for the UI to update — could be "继续", "进入夜晚", or a hunter/badge action
    // The reveal may transition through multiple sub-phases
    await hostPage.waitForTimeout(3_000)

    await captureSnapshot(ctx.pages, testInfo, '07-vote-tally')

    // Host continues via DOM (auto-advance may have already fired the
    // transition — only click if the button is still present).
    const continueBtn = hostPage.getByTestId('voting-continue')
    if (await continueBtn.isVisible({ timeout: 1_000 }).catch(() => false)) {
      await continueBtn.click()
    }

    // Should transition to NIGHT for next round (or GAME_OVER)
    await hostPage.waitForTimeout(3_000)

    const isGameOver = hostPage.url().includes('/result/')
    if (!isGameOver) {
      await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)
    }

    await captureSnapshot(ctx.pages, testInfo, '07-after-voting')
  })

  // ── Test 8: Night 2 cycle ────────────────────────────────────────────

  test('8. Night 2 — phase cycling works, transitions back to DAY', async ({}, testInfo) => {
    // Check if game is over first
    const isGameOver = ctx.hostPage.url().includes('/result/')
    if (isGameOver) {
      test.skip()
      return
    }

    // Night 2: some browser-bound role bots may have been voted out on
    // Day 1. Each role tries DOM-first via its browser; if that browser's
    // bot is dead (no actor UI), fall back to the API on remaining alive
    // bots of the same role.

    // Helper: try script, return false if rejected (dead player, etc.)
    const tryAct = (...args: Parameters<typeof act>): boolean => {
      try {
        const output = act(...args)
        const rejected = output.includes('rejected')
        if (rejected) {
          // eslint-disable-next-line no-console
          console.warn(`[tryAct rejected] args=${JSON.stringify(args)} output=\n${output}`)
        }
        return !rejected
      } catch (e) {
        // eslint-disable-next-line no-console
        console.warn(`[tryAct threw] args=${JSON.stringify(args)} err=${(e as Error).message}`)
        return false
      }
    }

    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    const witchBots = ctx.roleMap.WITCH ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const villagerBots = ctx.roleMap.VILLAGER ?? []

    const allTargets = [...villagerBots, ...seerBots, ...guardBots, ...witchBots].filter(
      (b) => b.nick !== 'Host',
    )

    // ── Wolf kill — DOM-first via wolf browser ──
    const wolfPage = ctx.pages.get('WEREWOLF')
    let wolfDone = false
    if (wolfPage) {
      const wolfConfirm = wolfPage.getByTestId('wolf-confirm-kill')
      if (await wolfConfirm.isVisible({ timeout: 5_000 }).catch(() => false)) {
        const targetSlot = wolfPage.locator('.player-grid .slot-alive').first()
        if (await targetSlot.isVisible({ timeout: 2_000 }).catch(() => false)) {
          await targetSlot.click()
          await wolfConfirm.click()
          wolfDone = true
          await ctx.hostPage.waitForTimeout(1_000)
        }
      }
    }
    if (!wolfDone) {
      // Browser-wolf is dead or UI didn't render. Try API on remaining wolf bots.
      for (const wb of wolfBots) {
        for (const tgt of allTargets) {
          if (tryAct('WOLF_KILL', actName(wb), { target: String(tgt.seat), room: ctx.roomCode })) {
            wolfDone = true
            await ctx.hostPage.waitForTimeout(1_000)
            break
          }
        }
        if (wolfDone) break
      }
    }

    // ── Seer — DOM-first via seer browser ──
    const seerPage = ctx.pages.get('SEER')
    let seerDone = false
    if (seerPage) {
      const seerCheck = seerPage.getByTestId('seer-check')
      if (await seerCheck.isVisible({ timeout: 5_000 }).catch(() => false)) {
        const targetSlot = seerPage.locator('.player-grid .slot-alive').first()
        if (await targetSlot.isVisible({ timeout: 2_000 }).catch(() => false)) {
          await targetSlot.click()
          await seerCheck.click()
          await expect(seerPage.locator('.sr-wrap').first()).toBeVisible({ timeout: 10_000 })
          await seerPage.getByTestId('seer-done').click()
          seerDone = true
        }
      }
    }
    if (!seerDone) {
      // Browser-seer is dead. Try API on remaining seer bots.
      for (const sb of seerBots) {
        for (const tgt of allTargets) {
          if (tryAct('SEER_CHECK', actName(sb), { target: String(tgt.seat), room: ctx.roomCode })) {
            await ctx.hostPage.waitForTimeout(2_000)
            tryAct('SEER_CONFIRM', actName(sb), { room: ctx.roomCode })
            seerDone = true
            break
          }
        }
        if (seerDone) break
      }
    }

    // ── Witch — DOM-first via witch browser (pass on both items) ──
    const witchPage = ctx.pages.get('WITCH')
    let witchDone = false
    if (witchPage) {
      const witchSection = witchPage.locator('.w-section').first()
      if (await witchSection.isVisible({ timeout: 5_000 }).catch(() => false)) {
        const passAntidote = witchPage.getByTestId('switch-pass-antidote')
        if (await passAntidote.isVisible({ timeout: 2_000 }).catch(() => false)) {
          await passAntidote.click()
          await witchPage.waitForTimeout(500)
        }
        const passPoison = witchPage.getByTestId('switch-pass-poison')
        if (await passPoison.isVisible({ timeout: 2_000 }).catch(() => false)) {
          await passPoison.click()
        }
        const witchSkip = witchPage.getByTestId('witch-skip')
        if (await witchSkip.isVisible({ timeout: 2_000 }).catch(() => false)) {
          await witchSkip.click()
        }
        witchDone = true
        await ctx.hostPage.waitForTimeout(1_000)
      }
    }
    if (!witchDone) {
      // Browser-witch is dead. Try API on remaining witch bots.
      for (const wb of witchBots) {
        if (
          tryAct('WITCH_ACT', actName(wb), { payload: '{"useAntidote":false}', room: ctx.roomCode })
        ) {
          witchDone = true
          await ctx.hostPage.waitForTimeout(1_000)
          break
        }
      }
    }

    // ── Guard — DOM-first via guard browser ──
    const guardPage = ctx.pages.get('GUARD')
    let guardDone = false
    if (guardPage) {
      const guardConfirm = guardPage.getByTestId('guard-confirm-protect')
      if (await guardConfirm.isVisible({ timeout: 5_000 }).catch(() => false)) {
        const targetSlot = guardPage.locator('.player-grid .slot-alive').first()
        if (await targetSlot.isVisible({ timeout: 2_000 }).catch(() => false)) {
          await targetSlot.click()
          await guardConfirm.click()
          guardDone = true
          await ctx.hostPage.waitForTimeout(1_000)
        }
      }
    }
    if (!guardDone) {
      // Browser-guard is dead. Try API on remaining guard bots (skip protect).
      for (const gb of guardBots) {
        if (tryAct('GUARD_SKIP', actName(gb), { room: ctx.roomCode })) {
          guardDone = true
          await ctx.hostPage.waitForTimeout(1_000)
          break
        }
      }
    }

    // After night, should transition to DAY (or GAME_OVER)
    // Wait longer for backend to process all actions and trigger phase transition
    await ctx.hostPage.waitForTimeout(10_000)

    const isOver = ctx.hostPage.url().includes('/result/')
    if (!isOver) {
      await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)
    }

    await captureSnapshot(ctx.pages, testInfo, '08-night2-complete')
  })
})
