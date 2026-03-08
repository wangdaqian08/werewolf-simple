/**
 * ─────────────────────────────────────────────────────────────────────────────
 * MOCK DATA — single source of truth for all fake API responses.
 *
 * When the backend is ready, delete this file and remove the mock setup from
 * main.ts (the `if (import.meta.env.VITE_MOCK)` block).
 * ─────────────────────────────────────────────────────────────────────────────
 */

import type { GameState, LoginResponse, Room, RoomPlayer } from '@/types'

// ── Auth ──────────────────────────────────────────────────────────────────────

export const MOCK_LOGIN: LoginResponse = {
  token: 'mock-jwt-token-abc123',
  user: { userId: 'u1', nickname: 'You' },
}

// ── Room (as host) ────────────────────────────────────────────────────────────
// u1 is the logged-in user (host). 8 of 12 seats filled.
// Guests Carol (u4) and 阿强 (u7) are NOT_READY — Start Game stays disabled
// until the STOMP event makes all guests ready 3s after room loads.

export const MOCK_ROOM_AS_HOST: Room = {
  roomId: 'room-001',
  roomCode: 'ABC123',
  hostId: 'u1',
  status: 'WAITING',
  config: {
    totalPlayers: 12,
    roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER'],
  },
  players: [
    {
      userId: 'u1',
      nickname: 'You',
      seatIndex: 1,
      status: 'NOT_READY',
      isHost: true,
      avatar: '⭐',
    },
    { userId: 'u2', nickname: 'Alice', seatIndex: 2, status: 'READY', isHost: false, avatar: '😊' },
    { userId: 'u3', nickname: 'Bob', seatIndex: 3, status: 'READY', isHost: false, avatar: '🎭' },
    {
      userId: 'u4',
      nickname: 'Carol',
      seatIndex: 4,
      status: 'NOT_READY',
      isHost: false,
      avatar: '🌙',
    },
    { userId: 'u5', nickname: 'Dave', seatIndex: 5, status: 'READY', isHost: false, avatar: '🌸' },
    { userId: 'u6', nickname: 'Tom', seatIndex: 6, status: 'READY', isHost: false, avatar: '🐯' },
    {
      userId: 'u7',
      nickname: '阿强',
      seatIndex: 7,
      status: 'NOT_READY',
      isHost: false,
      avatar: '🎸',
    },
    { userId: 'u8', nickname: '波波', seatIndex: 8, status: 'READY', isHost: false, avatar: '🌊' },
    {
      userId: 'u9',
      nickname: '小花',
      seatIndex: 9,
      status: 'NOT_READY',
      isHost: false,
      avatar: '🌺',
    },
    {
      userId: 'u10',
      nickname: '大牛',
      seatIndex: 10,
      status: 'READY',
      isHost: false,
      avatar: '🐮',
    },
    {
      userId: 'u11',
      nickname: '阿明',
      seatIndex: 11,
      status: 'NOT_READY',
      isHost: false,
      avatar: '🌟',
    },
    {
      userId: 'u12',
      nickname: '小鱼',
      seatIndex: 12,
      status: 'READY',
      isHost: false,
      avatar: '🐟',
    },
  ],
}

// ── Room (as guest) ───────────────────────────────────────────────────────────
// u2 is host, u1 (You) is a guest. 8 of 12 seats filled.

export const MOCK_ROOM_AS_GUEST: Room = {
  roomId: 'room-002',
  roomCode: 'XYZ789',
  hostId: 'u2',
  status: 'WAITING',
  config: {
    totalPlayers: 12,
    roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER'],
  },
  players: [
    {
      userId: 'u2',
      nickname: 'Alice',
      seatIndex: 1,
      status: 'NOT_READY',
      isHost: true,
      avatar: '⭐',
    },
    {
      userId: 'u1',
      nickname: 'You',
      seatIndex: 2,
      status: 'NOT_READY',
      isHost: false,
      avatar: '🎸',
    },
    { userId: 'u3', nickname: 'Bob', seatIndex: 3, status: 'READY', isHost: false, avatar: '🎭' },
    {
      userId: 'u4',
      nickname: 'Carol',
      seatIndex: 4,
      status: 'NOT_READY',
      isHost: false,
      avatar: '🌙',
    },
    { userId: 'u5', nickname: 'Dave', seatIndex: 5, status: 'READY', isHost: false, avatar: '🌸' },
    { userId: 'u6', nickname: 'Tom', seatIndex: 6, status: 'READY', isHost: false, avatar: '🐯' },
    { userId: 'u7', nickname: '小明', seatIndex: 7, status: 'READY', isHost: false, avatar: '😊' },
    { userId: 'u8', nickname: '波波', seatIndex: 8, status: 'READY', isHost: false, avatar: '🌊' },
  ],
}

// ── Game state ────────────────────────────────────────────────────────────────

export const MOCK_GAME_STATE: GameState = {
  gameId: 'game-001',
  phase: 'DAY',
  dayNumber: 1,
  myRole: 'SEER',
  sheriff: 'u2',
  players: [
    { userId: 'u1', nickname: 'You', seatIndex: 1, isAlive: true, isSheriff: false },
    { userId: 'u2', nickname: 'Alice', seatIndex: 2, isAlive: true, isSheriff: true },
    { userId: 'u3', nickname: 'Bob', seatIndex: 3, isAlive: true, isSheriff: false },
    { userId: 'u4', nickname: 'Carol', seatIndex: 4, isAlive: false, isSheriff: false },
    { userId: 'u5', nickname: 'Dave', seatIndex: 5, isAlive: true, isSheriff: false },
    { userId: 'u6', nickname: 'Eve', seatIndex: 6, isAlive: true, isSheriff: false },
    { userId: 'u7', nickname: 'Frank', seatIndex: 7, isAlive: true, isSheriff: false },
    { userId: 'u8', nickname: 'Grace', seatIndex: 8, isAlive: true, isSheriff: false },
    { userId: 'u9', nickname: 'Hank', seatIndex: 9, isAlive: true, isSheriff: false },
  ],
  events: [
    {
      type: 'NIGHT_RESULT',
      message: '☀️ Day 1 begins. Carol was killed last night.',
      timestamp: Date.now() - 5000,
    },
    { type: 'ROLE_INFO', message: '🔮 Your role: Seer', timestamp: Date.now() - 4000 },
  ],
}

// ── Result (game over) ────────────────────────────────────────────────────────

export const MOCK_GAME_RESULT = {
  ...MOCK_GAME_STATE,
  phase: 'GAME_OVER' as const,
  winner: 'VILLAGER',
  players: MOCK_GAME_STATE.players.map((p) => ({
    ...p,
    // roles revealed at game end
    role: (['u3', 'u6'] as string[]).includes(p.userId) ? 'WEREWOLF' : 'VILLAGER',
  })),
}

// ── Simulated STOMP events (pushed after a delay in mock mode) ────────────────
// These are the payloads that would normally come from the backend via WebSocket.

export const MOCK_STOMP_EVENTS = {
  // t=2s — first NOT_READY guest becomes ready (whoever that is in the configured player list).
  carolReady: {
    delayMs: 2000,
    topic: (roomId: string) => `/topic/room/${roomId}`,
    buildPayload: (players: RoomPlayer[]) => {
      const first = players.find((p) => !p.isHost && p.status === 'NOT_READY')
      return {
        type: 'ROOM_UPDATE',
        payload: { players: players.map((p) => (p === first ? { ...p, status: 'READY' } : p)) },
      }
    },
  },

  // t=4s — all guests READY → canStart = true → button enables.
  allReady: {
    delayMs: 4000,
    topic: (roomId: string) => `/topic/room/${roomId}`,
    buildPayload: (players: RoomPlayer[]) => ({
      type: 'ROOM_UPDATE',
      payload: { players: players.map((p) => (p.isHost ? p : { ...p, status: 'READY' })) },
    }),
  },

  // Simulate a vote event 5s into the game
  gameVoteEvent: {
    delayMs: 5000,
    topic: (gameId: string) => `/topic/game/${gameId}`,
    payload: {
      type: 'GAME_EVENT',
      payload: {
        type: 'VOTE_CAST',
        message: '🗳️ Alice voted for Frank.',
        timestamp: Date.now(),
      },
    },
  },
}
