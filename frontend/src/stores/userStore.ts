import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { userService } from '@/services/userService'

function isTokenExpired(token: string): boolean {
  try {
    const payload = JSON.parse(atob(token.split('.')[1]))
    return payload.exp * 1000 < Date.now()
  } catch {
    return true
  }
}

export const useUserStore = defineStore('user', () => {
  // All three values are persisted so they survive page refresh
  const token = ref<string | null>(localStorage.getItem('jwt'))
  const userId = ref<string | null>(localStorage.getItem('userId'))
  const nickname = ref<string | null>(localStorage.getItem('nickname'))

  const isLoggedIn = computed(() => !!token.value && !!userId.value)

  function hasValidSession(nick: string): boolean {
    return !!token.value && nickname.value === nick && !isTokenExpired(token.value)
  }

  async function login(nick: string) {
    if (hasValidSession(nick)) return
    const res = await userService.login(nick)
    token.value = res.token
    userId.value = res.user.userId
    nickname.value = res.user.nickname
    localStorage.setItem('jwt', res.token)
    localStorage.setItem('userId', res.user.userId)
    localStorage.setItem('nickname', res.user.nickname)
  }

  async function logout() {
    try {
      await userService.logout()
    } finally {
      token.value = null
      userId.value = null
      nickname.value = null
      localStorage.removeItem('jwt')
      localStorage.removeItem('userId')
      localStorage.removeItem('nickname')
    }
  }

  return { token, userId, nickname, isLoggedIn, login, logout }
})
