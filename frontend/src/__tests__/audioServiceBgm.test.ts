/**
 * Real-implementation tests for the BGM lifecycle in audioService.
 *
 * Distinct from useAudioService.test.ts (which mocks audioService wholesale)
 * and audioService.test.ts (which mocks Audio + AudioContext + document) —
 * those exercise the contract, this exercises the implementation.
 *
 * Regression target: the original BGM path routed the HTMLAudioElement
 * through Web Audio (createMediaElementSource → GainNode → destination).
 * AudioContext starts in 'suspended' state on Chrome and ctx.resume() is
 * async; the previous startBgm() called play() before the resume promise
 * settled, so the FIRST night's BGM was silent even though play() succeeded.
 *
 * The fix moved BGM to plain HTMLAudioElement.volume with a JS rAF tween.
 * These tests pin the contract by asserting:
 *   - the element's `volume` is set BEFORE play() (so it can never be silent)
 *   - no AudioContext / createMediaElementSource is constructed for BGM
 *   - duck/unduck and HIGH/LOW level changes mutate `el.volume` directly
 *
 * If anyone re-introduces the Web Audio routing, these tests fail.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

// ── Mocks ────────────────────────────────────────────────────────────────────

type MockAudio = {
  src: string
  play: ReturnType<typeof vi.fn>
  pause: ReturnType<typeof vi.fn>
  currentTime: number
  volume: number
  loop: boolean
  preload: string
  crossOrigin: string | null
  paused: boolean
  onended: (() => void) | null
  onerror: (() => void) | null
}

let mockAudioInstances: MockAudio[] = []
let documentListeners: Array<(e?: unknown) => void> = []

function createMockAudio(src = ''): MockAudio {
  const inst: MockAudio = {
    src,
    play: vi.fn().mockImplementation(function (this: MockAudio) {
      this.paused = false
      return Promise.resolve()
    }),
    pause: vi.fn().mockImplementation(function (this: MockAudio) {
      this.paused = true
    }),
    currentTime: 0,
    volume: 1,
    loop: false,
    preload: '',
    crossOrigin: null,
    paused: true,
    onended: null,
    onerror: null,
  }
  // bind play/pause to the instance so `this` is the mock. The original
  // play/pause are vi.fn mocks; rebind via closures so their handlers can
  // see the instance.
  const origPlay = inst.play
  const origPause = inst.pause
  inst.play = vi.fn(() => (origPlay as unknown as () => Promise<void>).call(inst))
  inst.pause = vi.fn(() => (origPause as unknown as () => void).call(inst))
  mockAudioInstances.push(inst)
  return inst
}

vi.stubGlobal('document', {
  ...globalThis.document,
  addEventListener: vi.fn((_evt: string, listener: (e?: unknown) => void) => {
    documentListeners.push(listener)
  }),
})

vi.stubGlobal(
  'Audio',
  vi.fn().mockImplementation(function (src?: string) {
    return createMockAudio(src ?? '')
  }),
)

const audioContextCtor = vi.fn().mockImplementation(function () {
  return {
    state: 'running',
    currentTime: 0,
    resume: vi.fn().mockResolvedValue(undefined),
    createGain: vi.fn(() => ({ gain: { value: 1 }, connect: vi.fn() })),
    createMediaElementSource: vi.fn(() => ({ connect: vi.fn() })),
    destination: {},
  }
})
vi.stubGlobal('AudioContext', audioContextCtor)

vi.stubGlobal(
  'requestAnimationFrame',
  vi.fn((cb: (t: number) => void) => {
    // Run synchronously at "end of tween" so volume settles immediately. The
    // tween is a self-iterating rAF; we want t === 1 on the first call so
    // applyBgmGain settles to target in one frame and does NOT recurse.
    //
    // Using Number.MAX_SAFE_INTEGER (not a 1e6 sentinel) makes the math
    // resilient when a test also mocks `performance.now()` to a large value
    // — e.g. the fix #3a watchdog test sets performance.now() to 1e6, which
    // would otherwise drive startTime == now and t == 0 → infinite recursion.
    cb(Number.MAX_SAFE_INTEGER)
    return 1
  }),
)
vi.stubGlobal('cancelAnimationFrame', vi.fn())

let audioService: typeof import('@/services/audioService').audioService

async function reloadService(): Promise<void> {
  vi.resetModules()
  mockAudioInstances = []
  documentListeners = []
  audioContextCtor.mockClear()
  ;(Audio as unknown as ReturnType<typeof vi.fn>).mockClear()
  const mod = await import('@/services/audioService')
  audioService = mod.audioService
}

describe('audioService BGM lifecycle (real implementation)', () => {
  beforeEach(async () => {
    localStorage.clear()
    await reloadService()
  })

  // ── Helpers ──────────────────────────────────────────────────────────────

  function getBgmInstance(): MockAudio | undefined {
    return mockAudioInstances.find((a) => a.src.includes('/audio/bgm/'))
  }

  function unlockUserGesture(): void {
    // Mute toggle counts as a user gesture and flips userInteracted=true.
    audioService.toggleMute()
    audioService.toggleMute()
  }

  // ── Contract: element-volume drives audibility (regression test) ─────────

  it('startBgm sets element.volume to a non-zero LOW target BEFORE play() is called', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')

    const bgm = getBgmInstance()
    expect(bgm, 'BGM <audio> must be created when user has interacted').toBeDefined()
    expect(bgm!.volume).toBeGreaterThan(0)
    expect(bgm!.volume).toBeLessThan(1)

    // play() must have been called AT LEAST once and the volume set BEFORE
    // that call resolved. We check ordering by capturing the volume seen by
    // the play mock — `inst.play.mock.calls.length === 1` guarantees one
    // start, and the mock was bound after volume assignment.
    expect(bgm!.play).toHaveBeenCalledTimes(1)
    expect(bgm!.paused).toBe(false)
  })

  it('startBgm does NOT create an AudioContext and does NOT route through Web Audio', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')

    // The original buggy implementation did:
    //   new AudioContext() → createGain() → createMediaElementSource(el).
    // The new implementation must not — it uses HTMLAudioElement.volume only.
    // Note: enableAudio still creates one AudioContext on first interaction
    // for legacy reasons (potential future use), so we check createGain was
    // never called (the BGM-only Web Audio entry points).
    if (audioContextCtor.mock.results.length > 0) {
      const result = audioContextCtor.mock.results[0]
      if (!result) return
      const ctx = result.value as {
        createGain: ReturnType<typeof vi.fn>
        createMediaElementSource: ReturnType<typeof vi.fn>
      }
      expect(
        ctx.createGain,
        'BGM must not use Web Audio gain nodes; el.volume controls level',
      ).not.toHaveBeenCalled()
      expect(
        ctx.createMediaElementSource,
        'BGM must not be captured by Web Audio; that path was silent on first NIGHT',
      ).not.toHaveBeenCalled()
    }
  })

  it('startBgm element src points at /audio/bgm/<filename> and loop=true', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')
    const bgm = getBgmInstance()!
    expect(bgm.src).toBe('/audio/bgm/suspicion.mp3')
    expect(bgm.loop).toBe(true)
  })

  // ── Idempotency ──────────────────────────────────────────────────────────

  it('startBgm is idempotent for the same filename', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')
    audioService.startBgm('suspicion.mp3')
    audioService.startBgm('suspicion.mp3')
    const bgmInstances = mockAudioInstances.filter((a) => a.src.includes('/audio/bgm/'))
    expect(bgmInstances.length).toBe(1)
  })

  it('startBgm with a different filename stops the previous track and starts the new one', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')
    const first = mockAudioInstances[mockAudioInstances.length - 1]!
    audioService.startBgm('心愿便利贴.mp3')
    const second = mockAudioInstances[mockAudioInstances.length - 1]!

    expect(first).not.toBe(second)
    // The old track was stopped (paused + src cleared during stopBgm).
    expect(first.pause).toHaveBeenCalled()
    expect(first.src).toBe('')
    // The new one is the active BGM.
    expect(second.src).toBe('/audio/bgm/%E5%BF%83%E6%84%BF%E4%BE%BF%E5%88%A9%E8%B4%B4.mp3')
    expect(second.paused).toBe(false)
  })

  // ── Deferred-start until first user gesture ──────────────────────────────

  it('startBgm before any user gesture does NOT create an Audio element', () => {
    audioService.startBgm('suspicion.mp3')
    expect(getBgmInstance()).toBeUndefined()
  })

  it('startBgm fires deferred when the first user gesture arrives', () => {
    audioService.startBgm('suspicion.mp3')
    expect(getBgmInstance()).toBeUndefined()

    // Fire any one of the captured document listeners — that's how the real
    // browser unlocks audio on the first click/touch/keydown/etc.
    expect(documentListeners.length).toBeGreaterThan(0)
    documentListeners[0]!()

    const bgm = getBgmInstance()
    expect(bgm, 'deferred BGM start must fire on first user gesture').toBeDefined()
    expect(bgm!.play).toHaveBeenCalled()
  })

  it('stopBgm called after a deferred start cancels the deferral', () => {
    audioService.startBgm('suspicion.mp3')
    audioService.stopBgm()

    // Even though no user gesture has fired yet, the deferred should be cleared.
    documentListeners.forEach((fn) => fn())
    expect(getBgmInstance()).toBeUndefined()
  })

  // ── Level + duck affect element.volume directly ─────────────────────────

  it('setBgmLevel(HIGH) raises element.volume relative to LOW', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')
    const bgm = getBgmInstance()!
    const lowVol = bgm.volume

    audioService.setBgmLevel('HIGH')
    expect(bgm.volume).toBeGreaterThan(lowVol)

    audioService.setBgmLevel('LOW')
    expect(bgm.volume).toBeCloseTo(lowVol, 5)
  })

  it('duckBgm reduces element.volume; unduckBgm restores it', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')
    audioService.setBgmLevel('HIGH')
    const bgm = getBgmInstance()!
    const highVol = bgm.volume

    audioService.duckBgm()
    expect(bgm.volume).toBeLessThan(highVol)

    audioService.unduckBgm()
    expect(bgm.volume).toBeCloseTo(highVol, 5)
  })

  // ── Mute integration ─────────────────────────────────────────────────────

  it('toggleMute while BGM plays drives element.volume to 0; un-mute restores', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')
    audioService.setBgmLevel('HIGH')
    const bgm = getBgmInstance()!
    const before = bgm.volume

    audioService.toggleMute() // mute
    expect(bgm.volume).toBe(0)

    audioService.toggleMute() // unmute
    expect(bgm.volume).toBeCloseTo(before, 5)
  })

  // ── Narration ducking auto-fires on playSequential ───────────────────────

  it('playSequential ducks BGM at first play, unducks when queue drains', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')
    audioService.setBgmLevel('HIGH')
    const bgm = getBgmInstance()!
    const highVol = bgm.volume

    audioService.playSequential(['wolf_open_eyes.mp3'])
    expect(bgm.volume).toBeLessThan(highVol)

    // Drive the queue to drain by firing onended on the narration item.
    const narration = mockAudioInstances.find((a) => a.src.includes('/audio/wolf_open_eyes.mp3'))
    expect(narration).toBeDefined()
    narration!.onended?.()

    expect(bgm.volume).toBeCloseTo(highVol, 5)
  })
})

// ── Defensive recovery for mobile-Chrome BGM failure modes ──────────────────
//
// Mobile Chrome rejects HTMLAudioElement.play() that isn't directly inside a
// user-activation event. BGM is started from a STOMP phase change (no gesture
// context), so the first attempt typically rejects. The original code
// swallowed that rejection. These tests pin the recovery contract:
//   #1 a rejected play() re-arms bgmPendingStart so the next gesture retries
//   #2 a paused BGM resumes on visibilitychange→visible (lock-screen recovery)
//   #3 the stuck-queue watchdog and the visibility-resume drain both call
//      unduckBgm so BGM is not left clamped at BGM_GAIN_DUCKED forever
describe('audioService BGM defensive recovery (mobile Chrome)', () => {
  beforeEach(async () => {
    localStorage.clear()
    await reloadService()
  })

  function getBgmInstance(): MockAudio | undefined {
    return mockAudioInstances.find((a) => a.src.includes('/audio/bgm/'))
  }

  function unlockUserGesture(): void {
    audioService.toggleMute()
    audioService.toggleMute()
  }

  it('fix #1: BGM play() rejection re-arms bgmPendingStart for the next gesture', async () => {
    unlockUserGesture()

    // First BGM element's play() rejects with NotAllowedError (the mobile
    // autoplay-policy signature). Subsequent Audio() calls go through the
    // default mock and succeed.
    ;(Audio as unknown as ReturnType<typeof vi.fn>).mockImplementationOnce(
      function (src?: string) {
        const inst = createMockAudio(src ?? '')
        inst.play = vi.fn(() => {
          // paused stays true (the default) since play() rejected
          const err: Error & { name?: string } = new Error('autoplay-blocked')
          err.name = 'NotAllowedError'
          return Promise.reject(err)
        })
        return inst
      },
    )

    audioService.startBgm('suspicion.mp3')
    // Let the rejection promise settle so the catch handler runs.
    await Promise.resolve()
    await Promise.resolve()

    expect(
      audioService.getBgmState().pendingStart,
      'rejection should re-arm bgmPendingStart',
    ).toBe(true)

    // Fire a gesture listener (one of the captured click/touchstart/etc).
    // setupUserInteractionTracking consumes pendingStart on every gesture, so
    // this should re-invoke startBgm with the same filename. The default mock
    // is back in play() so the retry succeeds.
    expect(documentListeners.length).toBeGreaterThan(0)
    documentListeners[0]!()

    const bgmInstances = mockAudioInstances.filter((a) => a.src.includes('/audio/bgm/'))
    expect(
      bgmInstances.length,
      'retry should create a fresh Audio element',
    ).toBeGreaterThanOrEqual(2)
    const latest = bgmInstances[bgmInstances.length - 1]!
    expect(latest.paused, 'retry play() should have succeeded').toBe(false)
    expect(audioService.getBgmState().pendingStart).toBe(false)
  })

  it('fix #2: visibilitychange→visible resumes a paused BGM element', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')
    const bgm = getBgmInstance()!
    expect(bgm.paused).toBe(false)

    // Simulate mobile lock-screen: the element pauses implicitly.
    bgm.paused = true
    bgm.play.mockClear()

    // visibilitychange handler reads document.visibilityState; set it on the
    // stubbed document and fire the handler. It's the last listener captured
    // (registered after the 5 gesture handlers in setupUserInteractionTracking).
    ;(document as unknown as { visibilityState: string }).visibilityState = 'visible'
    expect(documentListeners.length).toBeGreaterThanOrEqual(6)
    documentListeners[documentListeners.length - 1]!()

    expect(bgm.play, 'visibility-resume must re-issue play() on the BGM element').toHaveBeenCalled()
  })

  it('fix #3: visibility-resume drain unducks BGM when narration was mid-stream', () => {
    unlockUserGesture()
    audioService.startBgm('suspicion.mp3')
    audioService.setBgmLevel('HIGH')
    const bgm = getBgmInstance()!
    const highVol = bgm.volume

    // Kick off narration → BGM ducks; queue is now "playing".
    audioService.playSequential(['wolf_open_eyes.mp3'])
    expect(bgm.volume).toBeLessThan(highVol)

    // Tab goes hidden then comes back visible while narration was still in-flight.
    ;(document as unknown as { visibilityState: string }).visibilityState = 'visible'
    documentListeners[documentListeners.length - 1]!()

    expect(
      bgm.volume,
      'visibility drain must unduck BGM, restoring it to the HIGH target',
    ).toBeCloseTo(highVol, 5)
  })
})
