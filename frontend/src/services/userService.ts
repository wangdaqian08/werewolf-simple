import http from './http'
import type { LoginResponse, User } from '@/types'

export const userService = {
  async login(nickname: string): Promise<LoginResponse> {
    const { data } = await http.post<LoginResponse>('/user/login', { nickname })
    return data
  },

  async getProfile(): Promise<User> {
    const { data } = await http.get<User>('/user/profile')
    return data
  },

  async logout(): Promise<void> {
    await http.post('/user/logout')
  },
}
