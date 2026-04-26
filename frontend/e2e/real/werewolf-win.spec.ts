/**
 * Real-backend E2E: Werewolf win — play until wolves win and verify the result screen
 * shows all players' roles revealed.
 *
 * Strategy:
 *   - 9-player game, no sheriff (simpler flow)
 *   - Each round: wolves kill a villager, village abstains vote (no elimination)
 *   - After enough rounds, wolves outnumber village → GAME_OVER
 *   - Verify result screen shows winner + all roles
 */
import {expect, test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act, actName, type RoleName} from './helpers/shell-runner'
import {verifyAllBrowsersPhase} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'
import {readAlivePlayerIds, readHostUserId, readUnvotedAlivePlayerIds, waitForNightSubPhase, waitForVotingSubPhase} from './helpers/state-polling'

let ctx: GameContext

test.describe('Werewolf win — result screen shows all roles', () => {
  // 300s (up from 180s): the per-role sub-phase gates added for Category A
  // can each cost up to ~15s × CI-2× = 30s when a role's bot is dead (gate
  // waits full timeout before returning false). Across 6 rounds × 4 roles
  // that's ~720s worst case, but typical runs finish in ~180-240s. The old
  // budget was right at the edge and occasionally tipped over.
  test.setTimeout(300_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
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

  /** Try a script action, return false if it fails (dead player, wrong phase, etc.) */
  function tryAct(...args: Parameters<typeof act>): boolean {
    try {
      const output = act(...args)
      return !output.includes('rejected') && !output.includes('fail')
    } catch {
      return false
    }
  }

  /**
   * Complete a full night round via scripts + browser fallback.
   * Uses browser-page UI waits (not fixed timeouts) to detect sub-phase transitions.
   */
  async function completeNight(targetSeat: number) {
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    const witchBots = ctx.roleMap.WITCH ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const seerPage = ctx.pages.get('SEER')
    const witchPage = ctx.pages.get('WITCH')
    const guardPage = ctx.pages.get('GUARD')

    // Pre-filter role-bot lists to alive players only. roleMap is populated
    // once at game start and never updates — without this, each round re-tries
    // the full original list and wastes retries on "Actor is dead" rejections.
    // Fallback: if readAlivePlayerIds returns empty (state not yet loaded),
    // treat all role-bots as alive so first-round doesn't skip everyone.
    const aliveUserIds = await readAlivePlayerIds(ctx.hostPage, ctx.gameId)
    const isAlive = (uid: string) => aliveUserIds.size === 0 || aliveUserIds.has(uid)
    const aliveWolfBots = wolfBots.filter((b) => isAlive(b.userId))
    const aliveSeerBots = seerBots.filter((b) => isAlive(b.userId))
    const aliveWitchBots = witchBots.filter((b) => isAlive(b.userId))
    const aliveGuardBots = guardBots.filter((b) => isAlive(b.userId))

    // ── Wolf kill ──
    // Gate on backend sub-phase BEFORE firing the act(). Without this, act.sh
    // exits 0 even on rejection ("Not in WEREWOLF_PICK"), the coroutine stalls
    // waiting for the action that was silently dropped. See memory:
    // e2e-ci-vs-local-env-differences item 1. We also bail entirely if the
    // gate returns false (backend already past WEREWOLF_PICK — e.g. all wolves
    // dead, coroutine skipped the sub-phase) so we don't spam rejected actions.
    const reachedWolf = await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 15_000)
    const wolfPage = ctx.pages.get('WEREWOLF')
    if (reachedWolf && wolfPage) {
      await wolfPage
        .locator('.player-grid')
        .first()
        .waitFor({ state: 'visible', timeout: 10_000 })
        .catch(() => {})
    }
    let wolfDone = false
    if (reachedWolf) {
      for (const wb of aliveWolfBots) {
        if (tryAct('WOLF_KILL', actName(wb), { target: String(targetSeat), room: ctx.roomCode })) {
          wolfDone = true
          break
        }
      }
    }
    // Browser fallback: use wolf browser page (works whether host is wolf or not)
    if (reachedWolf && !wolfDone && wolfPage) {
      const slot = wolfPage.locator('.player-grid .slot-alive').first()
      if (await slot.isVisible({ timeout: 5_000 }).catch(() => false)) {
        await slot.click()
        await wolfPage.waitForTimeout(500)
        await wolfPage.getByTestId('wolf-confirm-kill').click()
      }
    }

    // Wait for seer sub-phase (or timeout if dead/skipped)
    if (seerPage) {
      await seerPage
        .getByText(/选择查验目标|Select a player to check/i)
        .first()
        .waitFor({ state: 'visible', timeout: 8_000 })
        .catch(() => {})
    }

    // ── Seer ── (may be dead in later rounds)
    // Gate on SEER_PICK before CHECK, SEER_RESULT before CONFIRM. Short-circuit
    // via waitForNightSubPhase returning false if the seer is dead and the
    // coroutine skipped the sub-phase.
    const reachedSeerPick = await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'SEER_PICK', 10_000)
    let seerDone = false
    if (reachedSeerPick) {
      for (const sb of aliveSeerBots) {
        const allSeats = [targetSeat, 1, 2, 3, 4, 5, 6, 7, 8, 9]
        for (const seat of allSeats) {
          if (tryAct('SEER_CHECK', actName(sb), { target: String(seat), room: ctx.roomCode })) {
            seerDone = true
            await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'SEER_RESULT', 8_000)
            // Wait for seer result before confirming
            if (seerPage) {
              await seerPage
                .locator('.sr-wrap')
                .first()
                .waitFor({ state: 'visible', timeout: 8_000 })
                .catch(() => {})
            }
            tryAct('SEER_CONFIRM', actName(sb), { room: ctx.roomCode })
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

    // Wait for witch sub-phase (or timeout if dead/skipped)
    if (witchPage) {
      await witchPage
        .locator('.w-section')
        .first()
        .waitFor({ state: 'visible', timeout: 8_000 })
        .catch(() => {})
    }

    // ── Witch ── (may be dead in later rounds)
    const reachedWitch = await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 10_000)
    let witchDone = false
    if (reachedWitch) {
      for (const wb of aliveWitchBots) {
        witchDone = tryAct('WITCH_ACT', actName(wb), {
          payload: '{"useAntidote":false}',
          room: ctx.roomCode,
        })
        if (witchDone) break
      }
    }
    if (!witchDone && witchPage) {
      if (await witchPage.locator('.w-section').first().isVisible().catch(() => false)) {
        const passBtn = witchPage.getByTestId('switch-pass-antidote')
        if (await passBtn.isVisible().catch(() => false)) await passBtn.click()
        await witchPage.waitForTimeout(500)
        const skipBtn = witchPage.getByTestId('witch-skip')
        if (await skipBtn.isVisible().catch(() => false)) await skipBtn.click()
      }
    }

    // Wait for guard sub-phase (or timeout if dead/skipped)
    if (guardPage) {
      await guardPage
        .getByText(/选择守护目标|Protect a player/i)
        .first()
        .waitFor({ state: 'visible', timeout: 8_000 })
        .catch(() => {})
    }

    // ── Guard ── (may be dead in later rounds)
    const reachedGuard = await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'GUARD_PICK', 10_000)
    let guardDone = false
    if (reachedGuard) {
      // Try each alive guard — drop the pre-existing unconditional break,
      // which failed the whole block if guard[0] happened to be dead.
      for (const gb of aliveGuardBots) {
        if (tryAct('GUARD_SKIP', actName(gb), { room: ctx.roomCode })) {
          guardDone = true
          break
        }
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

  /** Complete a day phase: host reveals result, starts vote, everyone abstains. Handles revotes. */
  async function completeDay() {
    const hostPage = ctx.hostPage

    // Host reveals night result
    const revealBtn = hostPage.getByTestId('day-reveal-result')
    if (await revealBtn.isVisible({ timeout: 10_000 }).catch(() => false)) {
      await revealBtn.click()
      await hostPage.waitForTimeout(1_000)
    }

    // Host starts vote
    const startVoteBtn = hostPage.getByTestId('day-start-vote')
    if (await startVoteBtn.isVisible({ timeout: 10_000 }).catch(() => false)) {
      await startVoteBtn.click()
    }

    // Vote cycle — repeat if revote triggered (tie)
    for (let attempt = 0; attempt < 3; attempt++) {
      // Wait for backend to be in VOTING / RE_VOTING before firing any vote action.
      // Without this gate, SUBMIT_VOTE bots fan out while backend is still in
      // DAY_DISCUSSION (or in VOTE_RESULT between revote rounds) and get rejected
      // with "Not in voting phase", stalling the whole day cycle.
      const target = attempt === 0 ? 'VOTING' : 'RE_VOTING'
      const reachedVoting = await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, target, 15_000)
      if (!reachedVoting) break

      const abstainBtn = hostPage.locator('.skip-btn').first()
      if (await abstainBtn.isVisible({ timeout: 10_000 }).catch(() => false)) {
        await abstainBtn.click()
        await hostPage.waitForTimeout(500)
      }

      // Fan-out abstain to alive, non-host, unvoted bots only. Avoids the
      // "Already voted" / "Dead players cannot vote" retry cascade in
      // act.sh when some bots have already abstained via the host-browser
      // click above.
      const unvoted = await readUnvotedAlivePlayerIds(ctx.hostPage, ctx.gameId)
      const hostId = await readHostUserId(ctx.hostPage)
      for (const bot of ctx.allBots) {
        if (bot.nick === 'Host' || bot.userId === hostId) continue
        if (!unvoted.has(bot.userId)) continue
        tryAct('SUBMIT_VOTE', bot.nick, { room: ctx.roomCode })
      }
      await hostPage.waitForTimeout(2_000)

      tryAct('VOTING_REVEAL_TALLY', 'HOST', { room: ctx.roomCode })
      await hostPage.waitForTimeout(2_000)

      tryAct('VOTING_CONTINUE', 'HOST', { room: ctx.roomCode })
      await hostPage.waitForTimeout(3_000)

      // Check if still in voting (revote) — if skip-btn reappears, another round needed
      const stillVoting = await hostPage.locator('.skip-btn').first().isVisible().catch(() => false)
      if (!stillVoting) break
    }
  }

  test('1. Start night — all browsers transition', async ({}, testInfo) => {
    const hostPage = ctx.hostPage
    const startNightBtn = hostPage.getByTestId('start-night')
    if (!(await startNightBtn.isVisible().catch(() => false))) {
      await startNightBtn.waitFor({ state: 'visible', timeout: 10_000 })
    }
    await startNightBtn.click()
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)
    await captureSnapshot(ctx.pages, testInfo, '01-night-start')
  })

  test('2. Play rounds until werewolf wins, verify result screen', async ({}, testInfo) => {
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const witchBots = ctx.roleMap.WITCH ?? []

    // Targets: kill non-wolf, non-host players each round (host must stay alive for game management)
    const targets = [...villagerBots, ...seerBots, ...guardBots, ...witchBots]
      .filter((b) => b.nick !== 'Host')
      .map((b) => b.seat)

    const maxRounds = 6

    for (let round = 0; round < maxRounds; round++) {
      if (ctx.hostPage.url().includes('/result/')) break

      // Night
      const targetSeat = targets[round % targets.length]
      await completeNight(targetSeat)

      // Wait for DAY or GAME_OVER
      await ctx.hostPage.waitForTimeout(5_000)
      if (ctx.hostPage.url().includes('/result/')) break

      // Day
      await completeDay()
      await ctx.hostPage.waitForTimeout(3_000)
      if (ctx.hostPage.url().includes('/result/')) break
    }

    // Game should be over — verify result screen
    await ctx.hostPage.waitForURL(/\/result\//, { timeout: 30_000 })
    await captureSnapshot(ctx.pages, testInfo, '02-game-over')

    const hostPage = ctx.hostPage
    await expect(hostPage.locator('.result-wrap')).toBeVisible({ timeout: 10_000 })

    // Winner displayed
    await expect(hostPage.locator('.outcome-title')).toBeVisible({ timeout: 5_000 })

    // "ROLES REVEALED" section
    await expect(hostPage.getByText(/身份揭露|ROLES REVEALED/i)).toBeVisible({ timeout: 5_000 })

    // All 9 players' roles shown
    const rolePills = hostPage.locator('.role-pill')
    const pillCount = await rolePills.count()
    expect(pillCount).toBe(9)

    // Wolf roles visible
    const wolfPills = hostPage.locator('.role-pill').filter({ hasText: /狼人/ })
    expect(await wolfPills.count()).toBeGreaterThanOrEqual(1)

    // Village roles visible
    const villagePills = hostPage.locator('.role-pill').filter({ hasText: /村民|预言家|女巫|守卫/ })
    expect(await villagePills.count()).toBeGreaterThanOrEqual(1)

    await captureSnapshot(ctx.pages, testInfo, '02-result-roles-revealed')
  })
})
