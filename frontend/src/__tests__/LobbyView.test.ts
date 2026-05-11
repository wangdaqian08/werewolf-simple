import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import LobbyView from '@/views/LobbyView.vue'
import { useUserStore } from '@/stores/userStore'

// JWT-shaped fixture: the store decodes the payload to gate `isLoggedIn`, so
// opaque strings would be treated as expired and purged on init.
function makeJwt(expSecondsFromNow: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + expSecondsFromNow }))
  return `${header}.${payload}.sig`
}
const VALID_JWT = () => makeJwt(60 * 60)
const EXPIRED_JWT = () => makeJwt(-60 * 60)

const getProvidersMock = vi.fn()
const loginMock = vi.fn()
const joinRoomMock = vi.fn()

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
    joinRoom: (...args: unknown[]) => joinRoomMock(...args),
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
    joinRoomMock.mockReset()
    joinRoomMock.mockResolvedValue({ roomId: '99', roomCode: 'ABCD' })
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
    localStorage.setItem('jwt', VALID_JWT())
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
    // The OAuth nickname is shown in the editable display-name input.
    const input = wrapper.find('[data-testid="display-name-input"]')
    expect((input.element as HTMLInputElement).value).toBe('Daniel Wang')
    // OAuth buttons are hidden once logged in (no need to re-auth).
    expect(wrapper.find('[data-testid="oauth-google"]').exists()).toBe(false)
  })

  it('logged-in user can click Create Room without re-login', async () => {
    localStorage.setItem('jwt', VALID_JWT())
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

  // ── Per-room nickname override (Option A) ─────────────────────────────────

  it('logged-in identity card has an editable nickname input pre-filled with OAuth nickname', async () => {
    localStorage.setItem('jwt', VALID_JWT())
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Daniel Wang')
    getProvidersMock.mockResolvedValue({
      google: { clientId: 'g' },
      wechat: null,
      guest: true,
    })

    const { wrapper } = await mountLobby()
    const input = wrapper.find('[data-testid="display-name-input"]')
    expect(input.exists()).toBe(true)
    expect((input.element as HTMLInputElement).value).toBe('Daniel Wang')
  })

  it('typing in the display-name input writes to userStore.displayName', async () => {
    localStorage.setItem('jwt', VALID_JWT())
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Daniel Wang')
    getProvidersMock.mockResolvedValue({ google: { clientId: 'g' }, wechat: null, guest: true })

    const { wrapper, store } = await mountLobby()
    const input = wrapper.find('[data-testid="display-name-input"]')
    await input.setValue('DW')

    expect(store.displayName).toBe('DW')
  })

  it('Join Room sends displayName as nickname to roomService.joinRoom when overridden', async () => {
    localStorage.setItem('jwt', VALID_JWT())
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Daniel Wang')
    getProvidersMock.mockResolvedValue({ google: { clientId: 'g' }, wechat: null, guest: true })

    const { wrapper } = await mountLobby()
    await wrapper.find('[data-testid="display-name-input"]').setValue('DW')
    await wrapper.find('[data-testid="room-code-input"]').setValue('ABCD')
    await wrapper.find('[data-testid="join-room-btn"]').trigger('click')
    await flushPromises()

    expect(joinRoomMock).toHaveBeenCalledWith({ roomCode: 'ABCD', nickname: 'DW' })
  })

  it('Join Room omits nickname when display-name was not changed (sends just roomCode)', async () => {
    localStorage.setItem('jwt', VALID_JWT())
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Daniel Wang')
    getProvidersMock.mockResolvedValue({ google: { clientId: 'g' }, wechat: null, guest: true })

    const { wrapper } = await mountLobby()
    await wrapper.find('[data-testid="room-code-input"]').setValue('ABCD')
    await wrapper.find('[data-testid="join-room-btn"]').trigger('click')
    await flushPromises()

    // No edit happened — displayName stayed null. roomService.joinRoom should
    // get just the room code (no `nickname` key).
    expect(joinRoomMock).toHaveBeenCalledWith({ roomCode: 'ABCD' })
  })

  // Regression: an expired JWT in localStorage used to keep `isLoggedIn` true,
  // hiding the Google login button so the user couldn't re-authenticate.
  it('shows the Google login button when the persisted JWT is expired', async () => {
    localStorage.setItem('jwt', EXPIRED_JWT())
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Daniel Wang')
    getProvidersMock.mockResolvedValue({ google: { clientId: 'g' }, wechat: null, guest: true })

    const { wrapper } = await mountLobby()

    expect(wrapper.find('[data-testid="signed-in-as"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="oauth-google"]').exists()).toBe(true)
  })

  it('Create Room saves displayName to userStore for CreateRoomView to pick up', async () => {
    localStorage.setItem('jwt', VALID_JWT())
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Daniel Wang')
    getProvidersMock.mockResolvedValue({ google: { clientId: 'g' }, wechat: null, guest: true })

    const { wrapper, store } = await mountLobby()
    await wrapper.find('[data-testid="display-name-input"]').setValue('DW')
    await wrapper.find('[data-testid="create-room-btn"]').trigger('click')
    await flushPromises()

    expect(store.displayName).toBe('DW')
  })
})
