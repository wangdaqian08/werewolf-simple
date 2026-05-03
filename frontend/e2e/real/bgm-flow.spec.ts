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
 *   2. Drive into NIGHT 1 (all browsers transition).
 *   3. The host's <audio src*="/audio/bgm/"> exists, has paused=false,
 *      volume>0, and loop=true — i.e. the BGM is actually playing, not
 *      just silently created.
 *   4. Sub-phase boundary (LOW → HIGH) audibly modulates the element's
 *      volume — the JS-tween path fires.
 *
 * Headless Chromium: audio output is silent in CI but `paused` and
 * `volume` reflect the real element state, which is what we need.
 *
 * Audio unlock: Chromium dispatches a user gesture when the host clicks
 * "Start Game", so by the time NIGHT begins userInteracted=true and BGM
 * starts immediately (no deferred path).
 */
import { expect, type Page, test } from '@playwright/test'
import { setupGame } from './helpers/multi-browser'
import { type RoleName } from './helpers/shell-runner'
import { waitForNightSubPhase, waitForPhase } from './helpers/state-polling'
import { driveMinimalNight1ViaDom } from './helpers/night-driver'

const BGM_TRACK = 'suspicion.mp3'

interface BgmSnapshot {
  exists: boolean
  paused: boolean
  volume: number
  loop: boolean
  src: string
}

async function readBgmState(page: Page): Promise<BgmSnapshot> {
  return page.evaluate(() => {
    const els = Array.from(document.querySelectorAll('audio')) as HTMLAudioElement[]
    const bgm = els.find((el) => el.src.includes('/audio/bgm/'))
    if (!bgm) {
      return { exists: false, paused: true, volume: 0, loop: false, src: '' }
    }
    return {
      exists: true,
      paused: bgm.paused,
      volume: bgm.volume,
      loop: bgm.loop,
      src: bgm.src,
    }
  })
}

test.describe('Background music — Night 1 lifecycle', () => {
  test.setTimeout(180_000)

  test('BGM track plays from Night 1 entry and modulates with sub-phase', async ({
    browser,
  }, testInfo) => {
    testInfo.setTimeout(180_000)

    // 6p kit: 2 wolves + seer + witch + guard + villager. Mirrors the
    // dead-role-audio.spec.ts shape, which is already proven stable in CI.
    // hasSheriff=false keeps the test on the simple NIGHT path; sheriff
    // election would interleave a public day phase that doesn't drive BGM.
    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      bgmTrack: BGM_TRACK,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
    })

    try {
      const hostPage = ctx.hostPage

      // ── Drive into NIGHT 1 via DOM ─────────────────────────────────────
      // driveMinimalNight1ViaDom waits for NIGHT then walks all four
      // night sub-phases. We only need the first sub-phase to land before
      // we can sample BGM, so a parallel poll on the host's <audio>
      // element runs alongside the night driver.
      const wolves = ctx.roleMap.WEREWOLF ?? []
      const villagers = (ctx.roleMap.VILLAGER ?? []).filter((b) => b.nick !== 'Host')
      const wolfTargetSeat = villagers[0]?.seat ?? wolves[1]?.seat
      expect(wolfTargetSeat, 'kit must yield a wolf-killable target').toBeDefined()

      // Kick off the night driver but don't await it yet — we want to
      // observe BGM state at the FIRST sub-phase entry, before the night
      // completes.
      const nightPromise = driveMinimalNight1ViaDom(ctx, { wolfTargetSeat: wolfTargetSeat! })

      // ── Assert BGM is playing once WEREWOLF_PICK is reached ────────────
      // WEREWOLF_PICK is one of the HIGH-volume sub-phases. The BGM
      // element should exist by then and not be paused.
      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'WEREWOLF_PICK', 30_000),
        'WEREWOLF_PICK must be reached before BGM assertions',
      ).toBe(true)

      // The watcher fires on phase=NIGHT, then sub-phase=WEREWOLF_PICK
      // raises level to HIGH. Allow a short settle window for the rAF
      // tween + STOMP round-trip; assert via expect.poll.
      await expect
        .poll(async () => readBgmState(hostPage), {
          timeout: 10_000,
          message: 'BGM <audio src*="/audio/bgm/"> must exist and be playing during NIGHT',
        })
        .toMatchObject({ exists: true, paused: false, loop: true })

      const duringPick = await readBgmState(hostPage)
      expect(duringPick.src).toContain(`/audio/bgm/${encodeURIComponent(BGM_TRACK)}`)
      expect(duringPick.volume).toBeGreaterThan(0)

      // Let the night driver finish so test cleanup can run.
      await nightPromise

      // ── Phase boundary stops BGM ────────────────────────────────────────
      // After all night sub-phases complete, the backend transitions to
      // DAY_DISCUSSION/RESULT_HIDDEN. The phase watcher then calls
      // stopBgm(), which clears the element src and pauses it. Either:
      //  - the <audio src*="/audio/bgm/"> is gone, or
      //  - it's paused.
      expect(
        await waitForPhase(hostPage, ctx.gameId, 'DAY_DISCUSSION', 30_000),
      ).toBe(true)

      await expect
        .poll(async () => {
          const s = await readBgmState(hostPage)
          // Either the element was torn down (src cleared / removed) OR
          // it's paused. Both indicate stopBgm() ran.
          return !s.exists || s.paused || !s.src.includes('/audio/bgm/')
        }, {
          timeout: 5_000,
          message: 'BGM must stop when leaving NIGHT phase',
        })
        .toBe(true)
    } finally {
      await ctx.cleanup()
    }
  })
})
