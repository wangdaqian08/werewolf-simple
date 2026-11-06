/**
 * Component tests for RoomView's role-pick panel gate (点将卡): catalog render,
 * activate/withdraw service calls + wallet refresh, FCFS/insufficient error
 * toast, the public shield badge on a holder's seat, and the Ready-button gate
 * while a perk request is in flight.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import RoomView from '@/views/RoomView.vue'
import { useRoomStore } from '@/stores/roomStore'
import type { Perk, PerkActivation, Room, RoomPlayer } from '@/types'

const h = vi.hoisted(() => {
  const fakeClient = {
    onConnect: null as null | (() => void),
    active: true,
    activate: vi.fn(),
    forceDisconnect: vi.fn(),
  }
  return {
    fakeClient,
    getPerksMock: vi.fn(),
    activatePerkMock: vi.fn(),
    withdrawPerkMock: vi.fn(),
    getWalletMock: vi.fn(),
    getRolePickMock: vi.fn(),
    chooseRolePickMock: vi.fn(),
  }
})

vi.mock('@/services/stompClient', () => ({
  createStompClient: vi.fn(() => h.fakeClient),
  subscribeToTopic: vi.fn(),
  getStompClient: () => h.fakeClient,
  disconnectStomp: vi.fn(),
}))
vi.mock('@/services/roomService', () => ({
  roomService: {
    getRoom: vi.fn(),
    claimSeat: vi.fn(),
    setReady: vi.fn(),
    leaveRoom: vi.fn(),
    kickPlayer: vi.fn(),
    getPerks: h.getPerksMock,
    getRolePick: h.getRolePickMock,
    chooseRolePick: h.chooseRolePickMock,
    activatePerk: h.activatePerkMock,
    withdrawPerk: h.withdrawPerkMock,
  },
}))
vi.mock('@/services/walletService', () => ({ walletService: { getWallet: h.getWalletMock } }))
vi.mock('@/services/gameService', () => ({ gameService: { startGame: vi.fn() } }))
vi.mock('@/services/http', () => ({ default: { post: vi.fn(), get: vi.fn() } }))
vi.mock('@/composables/useNavigationGuard', () => ({ useNavigationGuard: vi.fn() }))
vi.mock('@/composables/useConnectionLifecycle', () => ({ useConnectionLifecycle: vi.fn() }))

function makeJwt(expSecondsFromNow: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + expSecondsFromNow }))
  return `${header}.${payload}.sig`
}

const CATALOG: Perk[] = [
  {
    perkCode: 'NIGHT1_IMMUNITY',
    name: 'First Night Immunity',
    description: 'Safe night 1',
    priceCredits: 30,
  },
]

function rp(userId: string, seatIndex: number, opts: Partial<RoomPlayer> = {}): RoomPlayer {
  return { userId, nickname: userId, seatIndex, status: 'READY', isHost: false, ...opts }
}

function room(
  opts: { perksAllowed?: boolean; activations?: PerkActivation[]; u2Ready?: boolean } = {},
): Room {
  return {
    roomId: '1',
    roomCode: '123',
    hostId: 'u-host',
    status: 'WAITING',
    config: {
      totalPlayers: 4,
      wolfCount: 2,
      roles: ['WEREWOLF', 'VILLAGER'],
      perksAllowed: opts.perksAllowed,
    },
    players: [
      rp('u-host', 1, { nickname: 'Host', isHost: true }),
      rp('u2', 2, { nickname: 'Alice', status: opts.u2Ready === false ? 'NOT_READY' : 'READY' }),
      rp('u3', 3, { nickname: 'Bob' }),
      rp('u4', 4, { nickname: 'Carol' }),
    ],
    perkActivations: opts.activations ?? [],
  }
}

async function mountRoom(myUserId: string, r: Room = room()) {
  localStorage.setItem('jwt', makeJwt(3600))
  localStorage.setItem('userId', myUserId)
  localStorage.setItem('nickname', myUserId)
  const pinia = createPinia()
  setActivePinia(pinia)
  useRoomStore().setRoom(r)
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
  return wrapper
}

async function mountRoomNoWait(myUserId: string, r: Room) {
  localStorage.setItem('userId', myUserId)
  localStorage.setItem('nickname', myUserId)
  const pinia = createPinia()
  setActivePinia(pinia)
  useRoomStore().setRoom(r)
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
  await Promise.resolve()
  return wrapper
}

function axiosError(status: number, error?: string, code?: string) {
  return Object.assign(new Error('request failed'), {
    isAxiosError: true,
    response: { status, data: error ? { error, ...(code ? { code } : {}) } : {} },
  })
}

const VIEW_NONE = {
  choice: null,
  frozenCredits: 0,
  prices: { WOLF: 20, GOD: 20, VILLAGER: 20 },
  seats: { WOLF: 2, GOD: 1, VILLAGER: 1 },
}

describe('RoomView role-pick gate', () => {
  beforeEach(() => {
    localStorage.clear()
    h.getPerksMock.mockReset().mockResolvedValue(CATALOG)
    h.getRolePickMock.mockReset().mockResolvedValue(VIEW_NONE)
    h.chooseRolePickMock
      .mockReset()
      .mockImplementation(async (_r: string, choice: string) => ({ ...VIEW_NONE, choice }))
    h.getWalletMock.mockReset().mockResolvedValue({ balance: 100, recent: [] })
    h.fakeClient.onConnect = null
  })
  afterEach(() => vi.clearAllMocks())

  const readyBtn = (w: Awaited<ReturnType<typeof mountRoom>>) =>
    w.find('[data-testid="ready-btn"]').element as HTMLButtonElement

  it('gates Ready until a choice exists, then enables it', async () => {
    const w = await mountRoom('u2', room({ u2Ready: false }))
    expect(w.find('[data-testid="role-pick-panel"]').exists()).toBe(true)
    expect(readyBtn(w).disabled).toBe(true)
    expect(w.find('[data-testid="role-pick-status"]').exists()).toBe(true)
    await w.find('[data-testid="role-pick-NONE"]').trigger('click')
    await flushPromises()
    expect(h.chooseRolePickMock).toHaveBeenCalledWith('1', 'NONE')
    expect(readyBtn(w).disabled).toBe(false)
    expect(w.find('[data-testid="role-pick-status"]').exists()).toBe(false)
  })

  it('renders no panel and does not gate Ready when perks are disallowed', async () => {
    const w = await mountRoom('u2', room({ perksAllowed: false, u2Ready: false }))
    expect(w.find('[data-testid="role-pick-panel"]').exists()).toBe(false)
    expect(readyBtn(w).disabled).toBe(false)
    expect(h.getRolePickMock).not.toHaveBeenCalled()
  })

  it('keeps host Start disabled while the host has no choice', async () => {
    const r = room()
    r.players[0]!.status = 'NOT_READY' // the host never readies up; only guests do
    const w = await mountRoom('u-host', r)
    const start = () => w.find('[data-testid="start-game-btn"]').element as HTMLButtonElement
    expect(start().disabled).toBe(true)
    await w.find('[data-testid="role-pick-NONE"]').trigger('click')
    await flushPromises()
    expect(start().disabled).toBe(false)
  })

  it('locks the panel while the player is ready', async () => {
    h.getRolePickMock.mockResolvedValue({ ...VIEW_NONE, choice: 'NONE' })
    const w = await mountRoom('u2')
    expect(w.find('[data-testid="role-pick-locked"]').exists()).toBe(true)
  })

  it('refreshes the wallet after choosing and shows a translated error on failure', async () => {
    const w = await mountRoom('u2', room({ u2Ready: false }))
    h.getWalletMock.mockClear()
    await w.find('[data-testid="role-pick-WOLF"]').trigger('click')
    await flushPromises()
    expect(h.getWalletMock).toHaveBeenCalled()

    h.chooseRolePickMock.mockRejectedValue(
      axiosError(400, 'Insufficient credits', 'INSUFFICIENT_CREDITS'),
    )
    await w.find('[data-testid="role-pick-GOD"]').trigger('click')
    await flushPromises()
    const text = w.find('[data-testid="role-pick-error"]').text()
    expect(text).toBe('積分不足')
    expect(text).not.toContain('Insufficient')
  })

  it('never shows the raw backend text for an unknown error code', async () => {
    const w = await mountRoom('u2', room({ u2Ready: false }))
    h.chooseRolePickMock.mockRejectedValue(axiosError(400, 'Something odd', 'SOMETHING_NEW'))
    await w.find('[data-testid="role-pick-GOD"]').trigger('click')
    await flushPromises()
    const text = w.find('[data-testid="role-pick-error"]').text()
    expect(text).not.toContain('Something odd')
    expect(text.length).toBeGreaterThan(0)
  })

  it('a failed load shows a retry control, keeps Ready gated, and recovers on retry', async () => {
    h.getRolePickMock.mockRejectedValueOnce(new Error('boom'))
    const w = await mountRoom('u2', room({ u2Ready: false }))
    expect(w.find('[data-testid="role-pick-retry"]').exists()).toBe(true)
    expect(readyBtn(w).disabled).toBe(true)
    await w.find('[data-testid="role-pick-retry"]').trigger('click')
    await flushPromises()
    expect(w.find('[data-testid="role-pick-retry"]').exists()).toBe(false)
    await w.find('[data-testid="role-pick-NONE"]').trigger('click')
    await flushPromises()
    expect(readyBtn(w).disabled).toBe(false)
  })

  it('does not flash the load-error row while the first GET is pending', async () => {
    let rejectGet!: (e: Error) => void
    h.getRolePickMock.mockReturnValueOnce(new Promise((_, rej) => (rejectGet = rej)))
    localStorage.setItem('jwt', makeJwt(3600))
    const w = await mountRoomNoWait('u2', room({ u2Ready: false }))
    expect(w.find('[data-testid="role-pick-load-error"]').exists()).toBe(false)
    rejectGet(new Error('boom'))
    await flushPromises()
    expect(w.find('[data-testid="role-pick-load-error"]').exists()).toBe(true)
  })

  it('a ready-toggle ROOM_UPDATE with an unchanged board does not refetch', async () => {
    const w = await mountRoom('u2', room({ u2Ready: false }))
    h.getRolePickMock.mockClear()
    const r = room({ u2Ready: true })
    useRoomStore().setRoom(r)
    await flushPromises()
    expect(h.getRolePickMock).not.toHaveBeenCalled()
    r.config = { ...r.config, totalPlayers: 5 }
    useRoomStore().setRoom({ ...r })
    await flushPromises()
    expect(h.getRolePickMock).toHaveBeenCalledTimes(1)
    w.unmount()
  })

  it('a stale GET resolving after a choose does not overwrite the choice', async () => {
    const w = await mountRoom('u2', room({ u2Ready: false }))
    let resolveStale!: (v: unknown) => void
    h.getRolePickMock.mockReturnValueOnce(new Promise((res) => (resolveStale = res)))
    const r = room({ u2Ready: false })
    r.config = { ...r.config, totalPlayers: 5 }
    useRoomStore().setRoom(r)
    await flushPromises()
    await w.find('[data-testid="role-pick-WOLF"]').trigger('click')
    await flushPromises()
    resolveStale(VIEW_NONE)
    await flushPromises()
    expect(w.find('[data-testid="role-pick-WOLF"]').classes()).toContain('rp-sel')
  })

  it('refreshes the role-pick view after activating immunity', async () => {
    h.activatePerkMock.mockResolvedValue(undefined)
    const w = await mountRoom('u2', room({ u2Ready: false }))
    h.getRolePickMock.mockClear()
    await w.find('[data-testid="perk-activate-btn"]').trigger('click')
    await flushPromises()
    expect(h.getRolePickMock).toHaveBeenCalledTimes(1)
  })
})
