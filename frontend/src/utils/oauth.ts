/**
 * OAuth helpers for the Authorization Code redirect flow.
 *
 * Flow:
 *   1. Lobby button click → generateOAuthState() → buildGoogleAuthUrl() →
 *      window.location.href = …  (browser redirects to Google).
 *   2. Google returns to /auth/callback/google?code=…&state=…
 *   3. OAuthCallbackView calls consumeOAuthState() to verify the state, then
 *      POSTs the code to the backend.
 *
 * Storage choice: sessionStorage. Survives same-origin redirects (incl. Safari
 * ITP) but is naturally scoped to the tab — so a stale state from one tab
 * cannot be replayed in another.
 */

export const OAUTH_STATE_KEY = 'oauth_state'

const STATE_BYTE_LENGTH = 16 // → 32 hex chars

export function generateOAuthState(): string {
  const bytes = new Uint8Array(STATE_BYTE_LENGTH)
  crypto.getRandomValues(bytes)
  const state = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  sessionStorage.setItem(OAUTH_STATE_KEY, state)
  return state
}

export function consumeOAuthState(supplied: string): boolean {
  const stored = sessionStorage.getItem(OAUTH_STATE_KEY)
  // Always clear — single-use, even on mismatch (stops replay).
  sessionStorage.removeItem(OAUTH_STATE_KEY)
  return !!stored && stored === supplied
}

export interface GoogleAuthUrlOptions {
  state: string
  redirectUri: string
  clientId: string
}

const GOOGLE_AUTH_ENDPOINT = 'https://accounts.google.com/o/oauth2/v2/auth'

export function buildGoogleAuthUrl(opts: GoogleAuthUrlOptions): string {
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: opts.clientId,
    redirect_uri: opts.redirectUri,
    scope: 'openid email profile',
    state: opts.state,
    access_type: 'online',
    prompt: 'select_account',
  })
  return `${GOOGLE_AUTH_ENDPOINT}?${params.toString()}`
}
