import http from './http'
import type { Wallet } from '@/types'

export const walletService = {
  async getWallet(): Promise<Wallet> {
    const { data } = await http.get<Wallet>('/wallet')
    return data
  },
}
