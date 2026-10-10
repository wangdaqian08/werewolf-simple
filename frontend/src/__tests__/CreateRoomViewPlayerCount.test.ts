/**
 * CreateRoomView player-count stepper: 6–15 players (15 = 5 wolves / 5 gods /
 * 5 villagers, the largest board the current roles balance).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import CreateRoomView from '@/views/CreateRoomView.vue'

const h = vi.hoisted(() => ({ createRoomMock: vi.fn() }))

vi.mock('@/services/roomService', () => ({
  roomService: { createRoom: h.createRoomMock },
}))
vi.mock('@/services/audioTracksService', () => ({
  audioTracksService: { fetchTracks: vi.fn().mockResolvedValue([]) },
}))

async function mountCreateRoom() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/create-room', name: 'create-room', component: CreateRoomView },
      { path: '/room/:roomId', name: 'room', component: { template: '<div />' } },
    ],
  })
  await router.push('/create-room')
  await router.isReady()
  const wrapper = mount(CreateRoomView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

describe('CreateRoomView — player count', () => {
  beforeEach(() => {
    h.createRoomMock.mockReset().mockResolvedValue({
      roomId: '1',
      roomCode: '123',
      hostId: 'u1',
      status: 'WAITING',
      config: { totalPlayers: 15, roles: [] },
      players: [],
    })
  })
  afterEach(() => vi.clearAllMocks())

  it('goes up to 15 players and stops there', async () => {
    const wrapper = await mountCreateRoom()
    const increment = wrapper.find('[data-testid="player-count-increment"]')
    for (let i = 0; i < 10; i++) await increment.trigger('click')

    expect(wrapper.find('[data-testid="player-count-value"]').text()).toBe('15')
    expect(increment.attributes('disabled')).toBeDefined()
    expect(wrapper.find('.stepper-range').text()).toBe('6 – 15')
  })

  it('a 15-player room defaults to 5 wolves and is sent to the backend', async () => {
    const wrapper = await mountCreateRoom()
    for (let i = 0; i < 6; i++)
      await wrapper.find('[data-testid="player-count-increment"]').trigger('click')
    expect(wrapper.find('[data-testid="wolf-count-value"]').text()).toBe('5')

    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()

    expect(h.createRoomMock).toHaveBeenCalledWith(
      expect.objectContaining({
        config: expect.objectContaining({ totalPlayers: 15, wolfCount: 5 }),
      }),
    )
  })
})
