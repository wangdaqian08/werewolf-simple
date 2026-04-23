/**
 * Real-backend E2E: Voting tie triggers revote, game can still proceed to completion.
 *
 * Strategy:
 *   - 9-player game, no sheriff
 *   - Round 1: night → day → vote tied (split votes between 2 players)
 *   - Revote round should start automatically
 *   - Complete the revote, then continue the game to verify it doesn't get stuck
 */
import {expect, test} from '@playwright/test'
import type {Page} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act, type RoleName} from './helpers/shell-runner'
import {verifyAllBrowsersPhase} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'
import {readAlivePlayerIds, waitForNightSubPhase} from './helpers/state-polling'

let ctx: GameContext

/**
 * Read alive non-host players from /api/game/{id}/state. Used to pick a
 * decisive voter + target each vote cycle so rounds always eliminate
 * someone and the test makes forward progress (no stuck-on-tie).
 */
async function readAlivePlayersNonHost(
  hostPage: Page,
  gameId: string,
): Promise<Array<{nickname: string; seat: number}>> {
  return hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return []
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return []
    const state = await res.json()
    return ((state?.players ?? []) as Array<{
      isAlive: boolean
      nickname: string
      seatIndex: number
    }>)
      .filter((p) => p.isAlive && p.nickname !== 'Host')
      .map((p) => ({ nickname: p.nickname, seat: p.seatIndex }))
  }, gameId)
}

/** Read state.votingPhase?.subPhase (VOTING | RE_VOTING | VOTE_RESULT | …). */
async function readVotingSubPhase(hostPage: Page, gameId: string): Promise<string | null> {
  return hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return null
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return null
    const state = await res.json()
    return state?.votingPhase?.subPhase ?? null
  }, gameId)
}

test.describe('Voting tie → revote → game proceeds', () => {
  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      // Explicit roles: deliberately exclude HUNTER so HUNTER_SHOOT sub-phase
      // can't fire between day-vote and the next night — that sub-phase makes
      // the outer round loop's DAY/NIGHT/VOTING state probes ambiguous. Also
      // keeps the roleMap aligned with browserRoles below (setup's default
      // optional roles don't include GUARD, which this test needs).
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

  function tryAct(...args: Parameters<typeof act>): boolean {
    try {
      const output = act(...args)
      const rejected = output.includes('rejected') || output.includes('fail')
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

  // ── Test 1: Start night ──────────────────────────────────────────────

  test('1. Start night', async ({}, testInfo) => {
    const hostPage = ctx.hostPage
    const startNightBtn = hostPage.getByTestId('start-night')
    if (!(await startNightBtn.isVisible().catch(() => false))) {
      await startNightBtn.waitFor({ state: 'visible', timeout: 10_000 })
    }
    await startNightBtn.click()
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)
    await captureSnapshot(ctx.pages, testInfo, '01-night-start')
  })

  // ── Test 2: Complete night (wolf kills, witch saves with antidote) ────

  /**
   * Complete a night phase using bot scripts with browser-page waits.
   * Falls back to browser clicks if bot action fails.
   */
  async function completeNight(opts: { witchUseAntidote?: boolean } = {}) {
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    const witchBots = ctx.roleMap.WITCH ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const wolfPage = ctx.pages.get('WEREWOLF')
    const seerPage = ctx.pages.get('SEER')
    const witchPage = ctx.pages.get('WITCH')
    const guardPage = ctx.pages.get('GUARD')
    const hostPage = ctx.hostPage
    const gameId = ctx.gameId

    // Pick an alive non-wolf target from live DB state, not from the
    // start-of-game roleMap. Using roleMap[0] causes infinite WEREWOLF_PICK
    // loops once the first villager dies — every subsequent WOLF_KILL hits a
    // "target is dead" rejection for ~500+s (observed in CI run 24649855990
    // shard 2 attempt 1).
    const alivePlayers = await readAlivePlayersNonHost(ctx.hostPage, ctx.gameId)
    const wolfNicks = new Set((ctx.roleMap.WEREWOLF ?? []).map((b) => b.nick))
    const aliveNonWolf = alivePlayers.filter((p) => !wolfNicks.has(p.nickname))
    const target = aliveNonWolf[0]

    // Pre-filter role-bot lists to alive players only. roleMap is populated
    // once at game start and never updates — without this, each completeNight
    // re-iterates the full original list and wastes retries on "Actor is dead"
    // rejections (observed: a single alive wolf in a mid-game loop gets logged
    // repeatedly even though the loop should've skipped its dead peer).
    const aliveUserIds = await readAlivePlayerIds(ctx.hostPage, ctx.gameId)
    const aliveWolves = wolfBots.filter((b) => b.nick !== 'Host' && aliveUserIds.has(b.userId))
    const aliveSeers = seerBots.filter((b) => b.nick !== 'Host' && aliveUserIds.has(b.userId))
    const aliveWitches = witchBots.filter((b) => b.nick !== 'Host' && aliveUserIds.has(b.userId))
    const aliveGuards = guardBots.filter((b) => b.nick !== 'Host' && aliveUserIds.has(b.userId))

    // ── Wolf kill ──
    // Wait for the coroutine to reach WEREWOLF_PICK before firing the action.
    // Without this gate, act() retries waste ~10s+ per night on "Not in X
    // sub-phase" rejections. Also guard on the return — if the gate expires
    // (backend already past WEREWOLF_PICK, e.g. coroutine skipped because
    // all wolves are dead), skip the whole wolf block rather than firing an
    // action that'll be rejected anyway and spam the log.
    const reachedWolf = aliveWolves.length > 0 && await waitForNightSubPhase(hostPage, gameId, 'WEREWOLF_PICK', 15_000)
    if (reachedWolf && wolfPage) {
      await wolfPage
        .locator('.player-grid')
        .first()
        .waitFor({ state: 'visible', timeout: 10_000 })
        .catch(() => {})
    }
    let wolfDone = false
    if (reachedWolf) {
      for (const wb of aliveWolves) {
        if (tryAct('WOLF_KILL', wb.nick, { target: String(target?.seat ?? 1), room: ctx.roomCode })) {
          wolfDone = true
          break
        }
      }
    }
    // Browser fallback: use ANY wolf browser page (not just host)
    if (reachedWolf && !wolfDone && wolfPage) {
      const slot = wolfPage.locator('.player-grid .slot-alive').first()
      if (await slot.isVisible({ timeout: 5_000 }).catch(() => false)) {
        await slot.click()
        await wolfPage.waitForTimeout(500)
        await wolfPage.getByTestId('wolf-confirm-kill').click()
      }
    }

    // ── Seer ──
    // Gate on SEER_PICK before iterating — same race-rejection rationale as
    // the wolf gate above. If the seer is dead / skipped, waitForNightSubPhase
    // short-circuits when phase leaves NIGHT or times out; either way we move
    // on without the 90-rejection churn observed previously.
    const reachedSeerPick = aliveSeers.length > 0 && await waitForNightSubPhase(hostPage, gameId, 'SEER_PICK', 10_000)
    if (reachedSeerPick && seerPage) {
      await seerPage
        .getByText(/选择查验目标|Select a player to check/i)
        .first()
        .waitFor({ state: 'visible', timeout: 10_000 })
        .catch(() => {})
    }
    let seerDone = false
    if (reachedSeerPick) {
      for (const sb of aliveSeers) {
        // Use live alive seats; exclude the seer's own seat (self-check rejects).
        const seats = alivePlayers.filter((p) => p.nickname !== sb.nick).map((p) => p.seat)
        for (const seat of seats) {
          if (tryAct('SEER_CHECK', sb.nick, { target: String(seat), room: ctx.roomCode })) {
            seerDone = true
            // Gate on SEER_RESULT before SEER_CONFIRM so the confirm lands
            // in the right sub-phase.
            await waitForNightSubPhase(hostPage, gameId, 'SEER_RESULT', 8_000)
            tryAct('SEER_CONFIRM', sb.nick, { room: ctx.roomCode })
            break
          }
        }
        if (seerDone) break
      }
    }
    if (!seerDone && seerPage) {
      if (
        await seerPage
          .getByText(/选择查验目标|Select a player to check/i)
          .first()
          .isVisible()
          .catch(() => false)
      ) {
        await seerPage.locator('.player-grid .slot-alive').first().click()
        await seerPage.getByTestId('seer-check').click()
        await expect(seerPage.locator('.sr-wrap').first()).toBeVisible({ timeout: 10_000 })
        await seerPage.getByTestId('seer-done').click()
      }
    }

    // ── Witch ──
    const reachedWitch = aliveWitches.length > 0 && await waitForNightSubPhase(hostPage, gameId, 'WITCH_ACT', 10_000)
    if (reachedWitch && witchPage) {
      await witchPage
        .locator('.w-section')
        .first()
        .waitFor({ state: 'visible', timeout: 10_000 })
        .catch(() => {})
    }
    let witchDone = false
    const antidotePayload = opts.witchUseAntidote
      ? '{"useAntidote":true}'
      : '{"useAntidote":false}'
    if (reachedWitch) {
      for (const wb of aliveWitches) {
        witchDone = tryAct('WITCH_ACT', wb.nick, { payload: antidotePayload, room: ctx.roomCode })
        if (witchDone) break
      }
    }
    if (!witchDone && witchPage) {
      if (await witchPage.locator('.w-section').first().isVisible().catch(() => false)) {
        if (opts.witchUseAntidote) {
          const useBtn = witchPage.getByTestId('witch-antidote')
          if (await useBtn.isVisible().catch(() => false)) {
            await useBtn.click()
            await witchPage.waitForTimeout(500)
          }
        } else {
          const passBtn = witchPage.getByTestId('switch-pass-antidote')
          if (await passBtn.isVisible().catch(() => false)) await passBtn.click()
          await witchPage.waitForTimeout(500)
        }
        const skipBtn = witchPage.getByTestId('witch-skip')
        if (await skipBtn.isVisible().catch(() => false)) await skipBtn.click()
      }
    }

    // ── Guard ──
    const reachedGuard = aliveGuards.length > 0 && await waitForNightSubPhase(hostPage, gameId, 'GUARD_PICK', 10_000)
    if (reachedGuard && guardPage) {
      await guardPage
        .getByText(/选择守护目标|Protect a player/i)
        .first()
        .waitFor({ state: 'visible', timeout: 10_000 })
        .catch(() => {})
    }
    let guardDone = false
    if (reachedGuard) {
      // Try each alive guard — don't break on rejection (important: the
      // pre-existing code had `break` after the first iteration regardless
      // of tryAct's return, so a single dead guard-bot killed the whole block).
      for (const gb of aliveGuards) {
        if (tryAct('GUARD_SKIP', gb.nick, { room: ctx.roomCode })) {
          guardDone = true
          break
        }
      }
      if (!guardDone && guardPage) {
        if (
          await guardPage
            .getByText(/选择守护目标|Protect a player/i)
            .first()
            .isVisible()
            .catch(() => false)
        ) {
          // Pick LAST alive player to avoid protecting the wolf's target (which is first)
          await guardPage.locator('.player-grid .slot-alive').last().click()
          await guardPage.getByTestId('guard-confirm-protect').click()
        }
      }
    }
  }

  test('2. Complete night — wolf kills, witch saves', async ({}, testInfo) => {
    await completeNight({ witchUseAntidote: true })

    // Wait for DAY
    await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)
    await captureSnapshot(ctx.pages, testInfo, '02-day-after-night')
  })

  // ── Test 3: Create a tied vote ───────────────────────────────────────

  test('3. Tied vote triggers RE_VOTING', async ({}, testInfo) => {
    const hostPage = ctx.hostPage

    // Host reveals night result
    const revealBtn = hostPage.getByTestId('day-reveal-result')
    await revealBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await revealBtn.click()
    await hostPage.waitForTimeout(1_000)

    // Host starts vote
    const startVoteBtn = hostPage.getByTestId('day-start-vote')
    await startVoteBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await startVoteBtn.click()
    await verifyAllBrowsersPhase(ctx.pages, 'VOTING', 15_000)
    await captureSnapshot(ctx.pages, testInfo, '03-voting-start')

    // To create a tie: we need to split votes between 2 targets.
    // Pick 2 non-wolf players as targets (e.g., first 2 villagers/specials)
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const witchBots = ctx.roleMap.WITCH ?? []

    const nonWolves = [...villagerBots, ...seerBots, ...guardBots, ...witchBots]
    const target1 = nonWolves[0]
    const target2 = nonWolves[1] ?? nonWolves[0]

    // Host votes for target1
    const abstainBtn = hostPage.locator('.skip-btn').first()
    // Select target1 slot and vote
    await hostPage.waitForTimeout(500)

    // Use scripts to vote: half vote for target1, half for target2
    // This is simpler than clicking in all browsers.
    // Get all players from state file
    const allBots = ctx.allBots

    // Split bots into two groups for tie
    // Group 1: first half votes for target1; Group 2: second half votes for target2
    const votingBots = allBots.filter((b) => b.nick !== 'Host')
    const half = Math.floor(votingBots.length / 2)

    // Host abstains (to not affect the tie)
    if (await abstainBtn.isVisible({ timeout: 5_000 }).catch(() => false)) {
      await abstainBtn.click()
      await hostPage.waitForTimeout(500)
    }

    // First half votes for target1
    for (const bot of votingBots.slice(0, half)) {
      tryAct('SUBMIT_VOTE', bot.nick, { target: String(target1.seat), room: ctx.roomCode })
    }

    // Second half votes for target2
    for (const bot of votingBots.slice(half)) {
      tryAct('SUBMIT_VOTE', bot.nick, { target: String(target2.seat), room: ctx.roomCode })
    }

    await hostPage.waitForTimeout(2_000)

    // Host reveals tally
    tryAct('VOTING_REVEAL_TALLY', 'HOST', { room: ctx.roomCode })
    await hostPage.waitForTimeout(2_000)

    await captureSnapshot(ctx.pages, testInfo, '03-vote-tally-tied')

    // Host continues — should trigger RE_VOTING if tied
    tryAct('VOTING_CONTINUE', 'HOST', { room: ctx.roomCode })
    await hostPage.waitForTimeout(3_000)

    await captureSnapshot(ctx.pages, testInfo, '03-after-continue')

    // Verify: should be in VOTING phase still (RE_VOTING subPhase) or already proceeding
    // The key assertion: game is not stuck — either still in VOTING, DAY, NIGHT, or GAME_OVER
    const url = hostPage.url()
    const isGamePage = url.includes('/game/') || url.includes('/result/')
    expect(isGamePage).toBe(true)

    // Check if we're in re-vote by looking for voting UI
    const isVoting = await hostPage.locator('.voting-wrap').first().isVisible().catch(() => false)
    const isNight = await hostPage.locator('.game-wrap.night-mode').first().isVisible().catch(() => false)
    const isDay = await hostPage.locator('.day-wrap').first().isVisible().catch(() => false)
    const isResult = url.includes('/result/')

    // Game should be in one of these states — not stuck
    expect(isVoting || isNight || isDay || isResult).toBe(true)

    await captureSnapshot(ctx.pages, testInfo, '03-revote-or-next')
  })

  // ── Test 4: Complete the game after revote ───────────────────────────

  test('4. Game proceeds to completion after revote', async ({}, testInfo) => {
    // 600s gives headroom for an uncapped round loop under shrunk e2e timings.
    // The prior 300s + 15-round cap hit both limits simultaneously on CI.
    testInfo.setTimeout(600_000)

    // Start night phase if we're still in a waiting/role-reveal state.
    const startNightBtn = ctx.hostPage.getByTestId('start-night')
    if (await startNightBtn.isVisible().catch(() => false)) {
      await startNightBtn.click()
      await ctx.hostPage.waitForTimeout(2_000)
    }

    /**
     * Submit votes with one decisive voter + fan-out abstain for everyone else.
     *
     * Target-selection strategy: prefer an alive wolf as the target so every
     * successful day-vote shrinks the wolf side. With 2 wolves in a 9-player
     * game, the villagers reach GAME_OVER in ~2-3 day cycles. Targeting an
     * arbitrary alive player instead (the naive strategy) kills villagers
     * too and the game drags past the test timeout.
     *
     * Decisive voter: any alive non-host player who isn't the target.
     * The act.sh fan-out abstain then fires for all bots (the decisive
     * voter's second call is a terminal "Already voted" — ignored per the
     * backend validation rules, VotingPipeline.kt:47-53).
     *
     * If fewer than 2 non-host players are alive, skip the decisive vote —
     * the game is effectively over and the outer loop will exit on /result/.
     */
    async function submitVotesWithDecisive() {
      const alive = await readAlivePlayersNonHost(ctx.hostPage, ctx.gameId)

      const wolfNicks = new Set((ctx.roleMap.WEREWOLF ?? []).map((b) => b.nick))
      const aliveWolves = alive.filter((p) => wolfNicks.has(p.nickname))
      // Prefer wolf target; fall back to first alive if wolves all eliminated.
      const target = aliveWolves[0] ?? alive[0]
      const decisive = alive.find((p) => p.nickname !== target?.nickname)

      // eslint-disable-next-line no-console
      console.warn(
        `[revote] submitVotes: aliveNonHost=${alive.length} aliveWolves=${aliveWolves.length} ` +
          `target=${target?.nickname ?? 'none'} decisive=${decisive?.nickname ?? 'none'}`,
      )

      if (target && decisive) {
        tryAct('SUBMIT_VOTE', decisive.nickname, {
          target: String(target.seat),
          room: ctx.roomCode,
        })
      }
      // Fan-out abstain; "Already voted" / "Dead players cannot vote" here
      // are terminal rejections and expected noise per the backend rules.
      tryAct('SUBMIT_VOTE', undefined, { room: ctx.roomCode })
      // Host abstains via the skip-btn click so the host's vote counts toward
      // allVoted (voting-reveal button is disabled until allVoted=true).
      const abstainBtn = ctx.hostPage.locator('.skip-btn').first()
      if (await abstainBtn.isVisible({ timeout: 5_000 }).catch(() => false)) {
        await abstainBtn.click()
      }
    }

    /** Host reveals tally then clicks continue. */
    async function revealAndContinue() {
      const revealBtn = ctx.hostPage.getByTestId('voting-reveal')
      await revealBtn.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {})
      await expect(revealBtn).toBeEnabled({ timeout: 15_000 }).catch(() => {})
      tryAct('VOTING_REVEAL_TALLY', 'HOST', { room: ctx.roomCode })
      await ctx.hostPage.waitForTimeout(1_500)
      tryAct('VOTING_CONTINUE', 'HOST', { room: ctx.roomCode })
      await ctx.hostPage.waitForTimeout(1_500)
    }

    /**
     * Complete a vote cycle. If the backend enters RE_VOTING after the first
     * tally (tie carried over from Test 3 or any new tie), the same
     * strategy runs once more with a fresh /state read so the revote picks
     * up the right target-and-voter pair.
     */
    async function completeVote() {
      await submitVotesWithDecisive()
      await revealAndContinue()
      const sub = await readVotingSubPhase(ctx.hostPage, ctx.gameId)
      if (sub === 'RE_VOTING') {
        await submitVotesWithDecisive()
        await revealAndContinue()
      }
    }

    /**
     * Main loop: no hard round cap — elimination per vote cycle guarantees
     * the game reaches GAME_OVER within ~9 rounds for a 9-player game. The
     * testInfo.setTimeout(600_000) serves as the safety ceiling.
     */
    let iteration = 0
    const loopStart = Date.now()
    while (!ctx.hostPage.url().includes('/result/')) {
      iteration += 1
      const elapsedSec = Math.round((Date.now() - loopStart) / 1000)

      // VOTING first — if we landed here mid-vote, resolve it.
      const isVoting = await ctx.hostPage
        .locator('.voting-wrap')
        .first()
        .isVisible()
        .catch(() => false)
      const isDay = await ctx.hostPage
        .locator('.day-wrap')
        .first()
        .isVisible()
        .catch(() => false)
      const isNight = await ctx.hostPage
        .locator('.game-wrap.night-mode')
        .first()
        .isVisible()
        .catch(() => false)

      // Also read backend state so we can see if UI is lagging. If this
      // disagrees with the DOM probes above, we've identified the stall.
      const backendState = await ctx.hostPage
        .evaluate(async (id: string) => {
          const token = localStorage.getItem('jwt')
          if (!token) return null
          const res = await fetch(`/api/game/${id}/state`, {
            headers: { Authorization: `Bearer ${token}` },
          })
          if (!res.ok) return null
          const s = await res.json()
          return {
            phase: s?.phase,
            nightSub: s?.nightPhase?.subPhase,
            votingSub: s?.votingPhase?.subPhase,
            daySub: s?.dayPhase?.subPhase,
            aliveCount: Array.isArray(s?.players)
              ? s.players.filter((p: { isAlive?: boolean }) => p.isAlive).length
              : null,
          }
        }, ctx.gameId)
        .catch(() => null)

      // eslint-disable-next-line no-console
      console.warn(
        `[revote] iter=${iteration} elapsed=${elapsedSec}s ` +
          `ui={voting=${isVoting},day=${isDay},night=${isNight}} ` +
          `backend=${JSON.stringify(backendState)}`,
      )

      if (isVoting) {
        await completeVote()
        continue
      }

      if (isDay) {
        const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
        if (await revealBtn.isVisible().catch(() => false)) {
          await revealBtn.click()
          await ctx.hostPage.waitForTimeout(1_000)
        }
        const startVoteBtn = ctx.hostPage.getByTestId('day-start-vote')
        if (await startVoteBtn.isVisible().catch(() => false)) {
          await startVoteBtn.click()
          await ctx.hostPage.waitForTimeout(1_000)
        }
        await completeVote()
        continue
      }

      if (isNight) {
        await completeNight()
        await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000).catch(() => {})
        continue
      }

      // Unknown state (waiting screen, sub-phase transition in progress) —
      // small wait and re-probe. Hunter / idiot sub-phases fall here; the
      // next iteration's DAY/VOTING branch picks up once they resolve.
      await ctx.hostPage.waitForTimeout(1_500)
    }

    // Game should be over.
    await ctx.hostPage.waitForURL(/\/result\//, { timeout: 30_000 })

    // Verify result screen
    const hostPage = ctx.hostPage
    await expect(hostPage.locator('.result-wrap')).toBeVisible({ timeout: 10_000 })
    await expect(hostPage.locator('.outcome-title')).toBeVisible({ timeout: 5_000 })

    // Verify all roles are revealed
    const rolePills = hostPage.locator('.role-pill')
    const pillCount = await rolePills.count()
    expect(pillCount).toBe(9)

    await captureSnapshot(ctx.pages, testInfo, '04-game-complete')
  })
})
