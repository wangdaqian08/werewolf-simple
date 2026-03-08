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
    seatIndex: number
    status: PlayerStatus
    isHost: boolean
}

export interface RoomConfig {
    totalPlayers: number
    roles: string[]  // backend decides counts based on totalPlayers
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
    | 'SHERIFF_ELECTION'
    | 'DAY'
    | 'VOTING'
    | 'NIGHT'
    | 'GAME_OVER'

export type PlayerRole =
    | 'WEREWOLF'
    | 'VILLAGER'
    | 'SEER'
    | 'WITCH'
    | 'HUNTER'
    | 'GUARD'
    | 'IDIOT'

export interface GamePlayer {
    userId: string
    nickname: string
    seatIndex: number
    isAlive: boolean
    isSheriff: boolean
    role?: PlayerRole  // only revealed to self, or at game end
}

export interface GameState {
    gameId: string
    phase: GamePhase
    dayNumber: number
    players: GamePlayer[]
    myRole?: PlayerRole
    sheriff?: string  // userId of current sheriff
    events: GameEvent[]
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

// ── WebSocket STOMP ───────────────────────────────────────────────────────────

export interface StompMessage<T = unknown> {
    type: string
    payload: T
    timestamp: number
}
