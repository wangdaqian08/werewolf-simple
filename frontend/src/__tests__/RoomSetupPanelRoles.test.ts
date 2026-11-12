/**
 * RoomSetupPanel role cards (page 2, 選擇角色): tap = on/off, grey = off, colour = on.
 * 白狼王 takes one of the wolf seats, so enabling it never costs a villager seat.
 * A card that cannot change is aria-disabled, and tapping it shows the reason.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import RoomSetupPanel from '@/components/RoomSetupPanel.vue'
import type { Room } from '@/types'

const h = vi.hoisted(() => ({ updateBoardMock: vi.fn(), messageMock: vi.fn() }))

vi.mock('@/services/roomService', () => ({
  roomService: { updateBoard: h.updateBoardMock },
}))
vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: h.messageMock,
}))

async function mountSetup(
  players = 1,
  roles: string[] = ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER'],
) {
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
      roles,
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

type Wrapper = Awaited<ReturnType<typeof mountSetup>>
const card = (w: Wrapper, id: string) => w.find(`[data-testid="role-toggle-${id}"]`)
const disabled = (w: Wrapper, id: string) =>
  w.find(`[data-testid="${id}"]`).attributes('aria-disabled') === 'true'
const lastMessage = () => h.messageMock.mock.lastCall?.[0].message

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
    expect(kingToggle.attributes('aria-disabled')).toBe('false')
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

  // The seer and the medium exclude each other like every other pair: grey out, no auto-swap.
  it('MEDIUM is blocked while SEER is on; tapping it shows why and changes nothing', async () => {
    const w = await mountSetup() // SEER is on
    expect(disabled(w, 'role-toggle-MEDIUM')).toBe(true)
    expect(w.find('[data-testid="role-hint-MEDIUM"]').text()).toBe('⛓ 互斥')
    expect(w.find('[data-testid="role-hint-SEER"]').text()).toBe('排除 通靈師')
    await click(w, 'role-toggle-MEDIUM')
    expect(chipCount(w, 'MEDIUM')).toBe(0)
    expect(chipCount(w, 'SEER')).toBe(1)
    expect(lastMessage()).toBe('通靈師與預言家不能同時選，請先關閉預言家')
  })

  it('turning SEER off frees MEDIUM; with MEDIUM on, SEER is the blocked one', async () => {
    const w = await mountSetup()
    await click(w, 'role-toggle-SEER') // off
    expect(disabled(w, 'role-toggle-MEDIUM')).toBe(false)
    expect(w.find('[data-testid="role-hint-MEDIUM"]').exists()).toBe(false)
    await click(w, 'role-toggle-MEDIUM')
    expect(chipCount(w, 'MEDIUM')).toBe(1)
    expect(disabled(w, 'role-toggle-SEER')).toBe(true)
    expect(w.find('[data-testid="role-hint-SEER"]').text()).toBe('⛓ 互斥')
  })

  it('with neither on, the god maximum still counts the seer and the medium as one', async () => {
    const w = await mountSetup()
    for (let i = 0; i < 6; i++) await click(w, 'player-count-increment') // 15 players, 5 wolves
    await click(w, 'role-toggle-GUARD')
    await click(w, 'role-toggle-IDIOT') // 5 gods, so the seer may leave (minimum 4)
    await click(w, 'role-toggle-SEER')
    expect(chipCount(w, 'SEER')).toBe(0)
    expect(w.find('[data-testid="god-range"]').text()).toContain('4–6')
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
    expect(w.find('[data-testid="role-hint-MECHANICAL_WOLF"]').text()).toBe('🔒')
    expect(card(w, 'MECHANICAL_WOLF').attributes('title')).toBe('9 人及以上才能開啟')
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

describe('RoomSetupPanel — 机械狼 and 騎士 never share a board', () => {
  beforeEach(() => h.updateBoardMock.mockReset())
  afterEach(() => vi.clearAllMocks())

  const click = (w: Awaited<ReturnType<typeof mountSetup>>, id: string) =>
    w.find(`[data-testid="${id}"]`).trigger('click')
  const hint = (w: Wrapper, id: string) => w.find(`[data-testid="role-hint-${id}"]`)

  it('KNIGHT is blocked while MECHANICAL_WOLF is on; tapping it shows why', async () => {
    const w = await mountSetup()
    await click(w, 'role-toggle-MECHANICAL_WOLF')
    expect(disabled(w, 'role-toggle-KNIGHT')).toBe(true)
    expect(card(w, 'KNIGHT').element.parentElement?.classList).toContain('card-blocked')
    expect(hint(w, 'KNIGHT').text()).toBe('⛓ 互斥')
    expect(hint(w, 'MECHANICAL_WOLF').text()).toBe('排除 騎士、守墓人')
    await click(w, 'role-toggle-KNIGHT')
    expect(chipCount(w, 'KNIGHT')).toBe(0)
    expect(lastMessage()).toBe('騎士與機械狼不能同時選，請先關閉機械狼')
  })

  it('MECHANICAL_WOLF is blocked while KNIGHT is on; tapping it shows why', async () => {
    const w = await mountSetup()
    await click(w, 'role-toggle-KNIGHT')
    expect(disabled(w, 'role-toggle-MECHANICAL_WOLF')).toBe(true)
    expect(hint(w, 'MECHANICAL_WOLF').text()).toBe('⛓ 互斥')
    await click(w, 'role-toggle-MECHANICAL_WOLF')
    expect(chipCount(w, 'MECHANICAL_WOLF')).toBe(0)
    expect(lastMessage()).toBe('機械狼與騎士不能同時選，請先關閉騎士')
  })

  it('the god maximum leaves out KNIGHT while MECHANICAL_WOLF is on', async () => {
    const w = await mountSetup()
    for (let i = 0; i < 6; i++) await click(w, 'player-count-increment') // 15 players, 5 wolves
    expect(w.find('[data-testid="god-range"]').text()).toContain('4–6')
    await click(w, 'role-toggle-MECHANICAL_WOLF')
    expect(w.find('[data-testid="god-range"]').text()).toContain('4–5')
  })

  it('a stored room with both: an ON KNIGHT at the god minimum gets the minimum tooltip', async () => {
    // Saved before the two became exclusive: gods SEER + KNIGHT = the minimum for 3 wolves.
    const w = await mountSetup(1, ['WEREWOLF', 'VILLAGER', 'MECHANICAL_WOLF', 'SEER', 'KNIGHT'])
    expect(disabled(w, 'role-toggle-KNIGHT')).toBe(true)
    expect(w.find('[data-testid="role-toggle-KNIGHT"]').attributes('title')).toBe(
      '神職不能少於 2 個',
    )
    // The board itself is still flagged: each card names the role it excludes.
    expect(hint(w, 'KNIGHT').text()).toBe('排除 機械狼、守墓人')
    expect(hint(w, 'MECHANICAL_WOLF').text()).toBe('排除 騎士、守墓人')
  })

  it('turning one off frees the other', async () => {
    const w = await mountSetup()
    await click(w, 'role-toggle-KNIGHT')
    await click(w, 'role-toggle-KNIGHT') // off again
    expect(disabled(w, 'role-toggle-MECHANICAL_WOLF')).toBe(false)
    expect(hint(w, 'KNIGHT').exists()).toBe(false)
    expect(hint(w, 'MECHANICAL_WOLF').exists()).toBe(false)
  })
})

describe('RoomSetupPanel — 机械狼 and 守墓人 never share a board', () => {
  beforeEach(() => h.updateBoardMock.mockReset())
  afterEach(() => vi.clearAllMocks())

  const click = (w: Wrapper, id: string) => w.find(`[data-testid="${id}"]`).trigger('click')
  const hint = (w: Wrapper, id: string) => w.find(`[data-testid="role-hint-${id}"]`)

  it('GRAVEKEEPER is blocked while MECHANICAL_WOLF is on', async () => {
    const w = await mountSetup()
    await click(w, 'role-toggle-MECHANICAL_WOLF')
    expect(disabled(w, 'role-toggle-GRAVEKEEPER')).toBe(true)
    expect(card(w, 'GRAVEKEEPER').element.parentElement?.classList).toContain('card-blocked')
    expect(hint(w, 'GRAVEKEEPER').text()).toBe('⛓ 互斥')
    await click(w, 'role-toggle-GRAVEKEEPER')
    expect(chipCount(w, 'GRAVEKEEPER')).toBe(0)
    expect(lastMessage()).toBe('守墓人與機械狼不能同時選，請先關閉機械狼')
  })

  it('MECHANICAL_WOLF is blocked while GRAVEKEEPER is on', async () => {
    const w = await mountSetup()
    await click(w, 'role-toggle-GRAVEKEEPER')
    expect(disabled(w, 'role-toggle-MECHANICAL_WOLF')).toBe(true)
    expect(hint(w, 'MECHANICAL_WOLF').text()).toBe('⛓ 互斥')
    expect(hint(w, 'GRAVEKEEPER').text()).toBe('排除 機械狼、騎士')
    await click(w, 'role-toggle-MECHANICAL_WOLF')
    expect(chipCount(w, 'MECHANICAL_WOLF')).toBe(0)
    expect(lastMessage()).toBe('機械狼與守墓人不能同時選，請先關閉守墓人')
  })

  it('a stored room with KNIGHT and GRAVEKEEPER both on: the MECHANICAL_WOLF hint names both', async () => {
    // Saved before the knight and the gravekeeper became exclusive.
    const w = await mountSetup(1, [
      'WEREWOLF',
      'VILLAGER',
      'SEER',
      'WITCH',
      'KNIGHT',
      'GRAVEKEEPER',
    ])
    expect(card(w, 'MECHANICAL_WOLF').attributes('title')).toBe(
      '機械狼與騎士、守墓人不能同時選，請先關閉騎士、守墓人',
    )
    await click(w, 'role-toggle-KNIGHT') // off: only the gravekeeper still blocks it
    expect(card(w, 'MECHANICAL_WOLF').attributes('title')).toBe(
      '機械狼與守墓人不能同時選，請先關閉守墓人',
    )
  })

  it('GRAVEKEEPER is blocked while KNIGHT is on; tapping it shows why', async () => {
    const w = await mountSetup()
    await click(w, 'role-toggle-KNIGHT')
    expect(disabled(w, 'role-toggle-GRAVEKEEPER')).toBe(true)
    expect(hint(w, 'GRAVEKEEPER').text()).toBe('⛓ 互斥')
    await click(w, 'role-toggle-GRAVEKEEPER')
    expect(chipCount(w, 'GRAVEKEEPER')).toBe(0)
    expect(lastMessage()).toBe('守墓人與騎士不能同時選，請先關閉騎士')
  })

  it('KNIGHT is blocked while GRAVEKEEPER is on; turning the gravekeeper off frees it', async () => {
    const w = await mountSetup()
    await click(w, 'role-toggle-GRAVEKEEPER')
    expect(disabled(w, 'role-toggle-KNIGHT')).toBe(true)
    await click(w, 'role-toggle-KNIGHT')
    expect(chipCount(w, 'KNIGHT')).toBe(0)
    expect(lastMessage()).toBe('騎士與守墓人不能同時選，請先關閉守墓人')
    await click(w, 'role-toggle-GRAVEKEEPER') // off
    expect(disabled(w, 'role-toggle-KNIGHT')).toBe(false)
  })
})

describe('RoomSetupPanel — 騎士', () => {
  beforeEach(() => h.updateBoardMock.mockReset().mockResolvedValue({ config: {}, players: [] }))
  afterEach(() => vi.clearAllMocks())

  const savedRoles = async (w: Awaited<ReturnType<typeof mountSetup>>) => {
    await w.find('[data-testid="room-setup-done"]').trigger('click')
    await flushPromises()
    return h.updateBoardMock.mock.lastCall?.[1].roles as string[]
  }

  it('KNIGHT toggle shows in room setup and is sent in roles', async () => {
    const w = await mountSetup()
    await w.find('[data-testid="role-toggle-KNIGHT"]').trigger('click')
    expect(chipCount(w, 'KNIGHT')).toBe(1)
    expect(await savedRoles(w)).toContain('KNIGHT')
  })

  it('the GRAVEKEEPER toggle turns on and is sent in the room config', async () => {
    const w = await mountSetup()
    await w.find('[data-testid="role-toggle-GRAVEKEEPER"]').trigger('click')
    expect(chipCount(w, 'GRAVEKEEPER')).toBe(1)
    expect(await savedRoles(w)).toContain('GRAVEKEEPER')
  })

  it('default board for each player count is unchanged', async () => {
    const boards: Record<number, string[]> = {}
    for (const n of [6, 7, 8, 9, 10, 11, 12, 13, 14, 15]) {
      h.updateBoardMock.mockClear()
      const w = await mountSetup()
      for (let c = 9; c < n; c++)
        await w.find('[data-testid="player-count-increment"]').trigger('click')
      for (let c = 9; c > n; c--)
        await w.find('[data-testid="player-count-decrement"]').trigger('click')
      boards[n] = await savedRoles(w)
    }
    const base = ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER']
    // Recorded before KNIGHT existed: KNIGHT is appended last, so no default board gains it.
    expect(boards).toEqual({
      6: base,
      7: base,
      8: base,
      9: base,
      10: base,
      11: base,
      12: base,
      13: base,
      14: base,
      15: [...base, 'GUARD'],
    })
  })
})

describe('RoomSetupPanel — 選擇角色 page', () => {
  beforeEach(() => h.updateBoardMock.mockReset())
  afterEach(() => vi.clearAllMocks())

  const page = (w: Wrapper, id: string) => w.find(`[data-testid="${id}"]`).element as HTMLElement
  const cardClasses = (w: Wrapper, id: string) => card(w, id).element.parentElement?.classList

  it('opens from players & roles and returns with 確定', async () => {
    const w = await mountSetup()
    expect(page(w, 'role-select-page').style.display).toBe('none')
    await w.find('[data-testid="open-role-select"]').trigger('click')
    expect(page(w, 'role-select-page').style.display).toBe('')
    await w.find('[data-testid="role-select-done"]').trigger('click')
    expect(page(w, 'role-select-page').style.display).toBe('none')
  })

  it('each page opens scrolled to the top', async () => {
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    const w = await mountSetup()
    await w.find('[data-testid="open-role-select"]').trigger('click')
    expect(scrollTo).toHaveBeenLastCalledWith(0, 0)
    scrollTo.mockClear()
    await w.find('[data-testid="role-select-done"]').trigger('click')
    expect(scrollTo).toHaveBeenLastCalledWith(0, 0)
  })

  it('a card turns from grey to colour when tapped on, and back when tapped again', async () => {
    const w = await mountSetup()
    expect(cardClasses(w, 'GUARD')).toContain('card-off')
    await card(w, 'GUARD').trigger('click')
    expect(cardClasses(w, 'GUARD')).toContain('card-on')
    expect(cardClasses(w, 'GUARD')).toContain('camp-god')
    expect(card(w, 'GUARD').attributes('aria-pressed')).toBe('true')
    await card(w, 'GUARD').trigger('click')
    expect(cardClasses(w, 'GUARD')).toContain('card-off')
    expect(card(w, 'GUARD').attributes('aria-pressed')).toBe('false')
  })

  it('a locked card is deeper grey, and tapping it shows the reason', async () => {
    const w = await mountSetup()
    await w.find('[data-testid="player-count-decrement"]').trigger('click') // 8 players
    expect(cardClasses(w, 'WHITE_WOLF_KING')).toContain('card-locked')
    await card(w, 'WHITE_WOLF_KING').trigger('click')
    expect(lastMessage()).toBe('9 人及以上才能開啟')
    expect(chipCount(w, 'WHITE_WOLF_KING')).toBe(0)
  })

  it('a selected god at the minimum stays on and says why', async () => {
    const w = await mountSetup()
    await card(w, 'HUNTER').trigger('click') // 2 gods: the minimum for 3 wolves
    await card(w, 'WITCH').trigger('click')
    expect(chipCount(w, 'WITCH')).toBe(1)
    expect(lastMessage()).toBe('神職不能少於 2 個')
  })

  it('a required card opens its role info instead of toggling', async () => {
    const w = await mountSetup()
    await card(w, 'WEREWOLF').trigger('click')
    expect(document.body.querySelector('[data-testid="role-card-sheet"]')).not.toBeNull()
    expect(h.messageMock).not.toHaveBeenCalled()
    w.unmount()
  })

  it('the count bar shows players, wolves, gods and the villagers left', async () => {
    const w = await mountSetup() // 9 players, 3 wolves, 3 gods
    expect(w.find('[data-testid="role-count-bar"]').text().replace(/\s+/g, ' ')).toBe(
      '9 人 · 狼人 3 · 神職 3（2–4） · 村民 3',
    )
    expect(w.find('[data-testid="role-hint-VILLAGER"]').text()).toBe('×3 自動補')
  })
})
