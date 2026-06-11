/**
 * CreateRoomView "Perks" host toggle: defaults on, flips off on click, and the
 * chosen value is sent in the createRoom request config.
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

describe('CreateRoomView perks toggle', () => {
  beforeEach(() => {
    h.createRoomMock.mockReset().mockResolvedValue({
      roomId: '1',
      roomCode: '123',
      hostId: 'u1',
      status: 'WAITING',
      config: { totalPlayers: 9, roles: [] },
      players: [],
    })
  })
  afterEach(() => vi.clearAllMocks())

  it('defaults to enabled and sends perksAllowed:true', async () => {
    const wrapper = await mountCreateRoom()
    const toggle = wrapper.find('[data-testid="perksAllowed-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.attributes('data-perks-allowed')).toBe('true')

    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()

    expect(h.createRoomMock).toHaveBeenCalledWith(
      expect.objectContaining({ config: expect.objectContaining({ perksAllowed: true }) }),
    )
  })

  it('flips off on click and sends perksAllowed:false', async () => {
    const wrapper = await mountCreateRoom()
    await wrapper.find('[data-testid="perksAllowed-toggle"]').trigger('click')
    expect(
      wrapper.find('[data-testid="perksAllowed-toggle"]').attributes('data-perks-allowed'),
    ).toBe('false')

    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()

    expect(h.createRoomMock).toHaveBeenCalledWith(
      expect.objectContaining({ config: expect.objectContaining({ perksAllowed: false }) }),
    )
  })
})
