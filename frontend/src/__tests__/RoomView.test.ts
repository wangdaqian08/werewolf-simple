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
  await router.push('/room/1')
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
