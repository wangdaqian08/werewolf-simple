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

describe('useAudioService', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    cleanup?.()
    cleanup = null
  })

  // ── Backend AudioSequence playback ──────────────────────────────────────

  it('plays audio files when audioSequence appears in game state', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['goes_dark_close_eyes.mp3', 'wolf_open_eyes.mp3'])
    gameStore.setState(makeState({ audioSequence: seq }))
    await nextTick()

    expect(mockClearQueue).toHaveBeenCalled()
    expect(mockPlaySequential).toHaveBeenCalledWith([
      'goes_dark_close_eyes.mp3',
      'wolf_open_eyes.mp3',
    ])
  })

  it('does not play if audioSequence is absent', async () => {
    const gameStore = useGameStore()
    setupComposable()

    gameStore.setState(makeState()) // no audioSequence
    await nextTick()

    expect(mockPlaySequential).not.toHaveBeenCalled()
  })

  it('does not play if audioFiles array is empty', async () => {
    const gameStore = useGameStore()
    setupComposable()

    gameStore.setState(makeState({ audioSequence: makeSequence([]) }))
    await nextTick()

    expect(mockPlaySequential).not.toHaveBeenCalled()
  })

  // ── Deduplication by sequence id ────────────────────────────────────────

  it('skips duplicate sequence with same id', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['goes_dark_close_eyes.mp3'], 'seq-001')
    gameStore.setState(makeState({ audioSequence: seq }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)

    // Same id again — should skip
    mockPlaySequential.mockClear()
    gameStore.setState(makeState({ audioSequence: { ...seq } }))
    await nextTick()
    expect(mockPlaySequential).not.toHaveBeenCalled()
  })

  it('plays new sequence with different id', async () => {
    const gameStore = useGameStore()
    setupComposable()

    gameStore.setState(
      makeState({ audioSequence: makeSequence(['goes_dark_close_eyes.mp3'], 'seq-001') }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)

    // Different id — should play
    gameStore.setState(
      makeState({ audioSequence: makeSequence(['wolf_open_eyes.mp3'], 'seq-002') }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(2)
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['wolf_open_eyes.mp3'])
  })

  // ── Queue clearing on new sequence ──────────────────────────────────────

  it('clears queue before playing new sequence', async () => {
    const gameStore = useGameStore()
    setupComposable()

    gameStore.setState(makeState({ audioSequence: makeSequence(['a.mp3'], 'seq-1') }))
    await nextTick()

    mockClearQueue.mockClear()

    gameStore.setState(makeState({ audioSequence: makeSequence(['b.mp3'], 'seq-2') }))
    await nextTick()

    expect(mockClearQueue).toHaveBeenCalled()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['b.mp3'])
  })

  // ── Phase-specific audio sequences (backend contract) ───────────────────

  it('NIGHT phase: backend sends goes_dark_close_eyes + role open-eyes', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Backend sends this when entering NIGHT and transitioning to WEREWOLF_PICK
    const seq = makeSequence(['goes_dark_close_eyes.mp3', 'wolf_open_eyes.mp3'], 'night-enter')
    seq.phase = 'NIGHT'
    seq.subPhase = 'WEREWOLF_PICK'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith([
      'goes_dark_close_eyes.mp3',
      'wolf_open_eyes.mp3',
    ])
  })

  it('WEREWOLF_PICK → SEER_PICK: backend sends close + open eyes', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['wolf_close_eyes.mp3', 'seer_open_eyes.mp3'], 'wolf-to-seer')
    seq.phase = 'NIGHT'
    seq.subPhase = 'SEER_PICK'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['wolf_close_eyes.mp3', 'seer_open_eyes.mp3'])
  })

  it('SEER_RESULT → WITCH_ACT: backend sends close + open eyes', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['seer_close_eyes.mp3', 'witch_open_eyes.mp3'], 'seer-to-witch')
    seq.phase = 'NIGHT'
    seq.subPhase = 'WITCH_ACT'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['seer_close_eyes.mp3', 'witch_open_eyes.mp3'])
  })

  it('WITCH_ACT → GUARD_PICK: backend sends close + open eyes', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['witch_close_eyes.mp3', 'guard_open_eyes.mp3'], 'witch-to-guard')
    seq.phase = 'NIGHT'
    seq.subPhase = 'GUARD_PICK'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['witch_close_eyes.mp3', 'guard_open_eyes.mp3'])
  })

  it('SEER_PICK → SEER_RESULT: close eyes only (no open-eyes for SEER_RESULT)', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // SEER_RESULT has no open-eyes audio — only close seer eyes
    const seq = makeSequence(['seer_close_eyes.mp3'], 'seer-to-result')
    seq.phase = 'NIGHT'
    seq.subPhase = 'SEER_RESULT'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['seer_close_eyes.mp3'])
  })

  it('GUARD_PICK → end of night: backend sends close eyes only', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['guard_close_eyes.mp3'], 'guard-end')
    seq.phase = 'NIGHT'
    seq.subPhase = 'WAITING'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['guard_close_eyes.mp3'])
  })

  it('DAY phase: backend sends rooster_crowing and day_time', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['rooster_crowing.mp3', 'day_time.mp3'], 'day-enter')
    seq.phase = 'DAY_DISCUSSION'
    seq.subPhase = null
    gameStore.setState(makeState({ phase: 'DAY_DISCUSSION', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['rooster_crowing.mp3', 'day_time.mp3'])
  })

  it('ROLE_REVEAL / VOTING / GAME_OVER: no audio', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // These phases should not have audioSequence, but if they do with empty files, nothing plays
    const seq = makeSequence([], 'no-audio')
    seq.phase = 'ROLE_REVEAL'
    gameStore.setState(makeState({ phase: 'ROLE_REVEAL', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).not.toHaveBeenCalled()
  })

  // ── Full night cycle ────────────────────────────────────────────────────

  it('full night cycle plays correct audio sequence for each transition', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // 1. Enter night
    gameStore.setState(
      makeState({
        audioSequence: makeSequence(['goes_dark_close_eyes.mp3'], 'night-1'),
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['goes_dark_close_eyes.mp3'])

    // 2. WAITING → WEREWOLF_PICK
    gameStore.setState(
      makeState({
        audioSequence: makeSequence(['wolf_open_eyes.mp3'], 'wolf-open'),
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['wolf_open_eyes.mp3'])

    // 3. WEREWOLF_PICK → SEER_PICK (close wolf + open seer)
    gameStore.setState(
      makeState({
        audioSequence: makeSequence(['wolf_close_eyes.mp3', 'seer_open_eyes.mp3'], 'wolf-seer'),
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith([
      'wolf_close_eyes.mp3',
      'seer_open_eyes.mp3',
    ])

    // 4. SEER → WITCH (close seer + open witch)
    gameStore.setState(
      makeState({
        audioSequence: makeSequence(['seer_close_eyes.mp3', 'witch_open_eyes.mp3'], 'seer-witch'),
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith([
      'seer_close_eyes.mp3',
      'witch_open_eyes.mp3',
    ])

    // 5. WITCH → GUARD (close witch + open guard)
    gameStore.setState(
      makeState({
        audioSequence: makeSequence(['witch_close_eyes.mp3', 'guard_open_eyes.mp3'], 'witch-guard'),
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith([
      'witch_close_eyes.mp3',
      'guard_open_eyes.mp3',
    ])

    // 6. GUARD → end (close guard)
    gameStore.setState(
      makeState({
        audioSequence: makeSequence(['guard_close_eyes.mp3'], 'guard-end'),
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['guard_close_eyes.mp3'])

    // 7. Night → Day
    gameStore.setState(
      makeState({
        phase: 'DAY_DISCUSSION',
        audioSequence: makeSequence(['rooster_crowing.mp3', 'day_time.mp3'], 'day'),
      }),
    )
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['rooster_crowing.mp3', 'day_time.mp3'])

    // Total: 7 sequence plays
    expect(mockPlaySequential).toHaveBeenCalledTimes(7)
  })

  // ── Mute controls ──────────────────────────────────────────────────────

  it('toggleMute delegates to audioService', () => {
    setupComposable().toggleMute()
    expect(mockToggleMute).toHaveBeenCalled()
  })

  it('isMuted reflects audioService state', () => {
    mockIsMuted.mockReturnValue(false)
    const { isMuted } = setupComposable()
    expect(isMuted.value).toBe(false)
  })

  // ── Guard Close Eyes Bug Regression Tests ───────────────────────────────

  /**
   * REGRESSION TEST: guard_close_eyes.mp3 was playing twice
   *
   * Bug scenario:
   * 1. Guard completes - backend sends guard_close_eyes.mp3 audio sequence
   * 2. PhaseChanged event arrives, handler preserves old audioSequence
   * 3. AudioSequence event arrives with day audio (rooster_crowing.mp3)
   * 4. Handler overwrites with stale guard_close_eyes.mp3 → plays again!
   *
   * Fix: GameView handlers no longer preserve audioSequence.
   * This test verifies the useAudioService deduplication handles this correctly.
   */
  it('guard close eyes - same sequence ID should not replay (deduplication)', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // 1. Guard completes - first guard_close_eyes.mp3
    const guardCloseSeq = makeSequence(['guard_close_eyes.mp3'], 'seq-guard-close-001')
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

    // 2. Stale state update with SAME sequence ID (simulates race condition)
    // This would happen if PhaseChanged handler preserved and reapplied old audioSequence
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
        audioSequence: { ...guardCloseSeq }, // Same ID, different object reference
      }),
    )
    await nextTick()

    // Should NOT play again - same ID is deduplicated
    expect(mockPlaySequential).not.toHaveBeenCalled()

    // 3. New day audio sequence arrives
    const daySeq = makeSequence(['rooster_crowing.mp3', 'day_time.mp3'], 'seq-day-002')
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
  })

  it('guard as last role - complete audio sequence verification', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Full sequence when guard is last role:
    // 1. WITCH_ACT → GUARD_PICK: witch_close_eyes.mp3 + guard_open_eyes.mp3
    // 2. GUARD_PICK completion: guard_close_eyes.mp3 (ONCE)
    // 3. DAY transition: rooster_crowing.mp3 + day_time.mp3

    // Step 1: Witch to Guard transition
    const witchToGuardSeq = makeSequence(
      ['witch_close_eyes.mp3', 'guard_open_eyes.mp3'],
      'seq-witch-to-guard-001',
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

    // Step 2: Guard completes - guard_close_eyes.mp3
    const guardCloseSeq = makeSequence(['guard_close_eyes.mp3'], 'seq-guard-close-002')
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

    // Step 3: Day transition - rooster_crowing.mp3 + day_time.mp3
    const daySeq = makeSequence(['rooster_crowing.mp3', 'day_time.mp3'], 'seq-day-003')
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
    // The bug would have resulted in 4 (guard_close_eyes twice)
  })

  it('state update without audioSequence should not trigger playback', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Set initial audio
    const seq1 = makeSequence(['initial.mp3'], 'seq-initial')
    gameStore.setState(makeState({ audioSequence: seq1 }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)

    mockPlaySequential.mockClear()

    // State update WITHOUT audioSequence (simulates PhaseChanged handler after fix)
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
        // No audioSequence - should not trigger any audio
      }),
    )
    await nextTick()

    // No audio should play
    expect(mockPlaySequential).not.toHaveBeenCalled()

    // Now set new audio
    const seq2 = makeSequence(['new_audio.mp3'], 'seq-new')
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
        audioSequence: seq2,
      }),
    )
    await nextTick()

    // New audio should play
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['new_audio.mp3'])
  })
})
