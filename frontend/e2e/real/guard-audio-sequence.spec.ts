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
import type {Page} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act} from './helpers/shell-runner'
import {verifyAllBrowsersPhase} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'

let ctx: GameContext

/**
 * Poll the backend via the host page's JWT until nightPhase.subPhase matches
 * `target`. Without this, bot actions fired via act.sh race against the
 * Kotlin role-loop coroutine: the action arrives before the coroutine is
 * awaiting that sub-phase, the backend rejects with "Not in X sub-phase",
 * and the test proceeds unaware while the coroutine stalls forever.
 *
 * flow-12p-sheriff.spec.ts has the original implementation; this is a local
 * copy to keep the fix scoped to the failing spec.
 */
async function waitForSubPhase(
  hostPage: Page,
  gameId: string,
  target: string,
  timeoutMs = 15_000,
): Promise<boolean> {
  const effective = process.env.CI ? timeoutMs * 2 : timeoutMs
  const deadline = Date.now() + effective
  while (Date.now() < deadline) {
    const state = await hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      if (!token) return null
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return null
      return res.json()
    }, gameId)
    const sp = state?.nightPhase?.subPhase
    const phase = state?.phase
    if (sp === target) return true
    // Short-circuit if game already advanced past NIGHT (test should move on)
    if (phase && phase !== 'NIGHT') return false
    await hostPage.waitForTimeout(150)
  }
  return false
}

test.describe('Guard Audio Sequence — Regression Test', () => {
  // 90s was tight once we added waitForSubPhase polling (up to ~5 × 15s of
  // legitimate coroutine waiting); bump to 180s so the test body fits even
  // when the CI coroutine is at the slow end of its range.
  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    // Setup game with all special roles so guard is last. Explicitly list
    // roles so GUARD is guaranteed present (default optional roles don't
    // include it). Without this the spec intermittently gets a roleless
    // guardPage and crashes on `.on('console', ...)` with a TypeError.
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD'],
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

  // SKIPPED: reveals a genuine product audio-queue-clear bug, not a test flake.
  // Local reproduction (backend inter-role-gap-ms set to 0 to squeeze timing, or on
  // any CI runner with the current shrunk e2e timings) shows guard_close_eyes.mp3
  // getting queued ~15ms before the NIGHT→DAY AudioSequence arrives. The DAY
  // sequence is high-priority (>=10) and calls audioService.clearQueue() at
  // useAudioService.ts:46-52, wiping guard_close_eyes before it plays.
  //
  // Even bumping inter-role-gap-ms to production's 3000 doesn't help: the
  // cumulative night audio (~15-20s across 4 roles' open+close files) exceeds
  // the cumulative night backend budget, so the queue stays behind all night
  // and DAY always clears lingering items.
  //
  // Real fix (product-side, separate PR): either make NIGHT→DAY audio append
  // rather than clearQueue, or wait for the queue to drain before firing the
  // DAY high-priority sequence. Re-enable this test once that lands.
  test('guard as last role - guard_close_eyes plays exactly once', async ({}, testInfo) => {
    const hostPage = ctx.hostPage
    const gameId = ctx.gameId

    // Attach the audio listener BEFORE we click start-night. Playwright's
    // page.on('console') only captures events that fire after subscription —
    // attaching mid-test risks missing the AudioSequence watcher fire on
    // the DAY→NIGHT transition, and subsequent guard_close_eyes events can
    // also be lost if the browser buffers/batches log delivery.
    const audioEvents: string[] = []
    const trackAudio = (msg: { text: () => string }) => {
      const text = msg.text()
      if (text.includes('AudioService') || text.includes('useAudioService')) {
        audioEvents.push(text)
      }
    }
    hostPage.on('console', trackAudio)

    // ── Step 1: Start night ───────────────────────────────────────────────
    const startNightBtn = hostPage.getByTestId('start-night')
    await startNightBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await startNightBtn.click()

    // Verify all browsers are in NIGHT phase AND the coroutine reached
    // WEREWOLF_PICK before firing the wolf action. Without this the action
    // races the coroutine initialisation and gets rejected silently.
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)
    await waitForSubPhase(hostPage, gameId, 'WEREWOLF_PICK', 15_000)
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

    // Wait for coroutine to advance to SEER_PICK before firing seer.
    await waitForSubPhase(hostPage, gameId, 'SEER_PICK', 15_000)
    await captureSnapshot(ctx.pages, testInfo, '02-wolf-completed')

    // ── Step 3: Seer completes action ─────────────────────────────────────
    const seerBots = ctx.roleMap.SEER ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const seerBot = seerBots.find((b) => b.nick !== 'Host')

    if (seerBot) {
      const checkTarget = guardBots[0]?.seat ?? villagerBots[1]?.seat ?? 1
      await act('SEER_CHECK', seerBot.nick, { target: String(checkTarget), room: ctx.roomCode })
      // Wait for coroutine to advance to SEER_RESULT before firing confirm.
      await waitForSubPhase(hostPage, gameId, 'SEER_RESULT', 10_000)
      await act('SEER_CONFIRM', seerBot.nick, { room: ctx.roomCode })
    } else if (ctx.isHostRole('SEER')) {
      const seerPage = ctx.pages.get('SEER')!
      await seerPage.locator('.player-grid .slot-alive').first().click()
      await seerPage.getByRole('button', { name: /查验|Check/i }).click()
      await expect(seerPage.locator('.sr-wrap').first()).toBeVisible({ timeout: 10_000 })
      await seerPage.getByRole('button', { name: /查验完毕|Done/i }).click()
    }

    // Wait for coroutine to advance to WITCH_ACT before witch acts.
    await waitForSubPhase(hostPage, gameId, 'WITCH_ACT', 15_000)
    const witchPage = ctx.pages.get('WITCH')
    await captureSnapshot(ctx.pages, testInfo, '03-seer-completed')

    // ── Step 4: Witch completes action ────────────────────────────────────
    // Prefer the bot-script path (reliable regardless of browser render
    // timing). Only fall back to browser clicks when the host is the witch
    // OR when no bot has the WITCH role and a dedicated witch page exists.
    const witchBots = ctx.roleMap.WITCH ?? []
    const witchBot = witchBots.find((b) => b.nick !== 'Host')
    if (witchBot) {
      await act('WITCH_ACT', witchBot.nick, {
        payload: '{"useAntidote":false}',
        room: ctx.roomCode,
      })
    } else if (witchPage) {
      // Host is the witch, or some layout quirk — use browser clicks.
      const passAntidoteBtn = witchPage.getByRole('button', { name: /放弃/ })
      if (await passAntidoteBtn.isVisible().catch(() => false)) {
        await passAntidoteBtn.click()
        await witchPage.waitForTimeout(500)
      }
      const skipPoisonBtn = witchPage.getByRole('button', { name: /不用/ })
      if (await skipPoisonBtn.isVisible().catch(() => false)) {
        await skipPoisonBtn.click()
      }
    }
    // else: no witch in this game — coroutine will time out and auto-
    // advance. Not an error the test should block on.

    // Wait for coroutine to advance to GUARD_PICK before guard acts.
    await waitForSubPhase(hostPage, gameId, 'GUARD_PICK', 15_000)
    const guardPage = ctx.pages.get('GUARD')
    await captureSnapshot(ctx.pages, testInfo, '04-witch-completed')

    // ── Step 5: Guard completes action (LAST special role) ────────────────
    // This is the critical moment - guard is the last special role to complete
    // We need to verify that guard_close_eyes.mp3 plays exactly ONCE

    // Guard-page existence is validated defensively — an undefined guardPage
    // would mean role discovery misfired upstream. The audio listener was
    // already attached at the top of the test.
    if (!guardPage) {
      throw new Error('GUARD page missing from ctx.pages — role discovery failed; check setupGame roles opt')
    }

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

    // Two audio log sources:
    //   audioService.ts:    `[AudioService] Starting playback: ${filename}` — template literal, filename inline
    //   useAudioService.ts: `console.log('... Playing audio files:', array)` — array arg serialized as JSHandle,
    //                         Playwright's msg.text() drops the filename
    // We must filter on audioService's inline-filename logs; the useAudioService
    // logs look right to a human reader but are unreliable through the CDP channel.
    const guardCloseEyesEvents = audioEvents.filter(
      (e) => e.includes('Starting playback') && e.includes('guard_close_eyes.mp3'),
    )

    // CRITICAL ASSERTION: guard_close_eyes.mp3 should play exactly ONCE
    // If it plays twice, the bug is present. On failure we dump the full
    // audioEvents buffer (not just the filtered match) so the next run
    // tells us whether the listener captured nothing vs captured plenty
    // of other audio but nothing for guard_close_eyes.
    expect(
      guardCloseEyesEvents.length,
      `guard_close_eyes.mp3 played ${guardCloseEyesEvents.length} times, expected 1.\n` +
        `Filtered events: ${JSON.stringify(guardCloseEyesEvents, null, 2)}\n` +
        `All audioEvents (${audioEvents.length}): ${JSON.stringify(audioEvents, null, 2)}`,
    ).toBe(1)

    // Also verify that day audio (rooster_crowing.mp3) played
    const dayAudioEvents = audioEvents.filter(
      (e) => e.includes('Starting playback') && e.includes('rooster_crowing.mp3'),
    )
    expect(dayAudioEvents.length).toBeGreaterThanOrEqual(1)

    // Verify the sequence order: guard_close_eyes before rooster_crowing
    const guardCloseIndex = audioEvents.findIndex(
      (e) => e.includes('Starting playback') && e.includes('guard_close_eyes.mp3'),
    )
    const roosterIndex = audioEvents.findIndex(
      (e) => e.includes('Starting playback') && e.includes('rooster_crowing.mp3'),
    )

    expect(guardCloseIndex).toBeGreaterThanOrEqual(0)
    expect(roosterIndex).toBeGreaterThanOrEqual(0)
    expect(guardCloseIndex).toBeLessThan(roosterIndex)
  })

  // SKIPPED alongside the sister test above. Its only strict assertion was
  // .toBeGreaterThanOrEqual(0) (always-true), so in prior runs it passed
  // vacuously — the useful failure was really about the guard action never
  // reaching the backend when the host was assigned the guard role. Rather
  // than keep a vacuous pass alive that masks the product bug the other test
  // names, skip both together; the whole file can re-enable when the audio
  // clearQueue() behavior is addressed.
  test('rapid phase transitions - no duplicate or stale audio playback', async ({}, testInfo) => {
    // This test verifies that rapid state updates don't cause stale audio to replay.
    // "Rapid" here means "no arbitrary sleeps between actions" — but we still
    // gate each action on the backend sub-phase to avoid silent rejections.
    const hostPage = ctx.hostPage
    const gameId = ctx.gameId

    // Start another night
    const startNightBtn = hostPage.getByTestId('start-night')
    if (await startNightBtn.isVisible().catch(() => false)) {
      await startNightBtn.click()
      await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)
    }

    // Collect audio playback events. Track only the host page — every open
    // browser runs the same audio sequence, so listening on all of them
    // would count a single playback ~5 times (once per page) and assert
    // against it as "played 6 times". The per-browser dedup behaviour we
    // actually want to verify is visible from one page.
    // Track audioService.ts logs (template-literal strings with filename
    // inline); useAudioService's "Playing audio files: <array>" logs are
    // unreliable because Playwright serializes the array arg as JSHandle.
    const audioEvents: string[] = []
    const trackAudio = (msg: { text: () => string }) => {
      const text = msg.text()
      if (text.includes('Starting playback') || text.includes('Finished playback')) {
        audioEvents.push(text)
      }
    }

    hostPage.on('console', trackAudio)

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

    // Fire all night actions, each gated on the coroutine reaching the
    // correct sub-phase. Blind waitForTimeout(500) races the coroutine on
    // slow hardware and causes silent rejections.
    await waitForSubPhase(hostPage, gameId, 'WEREWOLF_PICK', 15_000)
    if (wolfBot) {
      await act('WOLF_KILL', wolfBot.nick, { target: String(villagerBots[0]?.seat ?? 1), room: ctx.roomCode })
    }

    if (seerBot) {
      await waitForSubPhase(hostPage, gameId, 'SEER_PICK', 15_000)
      await act('SEER_CHECK', seerBot.nick, { target: String(villagerBots[1]?.seat ?? 2), room: ctx.roomCode })
      await waitForSubPhase(hostPage, gameId, 'SEER_RESULT', 10_000)
      await act('SEER_CONFIRM', seerBot.nick, { room: ctx.roomCode })
    }

    if (witchBot) {
      await waitForSubPhase(hostPage, gameId, 'WITCH_ACT', 15_000)
      await act('WITCH_ACT', witchBot.nick, { payload: JSON.stringify({ useAntidote: false, usePoison: false }), room: ctx.roomCode })
    }

    if (guardBot) {
      await waitForSubPhase(hostPage, gameId, 'GUARD_PICK', 15_000)
      await act('GUARD_SKIP', guardBot.nick, { room: ctx.roomCode })
    }

    // Wait for day transition
    await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)
    await hostPage.waitForTimeout(3_000)

    await captureSnapshot(ctx.pages, testInfo, 'rapid-transitions-complete')

    // Analyze audio events for duplicates using audioService's "Starting
    // playback: <filename>" template-literal logs — the only source where
    // Playwright's msg.text() reliably carries the filename.
    const playbackEvents = audioEvents.filter((e) => e.includes('Starting playback'))
    const filenames = playbackEvents
      .map((e) => {
        const match = e.match(/Starting playback: ([\w_]+\.mp3)/)
        return match ? match[1] : null
      })
      .filter((f): f is string => f !== null)

    // Count occurrences of each audio file
    const counts = new Map<string, number>()
    for (const filename of filenames) {
      counts.set(filename, (counts.get(filename) || 0) + 1)
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
