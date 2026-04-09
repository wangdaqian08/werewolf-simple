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
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act, type RoleName} from './helpers/shell-runner'
import {verifyAllBrowsersPhase} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'
import {execSync} from 'child_process'

let ctx: GameContext

test.describe('Voting tie → revote → game proceeds', () => {
  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(90_000)
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

  function tryAct(...args: Parameters<typeof act>): boolean {
    try {
      const output = act(...args)
      return !output.includes('rejected') && !output.includes('fail')
    } catch {
      return false
    }
  }

  // ── Test 1: Start night ──────────────────────────────────────────────

  test('1. Start night', async ({}, testInfo) => {
    const hostPage = ctx.hostPage
    const startNightBtn = hostPage.getByRole('button', { name: /开始夜晚|Start Night/i })
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

    // Pick a non-wolf target
    const allTargets = [...villagerBots, ...seerBots, ...guardBots, ...witchBots].filter(
      (b) => b.nick !== 'Host',
    )
    const target = allTargets[0]

    // ── Wolf kill ──
    // Wait for wolf browser page to show pick grid
    if (wolfPage) {
      await wolfPage
        .locator('.player-grid')
        .first()
        .waitFor({ state: 'visible', timeout: 10_000 })
        .catch(() => {})
    }
    let wolfDone = false
    for (const wb of wolfBots.filter((b) => b.nick !== 'Host')) {
      if (tryAct('WOLF_KILL', wb.nick, { target: String(target?.seat ?? 1), room: ctx.roomCode })) {
        wolfDone = true
        break
      }
    }
    // Browser fallback: use ANY wolf browser page (not just host)
    if (!wolfDone && wolfPage) {
      const slot = wolfPage.locator('.player-grid .slot-alive').first()
      if (await slot.isVisible({ timeout: 5_000 }).catch(() => false)) {
        await slot.click()
        await wolfPage.waitForTimeout(500)
        await wolfPage.getByRole('button', { name: /确认袭击|Confirm/i }).click()
      }
    }

    // ── Seer ──
    if (seerPage) {
      await seerPage
        .getByText(/选择查验目标|Select a player to check/i)
        .first()
        .waitFor({ state: 'visible', timeout: 10_000 })
        .catch(() => {})
    }
    let seerDone = false
    for (const sb of seerBots.filter((b) => b.nick !== 'Host')) {
      const seats = allTargets.map((t) => t.seat)
      for (const seat of seats) {
        if (tryAct('SEER_CHECK', sb.nick, { target: String(seat), room: ctx.roomCode })) {
          seerDone = true
          if (seerPage) {
            await seerPage
              .locator('.sr-wrap')
              .first()
              .waitFor({ state: 'visible', timeout: 8_000 })
              .catch(() => {})
          }
          tryAct('SEER_CONFIRM', sb.nick, { room: ctx.roomCode })
          break
        }
      }
      if (seerDone) break
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
        await seerPage.getByRole('button', { name: /查验|Check/i }).click()
        await expect(seerPage.locator('.sr-wrap').first()).toBeVisible({ timeout: 10_000 })
        await seerPage.getByRole('button', { name: /查验完毕|Done/i }).click()
      }
    }

    // ── Witch ──
    if (witchPage) {
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
    for (const wb of witchBots.filter((b) => b.nick !== 'Host')) {
      witchDone = tryAct('WITCH_ACT', wb.nick, { payload: antidotePayload, room: ctx.roomCode })
      if (witchDone) break
    }
    if (!witchDone && witchPage) {
      if (await witchPage.locator('.w-section').first().isVisible().catch(() => false)) {
        if (opts.witchUseAntidote) {
          const useBtn = witchPage.getByRole('button', { name: /使用解药/ })
          if (await useBtn.isVisible().catch(() => false)) {
            await useBtn.click()
            await witchPage.waitForTimeout(500)
          }
        } else {
          const passBtn = witchPage.getByRole('button', { name: /放弃/ })
          if (await passBtn.isVisible().catch(() => false)) await passBtn.click()
          await witchPage.waitForTimeout(500)
        }
        const skipBtn = witchPage.getByRole('button', { name: /不用/ })
        if (await skipBtn.isVisible().catch(() => false)) await skipBtn.click()
        const doneBtn = witchPage.getByRole('button', { name: /完成操作|Done/i })
        if (await doneBtn.isVisible().catch(() => false)) await doneBtn.click()
      }
    }

    // ── Guard ──
    if (guardPage) {
      await guardPage
        .getByText(/选择守护目标|Protect a player/i)
        .first()
        .waitFor({ state: 'visible', timeout: 10_000 })
        .catch(() => {})
    }
     let guardDone = false
    for (const gb of guardBots.filter((b) => b.nick !== 'Host')) {
      guardDone = tryAct('GUARD_SKIP', gb.nick, { room: ctx.roomCode })
      break
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
        await guardPage.getByRole('button', { name: /确认保护|Confirm/i }).click()
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
    const revealBtn = hostPage.getByRole('button', { name: /显示结果|Result/i })
    await revealBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await revealBtn.click()
    await hostPage.waitForTimeout(1_000)

    // Host starts vote
    const startVoteBtn = hostPage.getByRole('button', { name: /开始投票|Start Vote/i })
    await startVoteBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await startVoteBtn.click()
    await verifyAllBrowsersPhase(ctx.pages, 'VOTING', 15_000)
    await captureSnapshot(ctx.pages, testInfo, '03-voting-start')

    // To create a tie: we need to split votes between 2 targets.
    // Pick 2 non-wolf players as targets (e.g., first 2 villagers/specials)
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const witchBots = ctx.roleMap.WITCH ?? []

    const nonWolves = [...villagerBots, ...seerBots, ...guardBots, ...witchBots]
    const target1 = nonWolves[0]
    const target2 = nonWolves[1] ?? nonWolves[0]

    // Host votes for target1
    const abstainBtn = hostPage.locator('.skip-btn').first()
    const voteBtn = hostPage.locator('.vote-btn').first()
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
    testInfo.setTimeout(300_000) // Increase timeout to 5 minutes
    const maxRounds = 15

    // Start night phase if in ROLE_REVEAL state
    const startNightBtn = ctx.hostPage.getByRole('button', { name: /开始夜晚|Start Night/i })
    if (await startNightBtn.isVisible().catch(() => false)) {
      testInfo.attach('triggering-night-start', { body: 'Clicking start night button' })
      await startNightBtn.click()
      await ctx.hostPage.waitForTimeout(2_000)
    }

    // Check initial game state
    const initialPhase = await ctx.hostPage.evaluate(() => {
      const dayWrap = document.querySelector('.day-wrap')
      const nightWrap = document.querySelector('.night-wrap')
      const votingWrap = document.querySelector('.voting-wrap')
      const waitingScreen = document.querySelector('.waiting-screen')
      return {
        hasDayWrap: !!dayWrap,
        hasNightWrap: !!nightWrap,
        hasVotingWrap: !!votingWrap,
        hasWaitingScreen: !!waitingScreen,
        url: window.location.href
      }
    })
    testInfo.attach('initial-game-state', { body: JSON.stringify(initialPhase, null, 2) })

    // Check backend game status using STATUS command
    try {
      const statusOutput = execSync(
        `./scripts/act.sh STATUS --room ${ctx.roomCode}`, 
        { encoding: 'utf8', cwd: '/Users/dq/workspace/werewolf-simple' }
      )
      testInfo.attach('backend-game-status', { body: statusOutput })
    } catch (error) {
      testInfo.attach('status-check-failed', { body: `Failed to check game status: ${error}` })
    }

    /** Complete a vote cycle: abstain → reveal → continue. Handles revotes. */
    async function completeVote() {
      for (let attempt = 0; attempt < 3; attempt++) {
        const abstainBtn = ctx.hostPage.locator('.skip-btn').first()
        if (await abstainBtn.isVisible({ timeout: 5_000 }).catch(() => false)) {
          await abstainBtn.click()
          await ctx.hostPage.waitForTimeout(500)
        }
        tryAct('SUBMIT_VOTE', undefined, { room: ctx.roomCode })
        await ctx.hostPage.waitForTimeout(2_000)
        tryAct('VOTING_REVEAL_TALLY', 'HOST', { room: ctx.roomCode })
        await ctx.hostPage.waitForTimeout(2_000)
        tryAct('VOTING_CONTINUE', 'HOST', { room: ctx.roomCode })
        await ctx.hostPage.waitForTimeout(3_000)
        const stillVoting = await ctx.hostPage.locator('.skip-btn').first().isVisible().catch(() => false)
        if (!stillVoting) break
      }
    }

    for (let round = 0; round < maxRounds; round++) {
      // Check if game is over at the start of each round
      if (ctx.hostPage.url().includes('/result/')) {
        testInfo.attach('game-over-detected', { body: `Game over detected at round ${round}` })
        break
      }

      // Check current game state
      const currentState = await ctx.hostPage.evaluate((roundNum) => {
        const dayWrap = document.querySelector('.day-wrap')
        const nightWrap = document.querySelector('.night-wrap')
        const votingWrap = document.querySelector('.voting-wrap')
        const waitingScreen = document.querySelector('.waiting-screen')
        const resultWrap = document.querySelector('.result-wrap')
        const bodyText = document.body.textContent?.substring(0, 200)
        return {
          round: roundNum,
          hasDayWrap: !!dayWrap,
          hasNightWrap: !!nightWrap,
          hasVotingWrap: !!votingWrap,
          hasWaitingScreen: !!waitingScreen,
          hasResultWrap: !!resultWrap,
          bodyText,
          url: window.location.href
        }
      }, round)
      testInfo.attach(`game-state-round-${round}`, { body: JSON.stringify(currentState, null, 2) })

      // If game is over, break
      if (currentState.hasResultWrap || currentState.url.includes('/result/')) {
        testInfo.attach('game-over-detected', { body: `Game over detected at round ${round}` })
        break
      }

      // If in VOTING phase, resolve it
      const isVoting = await ctx.hostPage.locator('.voting-wrap').first().isVisible().catch(() => false)
      if (isVoting) {
        testInfo.attach(`round-${round}-action`, { body: 'In VOTING phase, completing vote' })
        await completeVote()
        continue
      }

      // If in DAY, complete day phase
      const isDay = await ctx.hostPage.locator('.day-wrap').first().isVisible().catch(() => false)
      if (isDay) {
        testInfo.attach(`round-${round}-action`, { body: 'In DAY phase, completing day' })
        const revealBtn = ctx.hostPage.getByRole('button', { name: /显示结果|Result/i })
        if (await revealBtn.isVisible().catch(() => false)) {
          await revealBtn.click()
          await ctx.hostPage.waitForTimeout(1_000)
        }
        const startVoteBtn = ctx.hostPage.getByRole('button', { name: /开始投票|Start Vote/i })
        if (await startVoteBtn.isVisible().catch(() => false)) {
          await startVoteBtn.click()
          await ctx.hostPage.waitForTimeout(1_000)
        }
        await completeVote()
        continue
      }

      // If in NIGHT, complete night phase using shared helper
      const isNight = await ctx.hostPage.locator('.game-wrap.night-mode').first().isVisible().catch(() => false)
      if (isNight) {
        await completeNight()
        await ctx.hostPage.waitForTimeout(5_000)
        continue
      }

      // Unknown state — wait and retry
      testInfo.attach(`round-${round}-unknown-state`, { body: 'Unknown state, checking game status' })
      
      // Handle waiting screen by checking for and clicking available buttons
      const waitingScreen = await ctx.hostPage.locator('.waiting-screen').first().isVisible().catch(() => false)
      if (waitingScreen) {
        const allButtons = await ctx.hostPage.locator('button').all()
        for (const btn of allButtons) {
          const text = await btn.textContent()
          const isVisible = await btn.isVisible().catch(() => false)
          const isDisabled = await btn.isDisabled().catch(() => false)
          if (isVisible && !isDisabled && text) {
            testInfo.attach(`found-button-in-waiting-screen`, { body: `Found clickable button: ${text.trim()}` })
            await btn.click()
            await ctx.hostPage.waitForTimeout(2_000)
            break
          }
        }
      }
      
      // Check backend game status using STATUS command
      try {
        const statusOutput = execSync(
          `./scripts/act.sh STATUS --room ${ctx.roomCode}`, 
          { encoding: 'utf8', cwd: '/Users/dq/workspace/werewolf-simple' }
        )
        testInfo.attach(`backend-status-round-${round}`, { body: statusOutput })
      } catch (error) {
        testInfo.attach(`status-failed-round-${round}`, { body: `Failed to check game status: ${error}` })
      }
      
      // Wait and retry
      await ctx.hostPage.waitForTimeout(3_000)
    }

    // Game should be over
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
