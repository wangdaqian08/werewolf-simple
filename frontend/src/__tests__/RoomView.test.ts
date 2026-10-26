/**
 * Component test for RoomView's STOMP reconnect recovery.
 *
 * Guards the fix for the reported bug: a ROOM_UPDATE (ready/seat/kick) that lands
 * while the host's socket is briefly down is dropped by the broker, so on
 * reconnect RoomView MUST re-fetch the WAITING-room player list — otherwise the
 * ready count / Start button stays stale. This runs in the fast unit job (the
 * full repro lives in e2e/real/room-ready-sync.spec.ts).
 *
 * Strategy: mock the STOMP client so we can capture and invoke its `onConnect`
 * handler. The 1st call is the initial connect (no refetch); the 2nd is a
 * reconnect, which must call roomService.getRoom and adopt the fresh players.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import RoomView from '@/views/RoomView.vue'
import { useRoomStore } from '@/stores/roomStore'
import type { Room, RoomPlayer } from '@/types'
import { roomService } from '@/services/roomService.ts'

const h = vi.hoisted(() => {
  const fakeClient = {
    onConnect: null as null | (() => void),
    active: true,
    activate: vi.fn(),
    forceDisconnect: vi.fn(),
  }
  return {
    fakeClient,
    createStompClientMock: vi.fn(() => fakeClient),
    subscribeToTopicMock: vi.fn(),
    getRoomMock: vi.fn(),
  }
})

vi.mock('@/services/stompClient', () => ({
  createStompClient: h.createStompClientMock,
  subscribeToTopic: h.subscribeToTopicMock,
  getStompClient: () => h.fakeClient,
  disconnectStomp: vi.fn(),
}))
vi.mock('@/services/roomService', () => ({
  roomService: {
    getRoom: h.getRoomMock,
    claimSeat: vi.fn(),
    setReady: vi.fn(),
    leaveRoom: vi.fn(),
    kickPlayer: vi.fn(),
  },
}))
vi.mock('@/services/gameService', () => ({ gameService: { startGame: vi.fn() } }))
vi.mock('@/services/http', () => ({ default: { post: vi.fn() } }))
vi.mock('@/composables/useNavigationGuard', () => ({ useNavigationGuard: vi.fn() }))
vi.mock('@/composables/useConnectionLifecycle', () => ({ useConnectionLifecycle: vi.fn() }))

function makeJwt(expSecondsFromNow: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + expSecondsFromNow }))
  return `${header}.${payload}.sig`
}

function rp(userId: string, seatIndex: number, opts: Partial<RoomPlayer> = {}): RoomPlayer {
  return { userId, nickname: userId, seatIndex, status: 'NOT_READY', isHost: false, ...opts }
}

// host (seated) + 3 READY guests in a 4-seat room → all ready, Start enabled.
function allReadyRoom(): Room {
  return {
    roomId: '1',
    roomCode: '123',
    hostId: 'u-host',
    status: 'WAITING',
    config: { totalPlayers: 4, wolfCount: 2, roles: ['WEREWOLF', 'VILLAGER'] },
    players: [
      rp('u-host', 1, { nickname: 'Host', isHost: true }),
      rp('u2', 2, { nickname: 'Alice', status: 'READY' }),
      rp('u3', 3, { nickname: 'Bob', status: 'READY' }),
      rp('u4', 4, { nickname: 'Carol', status: 'READY' }),
    ],
  }
}

async function mountRoom(
  myUserId: string,
  room: Room = allReadyRoom(),
  path = '/room/1',
): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  localStorage.setItem('jwt', makeJwt(3600))
  localStorage.setItem('userId', myUserId)
  localStorage.setItem('nickname', myUserId)

  const pinia = createPinia()
  setActivePinia(pinia)
  // Pre-seed the room so onMounted does not fetch it (we control the reconnect fetch).
  useRoomStore().setRoom(room)

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: { template: '<div />' } },
      { path: '/room/:roomId', name: 'room', component: RoomView },
      { path: '/game/:gameId', name: 'game', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(RoomView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

// Fire the captured onConnect handler N times (1st = initial connect, 2nd = reconnect).
async function fireConnect() {
  h.fakeClient.onConnect?.()
  await flushPromises()
}

describe('RoomView — STOMP reconnect recovers missed ROOM_UPDATEs', () => {
  beforeEach(() => {
    localStorage.clear()
    h.fakeClient.onConnect = null
    h.fakeClient.active = true
    h.getRoomMock.mockReset()
    h.subscribeToTopicMock.mockReset()
  })
  afterEach(() => vi.clearAllMocks())

  it('host: a ready-change missed during the disconnect is recovered on reconnect (Start re-disables)', async () => {
    const { wrapper } = await mountRoom('u-host')
    const startBtn = () => wrapper.find('button.btn-primary').element as HTMLButtonElement

    // Initially all ready → Start enabled.
    expect(startBtn().disabled).toBe(false)
    expect(wrapper.find('.count-ready').text()).toBe('4')

    // Initial connect must NOT refetch the room.
    await fireConnect()
    expect(h.getRoomMock).not.toHaveBeenCalled()

    // While "disconnected", a guest un-readied; the reconnect fetch sees it.
    const stale = allReadyRoom()
    stale.players = stale.players.map((p) =>
      p.userId === 'u4' ? { ...p, status: 'NOT_READY' } : p,
    )
    h.getRoomMock.mockResolvedValue(stale)

    // Reconnect → must refetch + adopt the fresh players.
    await fireConnect()
    expect(h.getRoomMock).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.count-ready').text()).toBe('3')
    expect(startBtn().disabled).toBe(true)
  })

  it('guest: the ready count is re-synced on reconnect too (same RoomView path)', async () => {
    const { wrapper } = await mountRoom('u2') // a guest, not the host
    expect(wrapper.find('button.btn-primary').exists()).toBe(false) // guests have no Start button
    expect(wrapper.find('.count-ready').text()).toBe('4')

    await fireConnect() // initial
    const stale = allReadyRoom()
    stale.players = stale.players.map((p) =>
      p.userId === 'u4' ? { ...p, status: 'NOT_READY' } : p,
    )
    h.getRoomMock.mockResolvedValue(stale)

    await fireConnect() // reconnect
    expect(h.getRoomMock).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.count-ready').text()).toBe('3')
  })

  it('host: a seat change missed during the disconnect is recovered on reconnect', async () => {
    // 5-seat room with one empty seat (seat 5) so a guest can move into it.
    const room: Room = {
      ...allReadyRoom(),
      config: { totalPlayers: 5, wolfCount: 2, roles: ['WEREWOLF', 'VILLAGER'] },
    }
    const { wrapper } = await mountRoom('u-host', room)
    expect(wrapper.find('[data-seat="4"]').text()).toContain('Carol')

    await fireConnect() // initial
    const moved: Room = {
      ...room,
      players: room.players.map((p) => (p.userId === 'u4' ? { ...p, seatIndex: 5 } : p)),
    }
    h.getRoomMock.mockResolvedValue(moved)

    await fireConnect() // reconnect
    expect(h.getRoomMock).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-seat="5"]').text()).toContain('Carol')
    expect(wrapper.find('[data-seat="4"]').text()).not.toContain('Carol')
  })

  it('host: a player removed (kick/leave) during the disconnect is recovered on reconnect', async () => {
    const { wrapper } = await mountRoom('u-host')
    expect(wrapper.text()).toContain('Carol')

    await fireConnect() // initial
    const fewer = allReadyRoom()
    fewer.players = fewer.players.filter((p) => p.userId !== 'u4') // Carol kicked
    h.getRoomMock.mockResolvedValue(fewer)

    await fireConnect() // reconnect
    expect(h.getRoomMock).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).not.toContain('Carol')
    expect(wrapper.find('.count-ready').text()).toBe('3')
  })
})

describe('RoomView — members without a seat', () => {
  // A joiner who closes the app before sitting used to be invisible: they held
  // a place (the last player got "Room is full") and nobody could kick them.
  function roomWithGhost(): Room {
    const room = allReadyRoom()
    return {
      ...room,
      config: { ...room.config, totalPlayers: 5 },
      players: [...room.players, rp('u-ghost', 0, { nickname: 'Kevin', seatIndex: null })],
    }
  }

  beforeEach(() => {
    localStorage.clear()
    h.fakeClient.onConnect = null
    h.subscribeToTopicMock.mockReset()
    vi.mocked(roomService.kickPlayer).mockReset()
  })
  afterEach(() => vi.clearAllMocks())

  it.each(['u-host', 'u2'])('lists the unseated member to everyone (%s)', async (me) => {
    const { wrapper } = await mountRoom(me, roomWithGhost())

    const rows = wrapper.findAll('[data-testid="unseated-player"]')
    expect(rows.map((r) => r.text())).toEqual([expect.stringContaining('Kevin')])
  })

  it('host can kick an unseated member', async () => {
    const { wrapper } = await mountRoom('u-host', roomWithGhost())
    vi.mocked(roomService.kickPlayer).mockResolvedValueOnce(undefined)

    await wrapper.find('[data-testid="kick-unseated-u-ghost"]').trigger('click')
    await flushPromises()

    expect(roomService.kickPlayer).toHaveBeenCalledWith('1', 'u-ghost')
  })

  it('a guest gets no kick button for an unseated member', async () => {
    const { wrapper } = await mountRoom('u2', roomWithGhost())
    expect(wrapper.find('[data-testid="kick-unseated-u-ghost"]').exists()).toBe(false)
  })

  it('the unseated host is listed but never offered a kick of themselves', async () => {
    const room = roomWithGhost()
    room.players = room.players.map((p) => (p.isHost ? { ...p, seatIndex: null } : p))
    const { wrapper } = await mountRoom('u-host', room)

    expect(wrapper.findAll('[data-testid="unseated-player"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="kick-unseated-u-host"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="kick-unseated-u-ghost"]').exists()).toBe(true)
  })

  it('a member who just joined shows up at once from the ROOM_UPDATE', async () => {
    const { wrapper } = await mountRoom('u-host', {
      ...allReadyRoom(),
      config: { ...allReadyRoom().config, totalPlayers: 5 },
    })
    expect(wrapper.find('[data-testid="unseated-player"]').exists()).toBe(false)

    await fireConnect()
    const onRoomMsg = h.subscribeToTopicMock.mock.calls[0]![1] as (m: { body: string }) => void
    onRoomMsg({
      body: JSON.stringify({ type: 'ROOM_UPDATE', payload: { players: roomWithGhost().players } }),
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="unseated-player"]').text()).toContain('Kevin')
    expect(wrapper.find('[data-testid="kick-unseated-u-ghost"]').exists()).toBe(true)
  })
})

describe('RoomView — leaving the room', () => {
  beforeEach(() => {
    localStorage.clear()
    h.fakeClient.onConnect = null
    h.fakeClient.active = true
    h.getRoomMock.mockReset()
    h.subscribeToTopicMock.mockReset()
    vi.mocked(roomService.leaveRoom).mockReset()
  })
  afterEach(() => vi.clearAllMocks())

  it('clears the room and routes to lobby on a normal leave', async () => {
    const { wrapper, router } = await mountRoom('u2')
    vi.mocked(roomService.leaveRoom).mockResolvedValueOnce(undefined)

    await wrapper.find('button.back-btn').trigger('click')
    await flushPromises()

    expect(roomService.leaveRoom).toHaveBeenCalledTimes(1)
    expect(roomService.leaveRoom).toHaveBeenCalledWith('1')
    expect(router.currentRoute.value.name).toBe('lobby')
    expect(useRoomStore().room).toBeNull()
  })

  it('still routes to lobby when leaveRoom() rejects (never traps the player)', async () => {
    const { wrapper, router } = await mountRoom('u2')
    vi.mocked(roomService.leaveRoom).mockRejectedValueOnce(new Error('network 500'))

    await wrapper.find('button.back-btn').trigger('click')
    await flushPromises()

    expect(roomService.leaveRoom).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.name).toBe('lobby')
    expect(useRoomStore().room).toBeNull()
  })
})

describe('RoomView — changing players & roles', () => {
  beforeEach(() => {
    localStorage.clear()
    h.fakeClient.onConnect = null
    h.fakeClient.active = true
    h.getRoomMock.mockReset()
    h.subscribeToTopicMock.mockReset()
  })
  afterEach(() => vi.clearAllMocks())

  const roomMsg = async (payload: unknown) => {
    await fireConnect()
    const onRoomMsg = h.subscribeToTopicMock.mock.calls[0]![1] as (m: { body: string }) => void
    onRoomMsg({ body: JSON.stringify({ type: 'ROOM_UPDATE', payload }) })
    await flushPromises()
  }

  it('the host opens players & roles from the room', async () => {
    const { wrapper, router } = await mountRoom('u-host')

    await wrapper.find('[data-testid="edit-board-btn"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.setup).toBe('1')
    expect(wrapper.find('[data-testid="room-setup-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="room-seat-1"]').exists()).toBe(false)
  })

  it('closing players & roles returns to the seat grid', async () => {
    const { wrapper, router } = await mountRoom('u-host', allReadyRoom(), '/room/1?setup=1')

    wrapper.findComponent({ name: 'RoomSetupPanel' }).vm.$emit('done')
    await flushPromises()

    expect(router.currentRoute.value.query.setup).toBeUndefined()
    expect(wrapper.find('[data-testid="room-seat-1"]').exists()).toBe(true)
  })

  it('a guest has no edit button and never sees the panel', async () => {
    const { wrapper } = await mountRoom('u2', allReadyRoom(), '/room/1?setup=1')

    expect(wrapper.find('[data-testid="edit-board-btn"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="room-setup-panel"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="room-seat-1"]').exists()).toBe(true)
  })

  it('everyone gets a share button', async () => {
    const { wrapper } = await mountRoom('u2')
    expect(wrapper.find('[data-testid="share-room-btn"]').exists()).toBe(true)
  })

  it('a board change from the host redraws every seat grid', async () => {
    const { wrapper } = await mountRoom('u2')
    expect(wrapper.find('[data-testid="room-seat-5"]').exists()).toBe(false)

    await roomMsg({
      players: allReadyRoom().players,
      config: { totalPlayers: 5, wolfCount: 2, roles: ['WEREWOLF', 'VILLAGER'] },
    })

    expect(wrapper.find('[data-testid="room-seat-5"]').exists()).toBe(true)
  })

  it('a board change missed during a disconnect is picked up on reconnect', async () => {
    const { wrapper } = await mountRoom('u2')
    h.getRoomMock.mockResolvedValue({
      ...allReadyRoom(),
      config: { totalPlayers: 5, wolfCount: 2, roles: ['WEREWOLF', 'VILLAGER'] },
    })

    await fireConnect()
    await fireConnect() // reconnect → re-sync

    expect(wrapper.find('[data-testid="room-seat-5"]').exists()).toBe(true)
  })
})
