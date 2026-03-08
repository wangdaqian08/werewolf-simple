import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore } from '@/stores/userStore'

// Mock the userService so tests don't make real HTTP calls
vi.mock('@/services/userService', () => ({
  userService: {
    login: vi.fn().mockResolvedValue({
      token: 'test-token-xyz',
      user: { userId: 'u1', nickname: 'TestUser' },
    }),
    logout: vi.fn().mockResolvedValue(undefined),
  },
}))

describe('userStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
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
    expect(store.token).toBe('test-token-xyz')
    expect(store.userId).toBe('u1')
    expect(store.nickname).toBe('TestUser')
    expect(store.isLoggedIn).toBe(true)
  })

  it('login() persists token, userId, nickname to localStorage', async () => {
    const store = useUserStore()
    await store.login('TestUser')
    expect(localStorage.getItem('jwt')).toBe('test-token-xyz')
    expect(localStorage.getItem('userId')).toBe('u1')
    expect(localStorage.getItem('nickname')).toBe('TestUser')
  })

  it('restores session from localStorage on init', () => {
    localStorage.setItem('jwt', 'saved-token')
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
})
