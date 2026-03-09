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

export function setupMocks() {
  // Expose singleton so stompClient.ts can pick it up synchronously
  ;(globalThis as Record<string, unknown>).__mockStompClient = mockStompClient

  const mock = new AxiosMockAdapter(http, { delayResponse: 300 })

  // ── User ──────────────────────────────────────────────────────────────────────
  mock.onPost('/user/login').reply(200, MOCK_LOGIN)
  mock.onGet('/user/profile').reply(200, MOCK_LOGIN.user)
  mock.onPost('/user/logout').reply(200)

  // ── Room ──────────────────────────────────────────────────────────────────────
  // Mutable reference to the current player list for the active host room.
  // Updated by POST /room/seat so that lazy STOMP events see the current state.
  let activePlayers: RoomPlayer[] = []

  mock.onPost('/room/create').reply((config) => {
    const body = JSON.parse(config.data ?? '{}')
    const roomConfig = body.config ?? MOCK_ROOM_AS_HOST.config
    // Host occupies 1 seat, so guests fill at most totalPlayers - 1 seats.
    const players = MOCK_ROOM_AS_HOST.players.filter(
      (p) => p.isHost || (p.seatIndex !== null && p.seatIndex <= roomConfig.totalPlayers - 1),
    )
    activePlayers = [...players]
    const room = { ...MOCK_ROOM_AS_HOST, config: roomConfig, players }

    // Reschedule room STOMP events using the actual player list for this room
    const { carolReady, allReady } = MOCK_STOMP_EVENTS
    mockStompClient.resetSchedule()
    mockStompClient.scheduleEvent(
      carolReady.delayMs,
      carolReady.topic(room.roomId),
      carolReady.buildPayload(players),
    )
    // For 12-player rooms, suppress allReady so seats stay empty for host seat-change testing.
    // Use a lazy function so the payload is built at fire time — capturing seat updates
    // (e.g. host claiming a seat) that happened after schedule time.
    if (roomConfig.totalPlayers < 12) {
      mockStompClient.scheduleEvent(allReady.delayMs, allReady.topic(room.roomId), () =>
        allReady.buildPayload(activePlayers),
      )
    }
    mockStompClient.scheduleEvent(
      gameVoteEvent.delayMs,
      gameVoteEvent.topic(MOCK_GAME_STATE.gameId),
      gameVoteEvent.payload,
    )

    return [200, room]
  })
  mock.onPost('/room/join').reply(200, MOCK_ROOM_AS_GUEST)
  mock.onPost('/room/leave').reply(200)
  mock.onPost('/room/ready').reply(200)
  // Seat claim: update activePlayers so lazy STOMP events see current state
  mock.onPost('/room/seat').reply((config) => {
    const body = JSON.parse(config.data ?? '{}')
    activePlayers = activePlayers.map((p) =>
      p.userId === MOCK_LOGIN.user.userId ? { ...p, seatIndex: body.seatIndex } : p,
    )
    return [200]
  })
  mock.onGet(`/room/${MOCK_ROOM_AS_HOST.roomId}`).reply(200, MOCK_ROOM_AS_HOST)
  mock.onGet(`/room/${MOCK_ROOM_AS_GUEST.roomId}`).reply(200, MOCK_ROOM_AS_GUEST)
  mock.onGet('/room/list').reply(200, [MOCK_ROOM_AS_HOST])

  // ── Game ──────────────────────────────────────────────────────────────────────
  mock.onGet(/\/game\/state/).reply((_config) => [200, MOCK_GAME_STATE])
  mock.onGet(/\/game\/result/).reply((_config) => [200, MOCK_GAME_RESULT])
  mock.onPost('/game/action').reply(200, { success: true })

  // ── STOMP fake events ─────────────────────────────────────────────────────────
  // Room events (carolReady, allReady) are scheduled dynamically inside the
  // createRoom mock above, using the actual filtered player list for the room.
  // Only the game vote event is static (game phase, not room phase).
  const { gameVoteEvent } = MOCK_STOMP_EVENTS

  mockStompClient.scheduleEvent(
    gameVoteEvent.delayMs,
    gameVoteEvent.topic(MOCK_GAME_STATE.gameId),
    gameVoteEvent.payload,
  )

  console.warn('[mock] active — set VITE_MOCK=false in .env.development to use real backend')
}

export { mockStompClient }
