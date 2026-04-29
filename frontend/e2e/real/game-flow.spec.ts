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
  test.setTimeout(180_000) // 3 min per test — N1 + N2 + N3 nights each cycle 4 roles

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000) // setup can take a while with shell scripts
    // Explicit `roles` is required: setupGame's role-toggle block is only
    // entered when opts.roles is non-empty (multi-browser.ts:152). Without
    // this, the room defaults to CreateRoomView's enabledOptional set
    // {SEER, WITCH, HUNTER} — GUARD is OFF, no guard bot is assigned, and
    // ctx.pages.get('GUARD') returns undefined, crashing Test 4 with
    // "Cannot read properties of undefined (reading 'getByTestId')".
    // Verified locally: roles.sh on a fresh game showed
    // WEREWOLF×3 + SEER + WITCH + HUNTER + VILLAGER, no GUARD.
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'HUNTER', 'VILLAGER'] as RoleName[],
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
    invariants = await assertGameInvariants(ctx.hostPage, ctx.gameId, invariants, testInfo.title)
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
    expect(nightOneOutcome.wolfTargetSeat, 'wolf target slot must expose data-seat').not.toBeNull()
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
    expect(seerCheckedSeatAttr, 'seer target slot must expose data-seat').not.toBeNull()
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

    // -- Antidote decision: clicking antidote submits a COMPLETE WITCH_ACT
    //    (useAntidote=true, poisonTargetUserId=null) — the witch turn ends
    //    on the backend with this single click. Do NOT click skip-poison
    //    afterwards: that would send a second WITCH_ACT during the
    //    inter-role-gap window, race the role-loop's queuedActionSignal
    //    map, and auto-complete GUARD_PICK in 2 ms before the guard's
    //    browser can render guard-confirm-protect.
    //
    //    The witch UI's antidote/poison panels are independent visually —
    //    after antidote, skip-poison is still rendered (poisonDecided
    //    only flips when poison-specific action arrives). That's a
    //    frontend display nuance; under the hood the night is already
    //    advancing.
    const useAntidoteBtn = witchPage.getByTestId('witch-antidote')
    const passAntidoteBtn = witchPage.getByTestId('switch-pass-antidote')
    const witchSkipBtn = witchPage.getByTestId('witch-skip')

    if (await useAntidoteBtn.isVisible().catch(() => false)) {
      await captureSnapshot(ctx.pages, testInfo, '04-witch-antidote-choice')
      await useAntidoteBtn.click()
      nightOneOutcome.witchSavedTarget = true
      // Confirm WITCH_ACT actually advanced server-side before moving on
      // to the guard block — otherwise the test races the night loop.
      await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 8_000)
      await captureSnapshot(ctx.pages, testInfo, '04-witch-after-antidote')
    } else if (await passAntidoteBtn.isVisible().catch(() => false)) {
      // Witch has antidote but no kill happened (or witch already used
      // antidote in a prior round). Pass cleanly with one click.
      await passAntidoteBtn.click()
      await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 8_000)
    } else if (await witchSkipBtn.isVisible().catch(() => false)) {
      // No items at all (both consumed earlier rounds): single done click.
      await witchSkipBtn.click()
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
            await expect(page.getByTestId(`day-killed-seat-${wolfTarget}`)).toHaveCount(0)
          }
        } else if (wolfTarget !== null) {
          // No witch save → the wolf's target should appear in the kill list.
          await expect(page.getByTestId(`day-killed-seat-${wolfTarget}`)).toBeVisible({
            timeout: 10_000,
          })
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
    // Precondition contract: by this point, tests 1–7 have driven the game
    // through ROLE_REVEAL → NIGHT (day=1) → DAY → DAY_VOTING (day=1) →
    // NIGHT (day=2). 9p kit has 3 wolves (GameService.kt:316), test 7 voted
    // out 1 wolf, the POST_VOTE check sees 2 wolves vs 6 humans, no win
    // triggers, and the backend transitions to NIGHT day=2.
    //
    // Assert the contract explicitly. If a future change in test 7 (or in
    // role distribution) leaves the game in any other state, this assertion
    // fails with the actual phase/sub-phase from /api/game/{id}/state, and
    // the failure points straight at the violated invariant.
    const reachedNight = await waitForPhase(ctx.hostPage, ctx.gameId, 'NIGHT', 15_000)
    expect(reachedNight, 'expected NIGHT day=2 after test 7').toBe(true)
    const reachedWolfPick = await waitForNightSubPhase(
      ctx.hostPage,
      ctx.gameId,
      'WEREWOLF_PICK',
      15_000,
    )
    expect(reachedWolfPick, 'expected NIGHT/WEREWOLF_PICK on day=2 entry').toBe(true)

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

    // Read the live-alive set from /api/game/state so the API fallback only
    // tries actors / targets that are actually alive on the backend. Without
    // this filter, a wolf voted out at D1 stays in roleMap (which is built
    // at game start) and the inner loop fans 4 targets × act.sh's 3 retries
    // = 12-15 s burned per dead actor before reaching a live wolf.
    //
    // act.sh now short-circuits "Actor is dead" via PERMANENT_REJECTION_RE,
    // but cutting the dead-actor call upstream is faster AND surfaces the
    // intent ("we know who's alive") in the test code.
    const aliveIds = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return [] as string[]
      const state = await res.json()
      return ((state?.players ?? []) as Array<{ isAlive?: boolean; userId: string }>)
        .filter((p) => p.isAlive !== false)
        .map((p) => p.userId)
    }, ctx.gameId)
    const aliveSet = new Set(aliveIds)
    // Don't filter Host out. When host has a special role (e.g. host=WITCH)
    // and the role's DOM-first path doesn't fire (browser stale, slot
    // selector mismatch), the API fallback needs the host as the actor —
    // otherwise the night stalls at that sub-phase forever. actName(host)
    // returns 'HOST' and act.sh resolves to the cached host token, so
    // host-as-X works through the same script path.
    const aliveActorsOf = (role: RoleName) =>
      (ctx.roleMap[role] ?? []).filter((b) => aliveSet.has(b.userId))

    const wolfBots = aliveActorsOf('WEREWOLF')
    const seerBots = aliveActorsOf('SEER')
    const witchBots = aliveActorsOf('WITCH')
    const guardBots = aliveActorsOf('GUARD')
    const villagerBots = aliveActorsOf('VILLAGER')

    // Targets exclude wolves (a wolf can't kill another wolf) AND the host. The
    // host drives later steps that require host-alive UI — `.skip-btn` in
    // VotingPhase.vue is gated on `viewRole === 'ALIVE'`, so killing the host
    // here makes the day-2 abstain wait time out and tests 9-10 cascade-fail.
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
      // Browser-seer is dead. Try API on remaining seer bots. Exclude the
      // seer's own seat from candidate targets — `allTargets` includes
      // seerBots, so without this filter the inner loop tries SEER_CHECK
      // self-check, backend rejects "Cannot check yourself" (3× retries
      // pollute the log), then moves to the next target.
      for (const sb of seerBots) {
        for (const tgt of allTargets) {
          if (tgt.userId === sb.userId) continue
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

    // ── Witch — DOM-first via witch browser. ONE click only.
    //   Each witch button submits a complete WITCH_ACT (useAntidote +
    //   poisonTargetUserId in one payload). Clicking a second button
    //   sends a SECOND WITCH_ACT during the inter-role-gap window,
    //   which races queuedActionSignals[gameId] and auto-completes
    //   the next role's sub-phase in 2 ms — the bug that was failing
    //   GUARD_PICK on Night 1. Pick whichever pass button is visible
    //   and stop there.
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
        const passPoison = witchPage.getByTestId('switch-pass-poison')
        const witchSkip = witchPage.getByTestId('witch-skip')
        const visibleSoon = (loc: ReturnType<typeof witchPage.getByTestId>) =>
          loc
            .waitFor({ state: 'visible', timeout: 2_000 })
            .then(() => true)
            .catch(() => false)

        if (await visibleSoon(passAntidote)) {
          await passAntidote.click()
        } else if (await visibleSoon(passPoison)) {
          await passPoison.click()
        } else if (await visibleSoon(witchSkip)) {
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
    // Poll the backend's `phase` field rather than the URL — STOMP-driven
    // /result/ redirect lags backend commit, so URL-only check misses
    // GAME_OVER on CI. /api/game/state is the authoritative signal.
    const phaseAfterNight = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    if (phaseAfterNight !== 'GAME_OVER') {
      await waitForPhase(ctx.hostPage, ctx.gameId, 'DAY_DISCUSSION', 20_000)
      await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)
    }

    await captureSnapshot(ctx.pages, testInfo, '08-night2-complete')
  })

  // ── Test 9: Day 2 voting cycle ────────────────────────────────────────
  // Verifies the voting machinery still works on day 2: host reveals N2
  // results, opens the vote, bots vote a wolf out, tally → VOTE_RESULT,
  // continue → NIGHT day=3. This catches regressions where the second
  // voting round breaks (e.g. day-counter off-by-one, sub-phase reset
  // missing, allVoted check uses stale day-1 voter set).

  test('9. Day 2 voting — vote second wolf, transition to NIGHT day=3', async ({}, testInfo) => {
    const hostPage = ctx.hostPage

    // Precondition: test 8 left us in DAY_DISCUSSION (day=2) with the
    // night-2 result hidden.
    await waitForPhase(hostPage, ctx.gameId, 'DAY_DISCUSSION', 15_000)

    // Host reveals N2 result (whatever it was — depends on whether witch
    // had antidote remaining; in this spec test 4 spent it, so N2 kills land).
    const revealBtn = hostPage.getByTestId('day-reveal-result')
    await revealBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await revealBtn.click()
    await captureSnapshot(ctx.pages, testInfo, '09-day2-reveal')

    // Host opens voting.
    const startVoteBtn = hostPage.getByTestId('day-start-vote')
    await startVoteBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await startVoteBtn.click()
    await waitForVotingSubPhase(hostPage, ctx.gameId, 'VOTING', 10_000)

    // Host abstains first so the bot fan-out doesn't burn act.sh's retry
    // quota on the host (already-voted check).
    const hostId = await readHostUserId(hostPage)
    const abstainBtn = hostPage.locator('.skip-btn').first()
    await abstainBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await abstainBtn.click()
    if (hostId) {
      await waitForVoteRegistered(hostPage, ctx.gameId, hostId, 5_000)
    }

    // Pick the next alive wolf as target. roleMap is from setup-time, but
    // we filter by the live-alive set so a dead bot from test 7 isn't
    // chosen.
    const aliveIds = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return [] as string[]
      const state = await res.json()
      return ((state?.players ?? []) as Array<{ isAlive?: boolean; userId: string }>)
        .filter((p) => p.isAlive !== false)
        .map((p) => p.userId)
    }, ctx.gameId)
    const aliveSet = new Set(aliveIds)
    const wolfTarget = (ctx.roleMap.WEREWOLF ?? []).find(
      (b) => b.nick !== 'Host' && aliveSet.has(b.userId),
    )
    expect(wolfTarget, 'expected an alive non-host wolf for D2 vote').toBeDefined()

    // Fan out the vote across alive non-host bots that haven't already voted.
    const unvoted = await readUnvotedAlivePlayerIds(hostPage, ctx.gameId)
    const expectedVoterIds: string[] = []
    for (const bot of ctx.allBots) {
      if (bot.nick === 'Host' || bot.userId === hostId) continue
      if (!unvoted.has(bot.userId)) continue
      act('SUBMIT_VOTE', bot.nick, { target: String(wolfTarget!.seat), room: ctx.roomCode })
      expectedVoterIds.push(bot.userId)
    }
    if (expectedVoterIds.length > 0) {
      await waitForAllVotesRegistered(hostPage, ctx.gameId, expectedVoterIds, 10_000)
    }

    // Reveal tally.
    const revealTallyBtn = hostPage.getByTestId('voting-reveal')
    await revealTallyBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await revealTallyBtn.click()
    await waitForVotingSubPhase(hostPage, ctx.gameId, 'VOTE_RESULT', 6_000)

    await captureSnapshot(ctx.pages, testInfo, '09-day2-tally')

    // Continue to night.
    const continueBtn = hostPage.getByTestId('voting-continue')
    const continueVisible = await continueBtn
      .waitFor({ state: 'visible', timeout: 5_000 })
      .then(() => true)
      .catch(() => false)
    if (continueVisible) {
      await continueBtn.click()
    }

    // Either game continues to N3 (wolves > 0), or it ends right here
    // (POST_VOTE check fired villager-win because we voted the second
    // wolf and the third was killed somewhere already — unusual but
    // possible if a witch poisoned earlier). Both are valid outcomes;
    // this test asserts the deterministic part: the vote machinery
    // produced a sub-phase transition.
    //
    // Use API state, not URL, to detect game-end. The URL → /result/ redirect
    // is driven by a STOMP GameOverEvent → router.push, which lags backend
    // commit by 200-500 ms locally and longer under CI load. Polling
    // /api/game/state.phase === 'GAME_OVER' is the authoritative signal.
    const phaseAfterContinue = await hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    if (phaseAfterContinue !== 'GAME_OVER') {
      await waitForPhase(hostPage, ctx.gameId, 'NIGHT', 15_000)
      await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)
    }
    await captureSnapshot(ctx.pages, testInfo, '09-day2-complete')
  })

  // ── Test 10: Night 3 cycle ────────────────────────────────────────────
  // Verifies the night machinery still cycles on day 3 — same loop as
  // test 8 but one round later, after another wolf and another villager
  // have been eliminated. The browser-bound role bot may have died at
  // any point; each role tries DOM first, then falls back to API on
  // remaining live bots.

  test('10. Night 3 — cycle continues past day 2, transitions back to DAY day=3', async ({}, testInfo) => {
    // If the game ended at D2 (test 9 hit a villager-win edge), there's
    // nothing to test here — assert that fact and exit cleanly. NOT a
    // skip: a clean exit with a captured screenshot is the correct
    // outcome for the early-end branch.
    //
    // Detect via API state.phase, NOT URL. STOMP-driven router.push to
    // /result/ lags backend commit; on CI the URL can still be /game/N
    // even after phase=GAME_OVER, which then makes this guard miss the
    // early-end and the NIGHT precondition assertion below fail with
    // "expected NIGHT day=3 after test 9" — exactly the shape of the
    // CI shard-1 failure on commit f757f1e.
    const initialState = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? res.json() : null
    }, ctx.gameId)
    if (initialState?.phase === 'GAME_OVER') {
      await captureSnapshot(ctx.pages, testInfo, '10-game-over-at-d2')
      return
    }

    // Precondition: NIGHT day=3 / WEREWOLF_PICK after test 9.
    expect(
      await waitForPhase(ctx.hostPage, ctx.gameId, 'NIGHT', 15_000),
      'expected NIGHT day=3 after test 9',
    ).toBe(true)
    expect(
      await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 15_000),
      'expected NIGHT/WEREWOLF_PICK on day=3 entry',
    ).toBe(true)

    // Helper: wrap script call to surface rejections (same idiom as test 8).
    const tryAct = (...args: Parameters<typeof act>): boolean => {
      try {
        const out = act(...args)
        const rejected = out.includes('rejected')
        if (rejected) {
          // eslint-disable-next-line no-console
          console.warn(`[tryAct rejected] args=${JSON.stringify(args)} output=\n${out}`)
        }
        return !rejected
      } catch (e) {
        // eslint-disable-next-line no-console
        console.warn(`[tryAct threw] args=${JSON.stringify(args)} err=${(e as Error).message}`)
        return false
      }
    }

    // Read live alive set from the API for live filtering of role bots.
    const aliveIds = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return [] as string[]
      const state = await res.json()
      return ((state?.players ?? []) as Array<{ isAlive?: boolean; userId: string }>)
        .filter((p) => p.isAlive !== false)
        .map((p) => p.userId)
    }, ctx.gameId)
    const aliveSet = new Set(aliveIds)

    // Don't filter Host out of role actors. When host has a special role
    // and is the only alive actor (e.g. host=WITCH, witch is the sole
    // witch in the kit), filtering out the host leaves the role unactioned
    // and the night stalls at that sub-phase forever (CI shard-1 failure
    // on commit 67fb784 hit this with host-as-WITCH at N3 — game stuck at
    // NIGHT/WITCH_ACT day=3). actName() returns 'HOST' for the host bot
    // and act.sh resolves that to the cached host token, so host-as-X
    // routes through the same script path as a bot-as-X.
    const wolfBot = (ctx.roleMap.WEREWOLF ?? []).find((b) => aliveSet.has(b.userId))
    const seerBot = (ctx.roleMap.SEER ?? []).find((b) => aliveSet.has(b.userId))
    const witchBot = (ctx.roleMap.WITCH ?? []).find((b) => aliveSet.has(b.userId))
    const guardBot = (ctx.roleMap.GUARD ?? []).find((b) => aliveSet.has(b.userId))

    const isVisibleSoon = async (page: Page, testId: string, timeoutMs = 5_000) =>
      page
        .getByTestId(testId)
        .waitFor({ state: 'visible', timeout: timeoutMs })
        .then(() => true)
        .catch(() => false)

    // ── Wolf kill ──
    // After 2 day votes (test 7 + test 9), the originally browser-bound
    // WEREWOLF bot may be dead (voted out). The remaining alive wolf can
    // be the host (when host rolled WEREWOLF) — in which case `wolfBot`
    // (filtered to non-host) is undefined, but ctx.pages.get('WEREWOLF')
    // === hostPage (per setupGame's mapping at multi-browser.ts:362-364)
    // and the host's wolf-kill UI is rendered. DOM-first via the wolf
    // page handles host-as-wolf cleanly; API fallback handles the case
    // where the wolf page's bot is dead but another wolf bot is alive.
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
        await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 8_000)
      }
    }
    if (!wolfDone) {
      // wolfPage's bot is dead OR UI didn't render. Try API on remaining
      // alive non-host wolf bots.
      const wolfTargetCandidate = ctx.allBots.find(
        (b) =>
          b.nick !== 'Host' &&
          aliveSet.has(b.userId) &&
          !(ctx.roleMap.WEREWOLF ?? []).some((w) => w.userId === b.userId),
      )
      if (wolfBot && wolfTargetCandidate) {
        tryAct('WOLF_KILL', actName(wolfBot), {
          target: String(wolfTargetCandidate.seat),
          room: ctx.roomCode,
        })
        await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 8_000)
      }
    }

    // ── Seer check (just to advance the phase) ──
    if (seerBot) {
      const reached = await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'SEER_PICK', 10_000)
      if (reached) {
        const checkTarget = ctx.allBots.find(
          (b) => b.userId !== seerBot.userId && b.nick !== 'Host' && aliveSet.has(b.userId),
        )
        if (checkTarget) {
          tryAct('SEER_CHECK', actName(seerBot), {
            target: String(checkTarget.seat),
            room: ctx.roomCode,
          })
          await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'SEER_RESULT', 8_000)
          tryAct('SEER_CONFIRM', actName(seerBot), { room: ctx.roomCode })
        }
      }
    }

    // ── Witch act: by N3 the antidote is spent (test 4 used it). Just decline. ──
    if (witchBot) {
      const reached = await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 10_000)
      if (reached) {
        tryAct('WITCH_ACT', actName(witchBot), {
          room: ctx.roomCode,
          payload: '{"useAntidote":false}',
        })
      }
    }

    // ── Guard skip ──
    // GUARD_PROTECT carries a "cannot protect the same player two nights in
    // a row" rule (verified empirically — backend log on N3 with bot3
    // chosen at N2 returned `REJECTED reason="Cannot protect the same
    // player two nights in a row"`). Picking a deterministic-different
    // target across nights is brittle (the UI's `.slot-alive .first()`
    // ordering changes once kill targets are recorded). GUARD_SKIP doesn't
    // have this constraint — same as test 8's API fallback. Use it here to
    // advance the night cleanly.
    if (guardBot) {
      const reached = await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'GUARD_PICK', 10_000)
      if (reached) {
        const advanced = tryAct('GUARD_SKIP', actName(guardBot), { room: ctx.roomCode })
        if (advanced) {
          await waitForNightSubPhaseChange(ctx.hostPage, ctx.gameId, 'GUARD_PICK', 8_000)
        }
      }
    }

    // Backend transitions to DAY day=3, OR to GAME_OVER if a witch poison
    // at N3 kills the last wolf (rare but possible). Check API state first
    // so the GAME_OVER branch doesn't time out on waitForPhase DAY.
    const phaseAfterN3 = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    if (phaseAfterN3 !== 'GAME_OVER') {
      await waitForPhase(ctx.hostPage, ctx.gameId, 'DAY_DISCUSSION', 20_000)
      await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)
    }
    await captureSnapshot(ctx.pages, testInfo, '10-night3-complete')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Day 1 outcome scenarios — each reachable post-D1 end-state from the table in
// PR #75's review thread, asserted explicitly.
//
//   Row 1 (NIGHT/day=2)         — covered by the main flow's test 8.
//   Row 2 (GAME_OVER, villager) — this section.
//   Row 3 (GAME_OVER, wolves)   — this section.
//   Row 4 (HUNTER_SHOOT)        — this section.
//   Row 5 (BADGE_HANDOVER)      — covered by `flow-12p-sheriff.spec.ts` (CLASSIC + HARD_MODE).
//                                 Not reachable here: this spec uses hasSheriff=false.
//
// Each scenario starts a fresh `setupGame` because the kill plan needed to
// produce that end-state is incompatible with the prior test's game state.
// ─────────────────────────────────────────────────────────────────────────────

test.describe('Day 1 outcome scenarios — explicit end-state coverage', () => {
  test.setTimeout(180_000)

  // ── Row 2: villager-win when D1 vote eliminates the last wolf ──
  test('row 2 — villager-win after D1 vote kills the last wolf', async ({ browser }, testInfo) => {
    testInfo.setTimeout(240_000)
    // 6p kit: GameService.kt:316 → 2 wolves. Plus SEER + WITCH + 2 villagers
    // (HUNTER and GUARD intentionally off so D1's elimination cleanly
    // resolves to a win check, no HUNTER_SHOOT detour).
    const localCtx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
    })
    try {
      const hostPage = localCtx.hostPage
      // Don't filter host out: act.sh handles PLAYER='HOST' via the cached
      // host token (act.sh:378), so a host-as-WITCH/SEER/WEREWOLF row is
      // driveable through the same script path. The role lookup just needs
      // to return whoever holds the role, host or bot.
      const wolves = localCtx.roleMap.WEREWOLF ?? []
      const seer = (localCtx.roleMap.SEER ?? [])[0]
      const witch = (localCtx.roleMap.WITCH ?? [])[0]
      expect(wolves.length, 'kit must have 2 wolves').toBe(2)
      expect(seer, 'kit must have 1 seer').toBeDefined()
      expect(witch, 'kit must have 1 witch').toBeDefined()
      // eslint-disable-next-line no-console
      console.warn(
        `[row 2] hostRole=${localCtx.hostRole} wolves=${wolves.map((b) => b.nick).join(',')} ` +
          `seer=${seer.nick} witch=${witch.nick}`,
      )

      // Start night
      await hostPage.getByTestId('start-night').click()
      await waitForPhase(hostPage, localCtx.gameId, 'NIGHT', 15_000)

      // ── N1 ──
      // Wolves kill SOME victim. Backend rule (verified by log on a prior
      // run: REJECTED reason="Cannot use antidote and poison on the same
      // night"): witch can't use BOTH potions in one WITCH_ACT. So we
      // use poison only — the wolf-kill victim dies, AND wolves[1] dies
      // from poison. After N1: 2 deaths. wolves=1, humans=3. D1 vote of
      // wolves[0] then closes out the wolves → villager-win.
      const wolfIds = new Set(wolves.map((w) => w.userId))
      const victim =
        (localCtx.roleMap.VILLAGER ?? []).find((b) => !wolfIds.has(b.userId)) ??
        localCtx.allBots.find(
          (b) => !wolfIds.has(b.userId) && b.userId !== seer.userId && b.userId !== witch.userId,
        )
      expect(victim, 'need a non-wolf victim for the wolves to kill').toBeDefined()
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WEREWOLF_PICK', 15_000),
        'expected NIGHT/WEREWOLF_PICK before firing WOLF_KILL',
      ).toBe(true)
      act('WOLF_KILL', actName(wolves[0]), {
        target: String(victim!.seat),
        room: localCtx.roomCode,
      })

      // Seer checks (just to advance the phase deterministically). Assert
      // each gate so a wrong-sub-phase doesn't silently fire act() with
      // a "Not in <X> sub-phase" rejection in the CI log.
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_PICK', 15_000),
        'expected NIGHT/SEER_PICK before firing SEER_CHECK',
      ).toBe(true)
      act('SEER_CHECK', actName(seer), { target: String(wolves[0].seat), room: localCtx.roomCode })
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_RESULT', 10_000),
        'expected NIGHT/SEER_RESULT before firing SEER_CONFIRM',
      ).toBe(true)
      act('SEER_CONFIRM', actName(seer), { room: localCtx.roomCode })

      // Witch: poison-only on wolves[1]. No antidote (backend forbids
      // combined antidote+poison in a single WITCH_ACT).
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WITCH_ACT', 15_000),
        'expected NIGHT/WITCH_ACT before firing WITCH_ACT',
      ).toBe(true)
      act('WITCH_ACT', actName(witch), {
        room: localCtx.roomCode,
        payload: JSON.stringify({
          useAntidote: false,
          poisonTargetUserId: wolves[1].userId,
        }),
      })

      // Night resolves. victim dead (wolf kill, no save), wolves[1] dead (poison).
      await waitForPhase(hostPage, localCtx.gameId, 'DAY_DISCUSSION', 20_000)

      // ── D1 ──
      await hostPage.getByTestId('day-reveal-result').click()
      await hostPage.getByTestId('day-start-vote').click()
      await waitForVotingSubPhase(hostPage, localCtx.gameId, 'VOTING', 10_000)

      // Vote out wolves[0] (the surviving wolf). Iterate alive non-host
      // unvoted bots explicitly — `act('SUBMIT_VOTE', undefined, ...)` does
      // act.sh's full bot fan-out which includes dead bots, producing
      // "Dead players cannot vote" rejections in the CI log (the wolf
      // killed villagers[0] at N1, so by D1 they're dead).
      const unvoted1 = await readUnvotedAlivePlayerIds(hostPage, localCtx.gameId)
      for (const bot of localCtx.allBots) {
        if (bot.nick === 'Host') continue
        if (!unvoted1.has(bot.userId)) continue
        act('SUBMIT_VOTE', bot.nick, { target: String(wolves[0].seat), room: localCtx.roomCode })
      }
      const hostAbstain = hostPage.locator('.skip-btn').first()
      if (await hostAbstain.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await hostAbstain.click()
      }

      // Reveal tally → wolf eliminated → POST_VOTE check sees wolves=0 → villager_win.
      await hostPage.getByTestId('voting-reveal').click()

      // Assert game-over state via the API (authoritative) AND the result URL.
      await hostPage.waitForURL(/\/result\//, { timeout: 30_000 })
      const finalState = await hostPage.evaluate(async (id: string) => {
        const token = localStorage.getItem('jwt')
        const res = await fetch(`/api/game/${id}/state`, {
          headers: { Authorization: `Bearer ${token}` },
        })
        return res.ok ? res.json() : null
      }, localCtx.gameId)
      expect(finalState?.phase, 'phase=GAME_OVER expected').toBe('GAME_OVER')
      expect(finalState?.winner, 'winner=VILLAGER expected').toBe('VILLAGER')
      await captureSnapshot(localCtx.pages, testInfo, 'row2-villager-win')
    } finally {
      await localCtx.cleanup()
    }
  })

  // ── Row 3: wolf-win at parity when D1 vote eliminates a villager ──
  test('row 3 — wolf-win at parity after D1 mis-vote', async ({ browser }, testInfo) => {
    testInfo.setTimeout(240_000)
    const localCtx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
    })
    try {
      const hostPage = localCtx.hostPage
      const wolves = localCtx.roleMap.WEREWOLF ?? []
      const seer = (localCtx.roleMap.SEER ?? [])[0]
      const witch = (localCtx.roleMap.WITCH ?? [])[0]
      const villagers = localCtx.roleMap.VILLAGER ?? []
      expect(wolves.length, 'kit must have 2 wolves').toBe(2)
      expect(seer, 'kit must have 1 seer').toBeDefined()
      expect(witch, 'kit must have 1 witch').toBeDefined()
      expect(villagers.length, 'kit must have 2 villagers').toBe(2)
      // eslint-disable-next-line no-console
      console.warn(
        `[row 3] hostRole=${localCtx.hostRole} villagers=${villagers.map((b) => b.nick).join(',')}`,
      )

      await hostPage.getByTestId('start-night').click()
      await waitForPhase(hostPage, localCtx.gameId, 'NIGHT', 15_000)

      // ── N1 ── wolves kill villager-1, witch declines (villager-1 dies).
      // Assert each sub-phase gate so a wrong-sub-phase doesn't silently
      // fire act() with a "Not in <X> sub-phase" rejection in CI logs.
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WEREWOLF_PICK', 15_000),
        'expected NIGHT/WEREWOLF_PICK before firing WOLF_KILL',
      ).toBe(true)
      act('WOLF_KILL', actName(wolves[0]), {
        target: String(villagers[0].seat),
        room: localCtx.roomCode,
      })

      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_PICK', 15_000),
        'expected NIGHT/SEER_PICK before firing SEER_CHECK',
      ).toBe(true)
      act('SEER_CHECK', actName(seer), { target: String(wolves[0].seat), room: localCtx.roomCode })
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_RESULT', 10_000),
        'expected NIGHT/SEER_RESULT before firing SEER_CONFIRM',
      ).toBe(true)
      act('SEER_CONFIRM', actName(seer), { room: localCtx.roomCode })

      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WITCH_ACT', 15_000),
        'expected NIGHT/WITCH_ACT before firing WITCH_ACT',
      ).toBe(true)
      act('WITCH_ACT', actName(witch), {
        room: localCtx.roomCode,
        payload: '{"useAntidote":false}',
      })

      // After N1: 2 wolves + 3 humans (host + seer + witch + villagers[1] minus villagers[0]).
      await waitForPhase(hostPage, localCtx.gameId, 'DAY_DISCUSSION', 20_000)

      // ── D1 ── vote villagers[1] (NOT a wolf). After D1: 2 wolves + 2 humans → parity.
      await hostPage.getByTestId('day-reveal-result').click()
      await hostPage.getByTestId('day-start-vote').click()
      await waitForVotingSubPhase(hostPage, localCtx.gameId, 'VOTING', 10_000)

      // Per-bot fan-out filtering dead bots (villagers[0] died at N1).
      const unvoted2 = await readUnvotedAlivePlayerIds(hostPage, localCtx.gameId)
      for (const bot of localCtx.allBots) {
        if (bot.nick === 'Host') continue
        if (!unvoted2.has(bot.userId)) continue
        act('SUBMIT_VOTE', bot.nick, { target: String(villagers[1].seat), room: localCtx.roomCode })
      }
      const hostAbstain = hostPage.locator('.skip-btn').first()
      if (await hostAbstain.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await hostAbstain.click()
      }
      await hostPage.getByTestId('voting-reveal').click()

      await hostPage.waitForURL(/\/result\//, { timeout: 30_000 })
      const finalState = await hostPage.evaluate(async (id: string) => {
        const token = localStorage.getItem('jwt')
        const res = await fetch(`/api/game/${id}/state`, {
          headers: { Authorization: `Bearer ${token}` },
        })
        return res.ok ? res.json() : null
      }, localCtx.gameId)
      expect(finalState?.phase, 'phase=GAME_OVER expected').toBe('GAME_OVER')
      expect(finalState?.winner, 'winner=WEREWOLF expected (parity)').toBe('WEREWOLF')
      await captureSnapshot(localCtx.pages, testInfo, 'row3-wolf-win')
    } finally {
      await localCtx.cleanup()
    }
  })

  // ── Row 4: HUNTER_SHOOT subPhase fires when hunter is voted out at D1 ──
  test('row 4 — HUNTER_SHOOT subPhase when D1 votes the hunter', async ({ browser }, testInfo) => {
    testInfo.setTimeout(240_000)
    const localCtx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'HUNTER', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'HUNTER'] as RoleName[],
    })
    try {
      const hostPage = localCtx.hostPage
      // Don't filter host out: act.sh handles PLAYER='HOST' via host token.
      const wolves = localCtx.roleMap.WEREWOLF ?? []
      const seer = (localCtx.roleMap.SEER ?? [])[0]
      const witch = (localCtx.roleMap.WITCH ?? [])[0]
      const hunter = (localCtx.roleMap.HUNTER ?? [])[0]
      const villagers = localCtx.roleMap.VILLAGER ?? []
      expect(wolves.length, 'kit must have wolves').toBeGreaterThan(0)
      expect(seer, 'kit must have a seer').toBeDefined()
      expect(witch, 'kit must have a witch').toBeDefined()
      expect(hunter, 'kit must have a hunter').toBeDefined()
      expect(villagers.length, 'kit must have a villager for the wolf to kill').toBeGreaterThan(0)
      // eslint-disable-next-line no-console
      console.warn(
        `[row 4] hostRole=${localCtx.hostRole} hunter=${hunter.nick}(seat=${hunter.seat})`,
      )

      await hostPage.getByTestId('start-night').click()
      await waitForPhase(hostPage, localCtx.gameId, 'NIGHT', 15_000)

      // ── N1 ── wolves kill a villager. Witch saves them (no death) so D1
      // alive count is full and the hunter-vote elimination is the only D1
      // event. Assert each sub-phase gate so a wrong-sub-phase doesn't
      // silently fire act() with a "Not in <X> sub-phase" rejection.
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WEREWOLF_PICK', 15_000),
        'expected NIGHT/WEREWOLF_PICK before firing WOLF_KILL',
      ).toBe(true)
      act('WOLF_KILL', actName(wolves[0]), {
        target: String(villagers[0].seat),
        room: localCtx.roomCode,
      })

      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_PICK', 15_000),
        'expected NIGHT/SEER_PICK before firing SEER_CHECK',
      ).toBe(true)
      act('SEER_CHECK', actName(seer), { target: String(wolves[0].seat), room: localCtx.roomCode })
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_RESULT', 10_000),
        'expected NIGHT/SEER_RESULT before firing SEER_CONFIRM',
      ).toBe(true)
      act('SEER_CONFIRM', actName(seer), { room: localCtx.roomCode })

      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WITCH_ACT', 15_000),
        'expected NIGHT/WITCH_ACT before firing WITCH_ACT',
      ).toBe(true)
      act('WITCH_ACT', actName(witch), {
        room: localCtx.roomCode,
        payload: '{"useAntidote":true}',
      })

      await waitForPhase(hostPage, localCtx.gameId, 'DAY_DISCUSSION', 20_000)

      // ── D1 ── vote the hunter.
      await hostPage.getByTestId('day-reveal-result').click()
      await hostPage.getByTestId('day-start-vote').click()
      await waitForVotingSubPhase(hostPage, localCtx.gameId, 'VOTING', 10_000)

      // Per-bot fan-out — witch saved villagers[0] at N1 so all bots alive,
      // but iterate explicitly to match the pattern (and to surface any
      // dead bot via a positive readUnvotedAlivePlayerIds gate).
      const unvotedRow4 = await readUnvotedAlivePlayerIds(hostPage, localCtx.gameId)
      for (const bot of localCtx.allBots) {
        if (bot.nick === 'Host') continue
        if (!unvotedRow4.has(bot.userId)) continue
        act('SUBMIT_VOTE', bot.nick, { target: String(hunter.seat), room: localCtx.roomCode })
      }
      const hostAbstain = hostPage.locator('.skip-btn').first()
      if (await hostAbstain.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await hostAbstain.click()
      }
      await hostPage.getByTestId('voting-reveal').click()

      // Backend transitions to HUNTER_SHOOT (not VOTE_RESULT or GAME_OVER yet).
      const reachedHunterShoot = await waitForVotingSubPhase(
        hostPage,
        localCtx.gameId,
        'HUNTER_SHOOT',
        15_000,
      )
      expect(reachedHunterShoot, 'expected DAY_VOTING/HUNTER_SHOOT after voting hunter out').toBe(
        true,
      )
      await captureSnapshot(localCtx.pages, testInfo, 'row4-hunter-shoot-entered')

      // Drive the hunter's pass (no shoot) so the game can advance — the
      // important contract here is the SUB-PHASE TRANSITION, not which seat
      // hunter targets.
      act('HUNTER_PASS', actName(hunter), { room: localCtx.roomCode })

      // Sub-phase advances out of HUNTER_SHOOT (to VOTE_RESULT, NIGHT, or
      // GAME_OVER depending on remaining state). Either is acceptable —
      // we're not asserting a specific downstream state, only that the
      // transition out of HUNTER_SHOOT happened.
      await waitForCondition(
        async () => {
          const state = await hostPage.evaluate(async (id: string) => {
            const token = localStorage.getItem('jwt')
            const res = await fetch(`/api/game/${id}/state`, {
              headers: { Authorization: `Bearer ${token}` },
            })
            return res.ok ? res.json() : null
          }, localCtx.gameId)
          return state?.votingPhase?.subPhase !== 'HUNTER_SHOOT'
        },
        'sub-phase to leave HUNTER_SHOOT after HUNTER_PASS',
        10_000,
      )
      await captureSnapshot(localCtx.pages, testInfo, 'row4-hunter-shoot-resolved')
    } finally {
      await localCtx.cleanup()
    }
  })
})
