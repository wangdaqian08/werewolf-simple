import http from './http'
import type { GameActionRequest, GameActionResponse, GameState } from '@/types'

export const gameService = {
  async getState(gameId: string): Promise<GameState> {
    const { data } = await http.get<GameState>(`/game/${gameId}/state`)
    return data
  },

  async submitAction(req: GameActionRequest): Promise<GameActionResponse> {
    const { data } = await http.post<GameActionResponse>('/game/action', req)
    return data
  },

  async startGame(roomId: number): Promise<void> {
    await http.post('/game/start', { roomId })
  },
}
