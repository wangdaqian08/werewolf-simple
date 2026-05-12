/**
 * Tests for the host-aware default mute watcher introduced in GameView.vue.
 *
 * The watcher fires once when both userId and hostId are known:
 *   - host (userId === hostId) → setMuted(false)
 *   - non-host                 → setMuted(true)
 *
 * It must not re-fire on subsequent state updates (the once-per-load flag).
 *
 * We test the watcher logic directly with a minimal Vue effect scope and the
 * real Pinia stores rather than mounting the full GameView (which requires
 * a router, stompClient, etc.).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { effectScope, nextTick, watch } from 'vue'
import { useGameStore } from '@/stores/gameStore'
import { useUserStore } from '@/stores/userStore'
import type { GameState } from '@/types'

// ── Mock audioService ──────────────────────────────────────────────────────────

const mockSetMuted = vi.fn()

vi.mock('@/services/audioService', () => ({
  audioService: {
    setMuted: (...args: unknown[]) => mockSetMuted(...args),
    isMuted: vi.fn().mockReturnValue(false),
    toggleMute: vi.fn(),
    persistMute: vi.fn(),
    applyBgmGain: vi.fn(),
    stopAll: vi.fn(),
    playSequential: vi.fn(),
    clearQueue: vi.fn(),
    isQueueActive: vi.fn().mockReturnValue(false),
    startBgm: vi.fn(),
    stopBgm: vi.fn(),
    setBgmLevel: vi.fn(),
    setBgmVolume: vi.fn(),
    getBgmVolume: vi.fn().mockReturnValue(0.5),
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

/**
 * Mirrors the exact watcher installed in GameView.vue setup().
 * Returns a cleanup function.
 */
function installWatcher(
  userStore: ReturnType<typeof useUserStore>,
  gameStore: ReturnType<typeof useGameStore>,
  audioService: { setMuted: (v: boolean) => void },
) {
  let appliedDefaultMute = false
  const scope = effectScope()
  scope.run(() => {
    watch(
      () => [userStore.userId, gameStore.state?.hostId] as const,
      ([uid, hostId]) => {
        if (appliedDefaultMute) return
        if (!uid || !hostId) return
        appliedDefaultMute = true
        audioService.setMuted(uid !== hostId)
      },
      { immediate: true },
    )
  })
  return () => scope.stop()
}

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('GameView host-aware default mute watcher', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('host (userId === hostId) → setMuted(false)', async () => {
    const userStore = useUserStore()
    const gameStore = useGameStore()
    const { audioService } = await import('@/services/audioService')

    userStore.userId = 'host-1'
    gameStore.setState(makeState({ hostId: 'host-1' }))

    const cleanup = installWatcher(userStore, gameStore, audioService)
    await nextTick()

    expect(mockSetMuted).toHaveBeenCalledTimes(1)
    expect(mockSetMuted).toHaveBeenCalledWith(false)
    cleanup()
  })

  it('non-host (userId !== hostId) → setMuted(true)', async () => {
    const userStore = useUserStore()
    const gameStore = useGameStore()
    const { audioService } = await import('@/services/audioService')

    userStore.userId = 'player-2'
    gameStore.setState(makeState({ hostId: 'host-1' }))

    const cleanup = installWatcher(userStore, gameStore, audioService)
    await nextTick()

    expect(mockSetMuted).toHaveBeenCalledTimes(1)
    expect(mockSetMuted).toHaveBeenCalledWith(true)
    cleanup()
  })

  it('does not fire before both userId and hostId are known', async () => {
    const userStore = useUserStore()
    const gameStore = useGameStore()
    const { audioService } = await import('@/services/audioService')

    // userId known but hostId not yet
    userStore.userId = 'player-2'

    const cleanup = installWatcher(userStore, gameStore, audioService)
    await nextTick()

    expect(mockSetMuted).not.toHaveBeenCalled()
    cleanup()
  })

  it('does not re-fire on subsequent state updates (once-per-load flag)', async () => {
    const userStore = useUserStore()
    const gameStore = useGameStore()
    const { audioService } = await import('@/services/audioService')

    userStore.userId = 'player-2'
    gameStore.setState(makeState({ hostId: 'host-1' }))

    const cleanup = installWatcher(userStore, gameStore, audioService)
    await nextTick()

    expect(mockSetMuted).toHaveBeenCalledTimes(1)

    // Simulate subsequent state updates (e.g. STOMP broadcasts)
    gameStore.setState(makeState({ hostId: 'host-1', phase: 'DAY_DISCUSSION' }))
    await nextTick()
    gameStore.setState(makeState({ hostId: 'host-1', phase: 'NIGHT' }))
    await nextTick()

    // Must still be exactly 1 call
    expect(mockSetMuted).toHaveBeenCalledTimes(1)
    cleanup()
  })
})
