/**
 * ─────────────────────────────────────────────────────────────────────────────
 * MOCK DATA — single source of truth for all fake API responses.
 *
 * When the backend is ready, delete this file and remove the mock setup from
 * main.ts (the `if (import.meta.env.VITE_MOCK)` block).
 * ─────────────────────────────────────────────────────────────────────────────
 */

import type {
  ActionLogEntry,
  DayPhaseState,
  GameState,
  LoginResponse,
  NightPhaseState,
  PlayerRole,
  RoleRevealState,
  Room,
  SheriffElectionState,
  VoteRoundHistory,
  VoteTally,
  VoteVoter,
  VotingState,
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
  phase: 'DAY_DISCUSSION',
  dayNumber: 1,
  myRole: 'SEER',
  sheriff: 'u2',
  hostId: 'u1',
  hasSheriff: true,
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

// ── Result (game over) ────────────────────────────────────────────────────────

export const MOCK_GAME_RESULT = {
  ...MOCK_GAME_STATE,
  phase: 'GAME_OVER' as const,
  winner: 'VILLAGER' as const,
  players: MOCK_GAME_STATE.players.map((p) => ({
    ...p,
    role: MOCK_ROLE_ASSIGNMENTS[p.userId] ?? 'VILLAGER',
  })),
}

export const MOCK_GAME_RESULT_WOLVES = {
  ...MOCK_GAME_STATE,
  phase: 'GAME_OVER' as const,
  winner: 'WEREWOLF' as const,
  players: MOCK_GAME_STATE.players.map((p) => ({
    ...p,
    role: MOCK_ROLE_ASSIGNMENTS[p.userId] ?? 'VILLAGER',
  })),
}

export function makeRoleRevealState(totalCount: number, teammates?: string[]): RoleRevealState {
  return { confirmedCount: 0, totalCount, teammates }
}

// ── Day Phase mock states ─────────────────────────────────────────────────────
// Carol (u4, seat 4) was killed last night.

const NIGHT_RESULT = {
  killedPlayers: [
    {
      killedPlayerId: 'u4',
      killedNickname: 'Carol',
      killedSeatIndex: 4,
      killedAvatar: '🌙',
    },
  ],
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
      return { ...MOCK_GAME_STATE, phase: 'DAY_DISCUSSION', dayPhase: makeDayHidden() }
    case 'HOST_REVEALED':
      return { ...MOCK_GAME_STATE, phase: 'DAY_DISCUSSION', dayPhase: makeDayRevealed() }
    case 'DEAD':
      return {
        ...MOCK_GAME_STATE,
        hostId: 'u2',
        phase: 'DAY_DISCUSSION',
        players: PLAYERS_AS_DEAD,
        dayPhase: makeDayRevealed(),
      }
    case 'ALIVE_HIDDEN':
      return {
        ...MOCK_GAME_STATE,
        hostId: 'u2',
        phase: 'DAY_DISCUSSION',
        players: PLAYERS_AS_ALIVE,
        dayPhase: makeDayHidden(),
      }
    case 'ALIVE_REVEALED':
      return {
        ...MOCK_GAME_STATE,
        hostId: 'u2',
        phase: 'DAY_DISCUSSION',
        players: PLAYERS_AS_ALIVE,
        dayPhase: makeDayRevealed(),
      }
    case 'GUEST':
      return {
        ...MOCK_GAME_STATE,
        hostId: 'u2',
        phase: 'DAY_DISCUSSION',
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
  allVoted: false,
  voteProgress: { voted: 0, total: 8 },
}

// Host (u1) quit campaign during SPEECH — canVote=false, allVoted=false
// Tests that Reveal Result stays visible (disabled) even when host can't vote
export const MOCK_SHERIFF_VOTING_HOST_QUIT: SheriffElectionState = {
  subPhase: 'VOTING',
  timeRemaining: 45,
  candidates: [
    { userId: 'u1', nickname: '我', avatar: '⭐', status: 'QUIT' },
    { userId: 'u2', nickname: 'Alice', avatar: '😊', status: 'RUNNING' },
    { userId: 'u6', nickname: 'Tom', avatar: '🐯', status: 'RUNNING' },
  ],
  speakingOrder: ['u1', 'u2', 'u6'],
  canVote: false,
  allVoted: false,
  voteProgress: { voted: 1, total: 8 },
}

// u1 (host) is a running candidate in VOTING — tests self-vote prevention
export const MOCK_SHERIFF_VOTING_WITH_HOST_CANDIDATE: SheriffElectionState = {
  subPhase: 'VOTING',
  timeRemaining: 45,
  candidates: [
    { userId: 'u1', nickname: '我', avatar: '⭐', status: 'RUNNING' },
    { userId: 'u6', nickname: 'Tom', avatar: '🐯', status: 'RUNNING' },
  ],
  speakingOrder: ['u1', 'u6'],
  canVote: true,
  allVoted: false,
  voteProgress: { voted: 0, total: 8 },
}

export const MOCK_SHERIFF_RESULT: SheriffElectionState = {
  subPhase: 'RESULT',
  timeRemaining: 0,
  candidates: [
    { userId: 'u2', nickname: 'Alice', avatar: '😊', status: 'RUNNING' },
    { userId: 'u6', nickname: 'Tom', avatar: '🐯', status: 'RUNNING' },
    { userId: 'u3', nickname: 'Bob', avatar: '🎭', status: 'RUNNING' },
    // Eve quit during SPEECH — she is in candidates but must NOT appear in result tally
    { userId: 'u5', nickname: 'Eve', avatar: '🌸', status: 'QUIT' },
  ],
  speakingOrder: ['u2', 'u6', 'u3', 'u5'],
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
  variant:
    | 'WEREWOLF'
    | 'SEER_PICK'
    | 'SEER_RESULT'
    | 'WITCH'
    | 'GUARD'
    | 'WAITING'
    | 'SEER_IDLE'
    | 'DEAD',
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
    case 'SEER_IDLE':
      // SEER player during WEREWOLF_PICK — not their turn, should see sleep screen
      return {
        ...base,
        myRole: 'SEER',
        nightPhase: { subPhase: 'WEREWOLF_PICK', dayNumber: 1 } satisfies NightPhaseState,
      }
    case 'DEAD':
      // Dead player (u1) during WEREWOLF_PICK — should see elimination banner and sleep screen
      return {
        ...base,
        myRole: 'VILLAGER',
        players: base.players.map((p) => (p.userId === 'u1' ? { ...p, isAlive: false } : p)),
        nightPhase: { subPhase: 'WEREWOLF_PICK', dayNumber: 2 } satisfies NightPhaseState,
      }
  }
}

// ── Vote History mock data ────────────────────────────────────────────────────
// Simulates a completed Day 1 vote to show in Day 2 voting history panel.

export const MOCK_VOTE_HISTORY: VoteRoundHistory[] = [
  {
    // Day 1 — 6 candidates: forces horizontal scroll in history panel
    dayNumber: 1,
    eliminatedPlayerId: 'u5',
    eliminatedNickname: 'Dave',
    eliminatedSeatIndex: 5,
    eliminatedAvatar: '🌸',
    eliminatedRole: 'VILLAGER',
    hunterShotPlayerId: 'u2',
    hunterShotNickname: 'Alice',
    hunterShotSeatIndex: 2,
    hunterShotAvatar: '😊',
    hunterShotRole: 'WEREWOLF',
    tally: [
      {
        playerId: 'u5',
        nickname: 'Dave',
        seatIndex: 5,
        avatar: '🌸',
        votes: 4,
        voters: [
          { userId: 'u2', nickname: 'Alice', avatar: '😊', seatIndex: 2 } satisfies VoteVoter,
          { userId: 'u3', nickname: 'Bob', avatar: '🎭', seatIndex: 3 } satisfies VoteVoter,
          { userId: 'u6', nickname: 'Eve', avatar: '🦊', seatIndex: 6 } satisfies VoteVoter,
          { userId: 'u7', nickname: 'Frank', avatar: '🎸', seatIndex: 7 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u2',
        nickname: 'Alice',
        seatIndex: 2,
        avatar: '😊',
        votes: 3,
        voters: [
          { userId: 'u1', nickname: 'You', avatar: '⭐', seatIndex: 1 } satisfies VoteVoter,
          { userId: 'u5', nickname: 'Dave', avatar: '🌸', seatIndex: 5 } satisfies VoteVoter,
          { userId: 'u9', nickname: 'Hank', avatar: '🐯', seatIndex: 9 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u9',
        nickname: 'Hank',
        seatIndex: 9,
        avatar: '🐯',
        votes: 2,
        voters: [
          { userId: 'u8', nickname: 'Grace', avatar: '🌺', seatIndex: 8 } satisfies VoteVoter,
          { userId: 'u4', nickname: 'Carol', avatar: '🌙', seatIndex: 4 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u3',
        nickname: 'Bob',
        seatIndex: 3,
        avatar: '🎭',
        votes: 2,
        voters: [
          { userId: 'u6', nickname: 'Eve', avatar: '🦊', seatIndex: 6 } satisfies VoteVoter,
          { userId: 'u7', nickname: 'Frank', avatar: '🎸', seatIndex: 7 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u8',
        nickname: 'Grace',
        seatIndex: 8,
        avatar: '🌺',
        votes: 1,
        voters: [
          { userId: 'u9', nickname: 'Hank', avatar: '🐯', seatIndex: 9 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u4',
        nickname: 'Carol',
        seatIndex: 4,
        avatar: '🌙',
        votes: 1,
        voters: [
          { userId: 'u2', nickname: 'Alice', avatar: '😊', seatIndex: 2 } satisfies VoteVoter,
        ],
      },
    ],
  },
  {
    // Day 2 — 5 candidates
    dayNumber: 2,
    eliminatedPlayerId: 'u7',
    eliminatedNickname: 'Frank',
    eliminatedSeatIndex: 7,
    eliminatedAvatar: '🎸',
    eliminatedRole: 'HUNTER',
    hunterShotPlayerId: 'u8',
    hunterShotNickname: 'Grace',
    hunterShotSeatIndex: 8,
    hunterShotAvatar: '🌺',
    hunterShotRole: 'VILLAGER',
    tally: [
      {
        playerId: 'u7',
        nickname: 'Frank',
        seatIndex: 7,
        avatar: '🎸',
        votes: 5,
        voters: [
          { userId: 'u1', nickname: 'You', avatar: '⭐', seatIndex: 1 } satisfies VoteVoter,
          { userId: 'u3', nickname: 'Bob', avatar: '🎭', seatIndex: 3 } satisfies VoteVoter,
          { userId: 'u6', nickname: 'Eve', avatar: '🦊', seatIndex: 6 } satisfies VoteVoter,
          { userId: 'u8', nickname: 'Grace', avatar: '🌺', seatIndex: 8 } satisfies VoteVoter,
          { userId: 'u9', nickname: 'Hank', avatar: '🐯', seatIndex: 9 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u3',
        nickname: 'Bob',
        seatIndex: 3,
        avatar: '🎭',
        votes: 2,
        voters: [
          { userId: 'u7', nickname: 'Frank', avatar: '🎸', seatIndex: 7 } satisfies VoteVoter,
          { userId: 'u4', nickname: 'Carol', avatar: '🌙', seatIndex: 4 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u1',
        nickname: 'You',
        seatIndex: 1,
        avatar: '⭐',
        votes: 1,
        voters: [
          { userId: 'u5', nickname: 'Dave', avatar: '🌸', seatIndex: 5 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u9',
        nickname: 'Hank',
        seatIndex: 9,
        avatar: '🐯',
        votes: 1,
        voters: [
          { userId: 'u2', nickname: 'Alice', avatar: '😊', seatIndex: 2 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u4',
        nickname: 'Carol',
        seatIndex: 4,
        avatar: '🌙',
        votes: 1,
        voters: [{ userId: 'u3', nickname: 'Bob', avatar: '🎭', seatIndex: 3 } satisfies VoteVoter],
      },
    ],
  },
  {
    // Day 3 — 5 candidates
    dayNumber: 3,
    eliminatedPlayerId: 'u6',
    eliminatedNickname: 'Eve',
    eliminatedSeatIndex: 6,
    eliminatedAvatar: '🦊',
    eliminatedRole: 'WEREWOLF',
    tally: [
      {
        playerId: 'u6',
        nickname: 'Eve',
        seatIndex: 6,
        avatar: '🦊',
        votes: 4,
        voters: [
          { userId: 'u1', nickname: 'You', avatar: '⭐', seatIndex: 1 } satisfies VoteVoter,
          { userId: 'u3', nickname: 'Bob', avatar: '🎭', seatIndex: 3 } satisfies VoteVoter,
          { userId: 'u4', nickname: 'Carol', avatar: '🌙', seatIndex: 4 } satisfies VoteVoter,
          { userId: 'u9', nickname: 'Hank', avatar: '🐯', seatIndex: 9 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u1',
        nickname: 'You',
        seatIndex: 1,
        avatar: '⭐',
        votes: 2,
        voters: [
          { userId: 'u6', nickname: 'Eve', avatar: '🦊', seatIndex: 6 } satisfies VoteVoter,
          { userId: 'u3', nickname: 'Bob', avatar: '🎭', seatIndex: 3 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u9',
        nickname: 'Hank',
        seatIndex: 9,
        avatar: '🐯',
        votes: 1,
        voters: [
          { userId: 'u7', nickname: 'Frank', avatar: '🎸', seatIndex: 7 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u3',
        nickname: 'Bob',
        seatIndex: 3,
        avatar: '🎭',
        votes: 1,
        voters: [
          { userId: 'u8', nickname: 'Grace', avatar: '🌺', seatIndex: 8 } satisfies VoteVoter,
        ],
      },
      {
        playerId: 'u4',
        nickname: 'Carol',
        seatIndex: 4,
        avatar: '🌙',
        votes: 1,
        voters: [
          { userId: 'u2', nickname: 'Alice', avatar: '😊', seatIndex: 2 } satisfies VoteVoter,
        ],
      },
    ],
  },
]

// ── Voting Phase mock states ──────────────────────────────────────────────────
// Tom (u6, seat 6) is eliminated in all voting scenarios.

const MOCK_VOTE_TALLY: VoteTally[] = [
  {
    playerId: 'u6',
    nickname: 'Tom',
    seatIndex: 6,
    avatar: '🐯',
    votes: 5,
    voters: [
      { userId: 'u1', nickname: 'You', avatar: '⭐', seatIndex: 1 } satisfies VoteVoter,
      { userId: 'u5', nickname: 'Dave', avatar: '🌸', seatIndex: 5 } satisfies VoteVoter,
      { userId: 'u7', nickname: 'Frank', avatar: '🎸', seatIndex: 7 } satisfies VoteVoter,
      { userId: 'u8', nickname: 'Grace', avatar: '🌺', seatIndex: 8 } satisfies VoteVoter,
      { userId: 'u9', nickname: 'Hank', avatar: '🐯', seatIndex: 9 } satisfies VoteVoter,
    ],
  },
  {
    playerId: 'u2',
    nickname: 'Alice',
    seatIndex: 2,
    avatar: '😊',
    votes: 3,
    voters: [
      { userId: 'u3', nickname: 'Bob', avatar: '🎭', seatIndex: 3 } satisfies VoteVoter,
      { userId: 'u4', nickname: 'Carol', avatar: '🌙', seatIndex: 4 } satisfies VoteVoter,
      { userId: 'u6', nickname: 'Eve', avatar: '🦊', seatIndex: 6 } satisfies VoteVoter,
    ],
  },
  {
    playerId: 'u3',
    nickname: 'Bob',
    seatIndex: 3,
    avatar: '🎭',
    votes: 2,
    voters: [
      { userId: 'u2', nickname: 'Alice', avatar: '😊', seatIndex: 2 } satisfies VoteVoter,
      { userId: 'u9', nickname: 'Hank', avatar: '🐯', seatIndex: 9 } satisfies VoteVoter,
    ],
  },
]

export function makeVotingScenario(
  scenario:
    | 'DAY_VOTING'
    | 'VOTING_VOTED'
    | 'VOTING_REVEALED'
    | 'HUNTER_SHOOT'
    | 'BADGE_HANDOVER'
    | 'BADGE_SHERIFF'
    | 'BADGE_BURNED'
    | 'VOTING_NO_HISTORY'
    | 'VOTING_NO_DATA'
    | 'IDIOT_REVEAL'
    | 'RE_VOTING',
): GameState {
  const now = Date.now()
  const base: GameState = {
    ...MOCK_GAME_STATE,
    phase: 'DAY_VOTING',
    dayNumber: 2,
    dayPhase: undefined,
    voteHistory: MOCK_VOTE_HISTORY,
  }
  const eliminated = {
    eliminatedPlayerId: 'u6',
    eliminatedNickname: 'Tom',
    eliminatedSeatIndex: 6,
    eliminatedAvatar: '🐯',
    eliminatedRole: 'HUNTER' as const,
  }
  const commonTiming = {
    dayNumber: 2,
    phaseDeadline: now + 60_000,
    phaseStarted: now,
  }
  switch (scenario) {
    case 'DAY_VOTING':
      return {
        ...base,
        votingPhase: {
          subPhase: 'VOTING',
          ...commonTiming,
          canVote: true,
          votedPlayerIds: ['u3', 'u5', 'u7'],
          votesSubmitted: 3,
          totalVoters: 8,
          tally: MOCK_VOTE_TALLY,
        } satisfies VotingState,
      }
    case 'VOTING_VOTED':
      return {
        ...base,
        votingPhase: {
          subPhase: 'VOTING',
          ...commonTiming,
          canVote: false,
          myVote: 'u6',
          votedPlayerIds: ['u1', 'u3', 'u5', 'u6', 'u7'],
          votesSubmitted: 5,
          totalVoters: 8,
          tally: MOCK_VOTE_TALLY,
        } satisfies VotingState,
      }
    case 'VOTING_REVEALED':
      return {
        ...base,
        votingPhase: {
          subPhase: 'VOTING',
          ...commonTiming,
          canVote: false,
          myVote: 'u6',
          votedPlayerIds: ['u1', 'u2', 'u3', 'u5', 'u6', 'u7', 'u8', 'u9'],
          votesSubmitted: 8,
          totalVoters: 8,
          tally: MOCK_VOTE_TALLY,
          tallyRevealed: true,
          revealDeadline: now + 30_000,
          ...eliminated,
        } satisfies VotingState,
      }
    case 'HUNTER_SHOOT':
      return {
        ...base,
        votingPhase: {
          subPhase: 'HUNTER_SHOOT',
          ...commonTiming,
          ...eliminated,
        } satisfies VotingState,
      }
    case 'BADGE_HANDOVER':
      return {
        ...base,
        sheriff: 'u6',
        votingPhase: {
          subPhase: 'BADGE_HANDOVER',
          ...commonTiming,
          ...eliminated,
        } satisfies VotingState,
      }
    case 'BADGE_SHERIFF':
      // Badge passed — new sheriff (Alice u2) shown with star; waiting for host to continue
      return {
        ...base,
        sheriff: 'u2',
        players: base.players.map((p) => {
          if (p.userId === 'u2') return { ...p, sheriff: true }
          if (p.userId === 'u6') return { ...p, sheriff: false, isAlive: false }
          return p
        }),
        votingPhase: {
          subPhase: 'BADGE_HANDOVER',
          ...commonTiming,
          ...eliminated,
        } satisfies VotingState,
      }
    case 'BADGE_BURNED':
      // Badge destroyed — message shown; waiting for host to continue
      return {
        ...base,
        sheriff: 'u6',
        votingPhase: {
          subPhase: 'BADGE_HANDOVER',
          ...commonTiming,
          ...eliminated,
          badgeDestroyed: true,
        } satisfies VotingState,
      }
    case 'VOTING_NO_HISTORY':
      // myRole set (chip visible) but no vote history (history button hidden)
      return {
        ...base,
        myRole: 'SEER',
        voteHistory: [],
        votingPhase: {
          subPhase: 'VOTING',
          ...commonTiming,
          canVote: true,
          votedPlayerIds: [],
          votesSubmitted: 0,
          totalVoters: 8,
        } satisfies VotingState,
      }
    case 'VOTING_NO_DATA':
      // Neither myRole nor voteHistory — role-history row must be absent
      return {
        ...base,
        myRole: undefined,
        voteHistory: undefined,
        votingPhase: {
          subPhase: 'VOTING',
          ...commonTiming,
          canVote: true,
          votedPlayerIds: [],
          votesSubmitted: 0,
          totalVoters: 8,
        } satisfies VotingState,
      }
    case 'IDIOT_REVEAL':
      // You (u1) are the Idiot — revealed, alive but permanently lost voting right.
      // u1 received highest votes and triggered idiot reveal.
      return {
        ...base,
        myRole: 'IDIOT' as PlayerRole,
        players: base.players.map((p) => {
          if (p.userId === 'u1') return { ...p, canVote: false, idiotRevealed: true }
          return p
        }),
        votingPhase: {
          subPhase: 'VOTE_RESULT',
          ...commonTiming,
          canVote: false,
          votedPlayerIds: ['u2', 'u3', 'u4', 'u5', 'u6', 'u7', 'u8', 'u9'],
          votesSubmitted: 8,
          totalVoters: 8,
          tallyRevealed: true,
          idiotRevealedId: 'u1',
          idiotRevealedNickname: 'You',
          idiotRevealedSeatIndex: 1,
          tally: [
            {
              playerId: 'u1',
              nickname: 'You',
              seatIndex: 1,
              avatar: '⭐',
              votes: 5,
              voters: [
                { userId: 'u2', nickname: 'Alice', avatar: '😊', seatIndex: 2 } satisfies VoteVoter,
                { userId: 'u3', nickname: 'Bob', avatar: '🎭', seatIndex: 3 } satisfies VoteVoter,
                { userId: 'u4', nickname: 'Carol', avatar: '🌙', seatIndex: 4 } satisfies VoteVoter,
                { userId: 'u5', nickname: 'Dave', avatar: '🌸', seatIndex: 5 } satisfies VoteVoter,
                { userId: 'u6', nickname: 'Eve', avatar: '🦊', seatIndex: 6 } satisfies VoteVoter,
              ],
            },
            {
              playerId: 'u2',
              nickname: 'Alice',
              seatIndex: 2,
              avatar: '😊',
              votes: 2,
              voters: [
                { userId: 'u7', nickname: 'Frank', avatar: '🎸', seatIndex: 7 } satisfies VoteVoter,
                { userId: 'u8', nickname: 'Grace', avatar: '🌺', seatIndex: 8 } satisfies VoteVoter,
              ],
            },
            {
              playerId: 'u3',
              nickname: 'Bob',
              seatIndex: 3,
              avatar: '🎭',
              votes: 1,
              voters: [
                { userId: 'u9', nickname: 'Hank', avatar: '🐯', seatIndex: 9 } satisfies VoteVoter,
              ],
            },
          ],
        } satisfies VotingState,
      }
    case 'RE_VOTING':
      // Vote tied → second round open to all living candidates
      return {
        ...base,
        votingPhase: {
          subPhase: 'RE_VOTING',
          ...commonTiming,
          canVote: true,
          votedPlayerIds: [],
          votesSubmitted: 0,
          totalVoters: 8,
        } satisfies VotingState,
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

// ── Action Log ────────────────────────────────────────────────────────────────

export const MOCK_ACTION_LOG: ActionLogEntry[] = [
  {
    id: 1,
    eventType: 'NIGHT_DEATH',
    message: JSON.stringify({ dayNumber: 1, userId: 'u3', nickname: 'Charlie', seatIndex: 3 }),
    targetUserId: 'u3',
    createdAt: '2024-01-01T00:00:00Z',
  },
  {
    id: 2,
    eventType: 'VOTE_RESULT',
    message: JSON.stringify({
      dayNumber: 1,
      tally: [
        {
          userId: 'u5',
          nickname: 'Eve',
          seatIndex: 5,
          votes: 3,
          voters: [
            { userId: 'u1', nickname: 'You', seatIndex: 1 },
            { userId: 'u2', nickname: 'Bob', seatIndex: 2 },
            { userId: 'u4', nickname: 'Dave', seatIndex: 4 },
          ],
        },
      ],
      eliminatedUserId: 'u5',
      eliminatedNickname: 'Eve',
      eliminatedSeatIndex: 5,
      eliminatedRole: 'VILLAGER',
    }),
    targetUserId: 'u5',
    createdAt: '2024-01-01T12:00:00Z',
  },
]
