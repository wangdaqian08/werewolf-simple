import http from './http'
import type { ActionLogEntry, GameActionRequest, GameActionResponse, GameState } from '@/types'

export const gameService = {
  async getState(gameId: string): Promise<GameState> {
    const { data } = await http.get<GameState>(`/game/${gameId}/state`)
    return data
  },

  async submitAction(req: GameActionRequest): Promise<GameActionResponse> {
    try {
      const { data } = await http.post<GameActionResponse>('/game/action', req)
      return data
    } catch (err: unknown) {
      const axiosErr = err as {
        response?: { status?: number; data?: { success?: boolean; error?: string } }
      }
      if (axiosErr.response?.status === 400 && axiosErr.response.data) {
        return { success: false, message: axiosErr.response.data.error }
      }
      throw err
    }
  },

  async startGame(roomId: number): Promise<void> {
    await http.post('/game/start', { roomId })
  },

  async getActionLog(gameId: number): Promise<ActionLogEntry[]> {
    const { data } = await http.get<ActionLogEntry[]>(`/game/${gameId}/events`)
    return data
  },
}
