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

export function setupMocks() {
  // Expose singleton so stompClient.ts can pick it up synchronously
  ;(globalThis as Record<string, unknown>).__mockStompClient = mockStompClient

  const mock = new AxiosMockAdapter(http, { delayResponse: 300 })

  // ── User ──────────────────────────────────────────────────────────────────────
  mock.onPost('/user/login').reply(200, MOCK_LOGIN)
  mock.onGet('/user/profile').reply(200, MOCK_LOGIN.user)
  mock.onPost('/user/logout').reply(200)

  // ── Room ──────────────────────────────────────────────────────────────────────
  mock.onPost('/room/create').reply(200, MOCK_ROOM_AS_HOST)
  mock.onPost('/room/join').reply(200, MOCK_ROOM_AS_GUEST)
  mock.onPost('/room/leave').reply(200)
  mock.onPost('/room/ready').reply(200)
  mock.onGet(`/room/${MOCK_ROOM_AS_HOST.roomId}`).reply(200, MOCK_ROOM_AS_HOST)
  mock.onGet(`/room/${MOCK_ROOM_AS_GUEST.roomId}`).reply(200, MOCK_ROOM_AS_GUEST)
  mock.onGet('/room/list').reply(200, [MOCK_ROOM_AS_HOST])

  // ── Game ──────────────────────────────────────────────────────────────────────
  mock.onGet(/\/game\/state/).reply((_config) => [200, MOCK_GAME_STATE])
  mock.onGet(/\/game\/result/).reply((_config) => [200, MOCK_GAME_RESULT])
  mock.onPost('/game/action').reply(200, { success: true })

  // ── STOMP fake events ─────────────────────────────────────────────────────────
  // Events are registered here (data stays in data.ts) but fired relative to
  // when activate() is called — so they always arrive after the view subscribes.
  const { roomPlayerReady, gameVoteEvent } = MOCK_STOMP_EVENTS

  mockStompClient.scheduleEvent(
    roomPlayerReady.delayMs,
    roomPlayerReady.topic(MOCK_ROOM_AS_HOST.roomId),
    roomPlayerReady.payload,
  )

  mockStompClient.scheduleEvent(
    gameVoteEvent.delayMs,
    gameVoteEvent.topic(MOCK_GAME_STATE.gameId),
    gameVoteEvent.payload,
  )

  console.warn('[mock] active — set VITE_MOCK=false in .env.development to use real backend')
}

export { mockStompClient }
