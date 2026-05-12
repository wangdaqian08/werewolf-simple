import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// Vue watchers need an active effect scope
import { effectScope, nextTick } from 'vue'
import { useGameStore } from '@/stores/gameStore'
import { useRoomStore } from '@/stores/roomStore'
import type { AudioSequence, GameState, Room } from '@/types'
// Must import after mock
import { useAudioService } from '@/composables/useAudioService'

// ── Mock audioService ─────────────────────────────────────────────────────────

const mockPlaySequential = vi.fn()
const mockClearQueue = vi.fn()
const mockStopAll = vi.fn()
const mockIsMuted = vi.fn().mockReturnValue(false)
const mockToggleMute = vi.fn()
const mockSetMuted = vi.fn()
const mockIsQueueActive = vi.fn().mockReturnValue(false)
const mockStartBgm = vi.fn()
const mockStopBgm = vi.fn()
const mockSetBgmLevel = vi.fn()
const mockSetBgmVolume = vi.fn()
const mockGetBgmVolume = vi.fn().mockReturnValue(0.5)

vi.mock('@/services/audioService', () => ({
  audioService: {
    playSequential: (...args: unknown[]) => mockPlaySequential(...args),
    clearQueue: () => mockClearQueue(),
    stopAll: () => mockStopAll(),
    isMuted: () => mockIsMuted(),
    toggleMute: () => mockToggleMute(),
    setMuted: (...args: unknown[]) => mockSetMuted(...args),
    isQueueActive: () => mockIsQueueActive(),
    setGlobalVolume: vi.fn(),
    getGlobalVolume: vi.fn().mockReturnValue(1),
    startBgm: (...args: unknown[]) => mockStartBgm(...args),
    stopBgm: () => mockStopBgm(),
    setBgmLevel: (...args: unknown[]) => mockSetBgmLevel(...args),
    setBgmVolume: (...args: unknown[]) => mockSetBgmVolume(...args),
    getBgmVolume: () => mockGetBgmVolume(),
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

  // ── Queue behavior: priority-aware to guarantee sequential, non-overlapping playback ─

  it('high-priority sequence (>=10) APPENDS when queue still has role-owned audio in flight', async () => {
    // Regression: when DAY's rooster_crowing arrives while the role-owned
    // queue is still draining (e.g. guard_close_eyes queued ~15ms before),
    // the previous behavior unconditionally clearQueue()'d and dropped
    // guard_close_eyes — verified end-to-end in
    // /tmp/werewolf-e2e-backend.log on 2026-04-27. The queue-active branch
    // appends instead, preserving narrative integrity.
    mockIsQueueActive.mockReturnValue(true)

    const gameStore = useGameStore()
    setupComposable()

    gameStore.setState(
      makeState({
        audioSequence: { ...makeSequence(['rooster_crowing.mp3'], 'day-arrives'), priority: 10 },
      }),
    )
    await nextTick()

    expect(mockClearQueue).not.toHaveBeenCalled()
    expect(mockPlaySequential).toHaveBeenCalledWith(['rooster_crowing.mp3'])

    // Reset for any later test in this file that depends on queue-idle default.
    mockIsQueueActive.mockReturnValue(false)
  })

  it('high-priority sequence (>=10) clears the queue so it plays immediately', async () => {
    // Phase-boundary audio (DAY→NIGHT, NIGHT→DAY) must interrupt any lingering audio
    // so the new phase's ambience plays promptly.
    const gameStore = useGameStore()
    setupComposable()

    gameStore.setState(
      makeState({ audioSequence: { ...makeSequence(['a.mp3'], 'seq-1'), priority: 10 } }),
    )
    await nextTick()

    mockClearQueue.mockClear()

    gameStore.setState(
      makeState({ audioSequence: { ...makeSequence(['b.mp3'], 'seq-2'), priority: 10 } }),
    )
    await nextTick()

    expect(mockClearQueue).toHaveBeenCalled()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['b.mp3'])
  })

  it('DAY → WEREWOLF_PICK: player hears goes_dark_close_eyes → wolf_howl → wolf_open_eyes in order across two broadcasts', async () => {
    // This is the canonical "player experience" check the decoupled architecture
    // must guarantee. The backend emits the audio in two AudioSequence broadcasts:
    //
    //   Broadcast #1 (priority 10, from calculatePhaseTransition):
    //     [goes_dark_close_eyes.mp3, wolf_howl.mp3]
    //   Broadcast #2 (priority 5, from NightOrchestrator.nightRoleLoop):
    //     [wolf_open_eyes.mp3]
    //
    // The first one clears the queue and begins playback. The second one arrives
    // while the first may still be playing — it MUST append rather than truncate.
    // Collecting the full flat ordered list of files passed to playSequential gives
    // us the exact order the audioService.ts queue will play them back to the user.
    const gameStore = useGameStore()
    setupComposable()

    // ── Broadcast #1: DAY → NIGHT phase transition (goes_dark + wolf_howl) ──
    const phaseSeq = makeSequence(
      ['goes_dark_close_eyes.mp3', 'wolf_howl.mp3'],
      'phase-transition-day-to-night',
    )
    phaseSeq.priority = 10
    phaseSeq.phase = 'NIGHT'
    phaseSeq.subPhase = 'WEREWOLF_PICK'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: phaseSeq }))
    await nextTick()

    // ── Broadcast #2: WEREWOLF_PICK role-loop open eyes (wolf_open_eyes) ──
    const wolfOpenSeq = makeSequence(['wolf_open_eyes.mp3'], 'role-loop-wolf-open-eyes')
    wolfOpenSeq.priority = 5
    wolfOpenSeq.phase = 'NIGHT'
    wolfOpenSeq.subPhase = 'WEREWOLF_PICK'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: wolfOpenSeq }))
    await nextTick()

    // Broadcast #1 cleared; broadcast #2 appended (did NOT clear).
    expect(mockClearQueue).toHaveBeenCalledTimes(1)

    // Flat the files from each playSequential call in call order to reconstruct
    // the exact ordered sequence the player's audio queue will play.
    const orderedFiles = mockPlaySequential.mock.calls.flatMap((args) => args[0] as string[])
    expect(orderedFiles).toEqual([
      'goes_dark_close_eyes.mp3',
      'wolf_howl.mp3',
      'wolf_open_eyes.mp3',
    ])
  })

  it('low-priority sequence (<10) appends to the queue so files never overlap', async () => {
    // Role-owned audio (wolf_open_eyes after the DAY→NIGHT ambience) must wait for
    // whatever is currently playing to finish. Calling clearQueue here would
    // truncate the previous file mid-playback. This test locks in the contract
    // that guarantees the user hears goes_dark_close_eyes → wolf_howl → wolf_open_eyes
    // in order, regardless of how quickly the role-loop broadcast arrives.
    const gameStore = useGameStore()
    setupComposable()

    gameStore.setState(
      makeState({
        audioSequence: {
          ...makeSequence(['goes_dark_close_eyes.mp3', 'wolf_howl.mp3'], 'phase-seq'),
          priority: 10,
        },
      }),
    )
    await nextTick()
    mockClearQueue.mockClear()

    gameStore.setState(
      makeState({
        audioSequence: { ...makeSequence(['wolf_open_eyes.mp3'], 'role-seq'), priority: 5 },
      }),
    )
    await nextTick()

    expect(mockClearQueue).not.toHaveBeenCalled()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['wolf_open_eyes.mp3'])
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

  // ── Tab-wake no-side-effect recovery (PR C) ──────────────────────────────

  /**
   * Test A — "no stale replay after tab wake"
   *
   * Constraint (from plan): If a player resumes their phone during the game,
   * audio cues that already played on other players' phones must NOT replay
   * on the resumed phone.
   *
   * Mechanism: on visibilitychange→hidden the composable pre-dedupes every id
   * in the current audioReplayBuffer into playedIds. When refreshState() lands
   * the same buffer after reconnect, the audioReplayBuffer watcher's tryPlay
   * calls are all dedup-skipped. Only ids that arrive AFTER the wake (new ids)
   * are absent from playedIds and will play normally.
   */
  it('does not replay stale buffer cues after tab wake (pre-dedup on hidden)', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // 1. Populate the replay buffer with 3 cues before the composable mounts.
    const buf = [
      makeSequence(['seer_open_eyes.mp3'], 'seq-1'),
      makeSequence(['witch_open_eyes.mp3'], 'seq-2'),
      makeSequence(['guard_open_eyes.mp3'], 'seq-3'),
    ]
    gameStore.setState(makeState({ audioReplayBuffer: buf }))
    await nextTick()

    // 2. Trigger one live audioSequence so the composable has at least one
    //    played id (proves the composable is active / watchers are running).
    const liveSeq = makeSequence(['goes_dark_close_eyes.mp3'], 'seq-live-pre')
    gameStore.setState(makeState({ audioSequence: liveSeq, audioReplayBuffer: buf }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledWith(['goes_dark_close_eyes.mp3'])

    // 3. Tab goes to background — composable should pre-dedup buffer ids.
    mockPlaySequential.mockClear()
    Object.defineProperty(document, 'visibilityState', {
      value: 'hidden',
      configurable: true,
    })
    document.dispatchEvent(new Event('visibilitychange'))

    // 4. Simulate refreshState() landing after STOMP reconnect on resume.
    //    Same 3 cues + 1 new one (seq-4). The 3 stale cues must NOT play.
    const bufAfterResume = [...buf, makeSequence(['wolf_open_eyes.mp3'], 'seq-4')]
    gameStore.setState(makeState({ audioReplayBuffer: bufAfterResume }))
    await nextTick()

    // 5. Tab becomes visible.
    Object.defineProperty(document, 'visibilityState', {
      value: 'visible',
      configurable: true,
    })
    document.dispatchEvent(new Event('visibilitychange'))
    await nextTick()

    // Assert: zero new play() calls — all 3 stale cues were pre-deduped.
    // (seq-4 is in the buffer but not in a separate audioSequence broadcast,
    // so it also won't play here — it would only play if a live audioSequence
    // or a new-tail-id replay watcher triggers tryPlay with seq-4.)
    expect(mockPlaySequential).not.toHaveBeenCalled()
  })

  /**
   * Test B — "live cue after tab wake plays"
   *
   * After the tab wakes and the stale buffer is pre-deduped, a brand-new
   * audioSequence broadcast (new id, never seen by this device) must still
   * play normally. This proves the fix doesn't over-suppress future audio.
   */
  it('plays a live cue that arrives after tab wake (new id not in playedIds)', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // 1. Set up buffer with 3 stale cues.
    const buf = [
      makeSequence(['seer_open_eyes.mp3'], 'seq-1'),
      makeSequence(['witch_open_eyes.mp3'], 'seq-2'),
      makeSequence(['guard_open_eyes.mp3'], 'seq-3'),
    ]
    gameStore.setState(makeState({ audioReplayBuffer: buf }))
    await nextTick()

    // 2. Tab goes hidden → pre-dedup triggered.
    Object.defineProperty(document, 'visibilityState', {
      value: 'hidden',
      configurable: true,
    })
    document.dispatchEvent(new Event('visibilitychange'))

    // 3. Tab becomes visible again.
    Object.defineProperty(document, 'visibilityState', {
      value: 'visible',
      configurable: true,
    })
    document.dispatchEvent(new Event('visibilitychange'))
    await nextTick()
    mockPlaySequential.mockClear()

    // 4. A fresh live audioSequence arrives after wake (new id — not in playedIds).
    const liveAfterWake = makeSequence(['wolf_howl.mp3'], 'seq-live-after-wake')
    gameStore.setState(makeState({ audioSequence: liveAfterWake, audioReplayBuffer: buf }))
    await nextTick()

    // Assert: exactly this one cue plays — the live post-wake cue.
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)
    expect(mockPlaySequential).toHaveBeenCalledWith(['wolf_howl.mp3'])
  })

  // ── BGM track source (gameStore primary, roomStore fallback) ────────────
  //
  // roomStore is in-memory only and is wiped by a page reload. Without the
  // gameStore fallback below, a mid-game refresh entering NIGHT would never
  // call startBgm because roomStore.room is null. The backend now mirrors
  // Room.config.bgmTrack onto the /api/game/{id}/state response so
  // gameStore.state.bgmTrack survives the reload.
  //
  // Helper: build a minimal Room with a bgmTrack on config.
  function makeRoom(track: string | null): Room {
    return {
      roomId: 'r1',
      roomCode: 'ABCD',
      hostId: 'host',
      status: 'IN_GAME',
      players: [],
      config: {
        totalPlayers: 9,
        roles: [],
        bgmTrack: track,
      },
    }
  }

  it('NIGHT phase starts BGM from gameStore.state.bgmTrack (post-reload path)', async () => {
    const gameStore = useGameStore()
    // roomStore is empty — simulates a mid-game page reload where the lobby
    // state never re-hydrated.
    setupComposable()

    gameStore.setState(makeState({ phase: 'NIGHT', bgmTrack: 'suspicion.mp3' }))
    await nextTick()

    expect(mockStartBgm).toHaveBeenCalledWith('suspicion.mp3')
  })

  it('NIGHT phase falls back to roomStore.room.config.bgmTrack when gameStore has no track', async () => {
    const gameStore = useGameStore()
    const roomStore = useRoomStore()
    roomStore.setRoom(makeRoom('心愿便利贴.mp3'))
    setupComposable()

    gameStore.setState(makeState({ phase: 'NIGHT' })) // no bgmTrack
    await nextTick()

    expect(mockStartBgm).toHaveBeenCalledWith('心愿便利贴.mp3')
  })

  it('NIGHT phase with no track in either store does not start BGM', async () => {
    const gameStore = useGameStore()
    setupComposable()

    gameStore.setState(makeState({ phase: 'NIGHT' })) // no bgmTrack anywhere
    await nextTick()

    expect(mockStartBgm).not.toHaveBeenCalled()
  })

  it('gameStore.state.bgmTrack takes precedence over roomStore (the wire is authoritative)', async () => {
    const gameStore = useGameStore()
    const roomStore = useRoomStore()
    roomStore.setRoom(makeRoom('心愿便利贴.mp3'))
    setupComposable()

    gameStore.setState(makeState({ phase: 'NIGHT', bgmTrack: 'suspicion.mp3' }))
    await nextTick()

    expect(mockStartBgm).toHaveBeenCalledWith('suspicion.mp3')
    expect(mockStartBgm).not.toHaveBeenCalledWith('心愿便利贴.mp3')
  })
})
