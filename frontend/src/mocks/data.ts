/**
 * ─────────────────────────────────────────────────────────────────────────────
 * MOCK DATA — single source of truth for all fake API responses.
 *
 * When the backend is ready, delete this file and remove the mock setup from
 * main.ts (the `if (import.meta.env.VITE_MOCK)` block).
 * ─────────────────────────────────────────────────────────────────────────────
 */

import type {
  DayPhaseState,
  GameState,
  LoginResponse,
  NightPhaseState,
  PlayerRole,
  Room,
  RoleRevealState,
  SheriffElectionState,
} from '@/types'

// ── Auth ──────────────────────────────────────────────────────────────────────

export const MOCK_LOGIN: LoginResponse = {
  token: 'mock-jwt-token-abc123',
  user: { userId: 'u1', nickname: 'You' },
}

// ── Room (as host) ────────────────────────────────────────────────────────────
// u1 is the logged-in user (host), seatIndex null — must pick like everyone else.
// 8 guests occupy seats 1–8; host + guests exactly fill a 9-player room.

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
      seatIndex: null,
      status: 'NOT_READY',
      isHost: true,
      avatar: '⭐',
    },
    { userId: 'u2', nickname: 'Alice', seatIndex: 1, status: 'READY', isHost: false, avatar: '😊' },
    {
      userId: 'u3',
      nickname: 'Bob',
      seatIndex: 2,
      status: 'NOT_READY',
      isHost: false,
      avatar: '🎭',
    },
    { userId: 'u4', nickname: 'Carol', seatIndex: 3, status: 'READY', isHost: false, avatar: '🌙' },
    { userId: 'u5', nickname: 'Dave', seatIndex: 4, status: 'READY', isHost: false, avatar: '🌸' },
    { userId: 'u6', nickname: 'Tom', seatIndex: 5, status: 'READY', isHost: false, avatar: '🐯' },
    { userId: 'u7', nickname: '阿强', seatIndex: 6, status: 'READY', isHost: false, avatar: '🎸' },
    { userId: 'u8', nickname: '波波', seatIndex: 7, status: 'READY', isHost: false, avatar: '🌊' },
    { userId: 'u9', nickname: '小花', seatIndex: 8, status: 'READY', isHost: false, avatar: '🌺' },
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
    totalPlayers: 9,
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
      seatIndex: null, // guest must pick a number before readying up
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
  hostId: 'u1',
  players: [
    { userId: 'u1', nickname: 'You', seatIndex: 1, isAlive: true, isSheriff: false, avatar: '⭐' },
    { userId: 'u2', nickname: 'Alice', seatIndex: 2, isAlive: true, isSheriff: true, avatar: '😊' },
    { userId: 'u3', nickname: 'Bob', seatIndex: 3, isAlive: true, isSheriff: false, avatar: '🎭' },
    {
      userId: 'u4',
      nickname: 'Carol',
      seatIndex: 4,
      isAlive: false,
      isSheriff: false,
      avatar: '🌙',
    },
    { userId: 'u5', nickname: 'Dave', seatIndex: 5, isAlive: true, isSheriff: false, avatar: '🌸' },
    { userId: 'u6', nickname: 'Eve', seatIndex: 6, isAlive: true, isSheriff: false, avatar: '🦊' },
    {
      userId: 'u7',
      nickname: 'Frank',
      seatIndex: 7,
      isAlive: true,
      isSheriff: false,
      avatar: '🎸',
    },
    {
      userId: 'u8',
      nickname: 'Grace',
      seatIndex: 8,
      isAlive: true,
      isSheriff: false,
      avatar: '🌺',
    },
    { userId: 'u9', nickname: 'Hank', seatIndex: 9, isAlive: true, isSheriff: false, avatar: '🐯' },
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

// ── Role assignments (userId → PlayerRole) ────────────────────────────────────
export const MOCK_ROLE_ASSIGNMENTS: Record<string, PlayerRole> = {
  u1: 'SEER',
  u2: 'WEREWOLF',
  u3: 'VILLAGER',
  u4: 'VILLAGER',
  u5: 'WITCH',
  u6: 'WEREWOLF',
  u7: 'HUNTER',
  u8: 'VILLAGER',
  u9: 'GUARD',
}

export function makeRoleRevealState(totalCount: number, teammates?: string[]): RoleRevealState {
  return { confirmedCount: 0, totalCount, teammates }
}

// ── Day Phase mock states ─────────────────────────────────────────────────────
// Carol (u4, seat 4) was killed last night.

const NIGHT_RESULT = {
  killedPlayerId: 'u4',
  killedNickname: 'Carol',
  killedSeatIndex: 4,
  killedAvatar: '🌙',
}

// Factory functions so each debug trigger gets a fresh deadline relative to now.
export function makeDayHidden(): DayPhaseState {
  const totalMs = 10_000 // 10s for local testing
  return {
    subPhase: 'RESULT_HIDDEN',
    dayNumber: 1,
    phaseStarted: Date.now(),
    phaseDeadline: Date.now() + totalMs,
    nightResult: NIGHT_RESULT,
    canVote: false,
  }
}

export function makeDayRevealed(): DayPhaseState {
  const totalMs = 10_000 // 10s for local testing
  return {
    subPhase: 'RESULT_REVEALED',
    dayNumber: 1,
    phaseStarted: Date.now(),
    phaseDeadline: Date.now() + totalMs,
    nightResult: NIGHT_RESULT,
    canVote: true,
  }
}

// ── Day Phase scenarios (u1 = logged-in user in different roles) ──────────────

const BASE_PLAYERS = MOCK_GAME_STATE.players

// u1 is dead; u2 is host
const PLAYERS_AS_DEAD = BASE_PLAYERS.map((p) => (p.userId === 'u1' ? { ...p, isAlive: false } : p))

// u1 is alive but u2 is host (just remove host flag from u1)
const PLAYERS_AS_ALIVE = BASE_PLAYERS.map((p) => (p.userId === 'u1' ? { ...p, isAlive: true } : p))

// u1 is not in the game (guest/spectator)
const PLAYERS_AS_GUEST = BASE_PLAYERS.filter((p) => p.userId !== 'u1')

export function makeDayScenario(
  variant: 'HOST_HIDDEN' | 'HOST_REVEALED' | 'DEAD' | 'ALIVE_HIDDEN' | 'ALIVE_REVEALED' | 'GUEST',
): GameState {
  switch (variant) {
    case 'HOST_HIDDEN':
      return { ...MOCK_GAME_STATE, phase: 'DAY', dayPhase: makeDayHidden() }
    case 'HOST_REVEALED':
      return { ...MOCK_GAME_STATE, phase: 'DAY', dayPhase: makeDayRevealed() }
    case 'DEAD':
      return {
        ...MOCK_GAME_STATE,
        hostId: 'u2',
        phase: 'DAY',
        players: PLAYERS_AS_DEAD,
        dayPhase: makeDayRevealed(),
      }
    case 'ALIVE_HIDDEN':
      return {
        ...MOCK_GAME_STATE,
        hostId: 'u2',
        phase: 'DAY',
        players: PLAYERS_AS_ALIVE,
        dayPhase: makeDayHidden(),
      }
    case 'ALIVE_REVEALED':
      return {
        ...MOCK_GAME_STATE,
        hostId: 'u2',
        phase: 'DAY',
        players: PLAYERS_AS_ALIVE,
        dayPhase: makeDayRevealed(),
      }
    case 'GUEST':
      return {
        ...MOCK_GAME_STATE,
        hostId: 'u2',
        phase: 'DAY',
        players: PLAYERS_AS_GUEST,
        dayPhase: makeDayHidden(),
      }
  }
}

// Static exports retained for E2E tests (deadlines are set at import time, fine for short-lived tests)
export const MOCK_DAY_SCENARIO_HOST_HIDDEN: GameState = makeDayScenario('HOST_HIDDEN')
export const MOCK_DAY_SCENARIO_HOST_REVEALED: GameState = makeDayScenario('HOST_REVEALED')
export const MOCK_DAY_SCENARIO_DEAD: GameState = makeDayScenario('DEAD')
export const MOCK_DAY_SCENARIO_ALIVE_HIDDEN: GameState = makeDayScenario('ALIVE_HIDDEN')
export const MOCK_DAY_SCENARIO_ALIVE_REVEALED: GameState = makeDayScenario('ALIVE_REVEALED')
export const MOCK_DAY_SCENARIO_GUEST: GameState = makeDayScenario('GUEST')

// ── Sheriff Election mock states ──────────────────────────────────────────────
// u1 = You (logged-in user), u2 = Alice, u6 = Tom

export const MOCK_SHERIFF_SIGNUP: SheriffElectionState = {
  subPhase: 'SIGNUP',
  timeRemaining: 48,
  candidates: [
    { userId: 'u2', nickname: 'Alice', avatar: '😊', status: 'RUNNING' },
    { userId: 'u6', nickname: 'Tom', avatar: '🐯', status: 'RUNNING' },
  ],
  speakingOrder: ['u2', 'u6'],
  canVote: true,
}

export const MOCK_SHERIFF_SPEECH_CANDIDATE: SheriffElectionState = {
  subPhase: 'SPEECH',
  timeRemaining: 38,
  candidates: [
    { userId: 'u1', nickname: '我', avatar: '⭐', status: 'RUNNING' },
    { userId: 'u2', nickname: 'Alice', avatar: '😊', status: 'RUNNING' },
    { userId: 'u6', nickname: 'Tom', avatar: '🐯', status: 'RUNNING' },
  ],
  speakingOrder: ['u1', 'u2', 'u6'],
  currentSpeakerId: 'u1',
  canVote: true,
}

export const MOCK_SHERIFF_SPEECH_AUDIENCE: SheriffElectionState = {
  subPhase: 'SPEECH',
  timeRemaining: 22,
  candidates: [
    { userId: 'u2', nickname: 'Alice', avatar: '😊', status: 'QUIT' },
    { userId: 'u6', nickname: 'Tom', avatar: '🐯', status: 'RUNNING' },
  ],
  speakingOrder: ['u2', 'u6'],
  currentSpeakerId: 'u6',
  canVote: true,
}

export const MOCK_SHERIFF_VOTING: SheriffElectionState = {
  subPhase: 'VOTING',
  timeRemaining: 45,
  candidates: [
    { userId: 'u2', nickname: 'Alice', avatar: '😊', status: 'QUIT' },
    { userId: 'u6', nickname: 'Tom', avatar: '🐯', status: 'RUNNING' },
  ],
  speakingOrder: ['u2', 'u6'],
  canVote: true,
}

export const MOCK_SHERIFF_RESULT: SheriffElectionState = {
  subPhase: 'RESULT',
  timeRemaining: 0,
  candidates: [
    { userId: 'u2', nickname: 'Alice', avatar: '😊', status: 'RUNNING' },
    { userId: 'u6', nickname: 'Tom', avatar: '🐯', status: 'RUNNING' },
    { userId: 'u3', nickname: 'Bob', avatar: '🎭', status: 'RUNNING' },
  ],
  speakingOrder: ['u2', 'u6', 'u3'],
  result: {
    sheriffId: 'u6',
    sheriffNickname: 'Tom',
    sheriffAvatar: '🐯',
    tally: [
      {
        candidateId: 'u6',
        nickname: 'Tom',
        seatIndex: 5,
        votes: 5,
        voters: [
          { userId: 'u1', nickname: 'You', avatar: '⭐', seatIndex: 1 },
          { userId: 'u3', nickname: 'Bob', avatar: '🎭', seatIndex: 2 },
          { userId: 'u4', nickname: 'Carol', avatar: '🌙', seatIndex: 3 },
          { userId: 'u5', nickname: 'Dave', avatar: '🌸', seatIndex: 4 },
          { userId: 'u7', nickname: '阿强', avatar: '🎸', seatIndex: 6 },
        ],
      },
      {
        candidateId: 'u2',
        nickname: 'Alice',
        seatIndex: 1,
        votes: 3,
        voters: [
          { userId: 'u8', nickname: '波波', avatar: '🌊', seatIndex: 7 },
          { userId: 'u9', nickname: '小花', avatar: '🌺', seatIndex: 8 },
          { userId: 'u10', nickname: 'Iris', avatar: '🌷', seatIndex: 9 },
        ],
      },
      {
        candidateId: 'u3',
        nickname: 'Bob',
        seatIndex: 2,
        votes: 2,
        voters: [
          { userId: 'u13', nickname: 'Zara', avatar: '🦋', seatIndex: 12 },
          { userId: 'u14', nickname: '小龙', avatar: '🐉', seatIndex: 3 },
        ],
      },
    ],
    abstainCount: 2,
    abstainVoters: [
      { userId: 'u11', nickname: 'Jack', avatar: '🦊', seatIndex: 10 },
      { userId: 'u12', nickname: 'Lily', avatar: '🌻', seatIndex: 11 },
    ],
  },
}

// ── Night Phase mock states ───────────────────────────────────────────────────
// All night scenarios use MOCK_GAME_STATE players; u4 (Carol, seat 4) is dead.

export function makeNightScenario(
  variant: 'WEREWOLF' | 'SEER_PICK' | 'SEER_RESULT' | 'WITCH' | 'GUARD' | 'WAITING',
): GameState {
  const base: GameState = { ...MOCK_GAME_STATE, phase: 'NIGHT' }
  switch (variant) {
    case 'WEREWOLF':
      return {
        ...base,
        myRole: 'WEREWOLF',
        nightPhase: {
          subPhase: 'WEREWOLF_PICK',
          dayNumber: 2,
          teammates: ['Alice', 'Eve'], // u2=Alice, u6=Eve per MOCK_GAME_STATE players
        } satisfies NightPhaseState,
      }
    case 'SEER_PICK':
      return {
        ...base,
        myRole: 'SEER',
        nightPhase: { subPhase: 'SEER_PICK', dayNumber: 2 } satisfies NightPhaseState,
      }
    case 'SEER_RESULT':
      return {
        ...base,
        myRole: 'SEER',
        nightPhase: {
          subPhase: 'SEER_RESULT',
          dayNumber: 2,
          seerResult: {
            checkedPlayerId: 'u6',
            checkedNickname: 'Tom',
            checkedSeatIndex: 6,
            isWerewolf: true,
            history: [
              { round: 1, nickname: '小明', isWerewolf: false },
              { round: 2, nickname: 'Tom', isWerewolf: true },
            ],
          },
        } satisfies NightPhaseState,
      }
    case 'WITCH':
      return {
        ...base,
        myRole: 'WITCH',
        nightPhase: {
          subPhase: 'WITCH_ACT',
          dayNumber: 2,
          attackedPlayerId: 'u6',
          attackedNickname: 'Tom',
          attackedSeatIndex: 6,
          hasAntidote: true,
          hasPoison: true,
        } satisfies NightPhaseState,
      }
    case 'GUARD':
      return {
        ...base,
        myRole: 'GUARD',
        nightPhase: {
          subPhase: 'GUARD_PICK',
          dayNumber: 2,
          previousGuardTargetId: 'u5', // Dave (seat 5) — cannot protect again
        } satisfies NightPhaseState,
      }
    case 'WAITING':
      return {
        ...base,
        myRole: 'VILLAGER',
        nightPhase: { subPhase: 'WAITING', dayNumber: 2 } satisfies NightPhaseState,
      }
  }
}

// ── Simulated STOMP events (pushed after a delay in mock mode) ────────────────
// These are the payloads that would normally come from the backend via WebSocket.

export const MOCK_STOMP_EVENTS = {
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
