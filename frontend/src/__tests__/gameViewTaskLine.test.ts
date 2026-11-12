/** GameView ROLE_REVEAL: the player's own 单局任务 under the revealed role. */
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

const revealState = (myTask?: GameState['myTask']): GameState =>
  ({
    gameId: '7',
    phase: 'ROLE_REVEAL',
    dayNumber: 0,
    hostId: 'u1',
    myRole: 'SEER',
    players: [],
    events: [],
    myTask,
  }) as GameState

async function mountWith(state: GameState, reveal = true) {
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

        ActionMenu: true,
      },
    },
  })
  await flushPromises()
  if (reveal) {
    await wrapper.get('[data-testid="reveal-role-btn"]').trigger('click')
    await flushPromises()
  }
  return wrapper
}

describe('GameView role-reveal — my task', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows my task once the role is revealed', async () => {
    const w = await mountWith(revealState({ code: 'SURVIVE_TO_DAY3', score: 5, done: null }))
    expect(w.get('[data-testid="task-name"]').text()).toBe('活到第 3 天天亮')
    expect(w.get('[data-testid="task-score"]').text()).toBe('+5 戰績分')
  })

  it('hides it while the role is still unrevealed', async () => {
    const w = await mountWith(revealState({ code: 'SURVIVE_TO_DAY3', score: 5, done: null }), false)
    expect(w.find('[data-testid="task-line"]').exists()).toBe(false)
  })

  it.each([null, undefined])('renders nothing when myTask is %s', async (v) => {
    const w = await mountWith(revealState(v))
    expect(w.find('[data-testid="task-line"]').exists()).toBe(false)
  })
})
