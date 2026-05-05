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

export const useUserStore = defineStore('user', () => {
  // All four values are persisted so they survive page refresh
  const token = ref<string | null>(localStorage.getItem('jwt'))
  const userId = ref<string | null>(localStorage.getItem('userId'))
  const nickname = ref<string | null>(localStorage.getItem('nickname'))
  const avatarUrl = ref<string | null>(localStorage.getItem('avatarUrl'))

  const isLoggedIn = computed(() => !!token.value && !!userId.value)

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
  }

  async function login(nick: string) {
    if (hasValidSession(nick)) return
    const res = await userService.login(nick)
    applySession(res.token, res.user.userId, res.user.nickname, res.user.avatarUrl)
  }

  async function loginWithCode(provider: OAuthProvider, code: string) {
    const res = provider === 'google'
      ? await userService.loginWithGoogle(code)
      : await userService.loginWithWechat(code)
    applySession(res.token, res.user.userId, res.user.nickname, res.user.avatarUrl)
  }

  async function logout() {
    try {
      await userService.logout()
    } finally {
      token.value = null
      userId.value = null
      nickname.value = null
      avatarUrl.value = null
      localStorage.removeItem('jwt')
      localStorage.removeItem('userId')
      localStorage.removeItem('nickname')
      localStorage.removeItem('avatarUrl')
    }
  }

  return {
    token,
    userId,
    nickname,
    avatarUrl,
    isLoggedIn,
    login,
    loginWithCode,
    logout,
  }
})
