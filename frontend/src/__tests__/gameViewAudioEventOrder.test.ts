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

    // With the bug fix, both audio files play. The queue behaviour depends on
    // priority: sub-phase sequences are low-priority and append (non-overlapping)
    // so no clearQueue call is expected here. The critical assertion is that
    // the new sequence is actually played — the original bug dropped it entirely.
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

  it('GUARD_PICK to DAY transition - plays rooster_crowing.mp3 and day_time.mp3', async () => {
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
    const newSeq = makeSequence(['rooster_crowing.mp3', 'day_time.mp3'], 'seq-night-to-day')
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

    // Should play rooster_crowing.mp3 then day_time.mp3
    expect(mockPlaySequential).toHaveBeenCalledWith(['rooster_crowing.mp3', 'day_time.mp3'])
  })

  it('Multiple rapid high-priority sequences - each appends to the queue (no more clearQueue)', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // New contract (post guard-audio-sequence fix): high-priority sequences
    // NO LONGER clear the queue. Each appends in order, so rapid phase flips
    // produce the union of all audio in arrival order — no audio is lost.
    // See useAudioService.ts and audioService.waitForIdle for rationale.
    mockPlaySequential.mockClear()
    mockClearQueue.mockClear()

    gameStore.setState(
      makeState({
        audioSequence: { ...makeSequence(['file1.mp3'], 'seq-1'), priority: 10 },
      }),
    )
    await nextTick()

    gameStore.setState(
      makeState({
        audioSequence: { ...makeSequence(['file2.mp3', 'file3.mp3'], 'seq-2'), priority: 10 },
      }),
    )
    await nextTick()

    gameStore.setState(
      makeState({
        audioSequence: { ...makeSequence(['file4.mp3'], 'seq-3'), priority: 10 },
      }),
    )
    await nextTick()

    expect(mockClearQueue).not.toHaveBeenCalled()
    expect(mockPlaySequential).toHaveBeenCalledTimes(3)
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['file4.mp3'])
  })

  it('Multiple rapid low-priority sequences - all append, queue is never cleared', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Low-priority role-owned sequences (e.g. open/close eyes) must append so the
    // player hears each file play to completion without truncation. This is the
    // property that guarantees sequential, non-overlapping audio.
    mockPlaySequential.mockClear()
    mockClearQueue.mockClear()

    gameStore.setState(
      makeState({ audioSequence: { ...makeSequence(['a.mp3'], 'seq-1'), priority: 5 } }),
    )
    await nextTick()
    gameStore.setState(
      makeState({ audioSequence: { ...makeSequence(['b.mp3'], 'seq-2'), priority: 5 } }),
    )
    await nextTick()
    gameStore.setState(
      makeState({ audioSequence: { ...makeSequence(['c.mp3'], 'seq-3'), priority: 5 } }),
    )
    await nextTick()

    expect(mockClearQueue).not.toHaveBeenCalled()
    expect(mockPlaySequential).toHaveBeenCalledTimes(3)

    // Reconstruct the ordered queue from the append sequence.
    const playedFiles = mockPlaySequential.mock.calls.flatMap(
      (args: unknown[]) => args[0] as string[],
    )
    expect(playedFiles).toEqual(['a.mp3', 'b.mp3', 'c.mp3'])
  })

  /**
   * CRITICAL REGRESSION TEST
   *
   * This test prevents a race condition bug where guard_close_eyes.mp3
   * would play twice when guard is the last special role to complete.
   *
   * The bug occurred because PhaseChanged handler preserved audioSequence
   * at the START of the async handler, then overwrote with stale value
   * after AudioSequence event had already updated with new day audio.
   *
   * Sequence that caused the bug:
   * 1. guard_close_eyes.mp3 audio sequence received → plays
   * 2. PhaseChanged (NIGHT → DAY) event arrives
   * 3. Handler captures audioSequence = guard_close_eyes
   * 4. Handler starts async getState() call
   * 5. AudioSequence event arrives with DAY audio → day audio plays
   * 6. Handler finishes, overwrites with stale guard_close_eyes → plays again!
   *
   * Fix: Don't preserve audioSequence in PhaseChanged/NightSubPhaseChanged handlers.
   * AudioSequence events are the single source of truth for audio.
   */
  it('PhaseChanged race condition - stale audio should not replay after day audio', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // 1. Guard completes - backend sends guard_close_eyes audio
    const guardCloseSeq = makeSequence(['guard_close_eyes.mp3'], 'seq-guard-close')
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'WAITING', dayNumber: 1 },
        audioSequence: guardCloseSeq,
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['guard_close_eyes.mp3'])

    mockPlaySequential.mockClear()
    mockClearQueue.mockClear()

    // 2. PhaseChanged event (NIGHT → DAY) - handler would call getState() async
    // This simulates the state update from getState() WITHOUT preserving audio
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
        // With the bug: audioSequence would be preserved as guard_close_eyes
        // With the fix: audioSequence is NOT preserved (undefined or not set)
      }),
    )
    await nextTick()

    // No new audio yet - PhaseChanged doesn't set audio
    expect(mockPlaySequential).not.toHaveBeenCalled()

    // 3. AudioSequence event arrives with day audio
    const daySeq = makeSequence(['rooster_crowing.mp3', 'day_time.mp3'], 'seq-day')
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
        audioSequence: daySeq,
      }),
    )
    await nextTick()

    // Day audio should play
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['rooster_crowing.mp3', 'day_time.mp3'])

    // 4. Simulate a late/stale state update WITHOUT audioSequence
    // This is what happens with the bug fix: GameView handlers don't preserve
    // audioSequence, so stale state updates won't affect audio playback
    mockPlaySequential.mockClear()
    mockClearQueue.mockClear()

    // Stale state update WITHOUT audioSequence (simulates race condition with fix)
    // The bug would have preserved audioSequence, causing it to replay
    // The fix ensures audioSequence is NOT preserved in state updates
    const staleState = makeState({
      phase: 'DAY_DISCUSSION',
      dayPhase: {
        subPhase: 'RESULT_REVEALED', // Different sub-phase
        dayNumber: 1,
        phaseDeadline: 9999999999,
        phaseStarted: 0,
        canVote: true,
      },
      // No audioSequence - this is the key fix
    })

    gameStore.setState(staleState)
    await nextTick()

    // No audio should play from state-only update (no audioSequence)
    expect(mockPlaySequential).not.toHaveBeenCalled()

    // 5. Verify deduplication: same day sequence ID should not replay
    mockPlaySequential.mockClear()

    // Try to replay the same day audio sequence (same ID)
    gameStore.setState(
      makeState({
        phase: 'DAY_DISCUSSION',
        dayPhase: {
          subPhase: 'RESULT_REVEALED',
          dayNumber: 1,
          phaseDeadline: 9999999999,
          phaseStarted: 0,
          canVote: true,
        },
        audioSequence: daySeq, // Same ID as already played
      }),
    )
    await nextTick()

    // Should NOT play again - same ID is deduplicated
    expect(mockPlaySequential).not.toHaveBeenCalled()
  })

  /**
   * Test the full scenario: guard is last role, completes → night ends → day starts
   * This is the exact user scenario that triggered the bug.
   */
  it('Full guard-last-to-complete scenario - audio plays exactly once each', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Start: WITCH_ACT → GUARD_PICK transition
    const witchToGuardSeq = makeSequence(
      ['witch_close_eyes.mp3', 'guard_open_eyes.mp3'],
      'seq-witch-to-guard',
    )
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'GUARD_PICK', dayNumber: 1 },
        audioSequence: witchToGuardSeq,
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)
    expect(mockPlaySequential).toHaveBeenLastCalledWith([
      'witch_close_eyes.mp3',
      'guard_open_eyes.mp3',
    ])

    mockPlaySequential.mockClear()

    // Guard completes - guard_close_eyes.mp3 plays
    const guardCloseSeq = makeSequence(['guard_close_eyes.mp3'], 'seq-guard-close-177946')
    gameStore.setState(
      makeState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'WAITING', dayNumber: 1 },
        audioSequence: guardCloseSeq,
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['guard_close_eyes.mp3'])

    mockPlaySequential.mockClear()

    // Night ends - PhaseChanged (NIGHT → DAY)
    // Backend sends state update (no audioSequence)
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
    // No audio from PhaseChanged
    expect(mockPlaySequential).not.toHaveBeenCalled()

    // AudioSequence with day audio arrives
    const daySeq = makeSequence(
      ['rooster_crowing.mp3', 'day_time.mp3'],
      'seq-day-180011', // Newer timestamp
    )
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
        audioSequence: daySeq,
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['rooster_crowing.mp3', 'day_time.mp3'])

    // TOTAL: Exactly 3 audio sequences played
    // 1. witch_close_eyes + guard_open_eyes
    // 2. guard_close_eyes (once!)
    // 3. rooster_crowing + day_time
    // The bug would have made it 4 (guard_close_eyes twice)
  })

  /**
   * Test that AudioSequence events are the SINGLE SOURCE OF TRUTH for audio.
   * Other events like PhaseChanged, NightSubPhaseChanged should NOT set audioSequence.
   */
  it('AudioSequence is single source of truth - state updates without audio should not affect playback', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Set initial audio
    const seq1 = makeSequence(['initial.mp3'], 'seq-1')
    gameStore.setState(makeState({ audioSequence: seq1 }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)

    mockPlaySequential.mockClear()

    // State update WITHOUT audioSequence (simulates PhaseChanged handler)
    // Should NOT trigger any audio change
    gameStore.setState(
      makeState({
        phase: 'DAY_DISCUSSION',
        // No audioSequence set
      }),
    )
    await nextTick()

    // No audio should play from state-only update
    expect(mockPlaySequential).not.toHaveBeenCalled()
  })
})
