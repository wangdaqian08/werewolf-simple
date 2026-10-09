/**
 * CreateRoomView role limits, derived from the player count:
 *   gods ∈ [wolves − 1, wolves + 1] (at most 5), 白狼王 needs 9+ players.
 * The screen never shows an invalid board: blocked edits are disabled, and a
 * player-count change pulls the roles back into range.
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

type Wrapper = Awaited<ReturnType<typeof mountCreateRoom>>

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

const click = (w: Wrapper, testId: string, times = 1) =>
  Array.from({ length: times }).reduce<Promise<void>>(
    (p) => p.then(() => w.find(`[data-testid="${testId}"]`).trigger('click')),
    Promise.resolve(),
  )
const disabled = (w: Wrapper, testId: string) =>
  w.find(`[data-testid="${testId}"]`).attributes('disabled') !== undefined
const chip = (w: Wrapper, role: string) => {
  const el = w.find(`[data-testid="role-composition"] [data-role="${role}"]`)
  return el.exists() ? Number(el.attributes('data-count')) : 0
}
const gods = (w: Wrapper) =>
  ['SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT'].reduce((n, r) => n + chip(w, r), 0)

describe('CreateRoomView — role limits', () => {
  beforeEach(() => h.createRoomMock.mockReset())
  afterEach(() => vi.clearAllMocks())

  it('shows the allowed number of gods for the current board', async () => {
    const w = await mountCreateRoom() // 9 players, 3 wolves
    expect(w.find('[data-testid="god-range"]').text()).toContain('2–4')
  })

  it('gods cannot exceed wolves + 1', async () => {
    const w = await mountCreateRoom() // 3 wolves + 预言家/女巫/猎人
    await click(w, 'role-toggle-GUARD')
    expect(gods(w)).toBe(4)
    expect(disabled(w, 'role-toggle-IDIOT')).toBe(true)
  })

  it('gods cannot drop below wolves − 1', async () => {
    const w = await mountCreateRoom()
    await click(w, 'role-toggle-HUNTER')
    expect(gods(w)).toBe(2)
    expect(disabled(w, 'role-toggle-SEER')).toBe(true)
    expect(disabled(w, 'role-toggle-WITCH')).toBe(true)
    expect(disabled(w, 'role-toggle-GUARD')).toBe(false)
  })

  it('cannot add a wolf while that would leave too few gods', async () => {
    const w = await mountCreateRoom()
    await click(w, 'player-count-increment', 3) // 12 players, 4 wolves, 3 gods
    await click(w, 'wolf-count-decrement') // 3 wolves
    await click(w, 'role-toggle-HUNTER') // 2 gods (minimum for 3 wolves)
    expect(disabled(w, 'wolf-count-increment')).toBe(true)
  })

  it('cannot remove a wolf while that would leave too many gods', async () => {
    const w = await mountCreateRoom()
    await click(w, 'role-toggle-GUARD') // 3 wolves, 4 gods
    expect(disabled(w, 'wolf-count-decrement')).toBe(true)
  })

  it('fewer players drops gods from the end of the list to fit', async () => {
    const w = await mountCreateRoom()
    await click(w, 'player-count-increment', 3) // 12 players, 4 wolves
    await click(w, 'role-toggle-GUARD')
    await click(w, 'role-toggle-IDIOT') // 5 gods
    await click(w, 'player-count-decrement', 3) // 9 players, 3 wolves → max 4 gods
    expect(gods(w)).toBe(4)
    expect(chip(w, 'IDIOT')).toBe(0)
  })

  it('more players adds gods from the front of the list to fit', async () => {
    const w = await mountCreateRoom()
    await click(w, 'role-toggle-HUNTER') // 2 gods
    await click(w, 'player-count-increment', 3) // 12 players, 4 wolves → min 3 gods
    expect(gods(w)).toBe(3)
    expect(chip(w, 'HUNTER')).toBe(1)
  })

  it('白狼王 needs 9 or more players', async () => {
    const w = await mountCreateRoom()
    expect(disabled(w, 'role-toggle-WHITE_WOLF_KING')).toBe(false)
    await click(w, 'player-count-decrement')
    expect(disabled(w, 'role-toggle-WHITE_WOLF_KING')).toBe(true)
  })

  it('白狼王 row tells the host it needs 9 or more players', async () => {
    const w = await mountCreateRoom()
    await click(w, 'player-count-decrement') // 8 players: the toggle is disabled
    expect(w.find('[data-testid="role-hint-WHITE_WOLF_KING"]').text()).toContain(
      '9 人及以上才能开启',
    )
  })

  it('白狼王 switches off when the board drops below 9 players', async () => {
    const w = await mountCreateRoom()
    await click(w, 'role-toggle-WHITE_WOLF_KING')
    expect(chip(w, 'WHITE_WOLF_KING')).toBe(1)
    await click(w, 'player-count-decrement')
    expect(chip(w, 'WHITE_WOLF_KING')).toBe(0)
  })
})
