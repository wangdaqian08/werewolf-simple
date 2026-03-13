import AxiosMockAdapter from 'axios-mock-adapter'
import http from '@/services/http'
import {
  MOCK_GAME_RESULT,
  MOCK_GAME_STATE,
  MOCK_LOGIN,
  MOCK_ROOM_AS_GUEST,
  MOCK_ROOM_AS_HOST,
  MOCK_STOMP_EVENTS,
  MOCK_SHERIFF_SIGNUP,
  MOCK_SHERIFF_SPEECH_CANDIDATE,
  MOCK_SHERIFF_SPEECH_AUDIENCE,
  MOCK_SHERIFF_VOTING,
  MOCK_SHERIFF_RESULT,
} from './data'
import { mockStompClient } from './mockStompClient'
import type { GameState, RoomPlayer, SheriffElectionState } from '@/types'

// Mutable room state shared across mock endpoints so debug actions see current players.
let mockRoomId = MOCK_ROOM_AS_HOST.roomId
let mockPlayers: RoomPlayer[] = [...MOCK_ROOM_AS_HOST.players]
let mockTotalPlayers = MOCK_ROOM_AS_HOST.config.totalPlayers

const EXTRA_PLAYERS: { userId: string; nickname: string; avatar: string }[] = [
  { userId: 'x1', nickname: '小龙', avatar: '🐉' },
  { userId: 'x2', nickname: 'Nina', avatar: '🦋' },
  { userId: 'x3', nickname: 'Zara', avatar: '🌷' },
  { userId: 'x4', nickname: 'Rex', avatar: '🦁' },
]

// Mutable game state for debug endpoints
let mockGameState: GameState = { ...MOCK_GAME_STATE }

const SHERIFF_PRESETS: Record<string, SheriffElectionState> = {
  SIGNUP: MOCK_SHERIFF_SIGNUP,
  SPEECH_CANDIDATE: MOCK_SHERIFF_SPEECH_CANDIDATE,
  SPEECH_AUDIENCE: MOCK_SHERIFF_SPEECH_AUDIENCE,
  VOTING: MOCK_SHERIFF_VOTING,
  RESULT: MOCK_SHERIFF_RESULT,
}

function pushGameStateUpdate() {
  mockStompClient.fireNow(`/topic/game/${mockGameState.gameId}`, {
    type: 'GAME_STATE_UPDATE',
    payload: mockGameState,
  })
}

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
    mockTotalPlayers = roomConfig.totalPlayers
    return [200, room]
  })
  mock.onPost('/room/join').reply(() => {
    mockRoomId = MOCK_ROOM_AS_GUEST.roomId
    mockPlayers = [...MOCK_ROOM_AS_GUEST.players]
    mockTotalPlayers = MOCK_ROOM_AS_GUEST.config.totalPlayers
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

  // ── Debug: start game (fires GAME_STARTED on room topic) ─────────────────────
  mock.onPost('/debug/game/start').reply(() => {
    mockGameState = {
      ...MOCK_GAME_STATE,
      phase: 'SHERIFF_ELECTION',
      sheriffElection: { ...MOCK_SHERIFF_SIGNUP },
    }
    mockStompClient.fireNow(`/topic/room/${mockRoomId}`, {
      type: 'GAME_STARTED',
      payload: { gameId: mockGameState.gameId },
    })
    return [200]
  })

  // ── Debug: switch sheriff election sub-phase ──────────────────────────────────
  mock.onPost('/debug/sheriff/phase').reply((config) => {
    const { preset } = JSON.parse(config.data ?? '{}')
    const election = SHERIFF_PRESETS[preset]
    if (!election) return [400, { error: 'Unknown preset' }]
    mockGameState = {
      ...mockGameState,
      phase: 'SHERIFF_ELECTION',
      sheriffElection: { ...election },
    }
    pushGameStateUpdate()
    return [200, mockGameState]
  })

  mock.onPost('/debug/sheriff/candidate').reply((config) => {
    if (!mockGameState.sheriffElection) return [400]
    const { userId, nickname, avatar, action } = JSON.parse(config.data ?? '{}')
    const e = mockGameState.sheriffElection
    if (action === 'RUN') {
      if (!e.candidates.some((c) => c.userId === userId)) {
        mockGameState = {
          ...mockGameState,
          sheriffElection: {
            ...e,
            candidates: [...e.candidates, { userId, nickname, avatar, status: 'RUNNING' as const }],
          },
        }
        pushGameStateUpdate()
      }
    } else if (action === 'REMOVE') {
      mockGameState = {
        ...mockGameState,
        sheriffElection: {
          ...e,
          candidates: e.candidates.filter((c) => c.userId !== userId),
        },
      }
      pushGameStateUpdate()
    }
    return [200]
  })

  mock.onPost('/debug/sheriff/exit').reply(() => {
    mockGameState = { ...MOCK_GAME_STATE }
    pushGameStateUpdate()
    return [200]
  })

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

  // ── Debug: add a fake player to the room ──────────────────────────────────────
  mock.onPost('/debug/room/add-player').reply(() => {
    const takenSeats = new Set(mockPlayers.map((p) => p.seatIndex).filter((s) => s !== null))
    const nextSeat = Array.from({ length: mockTotalPlayers }, (_, i) => i + 1).find(
      (s) => !takenSeats.has(s),
    )
    if (nextSeat === undefined) return [400, { error: 'Room is full' }]

    const extraIdx = mockPlayers.filter((p) => p.userId.startsWith('x')).length
    const extra = EXTRA_PLAYERS[extraIdx % EXTRA_PLAYERS.length]!
    mockPlayers = [
      ...mockPlayers,
      {
        userId: extra.userId,
        nickname: extra.nickname,
        avatar: extra.avatar,
        seatIndex: nextSeat,
        status: 'NOT_READY' as const,
        isHost: false,
      },
    ]
    pushRoomUpdate()
    return [200, { players: mockPlayers }]
  })

  // ── Game ──────────────────────────────────────────────────────────────────────
  mock.onGet(/\/game\/state/).reply(() => [200, mockGameState])
  mock.onGet(/\/game\/result/).reply((_config) => [200, MOCK_GAME_RESULT])
  mock.onPost('/game/action').reply((config) => {
    const { actionType, targetId } = JSON.parse(config.data ?? '{}')
    if (mockGameState.phase === 'SHERIFF_ELECTION' && mockGameState.sheriffElection) {
      const e = mockGameState.sheriffElection
      if (actionType === 'SHERIFF_RUN') {
        if (!e.candidates.some((c) => c.userId === MOCK_LOGIN.user.userId)) {
          mockGameState = {
            ...mockGameState,
            sheriffElection: {
              ...e,
              candidates: [
                ...e.candidates,
                { userId: 'u1', nickname: '我', avatar: '⭐', status: 'RUNNING' as const },
              ],
            },
          }
          pushGameStateUpdate()
        }
      } else if (actionType === 'SHERIFF_PASS') {
        mockGameState = {
          ...mockGameState,
          sheriffElection: { ...e, hasPassed: true },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_WITHDRAW') {
        mockGameState = {
          ...mockGameState,
          sheriffElection: {
            ...e,
            candidates: e.candidates.filter((c) => c.userId !== MOCK_LOGIN.user.userId),
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_QUIT_CAMPAIGN') {
        mockGameState = {
          ...mockGameState,
          sheriffElection: {
            ...e,
            candidates: e.candidates.map((c) =>
              c.userId === 'u1' ? { ...c, status: 'QUIT' as const } : c,
            ),
            canVote: false,
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_START_CAMPAIGN') {
        const runningIds = e.candidates.filter((c) => c.status === 'RUNNING').map((c) => c.userId)
        mockGameState = {
          ...mockGameState,
          sheriffElection: {
            ...e,
            subPhase: 'SPEECH',
            speakingOrder: runningIds,
            currentSpeakerId: runningIds[0],
            hasPassed: false,
            timeRemaining: 60,
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_VOTE') {
        mockGameState = {
          ...mockGameState,
          sheriffElection: { ...e, myVote: targetId, abstained: false },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_ABSTAIN') {
        mockGameState = {
          ...mockGameState,
          sheriffElection: { ...e, myVote: undefined, abstained: true },
        }
        pushGameStateUpdate()
      }
    }
    return [200, { success: true }]
  })

  // ── STOMP fake events ─────────────────────────────────────────────────────────
  const { gameVoteEvent } = MOCK_STOMP_EVENTS

  mockStompClient.scheduleEvent(
    gameVoteEvent.delayMs,
    gameVoteEvent.topic(MOCK_GAME_STATE.gameId),
    gameVoteEvent.payload,
  )

  console.warn('[mock] active — set VITE_MOCK=false in .env.development to use real backend')
  console.warn(
    '[mock] debug: POST /debug/ready { userId, ready } to toggle player ready status\n' +
      '  players: ' +
      MOCK_ROOM_AS_HOST.players.map((p) => `${p.userId} (${p.nickname})`).join(', '),
  )
}

export { mockStompClient }
