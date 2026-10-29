import http from './http'
import type { AdminPlayer } from '@/types'

/** Admin credit grants. The backend checks admin + PIN on every call. */
export const adminService = {
  async isAdmin(): Promise<boolean> {
    const { data } = await http.get<{ admin: boolean }>('/admin/status')
    return data.admin
  },

  async searchPlayers(q: string): Promise<AdminPlayer[]> {
    const { data } = await http.get<AdminPlayer[]>('/admin/players', { params: { q } })
    return data
  },

  /** Returns the player with their new balance. */
  async grant(userId: string, amount: number, pin: string): Promise<AdminPlayer> {
    const { data } = await http.post<AdminPlayer>('/admin/grant', { userId, amount, pin })
    return data
  },
}
