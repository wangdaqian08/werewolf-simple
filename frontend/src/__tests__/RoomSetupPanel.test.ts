/**
 * RoomSetupPanel — the host's "players & roles" page for a room that already
 * exists: it edits the live room's board, so it starts from the room's config
 * and can't drop below the players who have already joined.
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { AxiosError, AxiosHeaders } from 'axios'
import RoomSetupPanel from '@/components/RoomSetupPanel.vue'
import { useRoomStore } from '@/stores/roomStore'
import type { Room } from '@/types'

const h = vi.hoisted(() => ({ updateBoardMock: vi.fn() }))

vi.mock('@/services/roomService', () => ({
  roomService: { updateBoard: h.updateBoardMock },
}))

function room(over: Partial<Room['config']> = {}, joined = 1): Room {
  return {
    roomId: '1',
    roomCode: '123',
    hostId: 'u1',
    status: 'WAITING',
    config: {
      totalPlayers: 9,
      wolfCount: 3,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER'],
      ...over,
    },
    players: Array.from({ length: joined }, (_, i) => ({
      userId: `u${i + 1}`,
      nickname: `P${i + 1}`,
      seatIndex: null,
      status: 'NOT_READY' as const,
      isHost: i === 0,
    })),
  } as Room
}

async function mountSetup(r: Room) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(RoomSetupPanel, { props: { room: r }, global: { plugins: [pinia] } })
  await flushPromises()
  return wrapper
}

const text = (w: Awaited<ReturnType<typeof mountSetup>>, id: string) =>
  w.find(`[data-testid="${id}"]`).text()

describe('RoomSetupPanel', () => {
  afterEach(() => vi.clearAllMocks())

  it("starts from the room's current board", async () => {
    const w = await mountSetup(
      room({ totalPlayers: 12, wolfCount: 4, roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'GUARD'] }),
    )
    expect(text(w, 'player-count-value')).toBe('12')
    expect(text(w, 'wolf-count-value')).toBe('4')
    expect(w.find('[data-testid="role-toggle-GUARD"]').classes()).toContain('toggle-on')
    expect(w.find('[data-testid="role-toggle-HUNTER"]').classes()).toContain('toggle-off')
  })

  it('shows the room code, how many have joined, and a share button', async () => {
    const w = await mountSetup(room({}, 7))
    expect(text(w, 'setup-room-code')).toBe('123')
    expect(text(w, 'setup-joined-count')).toContain('7')
    expect(w.find('[data-testid="share-room-btn"]').exists()).toBe(true)
  })

  it('cannot go below the players who have joined', async () => {
    const w = await mountSetup(room({}, 7))
    for (let i = 0; i < 5; i++)
      await w.find('[data-testid="player-count-decrement"]').trigger('click')
    expect(text(w, 'player-count-value')).toBe('7')
    expect(w.find('[data-testid="player-count-decrement"]').attributes('disabled')).toBeDefined()
  })

  it('saves the board, stores the updated room, then emits done', async () => {
    const updated = room({ totalPlayers: 10 })
    h.updateBoardMock.mockResolvedValue(updated)
    const w = await mountSetup(room())
    await w.find('[data-testid="player-count-increment"]').trigger('click')

    await w.find('[data-testid="room-setup-done"]').trigger('click')
    await flushPromises()

    expect(h.updateBoardMock).toHaveBeenCalledWith('1', {
      totalPlayers: 10,
      wolfCount: 3,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER'],
    })
    expect(useRoomStore().room?.config.totalPlayers).toBe(10)
    expect(w.emitted('done')).toHaveLength(1)
  })

  it("shows the backend's reason and stays open when the save is refused", async () => {
    h.updateBoardMock.mockRejectedValue(
      new AxiosError('400', 'ERR_BAD_REQUEST', undefined, undefined, {
        status: 400,
        statusText: 'Bad Request',
        data: { error: 'Seat 9 is taken' },
        headers: {},
        config: { headers: new AxiosHeaders() },
      }),
    )
    const w = await mountSetup(room())

    await w.find('[data-testid="room-setup-done"]').trigger('click')
    await flushPromises()

    expect(text(w, 'room-setup-error')).toBe('Seat 9 is taken')
    expect(w.emitted('done')).toBeUndefined()
  })
})
