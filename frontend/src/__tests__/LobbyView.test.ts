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
const getActiveRoomMock = vi.fn()

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
    getActiveRoom: (...args: unknown[]) => getActiveRoomMock(...args),
  },
}))

vi.mock('@/utils/oauth', async () => {
  const actual = await vi.importActual<typeof import('@/utils/oauth')>('@/utils/oauth')
  return {
    ...actual,
    generateOAuthState: vi.fn(() => 'fake-state-abc'),
  }
})

async function mountLobby(path = '/') {
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
  await router.push(path)
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

  // The backend rejects nicknames that are empty after removing invisible chars
  // (or overflow once normalised); the player must see why, not a room error.
  const nicknameRejected = () =>
    Object.assign(new Error('Request failed with status code 400'), {
      isAxiosError: true,
      response: { status: 400, data: { error: 'Nickname must contain visible characters' } },
    })

  it.each([
    ['join-room-btn', true],
    ['create-room-btn', false],
  ])('shows the backend reason when guest login is rejected (%s)', async (button, needsCode) => {
    getProvidersMock.mockRejectedValue(new Error('no oauth'))
    loginMock.mockRejectedValue(nicknameRejected())
    const { wrapper, router } = await mountLobby()

    await wrapper.find('[data-testid="nickname-input"]').setValue('​')
    if (needsCode) await wrapper.find('[data-testid="room-code-input"]').setValue('123')
    await wrapper.find(`[data-testid="${button}"]`).trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="lobby-error"]').text()).toBe(
      'Nickname must contain visible characters',
    )
    expect(joinRoomMock).not.toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('lobby')
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

  // "Room is full" used to read as "Room not found", so the last player blocked
  // by an unseated member thought they had the wrong code.
  it.each([
    ['Room is full', 'Room is full'],
    ['Room is not open', 'Room is not open'],
    // A genuinely missing room keeps the "check the code" hint.
    ['Room not found', 'Room not found. Check the code.'],
    [undefined, 'Room not found. Check the code.'],
  ])('shows the backend join reason (%s)', async (reason, shown) => {
    localStorage.setItem('jwt', VALID_JWT())
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Daniel Wang')
    getProvidersMock.mockResolvedValue({ google: { clientId: 'g' }, wechat: null, guest: true })
    joinRoomMock.mockRejectedValue(
      Object.assign(new Error('Request failed with status code 400'), {
        isAxiosError: true,
        response: { status: 400, data: reason ? { error: reason } : {} },
      }),
    )

    const { wrapper, router } = await mountLobby()
    await wrapper.find('[data-testid="room-code-input"]').setValue('123')
    await wrapper.find('[data-testid="join-room-btn"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="lobby-error"]').text()).toBe(shown)
    expect(router.currentRoute.value.name).toBe('lobby')
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

describe('LobbyView invite link', () => {
  beforeEach(() => {
    localStorage.clear()
    getProvidersMock.mockReset()
    getProvidersMock.mockResolvedValue({ google: null, wechat: null, guest: true })
    getActiveRoomMock.mockReset()
  })
  afterEach(() => vi.clearAllMocks())

  it('a shared link /?room=123 fills in the room code', async () => {
    const { wrapper } = await mountLobby('/?room=123')
    expect(
      (wrapper.find('[data-testid="room-code-input"]').element as HTMLInputElement).value,
    ).toBe('123')
  })
})

describe('LobbyView quick rejoin (#7)', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    getProvidersMock.mockReset()
    getProvidersMock.mockResolvedValue({ google: null, wechat: null, guest: true })
    getActiveRoomMock.mockReset()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  function loginLocally() {
    localStorage.setItem('jwt', VALID_JWT())
    localStorage.setItem('userId', 'u1')
    localStorage.setItem('nickname', 'Alice')
  }

  it('shows a Rejoin button when the player has an active room and navigates to it', async () => {
    loginLocally()
    getActiveRoomMock.mockResolvedValue({
      roomId: '7',
      roomCode: '123',
      status: 'IN_GAME',
      config: { totalPlayers: 9, roles: [] },
      players: [],
    })

    const { wrapper, router } = await mountLobby()

    const btn = wrapper.find('[data-testid="rejoin-room-btn"]')
    expect(btn.exists()).toBe(true)
    expect(btn.text()).toContain('123')

    const pushSpy = vi.spyOn(router, 'push')
    await btn.trigger('click')
    expect(pushSpy).toHaveBeenCalledWith({ name: 'room', params: { roomId: '7' } })
  })

  it('shows no Rejoin button when there is no active room', async () => {
    loginLocally()
    getActiveRoomMock.mockResolvedValue(null)

    const { wrapper } = await mountLobby()

    expect(wrapper.find('[data-testid="rejoin-room-btn"]').exists()).toBe(false)
  })

  it('shows no Rejoin button when the user is not logged in', async () => {
    getActiveRoomMock.mockResolvedValue({ roomId: '7', roomCode: '123', players: [] })

    const { wrapper } = await mountLobby()

    expect(wrapper.find('[data-testid="rejoin-room-btn"]').exists()).toBe(false)
    expect(getActiveRoomMock).not.toHaveBeenCalled()
  })
})
