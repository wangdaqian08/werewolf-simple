import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '@/stores/userStore'

// Build a JWT-shaped token with the given `exp` (seconds since epoch). The
// store decodes the payload to check expiry, so test fixtures need to look
// like real JWTs — opaque strings get treated as expired and purged.
function makeJwt(expSecondsFromNow: number): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + expSecondsFromNow }))
  return `${header}.${payload}.sig`
}
const FUTURE = 60 * 60 // 1h from now — well within validity
const PAST = -60 * 60 // 1h ago — expired

// Mock the userService so tests don't make real HTTP calls
vi.mock('@/services/userService', () => ({
  userService: {
    login: vi.fn(),
    loginWithGoogle: vi.fn(),
    loginWithWechat: vi.fn(),
    logout: vi.fn().mockResolvedValue(undefined),
  },
}))

// Re-seed userService mocks each test so JWT `exp` is computed at test time
// (avoids fixtures that "expire" if the suite is slow).
import { userService } from '@/services/userService'

describe('userStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    sessionStorage.clear()
    vi.mocked(userService.login).mockResolvedValue({
      token: makeJwt(FUTURE),
      user: { userId: 'u1', nickname: 'TestUser' },
    })
    vi.mocked(userService.loginWithGoogle).mockResolvedValue({
      token: makeJwt(FUTURE),
      user: {
        userId: 'google:abc',
        nickname: 'Daniel Wang',
        avatarUrl: 'https://lh3.googleusercontent.com/a/x',
      },
    })
    vi.mocked(userService.loginWithWechat).mockResolvedValue({
      token: makeJwt(FUTURE),
      user: {
        userId: 'wechat:xyz',
        nickname: '微信用户',
        avatarUrl: 'https://thirdwx.qlogo.cn/x.jpg',
      },
    })
  })

  it('starts logged out when localStorage is empty', () => {
    const store = useUserStore()
    expect(store.isLoggedIn).toBe(false)
    expect(store.userId).toBeNull()
    expect(store.nickname).toBeNull()
  })

  it('isLoggedIn is false when token exists but userId is missing', () => {
    // Simulate stale JWT without userId (the original bug)
    localStorage.setItem('jwt', 'stale-token')
    const store = useUserStore()
    expect(store.isLoggedIn).toBe(false)
  })

  it('login() sets state in store', async () => {
    const store = useUserStore()
    await store.login('TestUser')
    expect(store.token).toBeTruthy()
    expect(store.userId).toBe('u1')
    expect(store.nickname).toBe('TestUser')
    expect(store.isLoggedIn).toBe(true)
  })

  it('login() persists token, userId, nickname to localStorage', async () => {
    const store = useUserStore()
    await store.login('TestUser')
    expect(localStorage.getItem('jwt')).toBeTruthy()
    expect(localStorage.getItem('userId')).toBe('u1')
    expect(localStorage.getItem('nickname')).toBe('TestUser')
  })

  it('restores session from localStorage on init', () => {
    localStorage.setItem('jwt', makeJwt(FUTURE))
    localStorage.setItem('userId', 'u42')
    localStorage.setItem('nickname', 'Saved')
    const store = useUserStore()
    expect(store.isLoggedIn).toBe(true)
    expect(store.userId).toBe('u42')
    expect(store.nickname).toBe('Saved')
  })

  it('logout() clears store state', async () => {
    const store = useUserStore()
    await store.login('TestUser')
    await store.logout()
    expect(store.token).toBeNull()
    expect(store.userId).toBeNull()
    expect(store.nickname).toBeNull()
    expect(store.isLoggedIn).toBe(false)
  })

  it('logout() removes all keys from localStorage', async () => {
    const store = useUserStore()
    await store.login('TestUser')
    await store.logout()
    expect(localStorage.getItem('jwt')).toBeNull()
    expect(localStorage.getItem('userId')).toBeNull()
    expect(localStorage.getItem('nickname')).toBeNull()
  })

  // ── OAuth + avatarUrl ─────────────────────────────────────────────────────

  it('loginWithCode("google", code) sets token, userId, nickname, avatarUrl in store', async () => {
    const store = useUserStore()
    await store.loginWithCode('google', 'the-code')
    expect(store.token).toBeTruthy()
    expect(store.userId).toBe('google:abc')
    expect(store.nickname).toBe('Daniel Wang')
    expect(store.avatarUrl).toBe('https://lh3.googleusercontent.com/a/x')
    expect(store.isLoggedIn).toBe(true)
  })

  it('loginWithCode persists avatarUrl to localStorage', async () => {
    const store = useUserStore()
    await store.loginWithCode('google', 'the-code')
    expect(localStorage.getItem('avatarUrl')).toBe('https://lh3.googleusercontent.com/a/x')
  })

  it('loginWithCode("wechat", code) routes to wechat endpoint', async () => {
    const store = useUserStore()
    await store.loginWithCode('wechat', 'wechat-code')
    expect(store.userId).toBe('wechat:xyz')
    expect(store.nickname).toBe('微信用户')
    expect(store.avatarUrl).toBe('https://thirdwx.qlogo.cn/x.jpg')
  })

  it('restores avatarUrl from localStorage on init', () => {
    localStorage.setItem('jwt', makeJwt(FUTURE))
    localStorage.setItem('userId', 'google:abc')
    localStorage.setItem('nickname', 'Saved')
    localStorage.setItem('avatarUrl', 'https://example.com/saved.png')
    const store = useUserStore()
    expect(store.avatarUrl).toBe('https://example.com/saved.png')
  })

  it('logout() clears avatarUrl from store and localStorage', async () => {
    const store = useUserStore()
    await store.loginWithCode('google', 'code')
    expect(store.avatarUrl).toBeTruthy()
    await store.logout()
    expect(store.avatarUrl).toBeNull()
    expect(localStorage.getItem('avatarUrl')).toBeNull()
  })

  it('avatarUrl is null when not provided by guest login', async () => {
    const store = useUserStore()
    await store.login('TestUser')
    expect(store.avatarUrl).toBeNull()
  })

  // ── Per-room nickname override (displayName) ─────────────────────────────

  it('displayName starts as null', () => {
    const store = useUserStore()
    expect(store.displayName).toBeNull()
  })

  it('setDisplayName stores trimmed value and persists to sessionStorage', () => {
    const store = useUserStore()
    store.setDisplayName('  DW  ')
    expect(store.displayName).toBe('DW')
    expect(sessionStorage.getItem('displayName')).toBe('DW')
  })

  it('setDisplayName treats whitespace-only as null', () => {
    const store = useUserStore()
    store.setDisplayName('Real')
    store.setDisplayName('   ')
    expect(store.displayName).toBeNull()
    expect(sessionStorage.getItem('displayName')).toBeNull()
  })

  it('setDisplayName(null) clears the override', () => {
    const store = useUserStore()
    store.setDisplayName('DW')
    store.setDisplayName(null)
    expect(store.displayName).toBeNull()
    expect(sessionStorage.getItem('displayName')).toBeNull()
  })

  it('restores displayName from sessionStorage on init', () => {
    sessionStorage.setItem('displayName', 'PersistedNick')
    const store = useUserStore()
    expect(store.displayName).toBe('PersistedNick')
  })

  it('logout clears displayName', async () => {
    const store = useUserStore()
    await store.loginWithCode('google', 'code')
    store.setDisplayName('Custom')
    await store.logout()
    expect(store.displayName).toBeNull()
    expect(sessionStorage.getItem('displayName')).toBeNull()
  })

  it('logging in via OAuth clears any leftover displayName from a previous session', async () => {
    sessionStorage.setItem('displayName', 'StaleFromLastUser')
    const store = useUserStore()
    await store.loginWithCode('google', 'code')
    expect(store.displayName).toBeNull()
    expect(sessionStorage.getItem('displayName')).toBeNull()
  })

  it('logging in via guest clears any leftover displayName from a previous session', async () => {
    sessionStorage.setItem('displayName', 'Stale')
    const store = useUserStore()
    await store.login('TestUser')
    expect(store.displayName).toBeNull()
  })

  // ── JWT expiry detection ────────────────────────────────────────────────

  it('isLoggedIn is false when the persisted JWT is expired', () => {
    localStorage.setItem('jwt', makeJwt(PAST))
    localStorage.setItem('userId', 'u42')
    localStorage.setItem('nickname', 'Stale')
    const store = useUserStore()
    expect(store.isLoggedIn).toBe(false)
  })

  it('purges expired session from localStorage on store init', () => {
    localStorage.setItem('jwt', makeJwt(PAST))
    localStorage.setItem('userId', 'u42')
    localStorage.setItem('nickname', 'Stale')
    localStorage.setItem('avatarUrl', 'https://example.com/x.png')
    useUserStore()
    expect(localStorage.getItem('jwt')).toBeNull()
    expect(localStorage.getItem('userId')).toBeNull()
    expect(localStorage.getItem('nickname')).toBeNull()
    expect(localStorage.getItem('avatarUrl')).toBeNull()
  })

  it('clearSession() empties refs and localStorage without hitting the network', async () => {
    vi.mocked(userService.logout).mockClear()
    const store = useUserStore()
    await store.loginWithCode('google', 'code')
    expect(store.isLoggedIn).toBe(true)
    store.clearSession()
    expect(store.token).toBeNull()
    expect(store.userId).toBeNull()
    expect(store.nickname).toBeNull()
    expect(store.avatarUrl).toBeNull()
    expect(store.isLoggedIn).toBe(false)
    expect(localStorage.getItem('jwt')).toBeNull()
    expect(vi.mocked(userService.logout)).not.toHaveBeenCalled()
  })
})
