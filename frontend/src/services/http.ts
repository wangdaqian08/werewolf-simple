import axios from 'axios'
import { useUserStore } from '@/stores/userStore'

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

// Handle 401 globally — clear session so the UI drops back to the OAuth/guest
// view instead of staying stuck on a stale identity card.
http.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      useUserStore().clearSession()
    }
    return Promise.reject(err)
  },
)

export default http
