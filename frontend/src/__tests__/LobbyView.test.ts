import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import LobbyView from '@/views/LobbyView.vue'
import { useUserStore } from '@/stores/userStore'

const getProvidersMock = vi.fn()
const loginMock = vi.fn()

vi.mock('@/services/userService', () => ({
  userService: {
    login: (...args: unknown[]) => loginMock(...args),
    loginWithGoogle: vi.fn(),
    loginWithWechat: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
    getProviders: (...args: unknown[]) => getProvidersMock(...args),
  },
}))

vi.mock('@/services/roomService', () => ({
  roomService: {
    createRoom: vi.fn(),
    joinRoom: vi.fn(),
    leaveRoom: vi.fn(),
    getRoom: vi.fn(),
    getRoomList: vi.fn(),
    setReady: vi.fn(),
    claimSeat: vi.fn(),
    kickPlayer: vi.fn(),
  },
}))

vi.mock('@/utils/oauth', async () => {
  const actual = await vi.importActual<typeof import('@/utils/oauth')>('@/utils/oauth')
  return {
    ...actual,
    generateOAuthState: vi.fn(() => 'fake-state-abc'),
  }
})

async function mountLobby() {
  const pinia = createPinia()
  setActivePinia(pinia)

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: LobbyView },
      { path: '/create-room', name: 'create-room', component: { template: '<div />' } },
      { path: '/room/:roomId', name: 'room', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(LobbyView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router, store: useUserStore() }
}

describe('LobbyView OAuth UI', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    getProvidersMock.mockReset()
    loginMock.mockReset()
    // Stub window.location.href so click handlers can read/write without
    // navigating away (jsdom-like default would also work but we want to
    // assert the value was set).
    Object.defineProperty(window, 'location', {
      writable: true,
      value: {
        ...window.location,
        href: 'http://localhost/',
        assign: vi.fn(),
        origin: 'http://localhost',
      },
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('shows google sign-in button when providers.google is true', async () => {
    getProvidersMock.mockResolvedValue({
      google: { clientId: 'test-google.apps.googleusercontent.com' },
      wechat: { appId: 'test-wechat-id' },
      guest: true,
    })
    const { wrapper } = await mountLobby()

    expect(wrapper.find('[data-testid="oauth-google"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="oauth-wechat"]').exists()).toBe(true)
  })

  it('hides wechat button when providers.wechat is false', async () => {
    getProvidersMock.mockResolvedValue({
      google: { clientId: 'test-google.apps.googleusercontent.com' },
      wechat: null,
      guest: true,
    })
    const { wrapper } = await mountLobby()

    expect(wrapper.find('[data-testid="oauth-google"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="oauth-wechat"]').exists()).toBe(false)
  })

  it('hides google button when providers.google is false', async () => {
    getProvidersMock.mockResolvedValue({ google: null, wechat: null, guest: true })
    const { wrapper } = await mountLobby()

    expect(wrapper.find('[data-testid="oauth-google"]').exists()).toBe(false)
  })

  it('does not crash when getProviders fails — guest form is shown directly without a toggle', async () => {
    getProvidersMock.mockRejectedValue(new Error('network down'))
    const { wrapper } = await mountLobby()

    expect(wrapper.find('[data-testid="oauth-google"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="oauth-wechat"]').exists()).toBe(false)
    // No toggle when there's no OAuth — nickname input is visible immediately.
    expect(wrapper.find('[data-testid="guest-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="nickname-input"]').exists()).toBe(true)
  })

  it('guest section is collapsed by default and expands on toggle click', async () => {
    getProvidersMock.mockResolvedValue({
      google: { clientId: 'test-google.apps.googleusercontent.com' },
      wechat: null,
      guest: true,
    })
    const { wrapper } = await mountLobby()

    expect(wrapper.find('[data-testid="nickname-input"]').exists()).toBe(false)
    await wrapper.find('[data-testid="guest-toggle"]').trigger('click')
    expect(wrapper.find('[data-testid="nickname-input"]').exists()).toBe(true)
  })

  it('clicking the google button sets window.location.href to a Google auth URL', async () => {
    getProvidersMock.mockResolvedValue({
      google: { clientId: 'test-google.apps.googleusercontent.com' },
      wechat: null,
      guest: true,
    })
    const { wrapper } = await mountLobby()

    await wrapper.find('[data-testid="oauth-google"]').trigger('click')

    expect(window.location.href).toContain('https://accounts.google.com/o/oauth2/v2/auth')
    expect(window.location.href).toContain('state=fake-state-abc')
    expect(window.location.href).toContain('redirect_uri=')
  })

  it('shows logged-in identity card when userStore is OAuth-authenticated', async () => {
    localStorage.setItem('jwt', 'jwt')
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Daniel Wang')
    localStorage.setItem('avatarUrl', 'https://example.com/a.png')
    getProvidersMock.mockResolvedValue({
      google: { clientId: 'test-google.apps.googleusercontent.com' },
      wechat: null,
      guest: true,
    })

    const { wrapper } = await mountLobby()

    expect(wrapper.find('[data-testid="signed-in-as"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="signed-in-as"]').text()).toContain('Daniel Wang')
    // OAuth buttons are hidden once logged in (no need to re-auth).
    expect(wrapper.find('[data-testid="oauth-google"]').exists()).toBe(false)
  })

  it('logged-in user can click Create Room without re-login', async () => {
    localStorage.setItem('jwt', 'jwt')
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Daniel Wang')
    getProvidersMock.mockResolvedValue({
      google: { clientId: 'test-google.apps.googleusercontent.com' },
      wechat: null,
      guest: true,
    })

    const { wrapper, router } = await mountLobby()
    const pushSpy = vi.spyOn(router, 'push')

    await wrapper.find('[data-testid="create-room-btn"]').trigger('click')
    await flushPromises()

    // For OAuth users we skip userService.login (already authenticated) and
    // navigate straight to /create-room.
    expect(loginMock).not.toHaveBeenCalled()
    expect(pushSpy).toHaveBeenCalledWith({ name: 'create-room' })
  })
})
