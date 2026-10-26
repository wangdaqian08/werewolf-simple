/**
 * RoomSetupPanel player-count stepper: 6–15 players (15 = 5 wolves / 5 gods /
 * 5 villagers, the largest board the current roles balance).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import RoomSetupPanel from '@/components/RoomSetupPanel.vue'
import type { Room } from '@/types'

const h = vi.hoisted(() => ({ updateBoardMock: vi.fn() }))

vi.mock('@/services/roomService', () => ({
  roomService: { updateBoard: h.updateBoardMock },
}))

async function mountSetup(players = 1) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const room: Room = {
    roomId: '1',
    roomCode: '123',
    hostId: 'u1',
    status: 'WAITING',
    config: {
      totalPlayers: 9,
      wolfCount: 3,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER'],
    },
    players: Array.from({ length: players }, (_, i) => ({
      userId: `u${i + 1}`,
      nickname: `P${i + 1}`,
      seatIndex: null,
      status: 'NOT_READY',
      isHost: i === 0,
    })),
  } as Room
  const wrapper = mount(RoomSetupPanel, { props: { room }, global: { plugins: [pinia] } })
  await flushPromises()
  return wrapper
}

describe('RoomSetupPanel — player count', () => {
  beforeEach(() => {
    h.updateBoardMock.mockReset().mockResolvedValue({
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
    const wrapper = await mountSetup()
    const increment = wrapper.find('[data-testid="player-count-increment"]')
    for (let i = 0; i < 10; i++) await increment.trigger('click')

    expect(wrapper.find('[data-testid="player-count-value"]').text()).toBe('15')
    expect(increment.attributes('disabled')).toBeDefined()
    expect(wrapper.find('.stepper-range').text()).toBe('6 – 15')
  })

  it('a 15-player room defaults to 5 wolves and is sent to the backend', async () => {
    const wrapper = await mountSetup()
    for (let i = 0; i < 6; i++)
      await wrapper.find('[data-testid="player-count-increment"]').trigger('click')
    expect(wrapper.find('[data-testid="wolf-count-value"]').text()).toBe('5')

    await wrapper.find('[data-testid="room-setup-done"]').trigger('click')
    await flushPromises()

    expect(h.updateBoardMock).toHaveBeenCalledWith(
      '1',
      expect.objectContaining({ totalPlayers: 15, wolfCount: 5 }),
    )
  })
})
