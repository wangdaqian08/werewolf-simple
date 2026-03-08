import http from './http'
import type { CreateRoomRequest, JoinRoomRequest, Room } from '@/types'

export const roomService = {
  async createRoom(req: CreateRoomRequest): Promise<Room> {
    const { data } = await http.post<Room>('/room/create', req)
    return data
  },

  async joinRoom(req: JoinRoomRequest): Promise<Room> {
    const { data } = await http.post<Room>('/room/join', req)
    return data
  },

  async leaveRoom(): Promise<void> {
    await http.post('/room/leave')
  },

  async getRoom(roomId: string): Promise<Room> {
    const { data } = await http.get<Room>(`/room/${roomId}`)
    return data
  },

  async getRoomList(): Promise<Room[]> {
    const { data } = await http.get<Room[]>('/room/list')
    return data
  },

  async setReady(ready: boolean): Promise<void> {
    await http.post('/room/ready', { ready })
  },
}
