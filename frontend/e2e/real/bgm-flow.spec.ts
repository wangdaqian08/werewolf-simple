/**
 * Real-backend E2E: background music plays from Night 1.
 *
 * Regression target: a previous Web Audio routing of the BGM through
 * createMediaElementSource → GainNode kept the BGM silent on the FIRST
 * NIGHT because AudioContext.resume() is async and was not awaited before
 * play(). The fix replaced that with plain HTMLAudioElement.volume + a JS
 * rAF tween. This spec asserts the contract end-to-end:
 *
 *   1. Pick a BGM track on the CreateRoom page.
 *   2. Drive into NIGHT 1.
 *   3. The host's <audio src*="/audio/bgm/"> exists, has paused=false,
 *      volume>0, and loop=true — the BGM is actually playing.
 *   4. After all night sub-phases complete, BGM stops at NIGHT→DAY.
 *
 * Headless Chromium: audio output is silent in CI but `paused` and
 * `volume` reflect the real element state, which is what we need.
 *
 * Audio unlock: Chromium dispatches a user gesture when the host clicks
 * "Start Game", so by the time NIGHT begins userInteracted=true and BGM
 * starts immediately (no deferred path).
 *
 * Sequencing: this spec drives the night via shell `act()` calls (matching
 * dead-role-audio + witch-audio patterns proven stable in CI). An earlier
 * version fired `driveMinimalNight1ViaDom` as a parallel promise alongside
 * the test body's WEREWOLF_PICK wait — the two concurrent
 * `hostPage.evaluate` polling chains race over the page's evaluation queue
 * and the host page's GET /state can stick on NIGHT/WAITING even after
 * the backend transitions to WEREWOLF_PICK. The PR #95 CI shard 1/3
 * failure (run 25284910822) reproduced this consistently. Switching to
 * sequential shell-driven actions matches what the other audio specs do
 * and avoids the race entirely.
 */
import { expect, type Page, test } from '@playwright/test'
import { setupGame } from './helpers/multi-browser'
import { act, actName, type RoleName } from './helpers/shell-runner'
import { waitForNightSubPhase, waitForPhase } from './helpers/state-polling'

const BGM_TRACK = 'suspicion.mp3'

interface BgmSnapshot {
  exists: boolean
  paused: boolean
  volume: number
  loop: boolean
  src: string
  filename: string | null
  userInteracted: boolean
  pendingStart: boolean
}

/**
 * Read the live BGM state from the singleton audioService exposed on
 * window.__audioService.
 *
 * `new Audio(url)` creates an in-memory HTMLMediaElement that is NOT a
 * child of `document`, so `document.querySelectorAll('audio')` returns
 * nothing for the BGM element. The singleton hook bypasses DOM lookup
 * and returns the actual element's state.
 */
async function readBgmState(page: Page): Promise<BgmSnapshot> {
  return page.evaluate(() => {
    const svc = (window as unknown as { __audioService?: { getBgmState: () => BgmSnapshot } })
      .__audioService
    if (!svc) {
      return {
        exists: false,
        paused: true,
        volume: 0,
        loop: false,
        src: '',
        filename: null,
        userInteracted: false,
        pendingStart: false,
      }
    }
    return svc.getBgmState()
  })
}

test.describe('Background music — Night 1 lifecycle', () => {
  test.setTimeout(180_000)

  test('BGM track plays from Night 1 entry and modulates with sub-phase', async ({
    browser,
  }, testInfo) => {
    testInfo.setTimeout(180_000)

    // 6p kit mirrors dead-role-audio.spec.ts (CI-stable shape).
    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      bgmTrack: BGM_TRACK,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
    })

    try {
      const hostPage = ctx.hostPage
      const gameId = ctx.gameId

      // ── Resolve actor bots ─────────────────────────────────────────────
      // 6p kit = 2 wolves + 1 seer + 1 witch + 1 guard + 1 villager. Host
      // takes one slot at random, so non-host villagers can be 0 (if host
      // rolls VILLAGER). The wolf can target ANY non-wolf player — pick the
      // first villager bot, falling back to seer/witch/guard if the host
      // happens to be the only villager.
      const wolves = ctx.roleMap.WEREWOLF ?? []
      const seer = (ctx.roleMap.SEER ?? [])[0]
      const witch = (ctx.roleMap.WITCH ?? [])[0]
      const guard = (ctx.roleMap.GUARD ?? [])[0]
      const nonHostVillagers = (ctx.roleMap.VILLAGER ?? []).filter(
        (b) => b.nick !== 'Host',
      )

      expect(
        wolves.length >= 1 && seer && witch && guard,
        `kit must yield wolf + seer + witch + guard. ` +
          `Got wolves=${wolves.length}, seer=${!!seer}, witch=${!!witch}, guard=${!!guard}.`,
      ).toBeTruthy()

      // Wolf actor: prefer a non-host wolf (we'll send the act via shell;
      // host is also drivable but fewer code paths exercised).
      const wolfBot = wolves.find((w) => w.nick !== 'Host') ?? wolves[0]
      // Wolf kill target: any non-wolf, non-self player. Order of preference:
      // non-host villager → witch → guard → seer. We avoid targeting wolfBot
      // (self) and target the wolf actor wouldn't be suicide on day 1 even
      // if the helpers allowed it.
      const wolfTarget =
        nonHostVillagers[0] ??
        (witch.nick !== wolfBot.nick ? witch : null) ??
        (guard.nick !== wolfBot.nick ? guard : null) ??
        (seer.nick !== wolfBot.nick ? seer : null)
      expect(
        wolfTarget,
        'kit must yield a non-wolf wolf-kill target',
      ).toBeTruthy()

      // ── N1: start night ────────────────────────────────────────────────
      const startNightBtn = hostPage.getByTestId('start-night')
      await startNightBtn.waitFor({ state: 'visible', timeout: 15_000 })
      await startNightBtn.click()
      expect(
        await waitForPhase(hostPage, gameId, 'NIGHT', 15_000),
        'NIGHT phase not reached after start-night click',
      ).toBe(true)

      // ── WEREWOLF_PICK: BGM should be at HIGH volume ────────────────────
      expect(
        await waitForNightSubPhase(hostPage, gameId, 'WEREWOLF_PICK', 25_000),
        'WEREWOLF_PICK sub-phase not reached',
      ).toBe(true)

      // The phase watcher fires on phase=NIGHT (start BGM), then the
      // sub-phase watcher raises level to HIGH on WEREWOLF_PICK. Allow a
      // short settle window for the rAF tween + STOMP round-trip.
      await expect
        .poll(async () => readBgmState(hostPage), {
          timeout: 10_000,
          message: 'BGM must be playing during NIGHT (audioService.bgmAudioEl set, not paused)',
        })
        .toMatchObject({ exists: true, paused: false, loop: true })

      const duringPick = await readBgmState(hostPage)
      expect(duringPick.src).toContain(`/audio/bgm/${encodeURIComponent(BGM_TRACK)}`)
      expect(duringPick.filename).toBe(BGM_TRACK)
      expect(duringPick.volume).toBeGreaterThan(0)

      // ── Drive remaining sub-phases sequentially via shell `act()` ──────
      await act('WOLF_KILL', actName(wolfBot), {
        target: String(wolfTarget!.seat),
        room: ctx.roomCode,
      })

      expect(
        await waitForNightSubPhase(hostPage, gameId, 'SEER_PICK', 15_000),
        'SEER_PICK not reached',
      ).toBe(true)
      // Seer must not self-check; pick wolf as target.
      await act('SEER_CHECK', actName(seer), {
        target: String(wolfBot.seat),
        room: ctx.roomCode,
      })
      expect(
        await waitForNightSubPhase(hostPage, gameId, 'SEER_RESULT', 10_000),
      ).toBe(true)
      await act('SEER_CONFIRM', actName(seer), { room: ctx.roomCode })

      expect(
        await waitForNightSubPhase(hostPage, gameId, 'WITCH_ACT', 15_000),
      ).toBe(true)
      await act('WITCH_ACT', actName(witch), {
        room: ctx.roomCode,
        payload: '{"useAntidote":false}',
      })

      expect(
        await waitForNightSubPhase(hostPage, gameId, 'GUARD_PICK', 15_000),
      ).toBe(true)
      await act('GUARD_SKIP', actName(guard), { room: ctx.roomCode })

      // ── DAY_DISCUSSION: BGM stops ──────────────────────────────────────
      // After all sub-phases complete, the backend transitions to
      // DAY_DISCUSSION. The phase watcher then calls stopBgm(), which
      // pauses the element and clears its src. Either:
      //   - the <audio src*="/audio/bgm/"> is gone (src cleared), or
      //   - it's paused.
      expect(
        await waitForPhase(hostPage, gameId, 'DAY_DISCUSSION', 30_000),
        'DAY_DISCUSSION not reached after night',
      ).toBe(true)

      await expect
        .poll(
          async () => {
            const s = await readBgmState(hostPage)
            return !s.exists || s.paused || !s.src.includes('/audio/bgm/')
          },
          {
            timeout: 5_000,
            message: 'BGM must stop when leaving NIGHT phase',
          },
        )
        .toBe(true)
    } finally {
      await ctx.cleanup()
    }
  })
})
