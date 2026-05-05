import http from './http'
import type { LoginResponse, ProvidersResponse, User } from '@/types'

export const userService = {
  async login(nickname: string): Promise<LoginResponse> {
    const { data } = await http.post<LoginResponse>('/user/login', { nickname })
    return data
  },

  async loginWithGoogle(code: string): Promise<LoginResponse> {
    const { data } = await http.post<LoginResponse>('/auth/google', { code })
    return data
  },

  async loginWithWechat(code: string): Promise<LoginResponse> {
    const { data } = await http.post<LoginResponse>('/auth/wechat', { code })
    return data
  },

  async getProviders(): Promise<ProvidersResponse> {
    const { data } = await http.get<ProvidersResponse>('/auth/providers')
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
