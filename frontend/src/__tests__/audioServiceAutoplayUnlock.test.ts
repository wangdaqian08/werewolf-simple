import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Narration autoplay unlock — the mobile "first night has no sound" bug.
 *
 * Replicated (Firefox media.autoplay.blocking_policy=2 + Chromium
 * user-gesture-required): cues arrive via STOMP with no qualifying user
 * gesture; play() rejects with NotAllowedError (iOS: per fresh element,
 * regardless of page interaction history). The old code CONSUMED each
 * queue item on failure — an entire night of narration silently dropped,
 * and the first cue to arrive after a tap (e.g. wolf_close_eyes right
 * after the wolf picked a victim) suddenly played out loud.
 *
 * Contract under test:
 *  1. blocked narration is PARKED, not dropped (both the pre-interaction
 *     gate and NotAllowedError rejections)
 *  2. the next user gesture resumes the parked queue synchronously
 *  3. gestures prime (muted play/pause) the known cue pool + queued files
 *     so later un-gestured plays are element-blessed on iOS
 *  4. while parked, a newer sequence replaces the stale backlog
 *  5. stopAll/clearQueue cancel any pending resume
 */

type MockAudio = {
  play: ReturnType<typeof vi.fn>
  pause: ReturnType<typeof vi.fn>
  currentTime: number
  volume: number
  muted: boolean
  mutedAtPlayCalls: boolean[]
  loop: boolean
  preload: string
  src: string
  onended: (() => void) | null
}

let mockAudioInstances: MockAudio[] = []

function createMockAudio(src: string): MockAudio {
  const inst: MockAudio = {
    play: vi.fn(),
    pause: vi.fn(),
    currentTime: 0,
    volume: 1,
    muted: false,
    mutedAtPlayCalls: [],
    loop: false,
    preload: '',
    src,
    onended: null,
  }
  inst.play.mockImplementation(() => {
    inst.mutedAtPlayCalls.push(inst.muted)
    return Promise.resolve()
  })
  mockAudioInstances.push(inst)
  return inst
}

function instFor(filename: string): MockAudio | undefined {
  return mockAudioInstances.find((i) => decodeURIComponent(i.src).includes(filename))
}

const notAllowed = () => Object.assign(new Error('play() blocked'), { name: 'NotAllowedError' })

vi.stubGlobal('document', { ...globalThis.document, addEventListener: vi.fn() })
vi.stubGlobal(
  'Audio',
  vi.fn().mockImplementation(function (src: string) {
    return createMockAudio(src ?? '')
  }),
)
vi.stubGlobal(
  'AudioContext',
  vi.fn().mockImplementation(function () {
    return {}
  }),
)

let audioService: typeof import('@/services/audioService').audioService
let KNOWN_NARRATION_FILES: readonly string[]

/** The capture-phase document 'click' listener registered by the service (latest registration wins after resetModules). */
function fireGesture(): void {
  const calls = (document.addEventListener as unknown as ReturnType<typeof vi.fn>).mock.calls
  const clicks = calls.filter((c) => c[0] === 'click')
  const handler = clicks[clicks.length - 1]![1] as () => void
  handler()
}

const flush = () => new Promise<void>((r) => setTimeout(r, 0))

describe('audioService autoplay unlock', () => {
  beforeEach(async () => {
    localStorage.clear()
    mockAudioInstances = []
    // mockClear() resets calls but NOT implementations, so a persistent
    // mockImplementation from an earlier test (see "priming a rejected file
    // retries") would otherwise leak into every test after it. Restore the
    // default factory so each test is order-independent.
    ;(Audio as unknown as ReturnType<typeof vi.fn>).mockClear()
    ;(Audio as unknown as ReturnType<typeof vi.fn>).mockImplementation(function (src: string) {
      return createMockAudio(src ?? '')
    })
    vi.resetModules()
    const mod = await import('@/services/audioService')
    audioService = mod.audioService
    KNOWN_NARRATION_FILES = mod.KNOWN_NARRATION_FILES
  })

  // ── 1+2: pre-interaction parking ──────────────────────────────────────────

  it('parks narration queued before the first interaction and plays it on the first gesture', async () => {
    audioService.playSequential(['goes_dark_close_eyes.mp3', 'wolf_howl.mp3'])

    // Parked, not dropped: nothing played, queue intact.
    expect(instFor('goes_dark_close_eyes.mp3')?.play ?? { mock: { calls: [] } }).toSatisfy(
      (p: any) => (p.mock?.calls?.length ?? 0) === 0,
    )
    expect(audioService.isQueueActive()).toBe(true)

    fireGesture()
    await flush()

    const head = instFor('goes_dark_close_eyes.mp3')
    expect(head?.play).toHaveBeenCalledTimes(1)
    // The resumed head must be audible (not left muted by pool priming).
    expect(head?.mutedAtPlayCalls[0]).toBe(false)

    head?.onended?.()
    await flush()
    const tail = instFor('wolf_howl.mp3')
    // wolf_howl was pool-primed muted during the gesture, then really played
    // when its turn came — the real play must be unmuted.
    expect(tail?.play).toHaveBeenCalled()
    expect(tail?.mutedAtPlayCalls[tail.mutedAtPlayCalls.length - 1]).toBe(false)
  })

  // ── 1+2: NotAllowedError parking ─────────────────────────────────────────

  it('parks the sequence when the browser rejects play() with NotAllowedError and retries on the next gesture', async () => {
    audioService.toggleMute()
    audioService.toggleMute() // sets userInteracted=true, ends unmuted
    ;(Audio as unknown as ReturnType<typeof vi.fn>).mockImplementationOnce(function (src: string) {
      const inst = createMockAudio(src ?? '')
      inst.play
        .mockImplementationOnce(() => {
          inst.mutedAtPlayCalls.push(inst.muted)
          return Promise.reject(notAllowed())
        })
        .mockImplementation(() => {
          inst.mutedAtPlayCalls.push(inst.muted)
          return Promise.resolve()
        })
      return inst
    })

    audioService.playSequential(['blocked_head.mp3', 'tail_cue.mp3'])
    await flush()

    // Old behavior: rejection consumed the item and advanced to tail_cue.
    // New behavior: the whole sequence is parked awaiting a gesture.
    expect(audioService.isQueueActive()).toBe(true)
    expect(instFor('tail_cue.mp3')?.play ?? { mock: { calls: [] } }).toSatisfy(
      (p: any) => (p.mock?.calls?.length ?? 0) === 0,
    )

    fireGesture()
    await flush()
    const head = instFor('blocked_head.mp3')
    expect(head?.play).toHaveBeenCalledTimes(2) // rejected once, retried on gesture

    head?.onended?.()
    await flush()
    expect(instFor('tail_cue.mp3')?.play).toHaveBeenCalled()
  })

  it('still skips to the next file on non-autoplay play() errors', async () => {
    audioService.toggleMute()
    audioService.toggleMute()
    ;(Audio as unknown as ReturnType<typeof vi.fn>).mockImplementationOnce(function (src: string) {
      const inst = createMockAudio(src ?? '')
      inst.play.mockImplementation(() => Promise.reject(new Error('decode failure')))
      return inst
    })

    audioService.playSequential(['broken.mp3', 'ok.mp3'])
    await vi.waitFor(() => {
      expect(instFor('ok.mp3')?.play).toHaveBeenCalled()
    })
  })

  // ── 4: stale backlog replacement while parked ─────────────────────────────

  it('replaces a parked backlog with the newest sequence instead of stacking stale cues', async () => {
    audioService.playSequential(['stale_cue.mp3'])
    expect(audioService.isQueueActive()).toBe(true)

    audioService.playSequential(['fresh_a.mp3', 'fresh_b.mp3'])

    fireGesture()
    await flush()

    expect(instFor('stale_cue.mp3')?.play ?? { mock: { calls: [] } }).toSatisfy(
      (p: any) => (p.mock?.calls?.length ?? 0) === 0,
    )
    expect(instFor('fresh_a.mp3')?.play).toHaveBeenCalledTimes(1)

    instFor('fresh_a.mp3')?.onended?.()
    await flush()
    expect(instFor('fresh_b.mp3')?.play).toHaveBeenCalled()
  })

  // ── 3: gesture-time pool priming ─────────────────────────────────────────

  it('primes the known narration pool muted and leaves it muted so priming can never emit sound', async () => {
    fireGesture()

    expect(KNOWN_NARRATION_FILES.length).toBeGreaterThan(0)
    for (const file of KNOWN_NARRATION_FILES) {
      const el = instFor(file)
      expect(el, `${file} should be created by priming`).toBeTruthy()
      expect(el!.play).toHaveBeenCalledTimes(1)
      expect(el!.mutedAtPlayCalls[0], `${file} must be primed muted`).toBe(true)
    }

    await flush()
    for (const file of KNOWN_NARRATION_FILES) {
      const el = instFor(file)!
      expect(el.pause).toHaveBeenCalled()
      // Regression (real iPhone, game 93): restoring muted=false straight after
      // pause() let WebKit emit a fragment of every primed cue on each gesture —
      // players heard guard_close_eyes in a game that had no Guard. Primed
      // elements stay muted; playNextInQueue unmutes them for real playback.
      expect(el.muted, `${file} must stay muted after priming`).toBe(true)
      expect(el.currentTime).toBe(0)
    }
  })

  it('does not re-prime already primed files on later gestures', async () => {
    fireGesture()
    await flush()
    fireGesture()
    await flush()
    for (const file of KNOWN_NARRATION_FILES) {
      expect(instFor(file)!.play).toHaveBeenCalledTimes(1)
    }
  })

  it('priming a rejected file retries on a later gesture', async () => {
    const target = KNOWN_NARRATION_FILES[0]!
    ;(Audio as unknown as ReturnType<typeof vi.fn>).mockImplementation(function (src: string) {
      const inst = createMockAudio(src ?? '')
      if (decodeURIComponent(src ?? '').includes(target)) {
        inst.play
          .mockImplementationOnce(() => {
            inst.mutedAtPlayCalls.push(inst.muted)
            return Promise.reject(notAllowed())
          })
          .mockImplementation(() => {
            inst.mutedAtPlayCalls.push(inst.muted)
            return Promise.resolve()
          })
      }
      return inst
    })

    fireGesture()
    await flush()
    fireGesture()
    await flush()
    expect(instFor(target)!.play).toHaveBeenCalledTimes(2)
  })

  // ── 5: cancel pending resume ─────────────────────────────────────────────

  it('stopAll cancels a pending gesture resume', async () => {
    audioService.playSequential(['orphan.mp3'])
    expect(audioService.isQueueActive()).toBe(true)

    audioService.stopAll()
    fireGesture()
    await flush()

    expect(instFor('orphan.mp3')?.play ?? { mock: { calls: [] } }).toSatisfy(
      (p: any) => (p.mock?.calls?.length ?? 0) === 0,
    )
    expect(audioService.isQueueActive()).toBe(false)
  })

  // ── 6: the blocked signal that drives AudioUnlockBanner ───────────────────
  //
  // Parked narration and a deferred BGM start are both silent AND invisible.
  // On a real iPhone (game 94) a mid-game reload lost all 8 cues of night 1
  // plus BGM, because a parked backlog is replaced by the next sequence rather
  // than stacked — the cues were discarded, not merely delayed. The UI needs a
  // subscribable signal so it can tell the player a single tap fixes it.

  it('reports blocked while narration is parked and clears it on the resuming gesture', async () => {
    const seen: boolean[] = []
    const unsub = audioService.onAudioBlockedChange((b) => seen.push(b))

    expect(audioService.isAudioBlocked()).toBe(false)

    audioService.playSequential(['goes_dark_close_eyes.mp3'])
    expect(audioService.isAudioBlocked()).toBe(true)

    fireGesture()
    await flush()
    expect(audioService.isAudioBlocked()).toBe(false)

    expect(seen).toEqual([true, false])
    unsub()
  })

  it('reports blocked when a BGM start is deferred before the first gesture', () => {
    expect(audioService.isAudioBlocked()).toBe(false)
    audioService.startBgm('suspicion.mp3')
    expect(audioService.isAudioBlocked()).toBe(true)
  })

  it('is edge-triggered — repeated parks do not re-notify', () => {
    const seen: boolean[] = []
    const unsub = audioService.onAudioBlockedChange((b) => seen.push(b))

    audioService.playSequential(['a.mp3'])
    audioService.playSequential(['b.mp3'])
    audioService.playSequential(['c.mp3'])

    expect(seen).toEqual([true])
    unsub()
  })

  it('stops reporting blocked to an unsubscribed listener', () => {
    const seen: boolean[] = []
    const unsub = audioService.onAudioBlockedChange((b) => seen.push(b))
    unsub()

    audioService.playSequential(['a.mp3'])

    expect(seen).toEqual([])
    expect(audioService.isAudioBlocked()).toBe(true)
  })
})
