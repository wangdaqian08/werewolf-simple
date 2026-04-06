import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useGameStore } from '@/stores/gameStore'
import type { AudioSequence, GameState } from '@/types'

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

// Must import after mock
import { useAudioService } from '@/composables/useAudioService'

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
  // Vue watchers need an active effect scope
  const { effectScope } = require('vue')
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

    const seq = makeSequence(['天黑请闭眼.mp3', '狼人请睁眼.mp3'])
    gameStore.setState(makeState({ audioSequence: seq }))
    await nextTick()

    expect(mockClearQueue).toHaveBeenCalled()
    expect(mockPlaySequential).toHaveBeenCalledWith(['天黑请闭眼.mp3', '狼人请睁眼.mp3'])
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

    const seq = makeSequence(['天黑请闭眼.mp3'], 'seq-001')
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

    gameStore.setState(makeState({ audioSequence: makeSequence(['天黑请闭眼.mp3'], 'seq-001') }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(1)

    // Different id — should play
    gameStore.setState(makeState({ audioSequence: makeSequence(['狼人请睁眼.mp3'], 'seq-002') }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenCalledTimes(2)
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['狼人请睁眼.mp3'])
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

  it('NIGHT phase: backend sends 天黑请闭眼 + role open-eyes', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // Backend sends this when entering NIGHT and transitioning to WEREWOLF_PICK
    const seq = makeSequence(['天黑请闭眼.mp3', '狼人请睁眼.mp3'], 'night-enter')
    seq.phase = 'NIGHT'
    seq.subPhase = 'WEREWOLF_PICK'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['天黑请闭眼.mp3', '狼人请睁眼.mp3'])
  })

  it('WEREWOLF_PICK → SEER_PICK: backend sends close + open eyes', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['狼人请闭眼.mp3', '预言家请睁眼.mp3'], 'wolf-to-seer')
    seq.phase = 'NIGHT'
    seq.subPhase = 'SEER_PICK'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['狼人请闭眼.mp3', '预言家请睁眼.mp3'])
  })

  it('SEER_RESULT → WITCH_ACT: backend sends close + open eyes', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['预言家请闭眼.mp3', '女巫请睁眼.mp3'], 'seer-to-witch')
    seq.phase = 'NIGHT'
    seq.subPhase = 'WITCH_ACT'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['预言家请闭眼.mp3', '女巫请睁眼.mp3'])
  })

  it('WITCH_ACT → GUARD_PICK: backend sends close + open eyes', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['女巫请闭眼.mp3', '守卫请睁眼.mp3'], 'witch-to-guard')
    seq.phase = 'NIGHT'
    seq.subPhase = 'GUARD_PICK'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['女巫请闭眼.mp3', '守卫请睁眼.mp3'])
  })

  it('SEER_PICK → SEER_RESULT: close eyes only (no open-eyes for SEER_RESULT)', async () => {
    const gameStore = useGameStore()
    setupComposable()

    // SEER_RESULT has no open-eyes audio — only close seer eyes
    const seq = makeSequence(['预言家请闭眼.mp3'], 'seer-to-result')
    seq.phase = 'NIGHT'
    seq.subPhase = 'SEER_RESULT'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['预言家请闭眼.mp3'])
  })

  it('GUARD_PICK → end of night: backend sends close eyes only', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['守卫请闭眼.mp3'], 'guard-end')
    seq.phase = 'NIGHT'
    seq.subPhase = 'WAITING'
    gameStore.setState(makeState({ phase: 'NIGHT', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['守卫请闭眼.mp3'])
  })

  it('DAY phase: backend sends 天亮了', async () => {
    const gameStore = useGameStore()
    setupComposable()

    const seq = makeSequence(['天亮了.mp3'], 'day-enter')
    seq.phase = 'DAY'
    seq.subPhase = null
    gameStore.setState(makeState({ phase: 'DAY', audioSequence: seq }))
    await nextTick()

    expect(mockPlaySequential).toHaveBeenCalledWith(['天亮了.mp3'])
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
    gameStore.setState(makeState({
      audioSequence: makeSequence(['天黑请闭眼.mp3'], 'night-1'),
    }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['天黑请闭眼.mp3'])

    // 2. WAITING → WEREWOLF_PICK
    gameStore.setState(makeState({
      audioSequence: makeSequence(['狼人请睁眼.mp3'], 'wolf-open'),
    }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['狼人请睁眼.mp3'])

    // 3. WEREWOLF_PICK → SEER_PICK (close wolf + open seer)
    gameStore.setState(makeState({
      audioSequence: makeSequence(['狼人请闭眼.mp3', '预言家请睁眼.mp3'], 'wolf-seer'),
    }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['狼人请闭眼.mp3', '预言家请睁眼.mp3'])

    // 4. SEER → WITCH (close seer + open witch)
    gameStore.setState(makeState({
      audioSequence: makeSequence(['预言家请闭眼.mp3', '女巫请睁眼.mp3'], 'seer-witch'),
    }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['预言家请闭眼.mp3', '女巫请睁眼.mp3'])

    // 5. WITCH → GUARD (close witch + open guard)
    gameStore.setState(makeState({
      audioSequence: makeSequence(['女巫请闭眼.mp3', '守卫请睁眼.mp3'], 'witch-guard'),
    }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['女巫请闭眼.mp3', '守卫请睁眼.mp3'])

    // 6. GUARD → end (close guard)
    gameStore.setState(makeState({
      audioSequence: makeSequence(['守卫请闭眼.mp3'], 'guard-end'),
    }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['守卫请闭眼.mp3'])

    // 7. Night → Day
    gameStore.setState(makeState({
      phase: 'DAY',
      audioSequence: makeSequence(['天亮了.mp3'], 'day'),
    }))
    await nextTick()
    expect(mockPlaySequential).toHaveBeenLastCalledWith(['天亮了.mp3'])

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
})
