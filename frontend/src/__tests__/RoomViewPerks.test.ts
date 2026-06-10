/**
 * Component tests for RoomView's perk panel (Phase B): catalog render,
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
  const fakeClient = { onConnect: null as null | (() => void), active: true, activate: vi.fn(), forceDisconnect: vi.fn() }
  return {
    fakeClient,
    getPerksMock: vi.fn(),
    activatePerkMock: vi.fn(),
    withdrawPerkMock: vi.fn(),
    getWalletMock: vi.fn(),
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
  { perkCode: 'NIGHT1_IMMUNITY', name: 'First Night Immunity', description: 'Safe night 1', priceCredits: 30 },
]

function rp(userId: string, seatIndex: number, opts: Partial<RoomPlayer> = {}): RoomPlayer {
  return { userId, nickname: userId, seatIndex, status: 'READY', isHost: false, ...opts }
}

function room(opts: { perksAllowed?: boolean; activations?: PerkActivation[]; u2Ready?: boolean } = {}): Room {
  return {
    roomId: '1',
    roomCode: '123',
    hostId: 'u-host',
    status: 'WAITING',
    config: { totalPlayers: 4, wolfCount: 2, roles: ['WEREWOLF', 'VILLAGER'], perksAllowed: opts.perksAllowed },
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

function axiosError(status: number, error?: string) {
  return Object.assign(new Error('request failed'), {
    isAxiosError: true,
    response: { status, data: error ? { error } : {} },
  })
}

describe('RoomView perk panel', () => {
  beforeEach(() => {
    localStorage.clear()
    h.getPerksMock.mockReset().mockResolvedValue(CATALOG)
    h.activatePerkMock.mockReset().mockResolvedValue(undefined)
    h.withdrawPerkMock.mockReset().mockResolvedValue(undefined)
    h.getWalletMock.mockReset().mockResolvedValue({ balance: 100, recent: [] })
    h.fakeClient.onConnect = null
  })
  afterEach(() => vi.clearAllMocks())

  it('renders the catalog row with name, price and description', async () => {
    const wrapper = await mountRoom('u-host')
    const panel = wrapper.find('[data-testid="perk-panel"]')
    expect(panel.exists()).toBe(true)
    expect(panel.text()).toContain('First Night Immunity')
    expect(panel.text()).toContain('◈30')
    expect(panel.text()).toContain('Safe night 1')
  })

  it('hides the perk panel when the host disabled perks', async () => {
    const wrapper = await mountRoom('u-host', room({ perksAllowed: false }))
    expect(wrapper.find('[data-testid="perk-panel"]').exists()).toBe(false)
  })

  it('Activate calls roomService.activatePerk and refreshes the wallet', async () => {
    const wrapper = await mountRoom('u-host')
    await wrapper.find('[data-testid="perk-activate-btn"]').trigger('click')
    await flushPromises()
    expect(h.activatePerkMock).toHaveBeenCalledWith('1', 'NIGHT1_IMMUNITY')
    expect(h.getWalletMock).toHaveBeenCalled() // refreshWallet ran
    expect(wrapper.find('[data-testid="perk-error"]').exists()).toBe(false)
  })

  it('shows the backend error message when activation is rejected (FCFS / insufficient)', async () => {
    h.activatePerkMock.mockRejectedValue(axiosError(400, 'Perk already taken by another player'))
    const wrapper = await mountRoom('u-host')
    await wrapper.find('[data-testid="perk-activate-btn"]').trigger('click')
    await flushPromises()
    const err = wrapper.find('[data-testid="perk-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text()).toContain('Perk already taken by another player')
  })

  it('falls back to a generic error message when the response carries no error field', async () => {
    h.activatePerkMock.mockRejectedValue(axiosError(400))
    const wrapper = await mountRoom('u-host')
    await wrapper.find('[data-testid="perk-activate-btn"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="perk-error"]').text()).toContain('操作失败')
  })

  it('a held perk shows Withdraw for the holder and calls withdrawPerk', async () => {
    const activations = [{ userId: 'u2', perkCode: 'NIGHT1_IMMUNITY', perkName: 'First Night Immunity' }]
    const wrapper = await mountRoom('u2', room({ activations }))
    const withdrawBtn = wrapper.find('[data-testid="perk-withdraw-btn"]')
    expect(withdrawBtn.exists()).toBe(true)
    expect(wrapper.find('[data-testid="perk-activate-btn"]').exists()).toBe(false)
    await withdrawBtn.trigger('click')
    await flushPromises()
    expect(h.withdrawPerkMock).toHaveBeenCalledWith('1', 'NIGHT1_IMMUNITY')
  })

  it('shows a Taken indicator and disables Activate for non-holders, and badges the holder seat', async () => {
    const activations = [{ userId: 'u2', perkCode: 'NIGHT1_IMMUNITY', perkName: 'First Night Immunity' }]
    const wrapper = await mountRoom('u-host', room({ activations }))
    // Non-holder host sees the perk as taken by Alice and cannot activate.
    expect(wrapper.find('[data-testid="perk-panel"]').text()).toContain('Alice')
    const activateBtn = wrapper.find('[data-testid="perk-activate-btn"]')
    expect((activateBtn.element as HTMLButtonElement).disabled).toBe(true)
    // Public shield badge on the holder's seat (Alice = seat 2), not elsewhere.
    expect(wrapper.find('[data-testid="perk-badge-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="perk-badge-2"]').text()).toContain('🛡')
    expect(wrapper.find('[data-testid="perk-badge-1"]').exists()).toBe(false)
  })

  it('disables the guest Ready button while a perk request is in flight', async () => {
    // u2 is seated but NOT_READY → the Ready button is shown.
    h.activatePerkMock.mockReturnValue(new Promise(() => {})) // never resolves
    const wrapper = await mountRoom('u2', room({ u2Ready: false }))
    const readyBtn = () => wrapper.find('button.btn-gold')
    expect((readyBtn().element as HTMLButtonElement).disabled).toBe(false)

    await wrapper.find('[data-testid="perk-activate-btn"]').trigger('click')
    await wrapper.vm.$nextTick()

    expect((readyBtn().element as HTMLButtonElement).disabled).toBe(true)
  })
})
