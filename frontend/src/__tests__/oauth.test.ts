import { beforeEach, describe, expect, it } from 'vitest'
import {
  buildGoogleAuthUrl,
  consumeOAuthState,
  generateOAuthState,
  OAUTH_STATE_KEY,
} from '@/utils/oauth'

describe('oauth helpers', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  // ── generateOAuthState ────────────────────────────────────────────────────

  it('generateOAuthState returns a 32+ character hex string', () => {
    const state = generateOAuthState()
    expect(state).toMatch(/^[0-9a-f]{32,}$/)
    expect(state.length).toBeGreaterThanOrEqual(32)
  })

  it('generateOAuthState stores the state in sessionStorage under the well-known key', () => {
    const state = generateOAuthState()
    expect(sessionStorage.getItem(OAUTH_STATE_KEY)).toBe(state)
  })

  it('generateOAuthState produces a different state on each call', () => {
    const a = generateOAuthState()
    const b = generateOAuthState()
    expect(a).not.toBe(b)
  })

  // ── consumeOAuthState ─────────────────────────────────────────────────────

  it('consumeOAuthState returns true when the supplied state matches storage', () => {
    const state = generateOAuthState()
    expect(consumeOAuthState(state)).toBe(true)
  })

  it('consumeOAuthState clears storage on success (single-use replay protection)', () => {
    const state = generateOAuthState()
    consumeOAuthState(state)
    expect(sessionStorage.getItem(OAUTH_STATE_KEY)).toBeNull()
  })

  it('consumeOAuthState returns false on mismatch', () => {
    generateOAuthState()
    expect(consumeOAuthState('not-the-right-state')).toBe(false)
  })

  it('consumeOAuthState returns false when storage is empty', () => {
    expect(consumeOAuthState('whatever')).toBe(false)
  })

  it('consumeOAuthState returns false on second call (already consumed)', () => {
    const state = generateOAuthState()
    expect(consumeOAuthState(state)).toBe(true)
    expect(consumeOAuthState(state)).toBe(false)
  })

  // ── buildGoogleAuthUrl ────────────────────────────────────────────────────

  it('buildGoogleAuthUrl includes the required OAuth params', () => {
    const url = buildGoogleAuthUrl({
      state: 'abc123',
      redirectUri: 'http://localhost:5173/auth/callback/google',
      clientId: 'my-client.apps.googleusercontent.com',
    })
    const parsed = new URL(url)
    expect(parsed.origin + parsed.pathname).toBe('https://accounts.google.com/o/oauth2/v2/auth')
    expect(parsed.searchParams.get('response_type')).toBe('code')
    expect(parsed.searchParams.get('client_id')).toBe('my-client.apps.googleusercontent.com')
    expect(parsed.searchParams.get('redirect_uri')).toBe('http://localhost:5173/auth/callback/google')
    expect(parsed.searchParams.get('scope')).toBe('openid email profile')
    expect(parsed.searchParams.get('state')).toBe('abc123')
  })

  it('buildGoogleAuthUrl URL-encodes the redirect_uri', () => {
    const url = buildGoogleAuthUrl({
      state: 's',
      redirectUri: 'https://werewolf.example/auth/callback/google?x=1',
      clientId: 'c',
    })
    expect(url).toContain('redirect_uri=https%3A%2F%2Fwerewolf.example%2Fauth%2Fcallback%2Fgoogle%3Fx%3D1')
  })
})
