/**
 * RoomSetupPanel role toggles: 白狼王 takes one of the wolf seats, so enabling
 * it never costs a villager seat and is sent in the createRoom roles.
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

function chipCount(wrapper: Awaited<ReturnType<typeof mountSetup>>, role: string) {
  const chip = wrapper.find(`[data-testid="role-composition"] [data-role="${role}"]`)
  return chip.exists() ? Number(chip.attributes('data-count')) : 0
}

describe('RoomSetupPanel — 白狼王 toggle', () => {
  beforeEach(() => {
    h.updateBoardMock.mockReset().mockResolvedValue({
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
    const wrapper = await mountSetup()
    const wolvesBefore = chipCount(wrapper, 'WEREWOLF')
    const villagersBefore = chipCount(wrapper, 'VILLAGER')

    await wrapper.find('[data-testid="role-toggle-WHITE_WOLF_KING"]').trigger('click')

    expect(chipCount(wrapper, 'WHITE_WOLF_KING')).toBe(1)
    expect(chipCount(wrapper, 'WEREWOLF')).toBe(wolvesBefore - 1)
    expect(chipCount(wrapper, 'VILLAGER')).toBe(villagersBefore)
  })

  it('does not count against the god limit', async () => {
    const wrapper = await mountSetup()
    await wrapper.find('[data-testid="role-toggle-GUARD"]').trigger('click') // 3 wolves, 4 gods (max)
    const kingToggle = wrapper.find('[data-testid="role-toggle-WHITE_WOLF_KING"]')
    expect(kingToggle.attributes('disabled')).toBeUndefined()
    await kingToggle.trigger('click')
    expect(chipCount(wrapper, 'WHITE_WOLF_KING')).toBe(1)
  })

  it('sends WHITE_WOLF_KING in the board roles', async () => {
    const wrapper = await mountSetup()
    await wrapper.find('[data-testid="role-toggle-WHITE_WOLF_KING"]').trigger('click')

    await wrapper.find('[data-testid="room-setup-done"]').trigger('click')
    await flushPromises()

    expect(h.updateBoardMock).toHaveBeenCalledWith(
      '1',
      expect.objectContaining({ roles: expect.arrayContaining(['WHITE_WOLF_KING']) }),
    )
  })
})
