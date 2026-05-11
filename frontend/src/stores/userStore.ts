import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { userService } from '@/services/userService'
import type { OAuthProvider } from '@/types'

function isTokenExpired(token: string): boolean {
  try {
    const [, part] = token.split('.')
    if (!part) return true
    const payload = JSON.parse(atob(part))
    return payload.exp * 1000 < Date.now()
  } catch {
    return true
  }
}

// Drop persisted session if the stored JWT is already expired, so the store
// never boots into a half-valid "looks logged in but every request 401s" state.
function loadPersistedSession() {
  const token = localStorage.getItem('jwt')
  if (token && isTokenExpired(token)) {
    localStorage.removeItem('jwt')
    localStorage.removeItem('userId')
    localStorage.removeItem('nickname')
    localStorage.removeItem('avatarUrl')
    return { token: null, userId: null, nickname: null, avatarUrl: null }
  }
  return {
    token,
    userId: localStorage.getItem('userId'),
    nickname: localStorage.getItem('nickname'),
    avatarUrl: localStorage.getItem('avatarUrl'),
  }
}

export const useUserStore = defineStore('user', () => {
  // All four values are persisted so they survive page refresh
  const persisted = loadPersistedSession()
  const token = ref<string | null>(persisted.token)
  const userId = ref<string | null>(persisted.userId)
  const nickname = ref<string | null>(persisted.nickname)
  const avatarUrl = ref<string | null>(persisted.avatarUrl)

  // Per-room nickname override (Option A from the OAuth follow-up). Lives in
  // sessionStorage — survives a Lobby → CreateRoom navigation refresh, but
  // resets when the tab/window closes (which matches the "this game session"
  // semantics). Backend stores it on RoomPlayer.display_name; the User row's
  // nickname is left intact so the next OAuth login re-syncs from provider.
  const displayName = ref<string | null>(sessionStorage.getItem('displayName'))

  const isLoggedIn = computed(
    () => !!token.value && !!userId.value && !isTokenExpired(token.value),
  )

  function hasValidSession(nick: string): boolean {
    return !!token.value && nickname.value === nick && !isTokenExpired(token.value)
  }

  function applySession(t: string, uid: string, nick: string, avatar: string | null | undefined) {
    token.value = t
    userId.value = uid
    nickname.value = nick
    avatarUrl.value = avatar ?? null
    localStorage.setItem('jwt', t)
    localStorage.setItem('userId', uid)
    localStorage.setItem('nickname', nick)
    if (avatar) localStorage.setItem('avatarUrl', avatar)
    else localStorage.removeItem('avatarUrl')
    // Drop any leftover override from a previous session — the new account
    // gets a fresh display-name slate, defaulting to the provider nickname
    // until the user types something.
    clearDisplayName()
  }

  function setDisplayName(value: string | null) {
    const trimmed = value?.trim() ?? ''
    if (trimmed.length === 0) {
      displayName.value = null
      sessionStorage.removeItem('displayName')
    } else {
      displayName.value = trimmed
      sessionStorage.setItem('displayName', trimmed)
    }
  }

  function clearDisplayName() {
    displayName.value = null
    sessionStorage.removeItem('displayName')
  }

  async function login(nick: string) {
    if (hasValidSession(nick)) return
    const res = await userService.login(nick)
    applySession(res.token, res.user.userId, res.user.nickname, res.user.avatarUrl)
  }

  async function loginWithCode(provider: OAuthProvider, code: string) {
    const res =
      provider === 'google'
        ? await userService.loginWithGoogle(code)
        : await userService.loginWithWechat(code)
    applySession(res.token, res.user.userId, res.user.nickname, res.user.avatarUrl)
  }

  // Wipe both in-memory refs and persisted storage. Called from logout() and
  // from the 401 interceptor when the backend rejects an expired token.
  function clearSession() {
    token.value = null
    userId.value = null
    nickname.value = null
    avatarUrl.value = null
    localStorage.removeItem('jwt')
    localStorage.removeItem('userId')
    localStorage.removeItem('nickname')
    localStorage.removeItem('avatarUrl')
    clearDisplayName()
  }

  async function logout() {
    try {
      await userService.logout()
    } finally {
      clearSession()
    }
  }

  return {
    token,
    userId,
    nickname,
    avatarUrl,
    displayName,
    isLoggedIn,
    login,
    loginWithCode,
    logout,
    clearSession,
    setDisplayName,
  }
})
