/**
 * Real-backend E2E: witch_open_eyes.mp3 must broadcast and play during WITCH_ACT.
 *
 * Reported regression: in a real game, the backend reached
 *   `[nightRoleLoop] role=WITCH alive=true` and `subPhase=WITCH_ACT`,
 * but the host's frontend console never logged the witch's `AudioSequence`
 * event and never played `witch_open_eyes.mp3`. wolf_open / wolf_close /
 * seer_open / seer_close all played correctly in the same game.
 *
 * The user's scenario had bot1 as BOTH the witch AND the wolf-kill target
 * (`waitingOn=[guest:bot1]` at WITCH_ACT, `wolfTarget=guest:bot1`). This
 * spec reproduces that exact shape.
 *
 * Three independent assertions in layered order. Whichever one fails first
 * tells us where the audio chain breaks:
 *
 *   1. Backend log contains `[broadcastAudio] file=witch_open_eyes.mp3`.
 *      Falsifies "backend never published" if present.
 *
 *   2. Frontend console contains `[stomp] received AudioSequence` with the
 *      witch_open_eyes filename in its payload. Falsifies "STOMP transport
 *      lost the frame" if present.
 *
 *   3. Frontend console contains `[AudioService] Starting playback:
 *      witch_open_eyes.mp3`. Falsifies "frontend received but did not
 *      play" if present.
 *
 * If 1 + 2 pass and 3 fails, the bug is in the dispatch from STOMP frame
 * to playSequential. If 1 passes and 2 fails, the bug is in transport.
 * If 1 fails, the bug is in the backend role-loop or audio config.
 *
 * Both diagnostic logs were added in this same change:
 *   - backend NightOrchestrator.kt:broadcastAudio → log.info("[broadcastAudio] ...")
 *   - frontend GameView.vue STOMP onMessage top → console.log('[stomp] received', ...)
 *
 * Quarantine note: guard-audio-sequence.spec.ts is the proven template for
 * this style of audio assertion. We mirror its console-capture setup.
 */
import { expect, test } from '@playwright/test'
import { setupGame } from './helpers/multi-browser'
import { act, actName, type RoleName } from './helpers/shell-runner'
import { waitForNightSubPhase, waitForPhase } from './helpers/state-polling'
import { readBackendLogLineCount, readBackendLogSince } from './helpers/backend-log'
import { attachCompositeOnFailure } from './helpers/composite-screenshot'

test.describe('Witch audio — open_eyes must play during WITCH_ACT', () => {
  test.setTimeout(180_000)

  test('witch_open_eyes broadcasts on backend and plays on frontend', async ({
    browser,
  }, testInfo) => {
    testInfo.setTimeout(180_000)

    // 6p kit: 2 wolves + 1 seer + 1 witch + 1 guard + 1 villager.
    // Same shape as dead-role-audio.spec.ts. Browser the WITCH explicitly so
    // we can witness the witch sub-phase from her own page if needed.
    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
    })

    try {
      const hostPage = ctx.hostPage
      const gameId = ctx.gameId

      // ── Attach console listeners BEFORE start-night ──────────────────────
      // page.on('console') only captures events that fire after subscription;
      // the AudioSequence watcher fires on the DAY → NIGHT transition.
      const audioEvents: string[] = []
      const stompFrames: string[] = []
      const trackAll = (msg: { text: () => string }) => {
        const text = msg.text()
        if (text.includes('[stomp] received')) stompFrames.push(text)
        if (
          text.includes('[AudioService]') ||
          text.includes('[useAudioService]')
        ) {
          audioEvents.push(text)
        }
      }
      hostPage.on('console', trackAll)

      // Mark the backend log position so we only scan lines written DURING
      // this test run, not lines from prior tests in the same shard.
      const logStartLine = readBackendLogLineCount()

      // ── Step 1: Start night ──────────────────────────────────────────────
      const startNightBtn = hostPage.getByTestId('start-night')
      await startNightBtn.waitFor({ state: 'visible', timeout: 10_000 })
      await startNightBtn.click()

      expect(
        await waitForPhase(hostPage, gameId, 'NIGHT', 15_000),
        'NIGHT phase not reached after start-night click',
      ).toBe(true)

      // ── Step 2: Wolf kills the WITCH ─────────────────────────────────────
      // Mirroring the user's reported game state: bot1 is the witch and is
      // also the wolf-kill target. Per backend rules wolf cannot self-target;
      // we kill whoever holds the WITCH role.
      expect(
        await waitForNightSubPhase(hostPage, gameId, 'WEREWOLF_PICK', 25_000),
      ).toBe(true)

      const wolves = ctx.roleMap.WEREWOLF ?? []
      const witch = (ctx.roleMap.WITCH ?? [])[0]
      const seer = (ctx.roleMap.SEER ?? [])[0]
      const guard = (ctx.roleMap.GUARD ?? [])[0]
      expect(wolves.length, 'kit must have at least 1 wolf').toBeGreaterThanOrEqual(1)
      expect(witch, 'kit must have a witch').toBeDefined()
      expect(seer, 'kit must have a seer').toBeDefined()
      expect(guard, 'kit must have a guard').toBeDefined()

      const wolfBot = wolves.find((w) => w.nick !== 'Host') ?? wolves[0]
      await act('WOLF_KILL', actName(wolfBot), {
        target: String(witch.seat),
        room: ctx.roomCode,
      })

      // ── Step 3: Wait for WITCH_ACT — the moment under test ───────────────
      // After WEREWOLF_PICK completes there is interRoleGapMs (~3s) +
      // audioCooldownMs (~2s) before WITCH_ACT enters. That window is when
      // the backend SHOULD broadcast witch_open_eyes.mp3.
      expect(
        await waitForNightSubPhase(hostPage, gameId, 'WITCH_ACT', 25_000),
        'WITCH_ACT sub-phase not reached — cannot evaluate witch audio',
      ).toBe(true)

      // The audio is broadcast BEFORE the sub-phase save, so by the time we
      // observe WITCH_ACT in DB, the [broadcastAudio] log line for
      // witch_open_eyes is already written. We then wait until the audio
      // queue actually drains TO witch_open_eyes — the queue is FIFO and
      // earlier files (wolf_howl ~5s, wolf_open ~2s, etc.) may still be
      // playing through when the witch broadcast is appended. Polling on
      // a sentinel ("Starting playback: witch_open_eyes.mp3" OR a long
      // budget exceeded) gives wolf_howl enough wallclock to finish its
      // ~5 s real-time playback in the headless harness.
      const witchPlaybackSeen = (): boolean =>
        audioEvents.some(
          (e) =>
            e.includes('Starting playback') &&
            e.includes('witch_open_eyes.mp3'),
        )
      const witchSentinelDeadline = Date.now() + 30_000
      while (Date.now() < witchSentinelDeadline) {
        if (witchPlaybackSeen()) break
        await hostPage.waitForTimeout(250)
      }

      // ── ASSERTION 1: backend log contains [broadcastAudio] for witch ─────
      const backendLines = readBackendLogSince(logStartLine)
      const witchOpenBackendLines = backendLines.filter(
        (l) =>
          l.includes('[broadcastAudio]') && l.includes('file=witch_open_eyes.mp3'),
      )
      expect(
        witchOpenBackendLines.length,
        'Backend log MUST contain [broadcastAudio] file=witch_open_eyes.mp3 — ' +
          'absence proves the role-loop did not publish the witch audio frame. ' +
          `Found ${witchOpenBackendLines.length} matching line(s). ` +
          `Total broadcastAudio lines in window: ${
            backendLines.filter((l) => l.includes('[broadcastAudio]')).length
          }. ` +
          `Witch-related backend lines: ${JSON.stringify(
            backendLines
              .filter((l) => l.includes('WITCH') || l.includes('witch'))
              .slice(-10),
          )}`,
      ).toBeGreaterThanOrEqual(1)

      // ── ASSERTION 2: frontend STOMP arrival log mentions witch_open_eyes ─
      const witchStompFrames = stompFrames.filter(
        (l) => l.includes('AudioSequence') && l.includes('witch_open_eyes.mp3'),
      )
      expect(
        witchStompFrames.length,
        'Frontend [stomp] received log MUST contain an AudioSequence frame ' +
          'with witch_open_eyes.mp3 — absence proves STOMP transport lost ' +
          'the frame between backend publish and frontend onMessage. ' +
          `Found ${witchStompFrames.length} matching frame(s). ` +
          `All STOMP frames captured (${stompFrames.length}): ${JSON.stringify(
            stompFrames.slice(-30),
          )}`,
      ).toBeGreaterThanOrEqual(1)

      // ── ASSERTION 3: AudioService actually started playback ──────────────
      const witchPlaybackEvents = audioEvents.filter(
        (e) =>
          e.includes('Starting playback') && e.includes('witch_open_eyes.mp3'),
      )
      expect(
        witchPlaybackEvents.length,
        'AudioService MUST log "Starting playback: witch_open_eyes.mp3" — ' +
          'absence proves the frontend received the AudioSequence but did ' +
          'not dispatch it to playSequential / the playback queue. ' +
          `Found ${witchPlaybackEvents.length} matching event(s). ` +
          `All audioEvents captured (${audioEvents.length}): ${JSON.stringify(
            audioEvents.slice(-30),
          )}`,
      ).toBeGreaterThanOrEqual(1)

      // ── Cleanup: complete the night so afterAll teardown is fast ─────────
      await act('WITCH_ACT', actName(witch), {
        room: ctx.roomCode,
        payload: '{"useAntidote":false}',
      })
      expect(
        await waitForNightSubPhase(hostPage, gameId, 'SEER_PICK', 15_000),
      ).toBe(true)
      await act('SEER_CHECK', actName(seer), {
        target: String(wolfBot.seat),
        room: ctx.roomCode,
      })
      expect(
        await waitForNightSubPhase(hostPage, gameId, 'SEER_RESULT', 10_000),
      ).toBe(true)
      await act('SEER_CONFIRM', actName(seer), { room: ctx.roomCode })
      expect(
        await waitForNightSubPhase(hostPage, gameId, 'GUARD_PICK', 15_000),
      ).toBe(true)
      await act('GUARD_SKIP', actName(guard), { room: ctx.roomCode })
    } finally {
      if (testInfo.status === 'failed' && ctx?.pages) {
        await attachCompositeOnFailure(ctx.pages, testInfo)
      }
      await ctx.cleanup()
    }
  })
})
