import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// Vue watchers need an active effect scope
import { effectScope, nextTick } from 'vue'
import { useGameStore } from '@/stores/gameStore'
import type { AudioSequence, GameState } from '@/types'
// Must import after mock
import { useAudioService } from '@/composables/useAudioService'

// ── Mock audioService ─────────────────────────────────────────────────────────

const mockPlaySequential = vi.fn()
const mockClearQueue = vi.fn()
const mockStopAll = vi.fn()
const mockIsMuted = vi.fn().mockReturnValue(false)
const mockToggleMute = vi.fn()

vi.mock('@/services/audioService', () => ({
  audioService: {
    playSequential: (...args: unknown[]) => mockPlaySequential(...args),
    clearQueue: () => mockClearQueue(),
    stopAll: () => mockStopAll(),
    isMuted: () => mockIsMuted(),
    toggleMute: () => mockToggleMute(),
    setGlobalVolume: vi.fn(),
    getGlobalVolume: vi.fn().mockReturnValue(1),
  },
}))

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeState(overrides: Partial<GameState> = {}): GameState {
  return {
    gameId: 'g1',
    phase: 'NIGHT',
    dayNumber: 1,
    players: [],
    events: [],
    ...overrides,
  }
}

function makeSequence(audioFiles: string[], id?: string): AudioSequence {
  return {
    id: id ?? `seq-${Date.now()}-${Math.random()}`,
    phase: 'NIGHT',
    subPhase: null,
    audioFiles,
    priority: 5,
    timestamp: Date.now(),
  }
}

// ── Composable setup helper (must run inside effect scope for watchers) ───────

let cleanup: (() => void) | null = null

function setupComposable() {
  const scope = effectScope()
  const result = scope.run(() => useAudioService())!
  cleanup = () => scope.stop()
  return result
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('GameView Audio Event Order Bug', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    cleanup?.()
    cleanup = null
  })

  /**
   * CRITICAL BUG FIX TEST
   *
   * This test reproduces the bug where NightSubPhaseChanged event preserves
   * the old audioSequence, preventing the new AudioSequence from being played.
   *
   * The bug occurs when:
   * 1. NightSubPhaseChanged event arrives and re-fetches state
   * 2. The event handler preserves the current audioSequence
   * 3. AudioSequence event arrives with new audio files
   * 4. But the watcher sees the same audioSequence (because it was preserved)
   * 5. So the new audio is not played
   *
   * The fix is to NOT preserve audioSequence in NightSubPhaseChanged handler.
   */
  it('NightSubPhaseChanged then AudioSequence - ensures new audio plays (bug fix test)', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Simulate initial state with audioSequence
    const initialSeq = makeSequence(['seer_open_eyes.mp3'], 'seq-seer-open')
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'SEER_PICK', dayNumber: 1 },
        audioSequence: initialSeq,
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['seer_open_eyes.mp3'])

    // Simulate user clicking "查验完毕" - this triggers SEER_CONFIRM action
    // Backend sends two events in order:
    // 1. NightSubPhaseChanged (SEER_RESULT -> WITCH_ACT)
    // 2. AudioSequence ([seer_close_eyes.mp3, witch_open_eyes.mp3])

    // Event 1: NightSubPhaseChanged
    // This simulates what GameView.vue does in the NightSubPhaseChanged handler
    mockPlaySequential.mockClear()
    mockClearQueue.mockClear()

    // The handler would normally re-fetch state and preserve audioSequence
    // But with the bug fix, it should NOT preserve audioSequence
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'WITCH_ACT', dayNumber: 1 },
        // With the bug: audioSequence: initialSeq (preserved)
        // With the fix: audioSequence is NOT preserved
      }),
    )
    await nextTick()

    // After NightSubPhaseChanged, audio should NOT change (no new sequence yet)
    expect(mockPlaySequential).not.toHaveBeenCalled()

    // Event 2: AudioSequence
    const newSeq = makeSequence(['seer_close_eyes.mp3', 'witch_open_eyes.mp3'], 'seq-seer-to-witch')
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'WITCH_ACT', dayNumber: 1 },
        audioSequence: newSeq,
      }),
    )
    await nextTick()

    // With the bug fix, both audio files should play
    // With the bug, only the first file (seer_close_eyes.mp3) would play
    expect(mockClearQueue).toHaveBeenCalled()
    expect(mockPlaySequential).toHaveBeenCalledWith(['seer_close_eyes.mp3', 'witch_open_eyes.mp3'])
  })

  it('SEER_PICK to SEER_RESULT transition - plays correct audio', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Initial state: SEER_PICK phase with seer open eyes audio
    const initialSeq = makeSequence(['seer_open_eyes.mp3'], 'seq-seer-open')
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'SEER_PICK', dayNumber: 1 },
        audioSequence: initialSeq,
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)

    // User clicks "查验" button
    // Backend sends: SEER_PICK -> SEER_RESULT
    mockPlaySequential.mockClear()
    mockClearQueue.mockClear()

    // NightSubPhaseChanged
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'SEER_RESULT', dayNumber: 1 },
      }),
    )
    await nextTick()

    // AudioSequence
    const newSeq = makeSequence(['seer_close_eyes.mp3'], 'seq-seer-result')
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'SEER_RESULT', dayNumber: 1 },
        audioSequence: newSeq,
      }),
    )
    await nextTick()

    // Should play only seer_close_eyes.mp3 (no open eyes for SEER_RESULT)
    expect(mockPlaySequential).toHaveBeenCalledWith(['seer_close_eyes.mp3'])
  })

  it('SEER_RESULT to WITCH_ACT transition - plays both audio files', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Initial state: SEER_RESULT phase
    const initialSeq = makeSequence(['seer_close_eyes.mp3'], 'seq-seer-result')
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'SEER_RESULT', dayNumber: 1 },
        audioSequence: initialSeq,
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)

    // User clicks "查验完毕" button
    // Backend sends: SEER_RESULT -> WITCH_ACT
    mockPlaySequential.mockClear()
    mockClearQueue.mockClear()

    // NightSubPhaseChanged
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'WITCH_ACT', dayNumber: 1 },
      }),
    )
    await nextTick()

    // AudioSequence
    const newSeq = makeSequence(['seer_close_eyes.mp3', 'witch_open_eyes.mp3'], 'seq-seer-to-witch')
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'WITCH_ACT', dayNumber: 1 },
        audioSequence: newSeq,
      }),
    )
    await nextTick()

    // CRITICAL: Should play BOTH audio files
    // This was the bug - only seer_close_eyes.mp3 was playing
    expect(mockPlaySequential).toHaveBeenCalledWith(['seer_close_eyes.mp3', 'witch_open_eyes.mp3'])
  })

  it('WITCH_ACT to GUARD_PICK transition - plays both audio files', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Initial state: WITCH_ACT phase
    const initialSeq = makeSequence(['witch_open_eyes.mp3'], 'seq-witch-open')
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'WITCH_ACT', dayNumber: 1 },
        audioSequence: initialSeq,
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)

    // User completes witch action
    // Backend sends: WITCH_ACT -> GUARD_PICK
    mockPlaySequential.mockClear()
    mockClearQueue.mockClear()

    // NightSubPhaseChanged
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'GUARD_PICK', dayNumber: 1 },
      }),
    )
    await nextTick()

    // AudioSequence
    const newSeq = makeSequence(
      ['witch_close_eyes.mp3', 'guard_open_eyes.mp3'],
      'seq-witch-to-guard',
    )
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'GUARD_PICK', dayNumber: 1 },
        audioSequence: newSeq,
      }),
    )
    await nextTick()

    // Should play both audio files
    expect(mockPlaySequential).toHaveBeenCalledWith(['witch_close_eyes.mp3', 'guard_open_eyes.mp3'])
  })

  it('GUARD_PICK to DAY transition - plays day_time.mp3', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Initial state: GUARD_PICK phase
    const initialSeq = makeSequence(['guard_open_eyes.mp3'], 'seq-guard-open')
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'GUARD_PICK', dayNumber: 1 },
        audioSequence: initialSeq,
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)

    // User completes guard action
    // Backend sends: GUARD_PICK -> DAY
    mockPlaySequential.mockClear()
    mockClearQueue.mockClear()

    // NightSubPhaseChanged (actually PhaseChanged)
    gameStore.setState(
      makeState({
        phase: 'DAY_DISCUSSION',
        dayPhase: {
          subPhase: 'RESULT_HIDDEN',
          dayNumber: 1,
          phaseDeadline: 9999999999,
          phaseStarted: 0,
          canVote: true,
        },
      }),
    )
    await nextTick()

    // AudioSequence
    const newSeq = makeSequence(['day_time.mp3'], 'seq-night-to-day')
    gameStore.setState(
      makeState({
        phase: 'DAY_DISCUSSION',
        dayPhase: {
          subPhase: 'RESULT_HIDDEN',
          dayNumber: 1,
          phaseDeadline: 9999999999,
          phaseStarted: 0,
          canVote: true,
        },
        audioSequence: newSeq,
      }),
    )
    await nextTick()

    // Should play day_time.mp3
    expect(mockPlaySequential).toHaveBeenCalledWith(['day_time.mp3'])
  })

  it('Multiple rapid audio sequence changes - each plays with queue clearing', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Simulate rapid phase changes (could happen with fast user actions or network)
    mockPlaySequential.mockClear()
    mockClearQueue.mockClear()

    // First sequence
    gameStore.setState(
      makeState({
        audioSequence: makeSequence(['file1.mp3'], 'seq-1'),
      }),
    )
    await nextTick()

    // Second sequence (replaces first)
    gameStore.setState(
      makeState({
        audioSequence: makeSequence(['file2.mp3', 'file3.mp3'], 'seq-2'),
      }),
    )
    await nextTick()

    // Third sequence (replaces second)
    gameStore.setState(
      makeState({
        audioSequence: makeSequence(['file4.mp3'], 'seq-3'),
      }),
    )
    await nextTick()

    // Each sequence with a different ID will play
    // Queue is cleared before each new sequence
    expect(mockClearQueue).toHaveBeenCalledTimes(3)
    expect(mockPlaySequential).toHaveBeenCalledTimes(3)
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['file4.mp3'])
  })
})
