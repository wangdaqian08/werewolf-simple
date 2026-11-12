/**
 * GameView wiring for the mechanical wolf + medium night turns:
 *  - every one of their 8 actions is a NIGHT_TURN_ACTION, so a rejected submit bumps
 *    NightPhase's rejection-count (which resets its hasActed);
 *  - the private MediumResult / MechWolfResult events refetch the game state.
 *
 * Mounts the real GameView with a stub NightPhase; services, STOMP and audio are mocked.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent } from 'vue'
import GameView from '@/views/GameView.vue'
import { useUserStore } from '@/stores/userStore'
import type { GameState } from '@/types'

const h = vi.hoisted(() => {
  const fakeClient = {
    onConnect: null as null | (() => void),
    active: true,
    activate: vi.fn(),
    forceDisconnect: vi.fn(),
  }
  return {
    fakeClient,
    subscribeToTopicMock: vi.fn(),
    getStateMock: vi.fn(),
    submitActionMock: vi.fn(),
  }
})

vi.mock('@/services/stompClient', () => ({
  createStompClient: () => h.fakeClient,
  subscribeToTopic: h.subscribeToTopicMock,
  getStompClient: () => h.fakeClient,
  disconnectStomp: vi.fn(),
}))
vi.mock('@/services/gameService', () => ({
  gameService: { getState: h.getStateMock, submitAction: h.submitActionMock },
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

const NEW_ACTIONS = [
  'MECH_LEARN',
  'MECH_CHECK',
  'MECH_POISON',
  'MECH_PROTECT',
  'MECH_SKIP',
  'MECH_CONFIRM',
  'MEDIUM_CHECK',
  'MEDIUM_CONFIRM',
  'GRAVEKEEPER_CONFIRM',
]

// Stands in for NightPhase: exposes the rejection count; the test emits night-action on it.
const NightPhaseStub = defineComponent({
  props: { rejectionCount: { type: Number, default: 0 }, narration: { type: String, default: '' } },
  emits: ['night-action'],
  template: `<div data-testid="night-stub" :data-rejections="rejectionCount" :data-narration="narration" />`,
})

const nightState = (): GameState =>
  ({
    gameId: '7',
    phase: 'NIGHT',
    dayNumber: 1,
    hostId: 'u1',
    myRole: 'MEDIUM',
    players: [],
    events: [],
    nightPhase: { subPhase: 'MEDIUM_PICK', dayNumber: 1 },
  }) as GameState

async function mountGame() {
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
        NightPhase: NightPhaseStub,
        PlayerSlot: true,
        VolumeControl: true,
        RoleRevealCard: true,
        ActionMenu: true,
        SheriffElection: true,
        DayPhase: true,
        VotingPhase: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

async function connectAndGetPrivateHandler() {
  h.fakeClient.onConnect?.()
  await flushPromises()
  const call = h.subscribeToTopicMock.mock.calls.find((c) => c[0] === '/user/queue/private')
  return call![1] as (msg: { body: string }) => Promise<void>
}

describe('GameView — mechanical wolf + medium night turns', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    h.fakeClient.onConnect = null
    h.getStateMock.mockResolvedValue(nightState())
  })

  it.each(NEW_ACTIONS)('a rejected %s bumps the night rejection count', async (actionType) => {
    h.submitActionMock.mockResolvedValue({ success: false, message: 'not your turn' })
    const wrapper = await mountGame()
    const stub = wrapper.find('[data-testid="night-stub"]')
    expect(stub.attributes('data-rejections')).toBe('0')
    wrapper.findComponent(NightPhaseStub).vm.$emit('night-action', actionType, 'u2')
    await flushPromises()
    expect(h.submitActionMock).toHaveBeenCalledWith(expect.objectContaining({ actionType }))
    expect(stub.attributes('data-rejections')).toBe('1')
  })

  it('an accepted action does not bump the rejection count', async () => {
    h.submitActionMock.mockResolvedValue({ success: true })
    const wrapper = await mountGame()
    wrapper.findComponent(NightPhaseStub).vm.$emit('night-action', 'MEDIUM_CHECK', 'u2')
    await flushPromises()
    expect(wrapper.find('[data-testid="night-stub"]').attributes('data-rejections')).toBe('0')
  })

  it.each(['MediumResult', 'MechWolfResult'])('%s refetches the game state', async (type) => {
    await mountGame()
    const onPrivate = await connectAndGetPrivateHandler()
    h.getStateMock.mockClear()
    await onPrivate({ body: JSON.stringify({ type }) })
    expect(h.getStateMock).toHaveBeenCalledTimes(1)
  })

  it('the host gets the narration line of the newest audio; other players get none', async () => {
    const withAudio = (hostId: string): GameState =>
      ({
        ...nightState(),
        hostId,
        audioReplayBuffer: [
          {
            id: 'a',
            phase: 'NIGHT',
            subPhase: null,
            audioFiles: ['medium_open_eyes.mp3'],
            priority: 5,
            timestamp: 1,
          },
        ],
        audioSequence: {
          id: 'b',
          phase: 'NIGHT',
          subPhase: 'MEDIUM_PICK',
          audioFiles: ['medium_close_eyes.mp3'],
          priority: 5,
          timestamp: 2,
        },
      }) as GameState

    h.getStateMock.mockResolvedValue(withAudio('u1'))
    let wrapper = await mountGame()
    expect(wrapper.find('[data-testid="night-stub"]').attributes('data-narration')).toBe(
      'mediumClose',
    )

    h.getStateMock.mockResolvedValue(withAudio('someone-else'))
    wrapper = await mountGame()
    expect(wrapper.find('[data-testid="night-stub"]').attributes('data-narration')).toBe('')
  })
})
