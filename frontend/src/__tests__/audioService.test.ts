import { beforeEach, describe, expect, it, vi } from 'vitest'

// ── Mock HTMLAudioElement ─────────────────────────────────────────────────────

type MockAudio = {
  play: ReturnType<typeof vi.fn>
  pause: ReturnType<typeof vi.fn>
  currentTime: number
  volume: number
  loop: boolean
  preload: string
  onended: (() => void) | null
}

let mockAudioInstances: MockAudio[] = []

function createMockAudio(): MockAudio {
  const inst = {
    play: vi.fn().mockResolvedValue(undefined),
    pause: vi.fn(),
    currentTime: 0,
    volume: 1,
    loop: false,
    preload: '',
    onended: null as (() => void) | null,
  }
  mockAudioInstances.push(inst)
  return inst
}

vi.stubGlobal('document', { ...globalThis.document, addEventListener: vi.fn() })
// Must use `function` keyword for constructor mock
vi.stubGlobal(
  'Audio',
  vi.fn().mockImplementation(function () {
    return createMockAudio()
  }),
)
vi.stubGlobal(
  'AudioContext',
  vi.fn().mockImplementation(function () {
    return {}
  }),
)

let audioService: typeof import('@/services/audioService').audioService

describe('audioService', () => {
  beforeEach(async () => {
    localStorage.clear()
    mockAudioInstances = []
    ;(Audio as unknown as ReturnType<typeof vi.fn>).mockClear()
    vi.resetModules()
    const mod = await import('@/services/audioService')
    audioService = mod.audioService
    // Simulate user interaction so play works
    audioService.toggleMute() // mute (sets userInteracted=true)
    audioService.toggleMute() // unmute
  })

  // ── Sequential playback (core contract) ─────────────────────────────────

  it('playSequential plays first file immediately', () => {
    audioService.playSequential(['goes_dark_close_eyes.mp3', 'wolf_open_eyes.mp3'])
    expect(mockAudioInstances).toHaveLength(1)
    expect(mockAudioInstances[0]?.play).toHaveBeenCalledTimes(1)
  })

  it('playSequential does NOT play next file until current finishes', () => {
    audioService.playSequential(['a.mp3', 'b.mp3'])
    expect(mockAudioInstances).toHaveLength(1)
    // b.mp3 not created yet
    expect(Audio).toHaveBeenCalledTimes(1)
  })

  it('playSequential advances to next file on onended', () => {
    audioService.playSequential(['a.mp3', 'b.mp3', 'c.mp3'])

    mockAudioInstances[0]?.onended?.()
    expect(mockAudioInstances).toHaveLength(2)
    expect(mockAudioInstances[1]?.play).toHaveBeenCalled()

    mockAudioInstances[1]?.onended?.()
    expect(mockAudioInstances).toHaveLength(3)
    expect(mockAudioInstances[2]?.play).toHaveBeenCalled()
  })

  it('playSequential skips all when muted', () => {
    audioService.toggleMute()
    audioService.playSequential(['a.mp3', 'b.mp3'])
    expect(mockAudioInstances).toHaveLength(0)
  })

  // ── clearQueue ──────────────────────────────────────────────────────────

  it('clearQueue stops current and prevents queued files from playing', () => {
    audioService.playSequential(['a.mp3', 'b.mp3', 'c.mp3'])
    audioService.clearQueue()
    expect(mockAudioInstances[0]?.pause).toHaveBeenCalled()

    // onended fires after clear — no new audio should start
    mockAudioInstances[0]?.onended?.()
    expect(mockAudioInstances).toHaveLength(1)
  })

  // ── stopAll mid-playback resets queue state ─────────────────────────────
  //
  // Regression: pausing an HTMLAudioElement does NOT fire its `onended`
  // handler, so playNextInQueue is never invoked and `isPlayingQueue`
  // stayed stuck at true. The next playSequential queued files but
  // skipped processing because of the `!isPlayingQueue` gate, leaving
  // the next game's audio silent forever. Reproduced 2026-05-04 when
  // game 23 ended mid-witch_close_eyes; game 24 played no audio.

  it('stopAll mid-playback resets isPlayingQueue so the next playSequential plays', () => {
    audioService.playSequential(['a.mp3', 'b.mp3'])
    expect(audioService.isQueueActive()).toBe(true)
    expect(mockAudioInstances[0]?.play).toHaveBeenCalledTimes(1)

    // Simulate component unmount mid-playback (a.mp3 is still playing,
    // never received onended). useAudioService.onUnmounted does this.
    audioService.stopAll()
    expect(mockAudioInstances[0]?.pause).toHaveBeenCalled()
    expect(audioService.isQueueActive()).toBe(false)

    // Next mount → next playSequential MUST start a new playback.
    audioService.playSequential(['c.mp3'])
    expect(mockAudioInstances).toHaveLength(2)
    expect(mockAudioInstances[1]?.play).toHaveBeenCalledTimes(1)
  })

  // ── Mute ────────────────────────────────────────────────────────────────

  it('toggleMute toggles state and persists to localStorage', () => {
    expect(audioService.isMuted()).toBe(false)
    audioService.toggleMute()
    expect(audioService.isMuted()).toBe(true)
    expect(localStorage.getItem('audio-muted')).toBe('true')
    audioService.toggleMute()
    expect(audioService.isMuted()).toBe(false)
    expect(localStorage.getItem('audio-muted')).toBe('false')
  })

  it('toggleMute stops playing audio when muting', () => {
    audioService.playSequential(['a.mp3'])
    audioService.toggleMute()
    expect(mockAudioInstances[0]?.pause).toHaveBeenCalled()
  })

  it('restores mute from localStorage on init', async () => {
    localStorage.setItem('audio-muted', 'true')
    vi.resetModules()
    mockAudioInstances = []
    const mod = await import('@/services/audioService')
    expect(mod.audioService.isMuted()).toBe(true)
  })

  // ── Volume ──────────────────────────────────────────────────────────────

  it('setGlobalVolume clamps to 0-1', () => {
    audioService.setGlobalVolume(2)
    expect(audioService.getGlobalVolume()).toBe(1)
    audioService.setGlobalVolume(-1)
    expect(audioService.getGlobalVolume()).toBe(0)
  })

  it('setGlobalVolume updates cached instances', () => {
    audioService.playSequential(['a.mp3'])
    audioService.setGlobalVolume(0.3)
    expect(mockAudioInstances[0]?.volume).toBeCloseTo(0.3)
  })

  // ── Error resilience ────────────────────────────────────────────────────

  it('continues queue when play() rejects', async () => {
    // Override Audio constructor for the first call to return a failing instance
    ;(Audio as unknown as ReturnType<typeof vi.fn>).mockImplementationOnce(function () {
      const inst = createMockAudio()
      inst.play = vi.fn().mockRejectedValue(new Error('NotAllowedError'))
      return inst
    })

    audioService.playSequential(['fail.mp3', 'ok.mp3'])

    // Let the rejected promise settle — playNextInQueue is called in .catch()
    await vi.waitFor(() => {
      expect(mockAudioInstances).toHaveLength(2)
    })
    expect(mockAudioInstances[1]?.play).toHaveBeenCalled()
  })
})
