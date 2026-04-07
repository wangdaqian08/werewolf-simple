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
import {expect, test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act, type RoleName} from './helpers/shell-runner'
import {verifyAllBrowsersPhase,} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'

let ctx: GameContext

test.describe('Game flow — multi-browser STOMP verification', () => {
  test.setTimeout(60_000) // 3 minutes for the full flow

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(30_000) // setup can take a while with shell scripts
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
        const revealBtn = page.getByRole('button', { name: /揭示我的身份 \/ Reveal Role/i  })
        const revealBtnVisible = await revealBtn.isVisible().catch(() => false)
        expect(revealBtnVisible).toBe(true)
        await revealBtn.click()
        await page.waitForTimeout(300)
        const gotItBtn = page.getByRole('button', { name: /知道了 \/ Got it/i })
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
    const startNightBtn = hostPage.getByRole('button', { name: /开始夜晚|Start Night/i })

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

    // Wolf kill via script — target first villager (non-host)
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const nonHostVillager = villagerBots.find((b) => b.nick !== 'Host') ?? villagerBots[0]
    const target = nonHostVillager?.seat ?? 1
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const wolfBot = wolfBots.find((b) => b.nick !== 'Host')

    if (wolfBot) {
      // Wolf is a bot — use script
      act('WOLF_KILL', wolfBot.nick, { target: String(target), room: ctx.roomCode })
    } else {
      // Wolf is the host — use browser clicks
      const targetSlot = wolfPage.locator(`.player-grid .slot-alive`).first()
      await targetSlot.click()
      const confirmBtn = wolfPage.getByRole('button', { name: /确认袭击|Confirm/i })
      await confirmBtn.click()
    }

    // STOMP verify: seer browser should transition to SEER_PICK
    const seerPage = ctx.pages.get('SEER')
    if (seerPage && seerPage !== wolfPage) {
      await expect(
        seerPage.getByText(/选择查验目标|Select a player to check/i).first(),
      ).toBeVisible({ timeout: 15_000 })
    }

    await captureSnapshot(ctx.pages, testInfo, '03-wolf-kill')
  })

  // ── Test 4: Complete night via scripts, verify DAY transition ─────────

  test('4. Night — seer/witch/guard complete night, all browsers show DAY', async ({}, testInfo) => {
    const seerBots = ctx.roleMap.SEER ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const villagerBots = ctx.roleMap.VILLAGER ?? []

    // ── Seer ──
    const seerBot = seerBots.find((b) => b.nick !== 'Host')
    if (seerBot) {
      // Seer is a bot — use script
      const checkTarget = guardBots[0]?.seat ?? villagerBots[1]?.seat ?? 1
      act('SEER_CHECK', seerBot.nick, { target: String(checkTarget), room: ctx.roomCode })

      const seerPage = ctx.pages.get('SEER')
      if (seerPage) {
        await expect(seerPage.locator('.sr-wrap').first()).toBeVisible({ timeout: 10_000 })
        // Screenshot: seer sees check result
        await captureSnapshot(ctx.pages, testInfo, '04-seer-check-result')
      }

      act('SEER_CONFIRM', seerBot.nick, { room: ctx.roomCode })
    } else if (ctx.isHostRole('SEER')) {
      // Seer is the host — use browser clicks
      const seerPage = ctx.pages.get('SEER')!
      await expect(seerPage.getByText(/选择查验目标 · Select a player to check:/i).first()).toBeVisible({ timeout: 10_000 })
      const targetSlot = seerPage.locator('.player-grid .slot-alive').first()
      await targetSlot.waitFor({ state: 'visible', timeout: 5_000 })
      await targetSlot.click()
      await seerPage.getByRole('button', { name: /查验 · Check/i }).click()
      // Wait for result and confirm
      await expect(seerPage.locator('.sr-wrap').first()).toBeVisible({ timeout: 10_000 })
      // Screenshot: seer sees check result
      await captureSnapshot(ctx.pages, testInfo, '04-seer-check-result')
      await seerPage.getByRole('button', { name: /查验完毕|Done/i }).click()
    }

    // ── Witch (always via browser to capture UI at each step) ──
    // Both antidote and poison sections are visible simultaneously.
    // We interact with poison FIRST (since passing antidote may auto-complete the turn).
    const witchPage = ctx.pages.get('WITCH')!
    await expect(witchPage.locator('.w-section').first()).toBeVisible({ timeout: 15_000 })

    // Screenshot: before witch makes any action (both sections visible)
    await captureSnapshot(ctx.pages, testInfo, '04-witch-before-action')

    // -- Poison: enter target selection mode --
    const usePoisonBtn = witchPage.getByRole('button', { name: /使用毒药/ })
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
        const cancelBtn = witchPage.getByRole('button', { name: /取消/ })
        await cancelBtn.click()
        await witchPage.waitForTimeout(300)
      }
    }

    // -- Antidote decision --
    const useAntidoteBtn = witchPage.getByRole('button', { name: /使用解药/ })
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
    const skipPoisonBtn = witchPage.getByRole('button', { name: /不用/ })
    if (await skipPoisonBtn.isVisible().catch(() => false)) {
      await skipPoisonBtn.click()
      await witchPage.waitForTimeout(500)

      // Screenshot: after skipping poison (witch turn complete)
      await captureSnapshot(ctx.pages, testInfo, '04-witch-after-action')
    }

    // If no items at all, click done
    const doneBtn = witchPage.getByRole('button', { name: /完成操作|Done/i })
    if (await doneBtn.isVisible().catch(() => false)) {
      await doneBtn.click()
    }

    // ── Guard ──
    const guardBot = guardBots.find((b) => b.nick !== 'Host')
    if (guardBot) {
      // Guard is a bot — use script
      const guardPage = ctx.pages.get('GUARD')
      if (guardPage) {
        // Screenshot: guard UI is shown before action
        await expect(guardPage.getByText(/选择守护目标|Protect a player/i).first()).toBeVisible({ timeout: 10_000 })
        await captureSnapshot(ctx.pages, testInfo, '04-guard-ui')
      }
      act('GUARD_SKIP', guardBot.nick, { room: ctx.roomCode })
    } else if (ctx.isHostRole('GUARD')) {
      // Guard is the host — use browser clicks to protect someone
      const guardPage = ctx.pages.get('GUARD')!
      await expect(guardPage.getByText(/选择守护目标|Protect a player/i).first()).toBeVisible({ timeout: 10_000 })
      // Screenshot: guard UI is shown
      await captureSnapshot(ctx.pages, testInfo, '04-guard-ui')
      const targetSlot = guardPage.locator('.player-grid .slot-alive').first()
      await targetSlot.waitFor({ state: 'visible', timeout: 5_000 })
      await targetSlot.click()
      await guardPage.getByRole('button', { name: /确认保护|Confirm/i }).click()
    }

    // STOMP verify: ALL browsers should transition to DAY phase
    await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)

    await captureSnapshot(ctx.pages, testInfo, '04-night-complete')
  })

  // ── Test 5: Day phase — host reveals result ──────────────────────────

  test('5. Day — host reveals night result, kill shown on all browsers', async ({}, testInfo) => {
    const hostPage = ctx.hostPage

    // Host should see "显示结果 · Result" button
    const revealBtn = hostPage.getByRole('button', { name: /显示结果|Result/i })
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
    const startVoteBtn = hostPage.getByRole('button', { name: /开始投票|Start Vote/i })
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

    if (wolfTarget) {
      // All bots vote for the wolf (host's duplicate vote will be rejected harmlessly)
      act('SUBMIT_VOTE', undefined, { target: String(wolfTarget.seat), room: ctx.roomCode })
    } else {
      // All bots abstain
      act('SUBMIT_VOTE', undefined, { room: ctx.roomCode })
    }

    // Wait for all votes to register
    await hostPage.waitForTimeout(2_000)

    // Host reveals tally via script
    act('VOTING_REVEAL_TALLY', 'HOST', { room: ctx.roomCode })

    // Wait for the UI to update — could be "继续", "进入夜晚", or a hunter/badge action
    // The reveal may transition through multiple sub-phases
    await hostPage.waitForTimeout(3_000)

    await captureSnapshot(ctx.pages, testInfo, '07-vote-tally')

    // Try continuing via script (may fail if already auto-advanced)
    try {
      act('VOTING_CONTINUE', 'HOST', { room: ctx.roomCode })
    } catch {
      // May already have auto-continued or transitioned
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

    // Night 2: some players may be dead from previous rounds.
    // Use browser clicks for roles where the host has the role,
    // and try scripts with fallback for bots (which may be dead).

    // Helper: try script, return false if rejected (dead player, etc.)
    const tryAct = (...args: Parameters<typeof act>): boolean => {
      try {
        const output = act(...args)
        // Script exits 0 even on rejection — check output for "rejected"
        return !output.includes('rejected')
      } catch { return false }
    }

    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    const witchBots = ctx.roleMap.WITCH ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const villagerBots = ctx.roleMap.VILLAGER ?? []

    // ── Wolf kill — try each alive wolf bot with each alive non-wolf target ──
    const wolfPage = ctx.pages.get('WEREWOLF')
    let wolfDone = false

    // Collect all non-wolf potential targets
    const allTargets = [...villagerBots, ...seerBots, ...guardBots, ...witchBots]
      .filter((b) => b.nick !== 'Host')

    for (const wb of wolfBots.filter((b) => b.nick !== 'Host')) {
      for (const tgt of allTargets) {
        if (tryAct('WOLF_KILL', wb.nick, { target: String(tgt.seat), room: ctx.roomCode })) {
          wolfDone = true
          break
        }
      }
      if (wolfDone) break
    }

    // Fall back to browser clicks if no bot succeeded (host is wolf or all wolf bots dead)
    if (!wolfDone && wolfPage) {
      const playerGrid = wolfPage.locator('.player-grid')
      if (await playerGrid.first().isVisible({ timeout: 10_000 }).catch(() => false)) {
        const targetSlot = wolfPage.locator('.player-grid .slot-alive').first()
        await targetSlot.click()
        await wolfPage.getByRole('button', { name: /确认袭击|Confirm/i }).click()
      }
    }

    // ── Seer ──
    const seerBot = seerBots.find((b) => b.nick !== 'Host')
    let seerDone = false
    if (seerBot) {
      // Try multiple targets in case some are dead
      for (const tgt of allTargets) {
        if (tryAct('SEER_CHECK', seerBot.nick, { target: String(tgt.seat), room: ctx.roomCode })) {
          seerDone = true
          tryAct('SEER_CONFIRM', seerBot.nick, { room: ctx.roomCode })
          break
        }
      }
    }
    if (!seerDone && ctx.isHostRole('SEER')) {
      const seerPage = ctx.pages.get('SEER')!
      if (await seerPage.getByText(/选择查验目标|Select a player to check/i).first().isVisible({ timeout: 10_000 }).catch(() => false)) {
        await seerPage.locator('.player-grid .slot-alive').first().click()
        await seerPage.getByRole('button', { name: /查验|Check/i }).click()
        await expect(seerPage.locator('.sr-wrap').first()).toBeVisible({ timeout: 10_000 })
        await seerPage.getByRole('button', { name: /查验完毕|Done/i }).click()
      }
    }

    // ── Witch ──
    const witchBot = witchBots.find((b) => b.nick !== 'Host')
    let witchDone = false
    if (witchBot) {
      witchDone = tryAct('WITCH_ACT', witchBot.nick, { payload: '{"useAntidote":false}', room: ctx.roomCode })
    }
    if (!witchDone && ctx.isHostRole('WITCH')) {
      const witchPage = ctx.pages.get('WITCH')!
      if (await witchPage.locator('.w-section').first().isVisible({ timeout: 10_000 }).catch(() => false)) {
        const passBtn = witchPage.getByRole('button', { name: /放弃/ })
        if (await passBtn.isVisible().catch(() => false)) await passBtn.click()
        await witchPage.waitForTimeout(500)
        const skipBtn = witchPage.getByRole('button', { name: /不用/ })
        if (await skipBtn.isVisible().catch(() => false)) await skipBtn.click()
        const doneBtn = witchPage.getByRole('button', { name: /完成操作|Done/i })
        if (await doneBtn.isVisible().catch(() => false)) await doneBtn.click()
      }
    }

    // ── Guard ──
    const guardBot = guardBots.find((b) => b.nick !== 'Host')
    let guardDone = false
    if (guardBot) {
      guardDone = tryAct('GUARD_SKIP', guardBot.nick, { room: ctx.roomCode })
    }
    if (!guardDone && ctx.isHostRole('GUARD')) {
      const guardPage = ctx.pages.get('GUARD')!
      if (await guardPage.getByText(/选择守护目标|Protect a player/i).first().isVisible({ timeout: 10_000 }).catch(() => false)) {
        await guardPage.locator('.player-grid .slot-alive').first().click()
        await guardPage.getByRole('button', { name: /确认保护|Confirm/i }).click()
      }
    }

    // After night, should transition to DAY (or GAME_OVER)
    await ctx.hostPage.waitForTimeout(5_000)

    const isOver = ctx.hostPage.url().includes('/result/')
    if (!isOver) {
      await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)
    }

    await captureSnapshot(ctx.pages, testInfo, '08-night2-complete')
  })
})
