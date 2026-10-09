/**
 * CreateRoomView role toggles: 白狼王 takes one of the wolf seats, so enabling
 * it never costs a villager seat and is sent in the createRoom roles.
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

function chipCount(wrapper: Awaited<ReturnType<typeof mountCreateRoom>>, role: string) {
  const chip = wrapper.find(`[data-testid="role-composition"] [data-role="${role}"]`)
  return chip.exists() ? Number(chip.attributes('data-count')) : 0
}

describe('CreateRoomView — 白狼王 toggle', () => {
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

  it('replaces one werewolf seat and keeps the villager seats', async () => {
    const wrapper = await mountCreateRoom()
    const wolvesBefore = chipCount(wrapper, 'WEREWOLF')
    const villagersBefore = chipCount(wrapper, 'VILLAGER')

    await wrapper.find('[data-testid="role-toggle-WHITE_WOLF_KING"]').trigger('click')

    expect(chipCount(wrapper, 'WHITE_WOLF_KING')).toBe(1)
    expect(chipCount(wrapper, 'WEREWOLF')).toBe(wolvesBefore - 1)
    expect(chipCount(wrapper, 'VILLAGER')).toBe(villagersBefore)
  })

  it('does not count against the god limit', async () => {
    const wrapper = await mountCreateRoom()
    await wrapper.find('[data-testid="role-toggle-GUARD"]').trigger('click') // 3 wolves, 4 gods (max)
    const kingToggle = wrapper.find('[data-testid="role-toggle-WHITE_WOLF_KING"]')
    expect(kingToggle.attributes('disabled')).toBeUndefined()
    await kingToggle.trigger('click')
    expect(chipCount(wrapper, 'WHITE_WOLF_KING')).toBe(1)
  })

  it('sends WHITE_WOLF_KING in the createRoom roles', async () => {
    const wrapper = await mountCreateRoom()
    await wrapper.find('[data-testid="role-toggle-WHITE_WOLF_KING"]').trigger('click')

    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()

    expect(h.createRoomMock).toHaveBeenCalledWith(
      expect.objectContaining({
        config: expect.objectContaining({ roles: expect.arrayContaining(['WHITE_WOLF_KING']) }),
      }),
    )
  })
})
