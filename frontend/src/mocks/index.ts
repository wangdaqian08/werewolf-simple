import AxiosMockAdapter from 'axios-mock-adapter'
import http from '@/services/http'
import {
  MOCK_GAME_RESULT,
  MOCK_GAME_STATE,
  MOCK_LOGIN,
  MOCK_ROOM_AS_GUEST,
  MOCK_ROOM_AS_HOST,
  MOCK_STOMP_EVENTS,
} from './data'
import { mockStompClient } from './mockStompClient'
import type { RoomPlayer } from '@/types'

// Mutable room state shared across mock endpoints so debug actions see current players.
let mockRoomId = MOCK_ROOM_AS_HOST.roomId
let mockPlayers: RoomPlayer[] = [...MOCK_ROOM_AS_HOST.players]

function pushRoomUpdate() {
  mockStompClient.fireNow(`/topic/room/${mockRoomId}`, {
    type: 'ROOM_UPDATE',
    payload: { players: mockPlayers },
  })
}

export function setupMocks() {
  // Expose singleton so stompClient.ts can pick it up synchronously
  ;(globalThis as Record<string, unknown>).__mockStompClient = mockStompClient

  const mock = new AxiosMockAdapter(http, { delayResponse: 300 })

  // ── User ──────────────────────────────────────────────────────────────────────
  mock.onPost('/user/login').reply(200, MOCK_LOGIN)
  mock.onGet('/user/profile').reply(200, MOCK_LOGIN.user)
  mock.onPost('/user/logout').reply(200)

  // ── Room ──────────────────────────────────────────────────────────────────────
  mock.onPost('/room/create').reply((config) => {
    const body = JSON.parse(config.data ?? '{}')
    const roomConfig = body.config ?? MOCK_ROOM_AS_HOST.config
    // Host occupies 1 seat, so guests fill at most totalPlayers - 1 seats.
    const players = MOCK_ROOM_AS_HOST.players.filter(
      (p) => p.isHost || (p.seatIndex !== null && p.seatIndex <= roomConfig.totalPlayers - 1),
    )
    const room = { ...MOCK_ROOM_AS_HOST, config: roomConfig, players }
    mockRoomId = room.roomId
    mockPlayers = [...players]
    return [200, room]
  })
  mock.onPost('/room/join').reply(() => {
    mockRoomId = MOCK_ROOM_AS_GUEST.roomId
    mockPlayers = [...MOCK_ROOM_AS_GUEST.players]
    return [200, MOCK_ROOM_AS_GUEST]
  })
  mock.onPost('/room/leave').reply(200)
  mock.onPost('/room/ready').reply(200)
  mock.onPost('/room/seat').reply((config) => {
    const body = JSON.parse(config.data ?? '{}')
    mockPlayers = mockPlayers.map((p) =>
      p.userId === MOCK_LOGIN.user.userId ? { ...p, seatIndex: body.seatIndex } : p,
    )
    return [200]
  })
  mock.onGet(`/room/${MOCK_ROOM_AS_HOST.roomId}`).reply(200, MOCK_ROOM_AS_HOST)
  mock.onGet(`/room/${MOCK_ROOM_AS_GUEST.roomId}`).reply(200, MOCK_ROOM_AS_GUEST)
  mock.onGet('/room/list').reply(200, [MOCK_ROOM_AS_HOST])

  // ── Debug: manually set a player's ready status ───────────────────────────────
  // POST /debug/ready  { userId: string, ready: boolean }
  // Fires a STOMP ROOM_UPDATE immediately so the UI reacts.
  mock.onPost('/debug/ready').reply((config) => {
    const { userId, ready } = JSON.parse(config.data ?? '{}')
    mockPlayers = mockPlayers.map((p) =>
      p.userId === userId ? { ...p, status: ready ? 'READY' : 'NOT_READY' } : p,
    )
    pushRoomUpdate()
    return [200, { players: mockPlayers }]
  })

  // ── Game ──────────────────────────────────────────────────────────────────────
  mock.onGet(/\/game\/state/).reply((_config) => [200, MOCK_GAME_STATE])
  mock.onGet(/\/game\/result/).reply((_config) => [200, MOCK_GAME_RESULT])
  mock.onPost('/game/action').reply(200, { success: true })

  // ── STOMP fake events ─────────────────────────────────────────────────────────
  const { gameVoteEvent } = MOCK_STOMP_EVENTS

  mockStompClient.scheduleEvent(
    gameVoteEvent.delayMs,
    gameVoteEvent.topic(MOCK_GAME_STATE.gameId),
    gameVoteEvent.payload,
  )

  console.warn('[mock] active — set VITE_MOCK=false in .env.development to use real backend')
  console.info(
    '[mock] debug: POST /debug/ready { userId, ready } to toggle player ready status\n' +
      '  players:',
    MOCK_ROOM_AS_HOST.players.map((p) => `${p.userId} (${p.nickname})`).join(', '),
  )
}

export { mockStompClient }
