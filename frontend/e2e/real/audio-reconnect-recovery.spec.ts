/**
 * Real-backend E2E: a STOMP reconnect during NIGHT recovers AudioSequence
 * frames missed during the disconnect window.
 *
 * Bug being locked in
 * -------------------
 * Spring's SimpleBroker is fire-and-forget — it does NOT buffer messages
 * for clients that are momentarily offline. STOMP-JS auto-reconnects
 * after `reconnectDelay: 3000` ms, but any AudioSequence broadcast during
 * the disconnect window is permanently lost on the wire. Before the
 * reconnect-recovery fix, GameView.refreshState() (the onConnect handler)
 * fetched /api/game/{id}/state which carried no `audioSequence` field, so
 * missed cues never reached the client. Reproduced after three independent
 * prod sightings (game 19 witch_open, game 20 seer_open, plus a
 * re-occurrence reported 2026-05-03).
 *
 * Fix (under test)
 * ----------------
 * StompPublisher.broadcastGame intercepts every AudioSequence event and
 * appends to a small per-game ring buffer (AudioReplayCache).
 * GameService.getGameState surfaces both the latest cue (`audioSequence`)
 * and the full ring (`audioReplayBuffer`) so a STOMP-reconnect's
 * refreshState() can replay every missed cue, not just the last one.
 * Frontend dedup (useAudioService.ts playedIds Set) keeps still-online
 * clients from double-firing.
 *
 * Test shape
 * ----------
 * 1. Drive a 6p game to NIGHT 1 / WEREWOLF_PICK.
 * 2. Wolf bot kills (via shell `act`) so wolf_close_eyes lands on the
 *    host over STOMP — proves transport is working.
 * 3. Toggle the host page's network to offline for ~5 s. Under the e2e
 *    timing profile (cooldown=200, gap=500), the role loop schedules
 *    witch_open / witch_close / seer_open well within that window, so
 *    several cues land while the WebSocket is closed. STOMP-JS reconnects
 *    after `reconnectDelay: 3000` ms.
 * 4. Restore the network. The next refreshState() (fired by GameView's
 *    onConnect handler and by every NightSubPhaseChanged event) carries
 *    `audioReplayBuffer` containing every cached frame.
 * 5. The frontend's useAudioService.audioReplayBuffer watcher iterates
 *    each frame and fires audioService.playSequential for any id not yet
 *    in playedIds. Live STOMP frames for the same ids are deduped.
 *
 * Assertion
 * ---------
 * After NIGHT completes, every role-narration file MUST appear in the
 * `[useAudioService] AudioSequence (live|replay)` log. Whether a given
 * file arrived live (STOMP) or was recovered (replay) does not matter —
 * the user-visible contract is that they heard it. The test also asserts
 * that AT LEAST one file was recovered via the replay path (proving the
 * disconnect window actually exercised the recovery, not that the test
 * accidentally ran on the happy network path).
 *
 * Why this lives in its own spec
 * ------------------------------
 * bgm-flow.spec.ts asserts BGM doesn't suppress role narration under the
 * happy network path; introducing an offline-toggle there would entangle
 * two distinct guarantees. This file focuses solely on the reconnect-
 * recovery contract so a regression in either is easy to triage.
 */
import { expect, test } from '@playwright/test'
import { setupGame } from './helpers/multi-browser'
import { act, actName, type RoleName } from './helpers/shell-runner'
import { waitForNightSubPhase, waitForPhase } from './helpers/state-polling'

test.describe('Audio reconnect recovery', () => {
  test.setTimeout(180_000)

  test('STOMP reconnect during night replays missed AudioSequence via getGameState', async ({
    browser,
  }, testInfo) => {
    testInfo.setTimeout(180_000)

    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
    })

    // Capture console output BEFORE try-block so the cleanup-side `finally`
    // can reference the arrays for diagnostic dumps without TDZ issues.
    const stompFrames: string[] = []
    const liveAudioEvents: string[] = []
    const replayAudioEvents: string[] = []
    const stompLifecycle: string[] = []
    ctx.hostPage.on('console', (msg) => {
      const t = msg.text()
      if (t.includes('[stomp] received')) stompFrames.push(`${Date.now()} ${t}`)
      if (t.includes('[useAudioService] AudioSequence (live)'))
        liveAudioEvents.push(`${Date.now()} ${t}`)
      if (t.includes('[useAudioService] AudioSequence (replay)'))
        replayAudioEvents.push(`${Date.now()} ${t}`)
      if (t.includes('[stompClient]')) stompLifecycle.push(`${Date.now()} ${t}`)
    })

    try {
      const hostPage = ctx.hostPage
      const gameId = ctx.gameId

      const wolves = ctx.roleMap.WEREWOLF ?? []
      const seer = (ctx.roleMap.SEER ?? [])[0]
      const witch = (ctx.roleMap.WITCH ?? [])[0]
      const guard = (ctx.roleMap.GUARD ?? [])[0]
      const nonHostVillagers = (ctx.roleMap.VILLAGER ?? []).filter((b) => b.nick !== 'Host')

      expect(
        wolves.length >= 1 && seer && witch && guard,
        `kit must yield wolf + seer + witch + guard. ` +
          `Got wolves=${wolves.length}, seer=${!!seer}, witch=${!!witch}, guard=${!!guard}.`,
      ).toBeTruthy()

      const wolfBot = wolves.find((w) => w.nick !== 'Host') ?? wolves[0]
      const wolfTarget =
        nonHostVillagers[0] ??
        (witch.nick !== wolfBot.nick ? witch : null) ??
        (guard.nick !== wolfBot.nick ? guard : null) ??
        (seer.nick !== wolfBot.nick ? seer : null)
      expect(wolfTarget, 'kit must yield a non-wolf wolf-kill target').toBeTruthy()

      // ── N1: start night and reach WEREWOLF_PICK ────────────────────────
      const startNightBtn = hostPage.getByTestId('start-night')
      await startNightBtn.waitFor({ state: 'visible', timeout: 15_000 })
      await startNightBtn.click()
      expect(await waitForPhase(hostPage, gameId, 'NIGHT', 15_000)).toBe(true)
      expect(await waitForNightSubPhase(hostPage, gameId, 'WEREWOLF_PICK', 25_000)).toBe(true)

      // ── Wolf acts → wolf_close_eyes lands on the host over STOMP ───────
      await act('WOLF_KILL', actName(wolfBot), {
        target: String(wolfTarget!.seat),
        room: ctx.roomCode,
      })

      // Settle on wolf_close_eyes arriving live so we know the next role
      // transition is imminent. The backend's role-loop schedules WITCH_ACT
      // ~(audio-cooldown-ms + inter-role-gap-ms) after wolf_close, so the
      // 5 s offline window straddles that broadcast cleanly under e2e
      // timing (cooldown=200, gap=500).
      await expect
        .poll(
          () =>
            stompFrames.some(
              (f) => f.includes('AudioSequence') && f.includes('wolf_close_eyes.mp3'),
            ),
          { timeout: 30_000, message: 'wolf_close_eyes never reached the host via STOMP' },
        )
        .toBe(true)

      // ── Force host offline through the witch_open_eyes broadcast ──────
      // 5 s is comfortably longer than (audio-cooldown-ms + inter-role-gap-ms)
      // under any test profile, so witch_open is guaranteed to land while
      // the WebSocket is closed. STOMP-JS reconnects on `setOffline(false)`
      // (`reconnectDelay: 3000` ms means the next attempt fires within ~3 s).
      await ctx.hostPage.context().setOffline(true)
      await ctx.hostPage.waitForTimeout(5_000)
      await ctx.hostPage.context().setOffline(false)

      // ── Drive the rest of N1 through DAY_DISCUSSION ────────────────────
      // WITCH_ACT reaches via state polling (the host has come back online
      // and refreshState has fired by the time waitForNightSubPhase resolves).
      // 60 s timeout handles the worst-case reconnect cascade.
      expect(await waitForNightSubPhase(hostPage, gameId, 'WITCH_ACT', 60_000)).toBe(true)
      await act('WITCH_ACT', actName(witch), {
        room: ctx.roomCode,
        payload: '{"useAntidote":false}',
      })

      expect(await waitForNightSubPhase(hostPage, gameId, 'SEER_PICK', 30_000)).toBe(true)
      await act('SEER_CHECK', actName(seer), {
        target: String(wolfBot.seat),
        room: ctx.roomCode,
      })
      expect(await waitForNightSubPhase(hostPage, gameId, 'SEER_RESULT', 10_000)).toBe(true)
      await act('SEER_CONFIRM', actName(seer), { room: ctx.roomCode })

      expect(await waitForNightSubPhase(hostPage, gameId, 'GUARD_PICK', 30_000)).toBe(true)
      await act('GUARD_SKIP', actName(guard), { room: ctx.roomCode })

      expect(await waitForPhase(hostPage, gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)

      // Brief settle so any post-reconnect refreshState completes flushing
      // its useAudioService log line before assertions read the buffer.
      await hostPage.waitForTimeout(2_000)

      // Extract .mp3 file tokens from each event log. Playwright's
      // msg.text() serialises object literals with "Array(N)" placeholders
      // for nested arrays, but the AudioSequence id always ends with the
      // first audio filename (e.g. `1-1777887511799-seer_open_eyes.mp3`),
      // so scanning for `[a-z_]+\.mp3` cleanly extracts the filename
      // without sweeping in the leading `<gameId>-<timestamp>-` prefix
      // (those contain digits, the regex excludes digits to skip them).
      const extractFiles = (lines: string[]): Set<string> => {
        const out = new Set<string>()
        for (const line of lines) {
          for (const m of line.matchAll(/[a-z_]+\.mp3/g)) out.add(m[0]!)
        }
        return out
      }
      const liveFiles = extractFiles(liveAudioEvents)
      const replayFiles = extractFiles(replayAudioEvents)
      const heardByComposable = new Set<string>([...liveFiles, ...replayFiles])

      const stompFiles = new Set<string>()
      for (const frame of stompFrames) {
        const m = frame.match(/AudioSequence \[([^\]]+)\]/)
        if (!m) continue
        for (const raw of m[1]!.split(',')) {
          const f = raw.trim()
          if (f.endsWith('.mp3')) stompFiles.add(f)
        }
      }

      console.log(
        '[DIAG audio-reconnect-recovery]',
        'live events:',
        liveAudioEvents.length,
        [...liveFiles].sort(),
        'replay events:',
        replayAudioEvents.length,
        [...replayFiles].sort(),
        'STOMP frames:',
        stompFrames.length,
        [...stompFiles].sort(),
        'lifecycle:',
        stompLifecycle,
      )
      await import('node:fs').then(({ writeFileSync }) => {
        writeFileSync(
          '/tmp/audio-reconnect-recovery-dump.txt',
          [
            '== stompFrames ==',
            ...stompFrames,
            '',
            '== stompLifecycle ==',
            ...stompLifecycle,
            '',
            '== liveAudioEvents ==',
            ...liveAudioEvents,
            '',
            '== replayAudioEvents ==',
            ...replayAudioEvents,
          ].join('\n'),
        )
      })

      // ── ASSERTION 1: every role narration file was heard ───────────────
      // The user-visible contract: under a transient network blip, the
      // host MUST still hear every role's open/close cue. liveFiles ∪
      // replayFiles is the union of "arrived via STOMP" and "recovered via
      // getGameState's audioReplayBuffer". Both paths feed the same audio
      // queue through useAudioService.tryPlay.
      const N1_ROLE_FILES = [
        'wolf_open_eyes.mp3',
        'wolf_close_eyes.mp3',
        'witch_open_eyes.mp3',
        'witch_close_eyes.mp3',
        'seer_open_eyes.mp3',
        'seer_close_eyes.mp3',
        'guard_open_eyes.mp3',
        'guard_close_eyes.mp3',
      ]
      const missing = N1_ROLE_FILES.filter((f) => !heardByComposable.has(f))
      expect(
        missing,
        `Every role narration file MUST be heard by useAudioService — via ` +
          `live STOMP or via the audioReplayBuffer recovery. Missing: ` +
          `${JSON.stringify(missing)}\nLive: ${JSON.stringify([...liveFiles].sort())}` +
          `\nReplay: ${JSON.stringify([...replayFiles].sort())}` +
          `\nSTOMP: ${JSON.stringify([...stompFiles].sort())}`,
      ).toEqual([])

      // ── ASSERTION 2: at least one cue was recovered via replay ─────────
      // This proves the test actually exercised the recovery path and is
      // not just a happy-path run. If 0 replay events fire, the offline
      // window did not interrupt STOMP (e.g. timing drift, or the network
      // toggle didn't sever the WebSocket). The test then is not a
      // regression guard for the bug it claims to lock in — fail loudly.
      expect(
        replayFiles.size,
        `Recovery path was not exercised — 0 audioReplayBuffer cues fired ` +
          `during the offline window. The test no longer reproduces the ` +
          `STOMP-reconnect failure. Live events: ${liveAudioEvents.length}, ` +
          `replay events: ${replayAudioEvents.length}, ` +
          `lifecycle: ${JSON.stringify(stompLifecycle)}`,
      ).toBeGreaterThan(0)
    } finally {
      await ctx.cleanup()
    }
  })
})
