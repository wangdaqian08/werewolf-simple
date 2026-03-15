// ── User ──────────────────────────────────────────────────────────────────────

export interface User {
  userId: string
  nickname: string
}

export interface LoginResponse {
  token: string
  user: User
}

// ── Room ──────────────────────────────────────────────────────────────────────

export type PlayerStatus = 'NOT_READY' | 'READY'
export type RoomStatus = 'WAITING' | 'STARTING' | 'IN_GAME'

export interface RoomPlayer {
  userId: string
  nickname: string
  seatIndex: number | null // null = player joined but hasn't picked a number yet
  status: PlayerStatus
  isHost: boolean
  avatar?: string // emoji shown in the waiting room grid
}

export interface RoomConfig {
  totalPlayers: number
  roles: string[] // backend decides counts based on totalPlayers
}

export interface Room {
  roomId: string
  roomCode: string
  hostId: string
  status: RoomStatus
  players: RoomPlayer[]
  config: RoomConfig
}

export interface CreateRoomRequest {
  config: RoomConfig
}

export interface JoinRoomRequest {
  roomCode: string
}

// ── Game ──────────────────────────────────────────────────────────────────────

export type GamePhase =
  | 'ROLE_REVEAL'
  | 'SHERIFF_ELECTION'
  | 'DAY'
  | 'VOTING'
  | 'NIGHT'
  | 'GAME_OVER'

export type PlayerRole = 'WEREWOLF' | 'VILLAGER' | 'SEER' | 'WITCH' | 'HUNTER' | 'GUARD' | 'IDIOT'

export interface GamePlayer {
  userId: string
  nickname: string
  seatIndex: number
  isAlive: boolean
  isSheriff: boolean
  avatar?: string
  role?: PlayerRole // only revealed to self, or at game end
}

export interface RoleRevealState {
  confirmedCount: number
  totalCount: number
  teammates?: string[] // werewolf only: teammate nicknames
}

export interface GameState {
  gameId: string
  phase: GamePhase
  dayNumber: number
  players: GamePlayer[]
  myRole?: PlayerRole
  sheriff?: string // userId of current sheriff
  hostId?: string // userId of the room host
  winner?: 'WEREWOLF' | 'VILLAGER' // set by backend when phase is GAME_OVER
  events: GameEvent[]
  roleReveal?: RoleRevealState
  sheriffElection?: SheriffElectionState
  dayPhase?: DayPhaseState
}

export interface GameEvent {
  type: string
  message: string
  timestamp: number
  targetId?: string
}

export interface GameActionRequest {
  actionType: string
  targetId?: string
  data?: Record<string, unknown>
}

export interface GameActionResponse {
  success: boolean
  message?: string
  state?: Partial<GameState>
}

// ── Sheriff Election ──────────────────────────────────────────────────────────

export type SheriffSubPhase = 'SIGNUP' | 'SPEECH' | 'VOTING' | 'RESULT'

export interface SheriffCandidate {
  userId: string
  nickname: string
  avatar?: string
  status: 'RUNNING' | 'QUIT'
}

export interface SheriffVoter {
  userId: string
  nickname: string
  avatar?: string
  seatIndex: number
}

export interface SheriffVoteTally {
  candidateId: string
  nickname: string
  seatIndex?: number
  votes: number
  voters: SheriffVoter[]
}

export interface SheriffElectionState {
  subPhase: SheriffSubPhase
  timeRemaining: number
  candidates: SheriffCandidate[]
  speakingOrder: string[] // userIds in order
  currentSpeakerId?: string
  hasPassed?: boolean // true if I chose to pass on signup
  myVote?: string // userId I voted for
  abstained?: boolean
  canVote?: boolean // false if I quit campaign
  result?: {
    sheriffId: string
    sheriffNickname: string
    sheriffAvatar?: string
    tally: SheriffVoteTally[]
    abstainCount: number
    abstainVoters: SheriffVoter[]
  }
}

// ── Day Phase ─────────────────────────────────────────────────────────────────

export type DaySubPhase = 'RESULT_HIDDEN' | 'RESULT_REVEALED'

export interface NightResult {
  killedPlayerId: string
  killedNickname: string
  killedSeatIndex: number
  killedAvatar?: string
}

export interface DayPhaseState {
  subPhase: DaySubPhase
  dayNumber: number
  phaseDeadline: number // epoch ms when phase ends
  phaseStarted: number // epoch ms when phase started
  nightResult?: NightResult // always present for host; present for others only after RESULT_REVEALED
  canVote: boolean
  myVote?: string
  selectedPlayerId?: string
}

// ── WebSocket STOMP ───────────────────────────────────────────────────────────

export interface StompMessage<T = unknown> {
  type: string
  payload: T
  timestamp: number
}
