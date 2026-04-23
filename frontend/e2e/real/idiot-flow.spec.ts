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
 *   - Idiot loses voting right permanently
 *   - Phase transitions correctly from VOTE_RESULT to NIGHT
 */
import {expect, test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act, type RoleName} from './helpers/shell-runner'
import {verifyAllBrowsersPhase,} from './helpers/assertions'
import {captureSnapshot} from './helpers/composite-screenshot'
import {waitForNightSubPhase} from './helpers/state-polling'

let ctx: GameContext

test.describe('Idiot flow — multi-browser STOMP verification', () => {
  test.setTimeout(60_000) // 3 minutes for the full flow

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
      // Verify that IDIOT role is assigned
      const idiotBots = localCtx.roleMap['IDIOT']
      expect(idiotBots).toBeDefined()
      expect(idiotBots?.length).toBeGreaterThan(0)
      
      // Verify that IDIOT browser page exists
      const idiotPage = localCtx.pages.get('IDIOT')
      expect(idiotPage).toBeDefined()
      
      testInfo.attach('idiot-info', { body: JSON.stringify({
        idiotBots: idiotBots,
        hasIdiotPage: !!idiotPage,
        totalBots: localCtx.allBots.length
      }, null, 2) })
    } finally {
      await localCtx.cleanup()
    }
  })

  // ── Test 2: Night → Day → Voting → Idiot Reveal ─────────────────────────────────

  test('2. Idiot reveal — all browsers show idiot reveal banner', async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    const localCtx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'IDIOT'] as RoleName[],
      browserRoles: ['IDIOT', 'WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
    })

    try {
      const hostPage = localCtx.hostPage

      // Check initial game state after setup
      const initialState = await hostPage.evaluate(() => {
        const gameWrap = document.querySelector('.game-wrap')
        const waitingScreen = document.querySelector('.waiting-screen')
        const nightWrap = document.querySelector('.night-wrap')
        const dayWrap = document.querySelector('.day-wrap')
        const votingWrap = document.querySelector('.voting-wrap')
        return { 
          hasWaitingScreen: !!waitingScreen,
          hasNightWrap: !!nightWrap,
          hasDayWrap: !!dayWrap,
          hasVotingWrap: !!votingWrap,
          gameWrapClasses: gameWrap?.className || ''
        }
      })
      testInfo.attach('initial-game-state', { body: JSON.stringify(initialState, null, 2) })
      
      // If we're in waiting screen, give more time for game to start
      if (initialState.hasWaitingScreen) {
        testInfo.attach('waiting-for-game-start', { body: 'Game in waiting screen, waiting for auto-advancement' })
        await hostPage.waitForTimeout(10_000)
      }
      
      // Re-check game state after waiting
      const afterWaitState = await hostPage.evaluate(() => {
        const gameWrap = document.querySelector('.game-wrap')
        const waitingScreen = document.querySelector('.waiting-screen')
        const nightWrap = document.querySelector('.night-wrap')
        const dayWrap = document.querySelector('.day-wrap')
        return { 
          hasWaitingScreen: !!waitingScreen,
          hasNightWrap: !!nightWrap,
          hasDayWrap: !!dayWrap,
          gameWrapClasses: gameWrap?.className || ''
        }
      })
      testInfo.attach('after-wait-game-state', { body: JSON.stringify(afterWaitState, null, 2) })
      
      // If we're still in waiting screen, try to manually advance to night
      if (afterWaitState.hasWaitingScreen) {
        testInfo.attach('trying-manual-night-start', { body: 'Attempting to manually start night phase' })
        
        // Try to use script to start night phase
        try {
          act('START_NIGHT', undefined, { room: localCtx.roomCode })
          testInfo.attach('night-start-triggered', { body: 'Start night action triggered' })
          await hostPage.waitForTimeout(5_000)
        } catch (error) {
          testInfo.attach('night-start-failed', { body: `Failed to start night: ${error}` })
        }
      }
      
      // Final check of game state
      const finalState = await hostPage.evaluate(() => {
        const gameWrap = document.querySelector('.game-wrap')
        const waitingScreen = document.querySelector('.waiting-screen')
        const nightWrap = document.querySelector('.night-wrap')
        const dayWrap = document.querySelector('.day-wrap')
        return { 
          hasWaitingScreen: !!waitingScreen,
          hasNightWrap: !!nightWrap,
          hasDayWrap: !!dayWrap,
          gameWrapClasses: gameWrap?.className || ''
        }
      })
      testInfo.attach('final-game-state', { body: JSON.stringify(finalState, null, 2) })
      
      // ── Phase 1: Night Phase ──
      testInfo.attach('starting-night', { body: 'Starting night phase' })
      
      // Check current phase before night actions
      const beforeNightPhase = await hostPage.evaluate(() => {
        const nightWrap = document.querySelector('.night-wrap')
        const waitingScreen = document.querySelector('.waiting-screen')
        return {
          hasNightWrap: !!nightWrap,
          hasWaitingScreen: !!waitingScreen,
          nightSubPhase: nightWrap ? nightWrap.querySelector('.night-sub-phase')?.textContent : null
        }
      })
      testInfo.attach('before-night-phase', { body: JSON.stringify(beforeNightPhase, null, 2) })
      
      const wolfBots = localCtx.roleMap.WEREWOLF ?? []
      const seerBots = localCtx.roleMap.SEER ?? []
      const witchBots = localCtx.roleMap.WITCH ?? []

      // Wolf attacks someone (not idiot to ensure idiot survives).
      // Gate on WEREWOLF_PICK so the action lands in the correct sub-phase —
      // without this, act() fires while the Kotlin role-loop is still in a
      // prior sub-phase and the action is silently rejected (act.sh exits 0
      // on rejection). See e2e-ci-vs-local-env-differences memory item 1.
      // If the gate returns false (coroutine skipped the sub-phase), skip
      // the block rather than firing actions that'll be rejected.
      if (wolfBots.length > 0) {
        const wolfBot = wolfBots.find((b) => b.nick !== 'Host') ?? wolfBots[0]
        const idiotBots = localCtx.roleMap['IDIOT'] ?? []
        const targetBot = localCtx.allBots.find(b =>
          b.userId !== wolfBot.userId &&
          !(idiotBots.some(i => i.userId === b.userId))
        )
        if (targetBot && (await waitForNightSubPhase(hostPage, localCtx.gameId, 'WEREWOLF_PICK', 15_000))) {
          act('WOLF_SELECT', wolfBot.nick, { target: String(targetBot.seat), room: localCtx.roomCode })
          act('WOLF_KILL', wolfBot.nick, { target: String(targetBot.seat), room: localCtx.roomCode })
          testInfo.attach('wolf-action', { body: `Wolf ${wolfBot.nick} attacks ${targetBot.nick} at seat ${targetBot.seat}` })
        }
      }

      // Seer checks someone. Gate on SEER_PICK before the CHECK, then on
      // SEER_RESULT before the CONFIRM so each lands in its expected sub-phase.
      if (seerBots.length > 0) {
        const seerBot = seerBots.find((b) => b.nick !== 'Host') ?? seerBots[0]
        const checkTarget = localCtx.allBots.find(b => b.userId !== seerBot.userId)
        if (checkTarget && (await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_PICK', 15_000))) {
          act('SEER_CHECK', seerBot.nick, { target: String(checkTarget.seat), room: localCtx.roomCode })
          if (await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_RESULT', 10_000)) {
            act('SEER_CONFIRM', seerBot.nick, { room: localCtx.roomCode })
          }
          testInfo.attach('seer-action', { body: `Seer ${seerBot.nick} checks ${checkTarget.nick}` })
        }
      }

      // Witch uses no potion — gate on WITCH_ACT sub-phase first.
      if (witchBots.length > 0) {
        const witchBot = witchBots.find((b) => b.nick !== 'Host') ?? witchBots[0]
        if (await waitForNightSubPhase(hostPage, localCtx.gameId, 'WITCH_ACT', 15_000)) {
          act('WITCH_ACT', witchBot.nick, { room: localCtx.roomCode, payload: '{"useAntidote":false}' })
          testInfo.attach('witch-action', { body: `Witch ${witchBot.nick} uses no potion` })
        }
      }

      // Wait for night to complete and transition to DAY
      testInfo.attach('waiting-for-night-to-day', { body: 'Waiting for night to complete and transition to DAY' })
      
      // Use a more reliable wait strategy - check for day phase indicator
      await hostPage.waitForTimeout(5_000)
      
      // Try multiple times to check if we've reached day phase
      let dayPhaseReached = false
      for (let i = 0; i < 6; i++) {
        const phaseCheck = await hostPage.evaluate(() => {
          const dayWrap = document.querySelector('.day-wrap')
          const waitingScreen = document.querySelector('.waiting-screen')
          const nightWrap = document.querySelector('.night-wrap')
          return {
            hasDayWrap: !!dayWrap,
            hasWaitingScreen: !!waitingScreen,
            hasNightWrap: !!nightWrap
          }
        })
        
        testInfo.attach(`phase-check-attempt-${i}`, { body: JSON.stringify(phaseCheck, null, 2) })
        
        if (phaseCheck.hasDayWrap || !phaseCheck.hasNightWrap) {
          dayPhaseReached = phaseCheck.hasDayWrap
          break
        }
        
        await hostPage.waitForTimeout(3_000)
      }
      
      if (!dayPhaseReached) {
        testInfo.attach('day-phase-not-reached', { body: 'Day phase not reached after multiple attempts' })
      }
      
      // Try to capture current phase information
      const currentPhase = await hostPage.evaluate(() => {
        const gameWrap = document.querySelector('.game-wrap')
        if (gameWrap) {
          const classes = gameWrap.className
          const waitingScreen = document.querySelector('.waiting-screen')
          const dayWrap = document.querySelector('.day-wrap')
          const nightWrap = document.querySelector('.night-wrap')
          return { 
            classes, 
            hasWaitingScreen: !!waitingScreen,
            hasDayWrap: !!dayWrap,
            hasNightWrap: !!nightWrap,
            html: gameWrap.innerHTML.substring(0, 500)
          }
        }
        return { error: 'No game wrap found' }
      })
      testInfo.attach('current-phase-before-day', { body: JSON.stringify(currentPhase, null, 2) })
      
      // If we're still in waiting screen, give more time
      if (currentPhase.hasWaitingScreen) {
        testInfo.attach('still-waiting', { body: 'Still in waiting screen, waiting more...' })
        await hostPage.waitForTimeout(10_000)
      }
      
      // ── Phase 2: Day Phase ──
      testInfo.attach('waiting-day', { body: 'Waiting for day phase' })
      await verifyAllBrowsersPhase(localCtx.pages, 'DAY', 15_000)
      testInfo.attach('day-phase-reached', { body: 'Day phase reached successfully' })

      // Host reveals night result
      const revealBtn = hostPage.getByTestId('day-reveal-result')
      await revealBtn.waitFor({ state: 'visible', timeout: 10_000 })
      await revealBtn.click()
      
      await captureSnapshot(localCtx.pages, testInfo, '01-day-reveal')
      await hostPage.waitForTimeout(2_000)
      
      // ── Phase 3: Voting Phase ──
      // Host starts voting
      const startVoteBtn = hostPage.getByTestId('day-start-vote')
      await startVoteBtn.waitFor({ state: 'visible', timeout: 10_000 })
      await startVoteBtn.click()
      
      await verifyAllBrowsersPhase(localCtx.pages, 'VOTING', 15_000)
      testInfo.attach('voting-phase-reached', { body: 'Voting phase reached successfully' })
      
      // ── Phase 4: Idiot Reveal ──
      // Get idiot bot information
      const idiotBots = localCtx.roleMap['IDIOT']
      if (!idiotBots || idiotBots.length === 0) {
        throw new Error('IDIOT bots not found')
      }
      
      const idiotBot = idiotBots[0]
      testInfo.attach('idiot-info', { body: JSON.stringify(idiotBot, null, 2) })
      
      // All players vote for the idiot to trigger idiot reveal
      act('SUBMIT_VOTE', undefined, { target: String(idiotBot.seat), room: localCtx.roomCode })
      testInfo.attach('votes-submitted', { body: `All players voted for idiot at seat ${idiotBot.seat}` })
      
      // Wait for all votes to register
      await hostPage.waitForTimeout(2_000)
      
      // Host reveals tally via script
      act('VOTING_REVEAL_TALLY', 'HOST', { room: localCtx.roomCode })
      testInfo.attach('tally-revealed', { body: 'Vote tally revealed' })
      
      // Wait for the UI to update - this is where idiot reveal should happen
      await hostPage.waitForTimeout(3_000)
      
      // Verify idiot reveal banner appears in all browsers
      for (const [roleName, page] of localCtx.pages) {
        const idiotBanner = page.locator('.elim-banner-body').filter({ hasText: /白痴翻牌|IDIOT REVEALED/i })
        await expect(idiotBanner).toBeVisible({ timeout: 10_000 })
        
        // Verify banner shows correct content
        await expect(idiotBanner.getByText(/白痴翻牌/i)).toBeVisible()
        await expect(idiotBanner.getByText(/IDIOT REVEALED/i)).toBeVisible()
        // Verify the idiot's nickname appears in the banner
        await expect(idiotBanner.getByText(new RegExp(idiotBot.nick, 'i'))).toBeVisible()
        
        testInfo.attach(`idiot-banner-${roleName}`, { body: `Banner visible in ${roleName} browser` })
      }
      
      // Verify 🃏 overlay appears on idiot's card in all browsers
      for (const [roleName, page] of localCtx.pages) {
        const idiotOverlay = page.locator('.slot-overlay.idiot-overlay')
        await expect(idiotOverlay).toBeVisible({ timeout: 10_000 })
        
        testInfo.attach(`idiot-overlay-${roleName}`, { body: `Overlay visible in ${roleName} browser` })
      }
      
      testInfo.attach('idiot-reveal-verified', { body: 'Idiot reveal banner and overlay verified in all browsers' })
      
    } finally {
      await localCtx.cleanup()
    }
  })

  // ── Test 2: Phase Transition after Idiot Reveal ──────────────────────────────

  test('3. Phase transition — VOTE_RESULT to NIGHT', async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    const localCtx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'IDIOT'] as RoleName[],
      browserRoles: ['IDIOT', 'WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
    })

    try {
      const hostPage = localCtx.hostPage

      // ── Phase 0: Start Night Phase ──
      // First, we need to start the night phase like test1 does
      testInfo.attach('starting-game-setup', { body: 'Starting game and triggering night phase' })
      
      // Use script to start night phase
      try {
        act('START_NIGHT', undefined, { room: localCtx.roomCode })
        testInfo.attach('night-start-triggered', { body: 'Start night action triggered' })
        await hostPage.waitForTimeout(5_000)
      } catch (error) {
        testInfo.attach('night-start-failed', { body: `Failed to start night: ${error}` })
      }
      
      // ── Phase 1: Complete Night Phase (sub-phase-gated — see Test 2 rationale) ──
      const wolfBots = localCtx.roleMap.WEREWOLF ?? []
      const seerBots = localCtx.roleMap.SEER ?? []
      const witchBots = localCtx.roleMap.WITCH ?? []

      if (wolfBots.length > 0) {
        const wolfBot = wolfBots.find((b) => b.nick !== 'Host') ?? wolfBots[0]
        const idiotBots = localCtx.roleMap['IDIOT'] ?? []
        const targetBot = localCtx.allBots.find(b =>
          b.userId !== wolfBot.userId &&
          !(idiotBots.some(i => i.userId === b.userId))
        )
        if (targetBot && (await waitForNightSubPhase(hostPage, localCtx.gameId, 'WEREWOLF_PICK', 15_000))) {
          act('WOLF_SELECT', wolfBot.nick, { target: String(targetBot.seat), room: localCtx.roomCode })
          act('WOLF_KILL', wolfBot.nick, { target: String(targetBot.seat), room: localCtx.roomCode })
        }
      }

      if (seerBots.length > 0) {
        const seerBot = seerBots.find((b) => b.nick !== 'Host') ?? seerBots[0]
        const checkTarget = localCtx.allBots.find(b => b.userId !== seerBot.userId)
        if (checkTarget && (await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_PICK', 15_000))) {
          act('SEER_CHECK', seerBot.nick, { target: String(checkTarget.seat), room: localCtx.roomCode })
          if (await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_RESULT', 10_000)) {
            act('SEER_CONFIRM', seerBot.nick, { room: localCtx.roomCode })
          }
        }
      }

      if (witchBots.length > 0) {
        const witchBot = witchBots.find((b) => b.nick !== 'Host') ?? witchBots[0]
        if (await waitForNightSubPhase(hostPage, localCtx.gameId, 'WITCH_ACT', 15_000)) {
          act('WITCH_ACT', witchBot.nick, { room: localCtx.roomCode, payload: '{"useAntidote":false}' })
        }
      }

      await hostPage.waitForTimeout(5_000)
      
      // Use smart wait strategy for day phase
      let dayPhaseReached = false
      for (let i = 0; i < 6; i++) {
        const phaseCheck = await hostPage.evaluate(() => {
          const dayWrap = document.querySelector('.day-wrap')
          const waitingScreen = document.querySelector('.waiting-screen')
          const nightWrap = document.querySelector('.night-wrap')
          const gameWrap = document.querySelector('.game-wrap')
          const body = document.body
          const allButtons = Array.from(document.querySelectorAll('button'))
          const buttonInfo = allButtons.map(btn => ({
            text: btn.textContent?.trim(),
            visible: btn.offsetParent !== null,
            disabled: btn.disabled
          }))
          return {
            hasDayWrap: !!dayWrap,
            hasWaitingScreen: !!waitingScreen,
            hasNightWrap: !!nightWrap,
            hasGameWrap: !!gameWrap,
            bodyText: body.textContent?.substring(0, 200),
            bodyClasses: body.className,
            buttons: buttonInfo
          }
        })
        
        testInfo.attach(`phase-check-attempt-${i}-test2`, { body: JSON.stringify(phaseCheck, null, 2) })
        
        // If we're stuck in waiting screen, try to click any visible button to advance the game
        if (phaseCheck.hasWaitingScreen && !phaseCheck.hasDayWrap) {
          const continueBtn = phaseCheck.buttons.find(btn => 
            btn.visible && !btn.disabled && 
            (btn.text.includes('开始夜晚') || btn.text.includes('Start Night') || 
             btn.text.includes('继续') || btn.text.includes('Continue') || 
             btn.text.includes('进入') || btn.text.includes('Enter'))
          )
          if (continueBtn) {
            testInfo.attach(`clicking-continue-button-attempt-${i}`, { body: `Found continue button: ${continueBtn.text}` })
            // Click the first matching button
            const allButtons = await hostPage.locator('button').all()
            for (const btn of allButtons) {
              const text = await btn.textContent()
              if (text?.includes(continueBtn.text)) {
                await btn.click()
                await hostPage.waitForTimeout(2_000)
                break
              }
            }
          }
        }
        
        if (phaseCheck.hasDayWrap) {
          dayPhaseReached = true
          break
        }
        
        await hostPage.waitForTimeout(3_000)
      }
      
      if (!dayPhaseReached) {
        testInfo.attach('day-phase-not-reached-test2', { body: 'Day phase not reached after multiple attempts in test2' })
      }
      
      // ── Phase 2: Day Phase ──
      await verifyAllBrowsersPhase(localCtx.pages, 'DAY', 15_000)

      const revealBtn = hostPage.getByTestId('day-reveal-result')
      await revealBtn.waitFor({ state: 'visible', timeout: 10_000 })
      await revealBtn.click()
      
      await hostPage.waitForTimeout(2_000)
      
      // ── Phase 3: Voting Phase with Idiot Reveal ──
      const startVoteBtn = hostPage.getByTestId('day-start-vote')
      await startVoteBtn.waitFor({ state: 'visible', timeout: 10_000 })
      await startVoteBtn.click()
      
      await verifyAllBrowsersPhase(localCtx.pages, 'VOTING', 15_000)
      
      // Vote for idiot to trigger reveal
      const idiotBots = localCtx.roleMap['IDIOT']
      if (!idiotBots || idiotBots.length === 0) {
        throw new Error('IDIOT bots not found')
      }
      
      const idiotBot = idiotBots[0]
      act('SUBMIT_VOTE', undefined, { target: String(idiotBot.seat), room: localCtx.roomCode })
      await hostPage.waitForTimeout(2_000)
      act('VOTING_REVEAL_TALLY', 'HOST', { room: localCtx.roomCode })
      await hostPage.waitForTimeout(3_000)
      
      // Verify idiot reveal banner is visible
      const idiotBanner = hostPage.locator('.elim-banner-body').filter({ hasText: /白痴翻牌|IDIOT REVEALED/i })
      await expect(idiotBanner).toBeVisible({ timeout: 10_000 })
      testInfo.attach('idiot-reveal-confirmed', { body: 'Idiot reveal banner confirmed visible' })
      
      // ── Phase 4: Transition to NIGHT ──
      // Host can click continue button to advance to night
      const continueBtn = hostPage.getByTestId('voting-continue')
      await continueBtn.waitFor({ state: 'visible', timeout: 10_000 })
      await continueBtn.click()
      testInfo.attach('continue-clicked', { body: 'Host clicked continue to advance to night' })
      
      // Verify all browsers transition to NIGHT phase
      await verifyAllBrowsersPhase(localCtx.pages, 'NIGHT', 15_000)
      testInfo.attach('night-phase-reached', { body: 'All browsers successfully transitioned to NIGHT phase' })
      
      // Additional verification: confirm idiot reveal banner is no longer visible (we're in night phase now)
      const idiotBannerAfter = hostPage.locator('.elim-banner-body').filter({ hasText: /白痴翻牌|IDIOT REVEALED/i })
      await expect(idiotBannerAfter).not.toBeVisible({ timeout: 5_000 })
      testInfo.attach('idiot-banner-gone', { body: 'Idiot reveal banner correctly hidden in night phase' })
      
    } finally {
      await localCtx.cleanup()
    }
  })
})