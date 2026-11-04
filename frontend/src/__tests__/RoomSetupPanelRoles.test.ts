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

describe('RoomSetupPanel — 机械狼 and 通灵师', () => {
  beforeEach(() => h.updateBoardMock.mockReset())
  afterEach(() => vi.clearAllMocks())

  const click = (w: Awaited<ReturnType<typeof mountSetup>>, id: string) =>
    w.find(`[data-testid="${id}"]`).trigger('click')
  const disabled = (w: Awaited<ReturnType<typeof mountSetup>>, id: string) =>
    w.find(`[data-testid="${id}"]`).attributes('disabled') !== undefined

  it('enabling MEDIUM turns SEER off, and the reverse', async () => {
    const w = await mountSetup() // SEER is on
    await click(w, 'role-toggle-MEDIUM')
    expect(chipCount(w, 'MEDIUM')).toBe(1)
    expect(chipCount(w, 'SEER')).toBe(0)
    await click(w, 'role-toggle-SEER')
    expect(chipCount(w, 'SEER')).toBe(1)
    expect(chipCount(w, 'MEDIUM')).toBe(0)
  })

  it('swapping SEER for MEDIUM is allowed at the god maximum', async () => {
    const w = await mountSetup()
    await click(w, 'role-toggle-GUARD') // 4 gods: the maximum for 3 wolves
    expect(disabled(w, 'role-toggle-MEDIUM')).toBe(false)
    await click(w, 'role-toggle-MEDIUM')
    expect(chipCount(w, 'MEDIUM')).toBe(1)
    expect(chipCount(w, 'SEER')).toBe(0)
  })

  it('the seer/medium exclusivity hint shows only under a row whose partner is enabled', async () => {
    const w = await mountSetup() // SEER on, MEDIUM off
    expect(w.find('[data-testid="role-hint-SEER"]').exists()).toBe(false)
    expect(w.find('[data-testid="role-hint-MEDIUM"]').text()).toContain('不能同時選')
    await click(w, 'role-toggle-MEDIUM') // swaps SEER off
    expect(w.find('[data-testid="role-hint-MEDIUM"]').exists()).toBe(false)
    expect(w.find('[data-testid="role-hint-SEER"]').text()).toContain('不能同時選')
  })

  it('mechanical wolf needs 9+ players', async () => {
    const w = await mountSetup() // 9 players, 3 wolves
    expect(disabled(w, 'role-toggle-MECHANICAL_WOLF')).toBe(false)
    await click(w, 'role-toggle-MECHANICAL_WOLF')
    expect(chipCount(w, 'MECHANICAL_WOLF')).toBe(1)
    expect(chipCount(w, 'WEREWOLF')).toBe(2)
    await click(w, 'player-count-decrement') // 8 players: dropped
    expect(chipCount(w, 'MECHANICAL_WOLF')).toBe(0)
    expect(disabled(w, 'role-toggle-MECHANICAL_WOLF')).toBe(true)
    expect(w.find('[data-testid="role-hint-MECHANICAL_WOLF"]').text()).toContain(
      '9 人及以上才能開啟',
    )
  })

  it('mechanical wolf is allowed with 2 wolves, and counts toward the special-wolf limit', async () => {
    const w = await mountSetup()
    await click(w, 'role-toggle-HUNTER') // 2 gods, so wolves can drop to 2
    await click(w, 'wolf-count-decrement')
    expect(disabled(w, 'role-toggle-MECHANICAL_WOLF')).toBe(false)
    await click(w, 'role-toggle-MECHANICAL_WOLF')
    expect(disabled(w, 'role-toggle-BLACK_WOLF_KING')).toBe(true) // 2 wolves = 2 specials
  })
})
