/**
 * Real-backend E2E: Idiot role reveal and voting flow with multi-browser STOMP verification.
 *
 * Opens 6 isolated browser contexts (host + idiot + other roles)
 * and verifies that idiot reveal mechanics work correctly across all browsers.
 *
 * Key scenarios tested:
 *   - Idiot receives highest votes in first round
 *   - Idiot reveal banner appears in all browsers
 *   - 🃏 overlay appears on idiot's card
 *   - Phase transitions correctly from VOTE_RESULT to NIGHT
 */
import { expect, test, type Page } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { act, actName, type RoleName } from './helpers/shell-runner'
import { verifyAllBrowsersPhase } from './helpers/assertions'
import { captureSnapshot } from './helpers/composite-screenshot'
import { attachBackendLogOnFailure } from './helpers/backend-log'
import {
  readHostSeat,
  readHostUserId,
  readUnvotedAlivePlayerIds,
  waitForAllVotesRegistered,
  waitForNightSubPhase,
  waitForPhase,
  waitForVotingSubPhase,
} from './helpers/state-polling'

/**
 * Resolve the player who currently holds the IDIOT role to a vote target.
 *
 * `roleMap.IDIOT` only tracks non-host bots; when the random role roll lands
 * IDIOT on the host, that array is empty. The IDIOT mechanic is the same
 * regardless of who has the role, so we always resolve to a `{ seat, nickname }`
 * pair the test can use both for the bot fan-out vote and for the
 * banner-nickname assertion.
 *
 * Failures are surfaced via `expect()` so the test reports a positive contract
 * violation ("host's seat must be populated") rather than a generic Error.
 */
async function resolveIdiotTarget(
  localCtx: GameContext,
  hostPage: Page,
): Promise<{ seat: number; nickname: string; isHost: boolean }> {
  if (localCtx.isHostRole('IDIOT')) {
    // roles.sh's host entry has `seat: 0` (initial value before the seat-claim
    // click is reflected); read the real seat from /api/game/{id}/state.
    const hostSeat = await readHostSeat(hostPage, localCtx.gameId)
    expect(
      hostSeat,
      `host's state.players row must include a seat when host rolled IDIOT (game=${localCtx.gameId})`,
    ).not.toBeNull()
    return { seat: hostSeat as number, nickname: 'Host', isHost: true }
  }
  const idiotBots = localCtx.roleMap['IDIOT'] ?? []
  expect(
    idiotBots.length,
    `roleMap.IDIOT must contain at least one bot when hostRole=${localCtx.hostRole} — ` +
      `setupGame's role-assignment loop did not produce an IDIOT.`,
  ).toBeGreaterThan(0)
  const idiotBot = idiotBots[0]
  return { seat: idiotBot.seat, nickname: idiotBot.nick, isHost: false }
}

/**
 * Drive a 6p IDIOT-kit night through to DAY_DISCUSSION (day=1).
 *
 * Reused by tests 2 and 3, both of which need the same "wolf attacks
 * non-IDIOT, seer no-ops, witch declines" night plan to land DAY with the
 * IDIOT alive and votable. Sub-phase-gated for CI race-safety.
 */
async function runNight1ToDay(localCtx: GameContext): Promise<void> {
  const hostPage = localCtx.hostPage

  // Host clicks Start Night via DOM (start-night button is always rendered for
  // the host on the role-reveal panel; testid verified in GameView.vue:57).
  await hostPage.getByTestId('start-night').click()
  expect(
    await waitForPhase(hostPage, localCtx.gameId, 'NIGHT', 15_000),
    'expected NIGHT phase after host clicked start-night',
  ).toBe(true)

  // Don't filter Host out of role rosters: when the random role-assignment
  // lands a special role (WITCH / SEER) on the host, the kit only has ONE of
  // that role, so dropping the host leaves zero actors and the night gets
  // stuck waiting forever for the host's action that we never fire. The
  // 6p IDIOT-kit has only 1 SEER and 1 WITCH, so this matters.
  //
  // act.sh handles `actName(host) === 'HOST'` via the cached host token
  // (act.sh:378), so the host can drive any role action through the same
  // script path. WEREWOLF in this kit has 2 actors, so even if one is the
  // host the other bot wolf can still act — but for symmetry we don't
  // filter wolves either.
  const wolfBots = localCtx.roleMap.WEREWOLF ?? []
  const seerBots = localCtx.roleMap.SEER ?? []
  const witchBots = localCtx.roleMap.WITCH ?? []
  const idiotBots = localCtx.roleMap['IDIOT'] ?? []

  // Wolf attacks a non-IDIOT non-wolf alive seat — IDIOT must survive to D1.
  // When host is IDIOT, roleMap.IDIOT is empty (it tracks bots only), so we
  // also need to exclude the host from the target pool in that case.
  expect(wolfBots.length, 'kit must have at least one wolf').toBeGreaterThan(0)
  const wolfBot = wolfBots[0]
  const hostIsIdiot = localCtx.isHostRole('IDIOT')
  const wolfTarget = localCtx.allBots.find(
    (b) =>
      b.userId !== wolfBot.userId &&
      !idiotBots.some((i) => i.userId === b.userId) &&
      !wolfBots.some((w) => w.userId === b.userId) &&
      !(hostIsIdiot && b.nick === 'Host'),
  )
  expect(wolfTarget, 'kit must have a non-IDIOT non-wolf target').toBeDefined()
  expect(
    await waitForNightSubPhase(hostPage, localCtx.gameId, 'WEREWOLF_PICK', 15_000),
    'expected NIGHT/WEREWOLF_PICK',
  ).toBe(true)
  act('WOLF_KILL', actName(wolfBot), {
    target: String(wolfTarget!.seat),
    room: localCtx.roomCode,
  })

  // Seer checks the wolf (non-functional for the IDIOT-reveal contract — just
  // advances the phase deterministically). Always present in the kit, including
  // when host rolled SEER (uses host token via actName).
  expect(seerBots.length, 'kit must have a SEER').toBeGreaterThan(0)
  const seerBot = seerBots[0]
  expect(
    await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_PICK', 15_000),
    'expected NIGHT/SEER_PICK',
  ).toBe(true)
  act('SEER_CHECK', actName(seerBot), {
    target: String(wolfBot.seat),
    room: localCtx.roomCode,
  })
  expect(
    await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_RESULT', 10_000),
    'expected NIGHT/SEER_RESULT after SEER_CHECK',
  ).toBe(true)
  act('SEER_CONFIRM', actName(seerBot), { room: localCtx.roomCode })

  // Witch declines both potions so the wolf-killed villager actually dies and
  // the day count is right. Always present, including host-as-WITCH (uses
  // host token via actName).
  expect(witchBots.length, 'kit must have a WITCH').toBeGreaterThan(0)
  const witchBot = witchBots[0]
  expect(
    await waitForNightSubPhase(hostPage, localCtx.gameId, 'WITCH_ACT', 15_000),
    'expected NIGHT/WITCH_ACT',
  ).toBe(true)
  act('WITCH_ACT', actName(witchBot), {
    room: localCtx.roomCode,
    payload: '{"useAntidote":false}',
  })

  expect(
    await waitForPhase(hostPage, localCtx.gameId, 'DAY_DISCUSSION', 30_000),
    'expected DAY_DISCUSSION after night resolved',
  ).toBe(true)
}

/**
 * Drive D1 vote against the IDIOT and verify the tally is revealed.
 * Returns the resolved IDIOT info so callers can assert banner contents.
 */
async function voteIdiotOut(
  localCtx: GameContext,
): Promise<{ seat: number; nickname: string; isHost: boolean }> {
  const hostPage = localCtx.hostPage

  // Host reveals N1 result + opens vote.
  const revealBtn = hostPage.getByTestId('day-reveal-result')
  await revealBtn.waitFor({ state: 'visible', timeout: 10_000 })
  await revealBtn.click()
  const startVoteBtn = hostPage.getByTestId('day-start-vote')
  await startVoteBtn.waitFor({ state: 'visible', timeout: 10_000 })
  await startVoteBtn.click()
  await verifyAllBrowsersPhase(localCtx.pages, 'VOTING', 15_000)

  // Resolve IDIOT (works for both bot-IDIOT and host-IDIOT rolls).
  const idiot = await resolveIdiotTarget(localCtx, hostPage)

  // Fan-out vote: every alive non-host non-voted bot votes the IDIOT.
  const unvoted = await readUnvotedAlivePlayerIds(hostPage, localCtx.gameId)
  const hostId = await readHostUserId(hostPage)
  const expectedVoterIds: string[] = []
  for (const bot of localCtx.allBots) {
    if (bot.nick === 'Host' || bot.userId === hostId) continue
    if (!unvoted.has(bot.userId)) continue
    act('SUBMIT_VOTE', bot.nick, { target: String(idiot.seat), room: localCtx.roomCode })
    expectedVoterIds.push(bot.userId)
  }
  expect(expectedVoterIds.length, 'at least one bot must be eligible to vote').toBeGreaterThan(0)
  await waitForAllVotesRegistered(hostPage, localCtx.gameId, expectedVoterIds, 10_000)

  // Reveal tally → backend transitions to VOTE_RESULT.
  act('VOTING_REVEAL_TALLY', 'HOST', { room: localCtx.roomCode })
  expect(
    await waitForVotingSubPhase(hostPage, localCtx.gameId, 'VOTE_RESULT', 10_000),
    'expected DAY_VOTING/VOTE_RESULT after VOTING_REVEAL_TALLY',
  ).toBe(true)

  return idiot
}

test.describe('Idiot flow — multi-browser STOMP verification', () => {
  test.setTimeout(180_000) // 3 min — N1 + D1 vote + IDIOT reveal + (test 3) night transition

  // Each test in this file constructs its own localCtx via setupGame inside
  // the test body — there's no shared `ctx` to call attachCompositeOnFailure
  // against. Attach backend log directly so failures still ship the backend
  // tail (the single most useful artifact for stuck-on-NIGHT diagnostics).
  test.afterEach(async ({}, testInfo) => {
    await attachBackendLogOnFailure(testInfo)
  })

  // ── Test 1: Setup verification ──────────────────────────────────────────────

  test('1. Setup — idiot role assigned correctly', async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    const localCtx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'IDIOT'] as RoleName[],
      browserRoles: ['IDIOT', 'WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
    })

    try {
      // IDIOT is assigned either to the host or to one bot (mutually exclusive).
      if (localCtx.isHostRole('IDIOT')) {
        expect(localCtx.hostRole).toBe('IDIOT')
      } else {
        const idiotBots = localCtx.roleMap['IDIOT']
        expect(idiotBots).toBeDefined()
        expect(idiotBots?.length).toBeGreaterThan(0)
      }

      // setupGame's mapping (multi-browser.ts:362-405) routes the host's page
      // under the host's rolled-role key when that role is in browserRoles, OR
      // opens a new context for the first non-host bot of that role. Either
      // way, an IDIOT page exists.
      expect(localCtx.pages.get('IDIOT')).toBeDefined()
    } finally {
      await localCtx.cleanup()
    }
  })

  // ── Test 2: IDIOT reveal banner + overlay propagated to every browser ──

  test('2. Idiot reveal — all browsers show idiot reveal banner', async ({ browser }, testInfo) => {
    testInfo.setTimeout(180_000)
    const localCtx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'IDIOT'] as RoleName[],
      browserRoles: ['IDIOT', 'WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
    })

    try {
      await runNight1ToDay(localCtx)
      const idiot = await voteIdiotOut(localCtx)

      await captureSnapshot(localCtx.pages, testInfo, '02-tally-revealed')

      // Contract: every browser shows the IDIOT reveal banner with the
      // IDIOT's nickname AND the 🃏 overlay on the IDIOT's card.
      for (const [roleName, page] of localCtx.pages) {
        const idiotBanner = page
          .locator('.elim-banner-body')
          .filter({ hasText: /白痴翻牌|IDIOT REVEALED/i })
        await expect(
          idiotBanner,
          `[${roleName} browser] elim-banner-body must show IDIOT-reveal text`,
        ).toBeVisible({ timeout: 10_000 })
        await expect(
          idiotBanner.filter({ hasText: idiot.nickname }),
          `[${roleName} browser] reveal banner must contain IDIOT nickname (${idiot.nickname})`,
        ).toBeVisible()

        const idiotOverlay = page.locator('.slot-overlay.idiot-overlay')
        await expect(
          idiotOverlay,
          `[${roleName} browser] .slot-overlay.idiot-overlay must be visible after reveal`,
        ).toBeVisible({ timeout: 10_000 })
      }
    } finally {
      await localCtx.cleanup()
    }
  })

  // ── Test 3: VOTE_RESULT → NIGHT transition after IDIOT reveal ──

  test('3. Phase transition — VOTE_RESULT to NIGHT', async ({ browser }, testInfo) => {
    testInfo.setTimeout(180_000)
    const localCtx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'IDIOT'] as RoleName[],
      browserRoles: ['IDIOT', 'WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
    })

    try {
      await runNight1ToDay(localCtx)
      await voteIdiotOut(localCtx)

      // Pre-condition for this test's contract: reveal banner is visible
      // before continue is clicked.
      const hostPage = localCtx.hostPage
      const idiotBanner = hostPage
        .locator('.elim-banner-body')
        .filter({ hasText: /白痴翻牌|IDIOT REVEALED/i })
      await expect(idiotBanner).toBeVisible({ timeout: 10_000 })

      // Host clicks voting-continue to advance D1 → N2.
      const continueBtn = hostPage.getByTestId('voting-continue')
      await continueBtn.waitFor({ state: 'visible', timeout: 10_000 })
      await continueBtn.click()

      // Contract: every browser transitions to NIGHT phase, AND the
      // IDIOT-reveal banner is no longer visible (we left day-voting).
      await verifyAllBrowsersPhase(localCtx.pages, 'NIGHT', 15_000)
      await expect(
        idiotBanner,
        'IDIOT reveal banner must hide once we leave day-voting',
      ).not.toBeVisible({ timeout: 5_000 })
    } finally {
      await localCtx.cleanup()
    }
  })
})

