/** GameView ROLE_REVEAL: the holder-only line saying whether the role-pick card took effect. */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import GameView from '@/views/GameView.vue'
import { useUserStore } from '@/stores/userStore'
import type { GameState } from '@/types'

const h = vi.hoisted(() => ({
  fakeClient: {
    onConnect: null as null | (() => void),
    active: true,
    activate: vi.fn(),
    forceDisconnect: vi.fn(),
  },
  getStateMock: vi.fn(),
}))

vi.mock('@/services/stompClient', () => ({
  createStompClient: () => h.fakeClient,
  subscribeToTopic: vi.fn(),
  getStompClient: () => h.fakeClient,
  disconnectStomp: vi.fn(),
}))
vi.mock('@/services/gameService', () => ({
  gameService: { getState: h.getStateMock, submitAction: vi.fn() },
}))
vi.mock('@/services/http', () => ({ default: { post: vi.fn(), get: vi.fn() } }))
vi.mock('@/services/audioService', () => ({
  audioService: { setMuted: vi.fn(), isMuted: vi.fn().mockReturnValue(false) },
}))
vi.mock('@/composables/useAudioService', () => ({ useAudioService: vi.fn() }))
vi.mock('@/composables/useNavigationGuard', () => ({ useNavigationGuard: vi.fn() }))
vi.mock('@/composables/useConnectionLifecycle', () => ({ useConnectionLifecycle: vi.fn() }))
vi.mock('@/composables/useWakeLock', () => ({ useWakeLock: vi.fn() }))
vi.mock('element-plus', async (orig) => ({
  ...(await orig<typeof import('element-plus')>()),
  ElMessage: vi.fn(),
}))

const revealState = (myRolePick?: GameState['myRolePick']): GameState =>
  ({
    gameId: '7',
    phase: 'ROLE_REVEAL',
    dayNumber: 0,
    hostId: 'u1',
    myRole: 'SEER',
    players: [],
    events: [],
    myRolePick,
  }) as GameState

async function mountWith(state: GameState) {
  h.getStateMock.mockResolvedValue(state)
  const pinia = createPinia()
  setActivePinia(pinia)
  const userStore = useUserStore()
  userStore.token = 'tok'
  userStore.userId = 'u1'
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: { template: '<div />' } },
      { path: '/game/:gameId', name: 'game', component: GameView },
      { path: '/result/:gameId', name: 'result', component: { template: '<div />' } },
    ],
  })
  await router.push('/game/7')
  await router.isReady()
  const wrapper = mount(GameView, {
    global: {
      plugins: [pinia, router],
      stubs: {
        PlayerSlot: true,
        VolumeControl: true,
        RoleRevealCard: { template: '<div><slot /></div>' },
        ActionMenu: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

describe('GameView role-reveal — role-pick result line', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows the deduction line when the card took effect', async () => {
    const w = await mountWith(revealState({ choice: 'GOD', tookEffect: true, price: 20 }))
    expect(w.get('[data-testid="role-pick-result"]').text()).toBe('點將卡生效，遊戲結束扣除 20◈')
  })

  it('shows the refund line when the card missed', async () => {
    const w = await mountWith(revealState({ choice: 'WOLF', tookEffect: false, price: 20 }))
    expect(w.get('[data-testid="role-pick-result"]').text()).toBe(
      '點將卡未抽中，20◈ 將在遊戲結束時退回',
    )
  })

  it.each([null, undefined])('renders nothing when myRolePick is %s', async (v) => {
    const w = await mountWith(revealState(v))
    expect(w.find('[data-testid="role-pick-result"]').exists()).toBe(false)
  })
})
