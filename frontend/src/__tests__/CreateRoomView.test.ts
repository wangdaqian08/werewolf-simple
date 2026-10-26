/**
 * CreateRoomView — page 1 of room creation: game settings only. It creates the
 * room with the default board and opens page 2 (players & roles) in the room,
 * so the code can be shared while the host is still deciding the headcount.
 * Also the host toggles: perks, 空刀, 空守 are sent in the createRoom config.
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

describe('CreateRoomView — game settings page', () => {
  beforeEach(() => {
    h.createRoomMock.mockReset().mockResolvedValue({
      roomId: '7',
      roomCode: '123',
      hostId: 'u1',
      status: 'WAITING',
      config: { totalPlayers: 9, roles: [] },
      players: [],
    })
  })
  afterEach(() => vi.clearAllMocks())

  it('has no player-count or role controls — those are on the next page', async () => {
    const wrapper = await mountCreateRoom()
    expect(wrapper.find('[data-testid="player-count-value"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="role-toggle-GUARD"]').exists()).toBe(false)
  })

  it('creates the room with the default 9-player board, then opens players & roles', async () => {
    const wrapper = await mountCreateRoom()
    await wrapper.find('[data-testid="create-room-submit"]').trigger('click')
    await flushPromises()

    expect(h.createRoomMock).toHaveBeenCalledWith(
      expect.objectContaining({
        config: expect.objectContaining({
          totalPlayers: 9,
          wolfCount: 3,
          roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER'],
        }),
      }),
    )
    const route = wrapper.vm.$router.currentRoute.value
    expect(route.name).toBe('room')
    expect(route.params.roomId).toBe('7')
    expect(route.query.setup).toBe('1')
  })
})

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
describe('CreateRoomView 空刀 (no kill) toggle', () => {
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

  it('defaults to disabled and sends noKillAllowed:false', async () => {
    const wrapper = await mountCreateRoom()
    const toggle = wrapper.find('[data-testid="noKill-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.attributes('data-no-kill')).toBe('false')

    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()

    expect(h.createRoomMock).toHaveBeenCalledWith(
      expect.objectContaining({ config: expect.objectContaining({ noKillAllowed: false }) }),
    )
  })

  it('flips on when clicked and sends noKillAllowed:true', async () => {
    const wrapper = await mountCreateRoom()
    await wrapper.find('[data-testid="noKill-toggle"]').trigger('click')
    expect(wrapper.find('[data-testid="noKill-toggle"]').attributes('data-no-kill')).toBe('true')

    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()

    expect(h.createRoomMock).toHaveBeenCalledWith(
      expect.objectContaining({ config: expect.objectContaining({ noKillAllowed: true }) }),
    )
  })
})

describe('CreateRoomView 空守 (guard skip) toggle', () => {
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

  it('defaults to disabled and sends guardSkipAllowed:false', async () => {
    const wrapper = await mountCreateRoom()
    const toggle = wrapper.find('[data-testid="guardSkip-toggle"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.attributes('data-guard-skip')).toBe('false')

    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()

    expect(h.createRoomMock).toHaveBeenCalledWith(
      expect.objectContaining({ config: expect.objectContaining({ guardSkipAllowed: false }) }),
    )
  })

  it('flips on when clicked and sends guardSkipAllowed:true', async () => {
    const wrapper = await mountCreateRoom()
    await wrapper.find('[data-testid="guardSkip-toggle"]').trigger('click')
    expect(wrapper.find('[data-testid="guardSkip-toggle"]').attributes('data-guard-skip')).toBe(
      'true',
    )

    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()

    expect(h.createRoomMock).toHaveBeenCalledWith(
      expect.objectContaining({ config: expect.objectContaining({ guardSkipAllowed: true }) }),
    )
  })
})
