import http from './http'
import type {
  ActionLogEntry,
  GameActionRequest,
  GameActionResponse,
  GameReview,
  GameState,
  PlayerReview,
} from '@/types'

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

  async startTimer(gameId: number, durationSeconds: number): Promise<void> {
    await http.post(`/game/${gameId}/timer/start`, { durationSeconds })
  },

  async getReview(gameId: string): Promise<GameReview> {
    const { data } = await http.get<GameReview>(`/game/${gameId}/review`)
    return data
  },

  /** The same game as `getReview`, cut down to what the caller themselves knew. */
  async getPlayerReview(gameId: string): Promise<PlayerReview> {
    const { data } = await http.get<PlayerReview>(`/game/${gameId}/review/me`)
    return data
  },

  async stopTimer(gameId: number): Promise<void> {
    await http.post(`/game/${gameId}/timer/stop`)
  },
}
