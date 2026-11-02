import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { i18n } from '@/i18n'
import { useUserStore } from '@/stores/userStore'
import { saveResume } from './sessionResume'

const http = axios.create({
  baseURL: '/api',
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' },
})

// Attach JWT to every request
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('jwt')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

/**
 * The login is gone (JWT expired or rejected): clear it and send the player to the
 * login screen. Mid-game they would otherwise stay on the page as a control-less spectator.
 */
export function onSessionExpired(): void {
  const userStore = useUserStore()
  if (!userStore.token) return // already handled (several requests can fail at once)
  const here = router.currentRoute.value
  // Logging in again brings the player back here (same nickname = same guest account).
  saveResume({
    path: here.name === 'lobby' || here.name === 'auth-callback' ? undefined : here.fullPath,
    nickname: userStore.nickname ?? undefined,
    guest: userStore.isGuest,
  })
  userStore.clearSession()
  if (here.name !== 'lobby') {
    ElMessage({ message: i18n.global.t('common.sessionExpired'), type: 'warning', duration: 4000 })
    void router.push({ name: 'lobby' })
  }
}

http.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) onSessionExpired()
    return Promise.reject(err)
  },
)

export default http
