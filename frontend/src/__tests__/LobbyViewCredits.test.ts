/**
 * LobbyView credits chip: the balance chip in the identity card renders only
 * once the wallet has been refreshed (credits !== null), and stays hidden
 * otherwise. Isolated from LobbyView.test.ts so mocking walletService here
 * doesn't change that file's behaviour.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import LobbyView from '@/views/LobbyView.vue'

const h = vi.hoisted(() => ({ getWalletMock: vi.fn() }))

vi.mock('@/services/userService', () => ({
  userService: {
    login: vi.fn(),
    loginWithGoogle: vi.fn(),
    loginWithWechat: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
    getProviders: vi.fn().mockResolvedValue({ google: null, wechat: null, guest: true }),
  },
}))
vi.mock('@/services/roomService', () => ({
  roomService: {
    getActiveRoom: vi.fn().mockResolvedValue(null),
    joinRoom: vi.fn(),
    createRoom: vi.fn(),
  },
}))
vi.mock('@/services/walletService', () => ({ walletService: { getWallet: h.getWalletMock } }))

function makeJwt(expSecondsFromNow: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + expSecondsFromNow }))
  return `${header}.${payload}.sig`
}

async function mountLobby(opts: { loggedIn?: boolean } = {}) {
  if (opts.loggedIn !== false) {
    localStorage.setItem('jwt', makeJwt(3600))
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Daniel')
  }
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: LobbyView },
      { path: '/create-room', name: 'create-room', component: { template: '<div />' } },
      { path: '/room/:roomId', name: 'room', component: { template: '<div />' } },
      { path: '/account', name: 'account', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  const wrapper = mount(LobbyView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('LobbyView credits chip', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    h.getWalletMock.mockReset()
  })
  afterEach(() => vi.clearAllMocks())

  it('shows the credits chip with the balance once the wallet is refreshed', async () => {
    h.getWalletMock.mockResolvedValue({ balance: 250, recent: [] })
    const { wrapper } = await mountLobby()
    const chip = wrapper.find('[data-testid="credits-chip"]')
    expect(chip.exists()).toBe(true)
    expect(chip.text()).toContain('250')
    expect(chip.text()).toContain('积分 / Credits')
  })

  it('renders the chip even at a zero balance', async () => {
    h.getWalletMock.mockResolvedValue({ balance: 0, recent: [] })
    const { wrapper } = await mountLobby()
    const chip = wrapper.find('[data-testid="credits-chip"]')
    expect(chip.exists()).toBe(true)
    expect(chip.text()).toContain('0')
  })

  it('hides the chip when the wallet fetch fails (credits stays null)', async () => {
    h.getWalletMock.mockRejectedValue(new Error('network'))
    const { wrapper } = await mountLobby()
    expect(wrapper.find('[data-testid="credits-chip"]').exists()).toBe(false)
  })
})

describe('LobbyView account menu (☰)', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    h.getWalletMock.mockReset().mockResolvedValue({ balance: 250, recent: [] })
  })
  afterEach(() => vi.clearAllMocks())

  it('is visible when logged in', async () => {
    const { wrapper } = await mountLobby()
    expect(wrapper.find('[data-testid="account-menu-btn"]').exists()).toBe(true)
  })

  it('is absent when logged out', async () => {
    const { wrapper } = await mountLobby({ loggedIn: false })
    expect(wrapper.find('[data-testid="account-menu-btn"]').exists()).toBe(false)
  })

  it('navigates to /account on click', async () => {
    const { wrapper, router } = await mountLobby()
    await wrapper.find('[data-testid="account-menu-btn"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/account')
  })
})
