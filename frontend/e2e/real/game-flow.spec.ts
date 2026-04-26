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
import { expect, type Page, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { act, actName, type RoleName } from './helpers/shell-runner'
import { verifyAllBrowsersPhase } from './helpers/assertions'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import {
  readHostUserId,
  readUnvotedAlivePlayerIds,
  waitForAllVotesRegistered,
  waitForCondition,
  waitForNightSubPhase,
  waitForNightSubPhaseChange,
  waitForPhase,
  waitForVoteRegistered,
  waitForVotingSubPhase,
} from './helpers/state-polling'
import { assertNoBrowserErrors } from './helpers/error-sentinel'
import {
  assertGameInvariants,
  type GameInvariantState,
  newInvariantState,
} from './helpers/invariants'

let ctx: GameContext
// Shared across the describe block — assertGameInvariants returns the
// updated snapshot after each call so we can compare every step's
// state to the previous step's. Initialized in beforeAll.
let invariants: GameInvariantState = newInvariantState()

// Action-observability ledger: each gameplay action records its
// expected DOM/state side-effect, and a later step asserts the
// expectation matches reality. Without this the test could "succeed"
// even when the action targeted the wrong seat or its result was lost.
interface ExpectedNightOutcome {
  wolfTargetSeat: number | null
  witchSavedTarget: boolean
}
let nightOneOutcome: ExpectedNightOutcome = { wolfTargetSeat: null, witchSavedTarget: false }

/** Look up the role of a player by seat. Reads from ctx.roleMap (built
 *  at game start by roles.sh). Returns null if no bot/host has that seat. */
function roleOfSeat(seat: number): RoleName | null {
  for (const [role, bots] of Object.entries(ctx.roleMap) as [RoleName, typeof ctx.allBots][]) {
    if (bots.some((b) => b.seat === seat)) return role
  }
  return null
}

test.describe('Game flow — multi-browser STOMP verification', () => {
  test.setTimeout(60_000) // 3 minutes for the full flow

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000) // setup can take a while with shell scripts
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[],
    })
    invariants = newInvariantState()
    nightOneOutcome = { wolfTargetSeat: null, witchSavedTarget: false }
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
    // Invariant guard #4: cheap state read after every step — phase rank
    // monotonic, alive count never grows, sub-phase belongs to parent,
    // sheriff alive (or in BADGE_HANDOVER / GAME_OVER). The returned
    // state threads into the next test step.
    invariants = await assertGameInvariants(
      ctx.hostPage,
      ctx.gameId,
      invariants,
      testInfo.title,
    )
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
    // Action observability: capture the wolf's target seat from the slot's
    // data-seat attribute BEFORE clicking. Test 5 asserts the day-result
    // banner is consistent with this target (witch-saved → peaceful).
    const wolfSeatAttr = await wolfTargetSlot.getAttribute('data-seat')
    nightOneOutcome.wolfTargetSeat = wolfSeatAttr ? Number(wolfSeatAttr) : null
    expect(
      nightOneOutcome.wolfTargetSeat,
      'wolf target slot must expose data-seat',
    ).not.toBeNull()
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
    // Action observability: record which seat the seer is about to check
    // so we can verify the result card shows the correct alignment for
    // that seat's actual role (cross-referenced via ctx.roleMap).
    const seerCheckedSeatAttr = await seerTargetSlot.getAttribute('data-seat')
    expect(
      seerCheckedSeatAttr,
      'seer target slot must expose data-seat',
    ).not.toBeNull()
    const seerCheckedSeat = Number(seerCheckedSeatAttr)
    await seerTargetSlot.click()
    await seerCheckBtn.click()

    // Result card surfaces with data-alignment ('wolf' | 'village') and
    // data-checked-seat. Verify those match the actual role of the
    // checked player. A bug here means the seer is being LIED to.
    const resultCard = seerPage.getByTestId('seer-result-card')
    await expect(resultCard).toBeVisible({ timeout: 10_000 })
    const resultSeat = Number(await resultCard.getAttribute('data-checked-seat'))
    const resultAlignment = await resultCard.getAttribute('data-alignment')
    expect(resultSeat).toBe(seerCheckedSeat)
    const actualRole = roleOfSeat(seerCheckedSeat)
    const expectedAlignment = actualRole === 'WEREWOLF' ? 'wolf' : 'village'
    expect(
      resultAlignment,
      `seer-result-card alignment for seat ${seerCheckedSeat} (role=${actualRole})`,
    ).toBe(expectedAlignment)

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
      // Wait for the poison grid to render — use-poison click should
      // surface poison-mode-cancel and the small target grid.
      const cancelBtn = witchPage.getByTestId('poison-mode-cancel')
      await expect(cancelBtn).toBeVisible({ timeout: 5_000 })

      await captureSnapshot(ctx.pages, testInfo, '04-witch-poison-select')

      const poisonTarget = witchPage.locator('.player-grid-sm .slot-alive').first()
      if (await poisonTarget.isVisible().catch(() => false)) {
        await poisonTarget.click()
        // Confirm button only enables after a target is selected — its
        // enabled state proves the click was registered.
        await expect(witchPage.getByTestId('witch-poison-confirm')).toBeEnabled({
          timeout: 5_000,
        })

        await captureSnapshot(ctx.pages, testInfo, '04-witch-poison-selected')

        // Cancel — we don't actually want to poison in round 1.
        await cancelBtn.click()
        // Cancelling exits poison mode — use-poison button reappears.
        await expect(usePoisonBtn).toBeVisible({ timeout: 5_000 })
      }
    }

    // -- Antidote decision --
    const useAntidoteBtn = witchPage.getByTestId('witch-antidote')
    if (await useAntidoteBtn.isVisible().catch(() => false)) {
      await captureSnapshot(ctx.pages, testInfo, '04-witch-antidote-choice')

      await useAntidoteBtn.click()
      // Action observability: witch saved the wolf's target. Test 5
      // asserts the day banner is the peaceful variant.
      nightOneOutcome.witchSavedTarget = true
      // After antidote click, the antidote section is consumed — the
      // skip-poison or witch-skip button should surface as the next
      // decision. Wait for either rather than for a fixed 500ms.
      await expect(
        witchPage.locator(
          '[data-testid="switch-pass-poison"], [data-testid="witch-skip"]',
        ),
      ).toBeVisible({ timeout: 5_000 })

      await captureSnapshot(ctx.pages, testInfo, '04-witch-after-antidote')
    }

    // -- Skip poison (if still available after antidote) --
    const skipPoisonBtn = witchPage.getByTestId('switch-pass-poison')
    if (await skipPoisonBtn.isVisible().catch(() => false)) {
      await skipPoisonBtn.click()
      // Skipping poison ends the witch turn — wait for the night
      // sub-phase to advance past WITCH_ACT (proves the backend
      // received the action) before continuing.
      await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 8_000)
      await captureSnapshot(ctx.pages, testInfo, '04-witch-after-action')
    }

    // If no items at all (rare: both antidote+poison already consumed
    // in earlier rounds), click the done button.
    const doneBtn = witchPage.getByTestId('witch-skip')
    if (await doneBtn.isVisible().catch(() => false)) {
      await doneBtn.click()
      await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 8_000)
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

    // Action observability: in Night 1 the wolf killed seat
    // `nightOneOutcome.wolfTargetSeat` and the witch antidoted. The
    // expected banner is therefore the PEACEFUL variant on every
    // browser, AND the wolf's target seat must NOT appear in any
    // kill list. If the witch save was lost we'd see the kill banner
    // here — exactly the kind of bug the existing "phase advanced"
    // check would have masked.
    const expectedPeaceful = nightOneOutcome.witchSavedTarget
    const wolfTarget = nightOneOutcome.wolfTargetSeat

    await Promise.all(
      Array.from(ctx.pages.values()).map(async (page) => {
        if (expectedPeaceful) {
          await expect(page.getByTestId('day-banner-peaceful')).toBeVisible({
            timeout: 10_000,
          })
          if (wolfTarget !== null) {
            // Even though the kill banner shouldn't be present, double-check
            // the wolf's saved target is NOT listed as killed anywhere.
            await expect(
              page.getByTestId(`day-killed-seat-${wolfTarget}`),
            ).toHaveCount(0)
          }
        } else if (wolfTarget !== null) {
          // No witch save → the wolf's target should appear in the kill list.
          await expect(
            page.getByTestId(`day-killed-seat-${wolfTarget}`),
          ).toBeVisible({ timeout: 10_000 })
        } else {
          await expect(page.locator('.day-wrap .banner').first()).toBeVisible({
            timeout: 10_000,
          })
        }
      }),
    )

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
    const hostId = await readHostUserId(ctx.hostPage)

    // Host votes FIRST via browser — abstain
    // (Must do this BEFORE bot votes, since act() now includes host in "all" players)
    const abstainBtn = hostPage.locator('.skip-btn').first()
    await abstainBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await abstainBtn.click()
    // Confirm the abstain registered before bots fan out — otherwise the
    // backend may still see the host as "unvoted" and accept the bot
    // SUBMIT_VOTE that act.sh fans out on the host's behalf.
    if (hostId) {
      await waitForVoteRegistered(ctx.hostPage, ctx.gameId, hostId, 5_000)
    }

    // Find a wolf target to vote for (use the first alive wolf bot)
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const wolfTarget = wolfBots.find((b) => b.nick !== 'Host')

    // Fan-out the vote only to bots that are alive, not host, and haven't
    // voted yet — the host already clicked abstain above so their userId is
    // in state.votingPhase.votedPlayerIds. Without the filter, act.sh would
    // iterate every bot (plus host) and the redundant attempts on the
    // already-voted host would burn act.sh's 3× retry quota on rejection.
    const unvoted = await readUnvotedAlivePlayerIds(ctx.hostPage, ctx.gameId)
    const voteOpts: { target?: string; room: string } = wolfTarget
      ? { target: String(wolfTarget.seat), room: ctx.roomCode }
      : { room: ctx.roomCode }
    const expectedVoterIds: string[] = []
    for (const bot of ctx.allBots) {
      if (bot.nick === 'Host' || bot.userId === hostId) continue
      if (!unvoted.has(bot.userId)) continue
      act('SUBMIT_VOTE', bot.nick, voteOpts)
      expectedVoterIds.push(bot.userId)
    }

    // Wait until every fan-out vote is registered before revealing the
    // tally — without this, the reveal can race the last vote and produce
    // an inconsistent count.
    if (expectedVoterIds.length > 0) {
      await waitForAllVotesRegistered(ctx.hostPage, ctx.gameId, expectedVoterIds, 10_000)
    }

    // Host reveals tally via DOM — exercises the host's reveal button
    // wiring + STOMP fan-out, not just the API path.
    const revealTallyBtn = hostPage.getByTestId('voting-reveal')
    await revealTallyBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await revealTallyBtn.click()

    // The reveal click should advance the voting sub-phase to VOTE_RESULT
    // (or HUNTER_SHOOT / BADGE_HANDOVER if the eliminated player triggers
    // those). Either way, the sub-phase changes from VOTING.
    await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'VOTE_RESULT', 6_000)

    await captureSnapshot(ctx.pages, testInfo, '07-vote-tally')

    // Host continues via DOM (auto-advance may have already fired the
    // transition — only click if the button is still present).
    const continueBtn = hostPage.getByTestId('voting-continue')
    const continueVisible = await continueBtn
      .waitFor({ state: 'visible', timeout: 2_000 })
      .then(() => true)
      .catch(() => false)
    if (continueVisible) {
      await continueBtn.click()
    }

    // Should transition to NIGHT for next round (or GAME_OVER). The
    // verifyAllBrowsersPhase below has its own internal wait — no
    // separate buffer needed here.
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

    // Locator visibility helper — Playwright's isVisible() does NOT retry,
    // so wrapping waitFor lets us return a boolean after a real wait.
    const isVisibleSoon = async (page: Page, testId: string, timeoutMs = 5_000) =>
      page
        .getByTestId(testId)
        .waitFor({ state: 'visible', timeout: timeoutMs })
        .then(() => true)
        .catch(() => false)

    // ── Wolf kill — DOM-first via wolf browser ──
    const wolfPage = ctx.pages.get('WEREWOLF')
    let wolfDone = false
    if (wolfPage && (await isVisibleSoon(wolfPage, 'wolf-confirm-kill'))) {
      const targetSlot = wolfPage.locator('.player-grid .slot-alive').first()
      const slotReady = await targetSlot
        .waitFor({ state: 'visible', timeout: 2_000 })
        .then(() => true)
        .catch(() => false)
      if (slotReady) {
        await targetSlot.click()
        await wolfPage.getByTestId('wolf-confirm-kill').click()
        wolfDone = true
        // Confirm backend processed the kill before moving to seer/witch
        // — otherwise the sub-phase polling for the next role can race
        // against the wolf's coroutine commit.
        await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 8_000)
      }
    }
    if (!wolfDone) {
      // Browser-wolf is dead or UI didn't render. Try API on remaining wolf bots.
      for (const wb of wolfBots) {
        for (const tgt of allTargets) {
          if (tryAct('WOLF_KILL', actName(wb), { target: String(tgt.seat), room: ctx.roomCode })) {
            wolfDone = true
            await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 8_000)
            break
          }
        }
        if (wolfDone) break
      }
    }

    // ── Seer — DOM-first via seer browser ──
    const seerPage = ctx.pages.get('SEER')
    let seerDone = false
    if (seerPage && (await isVisibleSoon(seerPage, 'seer-check'))) {
      const targetSlot = seerPage.locator('.player-grid .slot-alive').first()
      const slotReady = await targetSlot
        .waitFor({ state: 'visible', timeout: 2_000 })
        .then(() => true)
        .catch(() => false)
      if (slotReady) {
        // Action observability: capture the checked seat and verify the
        // alignment matches actual role — same contract as Night 1, so a
        // Night-2 seer-result regression is caught the same way.
        const seatAttr = await targetSlot.getAttribute('data-seat')
        const checkedSeat = seatAttr ? Number(seatAttr) : null
        await targetSlot.click()
        await seerPage.getByTestId('seer-check').click()
        const card = seerPage.getByTestId('seer-result-card')
        await expect(card).toBeVisible({ timeout: 10_000 })
        if (checkedSeat !== null) {
          const resultSeat = Number(await card.getAttribute('data-checked-seat'))
          expect(resultSeat).toBe(checkedSeat)
          const resultAlignment = await card.getAttribute('data-alignment')
          const actualRole = roleOfSeat(checkedSeat)
          // actualRole may be null if the seer happened to check the
          // host without a bot entry; in that case skip the strict
          // alignment check rather than fail noisily.
          if (actualRole) {
            const expectedAlignment = actualRole === 'WEREWOLF' ? 'wolf' : 'village'
            expect(
              resultAlignment,
              `night-2 seer alignment for seat ${checkedSeat} (role=${actualRole})`,
            ).toBe(expectedAlignment)
          }
        }
        await seerPage.getByTestId('seer-done').click()
        // Seer-done advances backend from SEER_RESULT to next role.
        await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'SEER_RESULT', 8_000)
        seerDone = true
      }
    }
    if (!seerDone) {
      // Browser-seer is dead. Try API on remaining seer bots.
      for (const sb of seerBots) {
        for (const tgt of allTargets) {
          if (tryAct('SEER_CHECK', actName(sb), { target: String(tgt.seat), room: ctx.roomCode })) {
            // Backend transitions SEER_PICK → SEER_RESULT after CHECK,
            // then SEER_RESULT → next sub-phase after CONFIRM.
            await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'SEER_RESULT', 5_000)
            tryAct('SEER_CONFIRM', actName(sb), { room: ctx.roomCode })
            await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'SEER_RESULT', 8_000)
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
      const sectionReady = await witchPage
        .locator('.w-section')
        .first()
        .waitFor({ state: 'visible', timeout: 5_000 })
        .then(() => true)
        .catch(() => false)
      if (sectionReady) {
        const passAntidote = witchPage.getByTestId('switch-pass-antidote')
        if (
          await passAntidote
            .waitFor({ state: 'visible', timeout: 2_000 })
            .then(() => true)
            .catch(() => false)
        ) {
          await passAntidote.click()
        }
        const passPoison = witchPage.getByTestId('switch-pass-poison')
        if (
          await passPoison
            .waitFor({ state: 'visible', timeout: 2_000 })
            .then(() => true)
            .catch(() => false)
        ) {
          await passPoison.click()
        }
        const witchSkip = witchPage.getByTestId('witch-skip')
        if (
          await witchSkip
            .waitFor({ state: 'visible', timeout: 2_000 })
            .then(() => true)
            .catch(() => false)
        ) {
          await witchSkip.click()
        }
        await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 8_000)
        witchDone = true
      }
    }
    if (!witchDone) {
      // Browser-witch is dead. Try API on remaining witch bots.
      for (const wb of witchBots) {
        if (
          tryAct('WITCH_ACT', actName(wb), { payload: '{"useAntidote":false}', room: ctx.roomCode })
        ) {
          await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 8_000)
          witchDone = true
          break
        }
      }
    }

    // ── Guard — DOM-first via guard browser ──
    const guardPage = ctx.pages.get('GUARD')
    let guardDone = false
    if (guardPage && (await isVisibleSoon(guardPage, 'guard-confirm-protect'))) {
      const targetSlot = guardPage.locator('.player-grid .slot-alive').first()
      const slotReady = await targetSlot
        .waitFor({ state: 'visible', timeout: 2_000 })
        .then(() => true)
        .catch(() => false)
      if (slotReady) {
        await targetSlot.click()
        await guardPage.getByTestId('guard-confirm-protect').click()
        await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'GUARD_PICK', 8_000)
        guardDone = true
      }
    }
    if (!guardDone) {
      // Browser-guard is dead. Try API on remaining guard bots (skip protect).
      for (const gb of guardBots) {
        if (tryAct('GUARD_SKIP', actName(gb), { room: ctx.roomCode })) {
          await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'GUARD_PICK', 8_000)
          guardDone = true
          break
        }
      }
    }

    // After night, the backend transitions NIGHT → DAY_PENDING → DAY_DISCUSSION.
    // Poll the backend rather than guess a 10-second buffer; if the game
    // ended (GAME_OVER), the page URL will already be /result/...
    const isOver = ctx.hostPage.url().includes('/result/')
    if (!isOver) {
      await waitForPhase(ctx.hostPage, ctx.gameId, 'DAY_DISCUSSION', 20_000)
      await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)
    }

    await captureSnapshot(ctx.pages, testInfo, '08-night2-complete')
  })
})
