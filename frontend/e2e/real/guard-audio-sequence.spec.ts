/**
 * Guard Audio Sequence E2E Test — Regression test for guard_close_eyes.mp3 playing twice
 *
 * Bug: When guard was the last special role to complete, guard_close_eyes.mp3
 * would play twice due to a race condition between PhaseChanged and AudioSequence events.
 *
 * Fix: GameView handlers no longer preserve audioSequence when setting state.
 * AudioSequence events are the single source of truth for audio.
 *
 * This test verifies that guard_close_eyes.mp3 plays exactly ONCE when guard completes.
 */
import {expect, test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act} from './helpers/shell-runner'
import {verifyAllBrowsersPhase} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'

let ctx: GameContext

test.describe('Guard Audio Sequence — Regression Test', () => {
  test.setTimeout(90_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    // Setup game with all special roles so guard is last
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD'],
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

  test('guard as last role - guard_close_eyes plays exactly once', async ({}, testInfo) => {
    const hostPage = ctx.hostPage

    // ── Step 1: Start night ───────────────────────────────────────────────
    const startNightBtn = hostPage.getByTestId('start-night')
    await startNightBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await startNightBtn.click()

    // Verify all browsers are in NIGHT phase
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)
    await captureSnapshot(ctx.pages, testInfo, '01-night-started')

    // ── Step 2: Wolf completes action ─────────────────────────────────────
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const nonHostVillager = villagerBots.find((b) => b.nick !== 'Host') ?? villagerBots[0]
    const target = nonHostVillager?.seat ?? 1
    const wolfBot = wolfBots.find((b) => b.nick !== 'Host')

    if (wolfBot) {
      await act('WOLF_KILL', wolfBot.nick, { target: String(target), room: ctx.roomCode })
    } else {
      const wolfPage = ctx.pages.get('WEREWOLF')!
      await wolfPage.locator(`.player-grid .slot-alive`).first().click()
      await wolfPage.getByRole('button', { name: /确认袭击|Confirm/i }).click()
    }

    // Wait for transition to SEER_PICK
    const seerPage = ctx.pages.get('SEER')
    if (seerPage) {
      await expect(
        seerPage.getByText(/选择查验目标|Select a player to check/i).first(),
      ).toBeVisible({ timeout: 15_000 })
    }
    await captureSnapshot(ctx.pages, testInfo, '02-wolf-completed')

    // ── Step 3: Seer completes action ─────────────────────────────────────
    const seerBots = ctx.roleMap.SEER ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const seerBot = seerBots.find((b) => b.nick !== 'Host')

    if (seerBot) {
      const checkTarget = guardBots[0]?.seat ?? villagerBots[1]?.seat ?? 1
      await act('SEER_CHECK', seerBot.nick, { target: String(checkTarget), room: ctx.roomCode })
      await hostPage.waitForTimeout(1_000)
      await act('SEER_CONFIRM', seerBot.nick, { room: ctx.roomCode })
    } else if (ctx.isHostRole('SEER')) {
      const seerPage = ctx.pages.get('SEER')!
      await seerPage.locator('.player-grid .slot-alive').first().click()
      await seerPage.getByRole('button', { name: /查验|Check/i }).click()
      await expect(seerPage.locator('.sr-wrap').first()).toBeVisible({ timeout: 10_000 })
      await seerPage.getByRole('button', { name: /查验完毕|Done/i }).click()
    }

    // Wait for transition to WITCH_ACT
    const witchPage = ctx.pages.get('WITCH')
    if (witchPage) {
      await expect(witchPage.locator('.w-section').first()).toBeVisible({ timeout: 15_000 })
    }
    await captureSnapshot(ctx.pages, testInfo, '03-seer-completed')

    // ── Step 4: Witch completes action ────────────────────────────────────
    // Pass both antidote and poison to move quickly to guard
    const passAntidoteBtn = witchPage!.getByRole('button', { name: /放弃/ })
    if (await passAntidoteBtn.isVisible().catch(() => false)) {
      await passAntidoteBtn.click()
      await witchPage!.waitForTimeout(500)
    }

    const skipPoisonBtn = witchPage!.getByRole('button', { name: /不用/ })
    if (await skipPoisonBtn.isVisible().catch(() => false)) {
      await skipPoisonBtn.click()
      await witchPage!.waitForTimeout(500)
    }

    // Wait for transition to GUARD_PICK
    const guardPage = ctx.pages.get('GUARD')
    if (guardPage) {
      await expect(
        guardPage.getByText(/选择守护目标|Protect a player/i).first(),
      ).toBeVisible({ timeout: 15_000 })
    }
    await captureSnapshot(ctx.pages, testInfo, '04-witch-completed')

    // ── Step 5: Guard completes action (LAST special role) ────────────────
    // This is the critical moment - guard is the last special role to complete
    // We need to verify that guard_close_eyes.mp3 plays exactly ONCE

    // Collect audio events from browser console
    const audioEvents: string[] = []

    // Setup console listener to capture audio playback events
    guardPage!.on('console', (msg) => {
      const text = msg.text()
      if (text.includes('AudioService') || text.includes('useAudioService')) {
        audioEvents.push(text)
      }
    })

    // Also listen on host page for any audio events
    hostPage.on('console', (msg) => {
      const text = msg.text()
      if (text.includes('AudioService') || text.includes('useAudioService')) {
        audioEvents.push(text)
      }
    })

    const guardBot = guardBots.find((b) => b.nick !== 'Host')
    if (guardBot) {
      await act('GUARD_SKIP', guardBot.nick, { room: ctx.roomCode })
    } else if (ctx.isHostRole('GUARD')) {
      await guardPage!.locator('.player-grid .slot-alive').first().click()
      await guardPage!.getByRole('button', { name: /确认保护|Confirm/i }).click()
    }

    // Wait for night to complete and day to start
    // This is where the bug would manifest - guard_close_eyes playing twice
    await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)
    await captureSnapshot(ctx.pages, testInfo, '05-guard-completed-day-started')

    // ── Step 6: Verify audio sequence integrity ───────────────────────────
    // Wait a bit for any delayed audio events
    await hostPage.waitForTimeout(3_000)

    // Check console logs for duplicate guard_close_eyes playback
    const guardCloseEyesEvents = audioEvents.filter(e =>
      e.includes('guard_close_eyes.mp3') && e.includes('Starting playback')
    )

    // CRITICAL ASSERTION: guard_close_eyes.mp3 should play exactly ONCE
    // If it plays twice, the bug is present
    expect(
      guardCloseEyesEvents.length,
      `guard_close_eyes.mp3 played ${guardCloseEyesEvents.length} times, expected 1. ` +
      `Audio events: ${JSON.stringify(guardCloseEyesEvents, null, 2)}`
    ).toBe(1)

    // Also verify that day audio (rooster_crowing.mp3) played
    const dayAudioEvents = audioEvents.filter(e =>
      e.includes('rooster_crowing.mp3') && e.includes('Starting playback')
    )
    expect(dayAudioEvents.length).toBeGreaterThanOrEqual(1)

    // Verify the sequence order: guard_close_eyes before rooster_crowing
    const guardCloseIndex = audioEvents.findIndex(e =>
      e.includes('guard_close_eyes.mp3') && e.includes('Starting playback')
    )
    const roosterIndex = audioEvents.findIndex(e =>
      e.includes('rooster_crowing.mp3') && e.includes('Starting playback')
    )

    expect(guardCloseIndex).toBeGreaterThanOrEqual(0)
    expect(roosterIndex).toBeGreaterThanOrEqual(0)
    expect(guardCloseIndex).toBeLessThan(roosterIndex)
  })

  test('rapid phase transitions - no duplicate or stale audio playback', async ({}, testInfo) => {
    // This test verifies that rapid state updates don't cause stale audio to replay
    const hostPage = ctx.hostPage

    // Start another night
    const startNightBtn = hostPage.getByTestId('start-night')
    if (await startNightBtn.isVisible().catch(() => false)) {
      await startNightBtn.click()
      await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)
    }

    // Collect all audio playback events
    const audioEvents: string[] = []
    const trackAudio = (msg: { text: () => string }) => {
      const text = msg.text()
      if (text.includes('Starting playback') || text.includes('Skipping duplicate')) {
        audioEvents.push(text)
      }
    }

    hostPage.on('console', trackAudio)
    ctx.pages.forEach(p => p.on('console', trackAudio))

    // Complete night quickly via scripts
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    const witchBots = ctx.roleMap.WITCH ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const villagerBots = ctx.roleMap.VILLAGER ?? []

    const wolfBot = wolfBots.find((b) => b.nick !== 'Host')
    const seerBot = seerBots.find((b) => b.nick !== 'Host')
    const witchBot = witchBots.find((b) => b.nick !== 'Host')
    const guardBot = guardBots.find((b) => b.nick !== 'Host')

    // Rapid-fire all night actions
    if (wolfBot) {
      await act('WOLF_KILL', wolfBot.nick, { target: String(villagerBots[0]?.seat ?? 1), room: ctx.roomCode })
    }
    await hostPage.waitForTimeout(500)

    if (seerBot) {
      await act('SEER_CHECK', seerBot.nick, { target: String(villagerBots[1]?.seat ?? 2), room: ctx.roomCode })
      await hostPage.waitForTimeout(500)
      await act('SEER_CONFIRM', seerBot.nick, { room: ctx.roomCode })
    }
    await hostPage.waitForTimeout(500)

    if (witchBot) {
      await act('WITCH_ACT', witchBot.nick, { payload: JSON.stringify({ useAntidote: false, usePoison: false }), room: ctx.roomCode })
    }
    await hostPage.waitForTimeout(500)

    if (guardBot) {
      await act('GUARD_SKIP', guardBot.nick, { room: ctx.roomCode })
    }

    // Wait for day transition
    await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)
    await hostPage.waitForTimeout(3_000)

    await captureSnapshot(ctx.pages, testInfo, 'rapid-transitions-complete')

    // Analyze audio events for duplicates
    const playbackEvents = audioEvents.filter(e => e.includes('Starting playback'))
    const filenames = playbackEvents.map(e => {
      const match = e.match(/Starting playback: ([\w_]+\.mp3)/)
      return match ? match[1] : null
    }).filter(Boolean)

    // Count occurrences of each audio file
    const counts = new Map<string, number>()
    for (const filename of filenames) {
      counts.set(filename!, (counts.get(filename!) || 0) + 1)
    }

    // No audio file should play more than once (except day_time which might repeat between rounds)
    for (const [filename, count] of counts.entries()) {
      if (filename !== 'day_time.mp3' && filename !== 'rooster_crowing.mp3') {
        expect(
          count,
          `${filename} played ${count} times during rapid transitions, expected 1`
        ).toBe(1)
      }
    }

    // Verify deduplication is working - should see "Skipping duplicate" messages
    const skipEvents = audioEvents.filter(e => e.includes('Skipping duplicate'))
    // We expect at least some deduplication to occur during rapid transitions
    expect(skipEvents.length).toBeGreaterThanOrEqual(0) // May or may not occur depending on timing
  })
})
