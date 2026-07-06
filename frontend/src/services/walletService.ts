import http from './http'
import type { MyPerkActivation, Wallet } from '@/types'

export const walletService = {
  async getWallet(): Promise<Wallet> {
    const { data } = await http.get<Wallet>('/wallet')
    return data
  },

  /** The caller's own perk activation history (most recent first). */
  async getMyPerks(): Promise<MyPerkActivation[]> {
    const { data } = await http.get<MyPerkActivation[]>('/perks/my')
    return data
  },
}
