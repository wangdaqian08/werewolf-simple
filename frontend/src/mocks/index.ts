import AxiosMockAdapter from 'axios-mock-adapter'
import http from '@/services/http'
import {
  makeDayHidden,
  makeDayRevealed,
  makeDayScenario,
  makeNightScenario,
  makeRoleRevealState,
  makeVotingScenario,
  MOCK_DAY_SCENARIO_ALIVE_HIDDEN,
  MOCK_DAY_SCENARIO_ALIVE_REVEALED,
  MOCK_DAY_SCENARIO_DEAD,
  MOCK_DAY_SCENARIO_GUEST,
  MOCK_DAY_SCENARIO_HOST_HIDDEN,
  MOCK_DAY_SCENARIO_HOST_REVEALED,
  MOCK_GAME_RESULT,
  MOCK_GAME_RESULT_WOLVES,
  MOCK_GAME_STATE,
  MOCK_LOGIN,
  MOCK_ROLE_ASSIGNMENTS,
  MOCK_ROOM_AS_GUEST,
  MOCK_ROOM_AS_HOST,
  MOCK_SHERIFF_RESULT,
  MOCK_SHERIFF_SIGNUP,
  MOCK_SHERIFF_SPEECH_AUDIENCE,
  MOCK_SHERIFF_SPEECH_CANDIDATE,
  MOCK_SHERIFF_VOTING,
  MOCK_SHERIFF_VOTING_HOST_QUIT,
  MOCK_SHERIFF_VOTING_WITH_HOST_CANDIDATE,
  MOCK_STOMP_EVENTS,
} from './data'
import { mockStompClient } from './mockStompClient'
import type {
  AudioSequence,
  DayPhaseState,
  GamePhase,
  GameState,
  RoomPlayer,
  SheriffElectionState,
} from '@/types'

// ── Audio sequence helpers (mirrors backend AudioService logic) ───────────────

const OPEN_EYES: Record<string, string> = {
  WEREWOLF_PICK: 'wolf_open_eyes.mp3',
  SEER_PICK: 'seer_open_eyes.mp3',
  WITCH_ACT: 'witch_open_eyes.mp3',
  GUARD_PICK: 'guard_open_eyes.mp3',
}

const CLOSE_EYES: Record<string, string> = {
  WEREWOLF_PICK: 'wolf_close_eyes.mp3',
  SEER_PICK: 'seer_close_eyes.mp3',
  SEER_RESULT: 'seer_close_eyes.mp3',
  WITCH_ACT: 'witch_close_eyes.mp3',
  GUARD_PICK: 'guard_close_eyes.mp3',
}

let audioSeqCounter = 0

function makeAudioSequence(
  phase: GamePhase,
  subPhase: string | null,
  audioFiles: string[],
  priority = 5,
): AudioSequence {
  return {
    id: `mock-${++audioSeqCounter}-${phase}`,
    phase,
    subPhase,
    audioFiles,
    priority,
    timestamp: Date.now(),
  }
}

function phaseAudio(newPhase: GamePhase, subPhase?: string | null): AudioSequence {
  const files: string[] = []
  if (newPhase === 'NIGHT') files.push('goes_dark_close_eyes.mp3')
  if (newPhase === 'DAY') files.push('day_time.mp3')
  return makeAudioSequence(newPhase, subPhase ?? null, files, 10)
}

function nightSubPhaseAudio(oldSubPhase: string | null, newSubPhase: string): AudioSequence {
  const files: string[] = []
  if (oldSubPhase && oldSubPhase !== 'WAITING' && CLOSE_EYES[oldSubPhase]) {
    files.push(CLOSE_EYES[oldSubPhase])
  }
  if (OPEN_EYES[newSubPhase]) {
    files.push(OPEN_EYES[newSubPhase])
  }
  return makeAudioSequence('NIGHT', newSubPhase, files)
}

// Mutable room state shared across mock endpoints so debug actions see current players.
let mockRoomId = MOCK_ROOM_AS_HOST.roomId
let mockHostId = MOCK_ROOM_AS_HOST.hostId
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
let confirmedUserIds = new Set<string>()

const SHERIFF_PRESETS: Record<string, SheriffElectionState> = {
  SIGNUP: MOCK_SHERIFF_SIGNUP,
  SPEECH_CANDIDATE: MOCK_SHERIFF_SPEECH_CANDIDATE,
  SPEECH_AUDIENCE: MOCK_SHERIFF_SPEECH_AUDIENCE,
  VOTING: MOCK_SHERIFF_VOTING,
  VOTING_HOST_QUIT: MOCK_SHERIFF_VOTING_HOST_QUIT,
  VOTING_WITH_HOST_CANDIDATE: MOCK_SHERIFF_VOTING_WITH_HOST_CANDIDATE,
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

  const mockDelay = import.meta.env.VITE_MOCK_FAST === 'true' ? 50 : 300
  const mock = new AxiosMockAdapter(http, { delayResponse: mockDelay })

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
    mockHostId = room.hostId
    mockPlayers = [...players]
    mockTotalPlayers = roomConfig.totalPlayers
    return [200, room]
  })
  mock.onPost('/room/join').reply((config) => {
    const body = JSON.parse(config.data ?? '{}')
    const code = (body.roomCode ?? '').toUpperCase()
    if (code !== MOCK_ROOM_AS_GUEST.roomCode && code !== MOCK_ROOM_AS_HOST.roomCode) {
      return [404, { message: 'Room not found' }]
    }
    mockRoomId = MOCK_ROOM_AS_GUEST.roomId
    mockHostId = MOCK_ROOM_AS_GUEST.hostId
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

  // ── Debug: start game (fires GAME_STARTED on room topic, then ROLE_REVEAL) ───
  mock.onPost('/debug/game/start').reply((config) => {
    const body = JSON.parse(config.data ?? '{}')
    const hasSheriff = body.hasSheriff !== false // default true
    confirmedUserIds = new Set()
    mockGameState = {
      ...MOCK_GAME_STATE,
      hostId: mockHostId,
      hasSheriff,
      phase: 'ROLE_REVEAL',
      myRole: 'SEER',
      roleReveal: makeRoleRevealState(mockPlayers.length),
    }
    mockStompClient.fireNow(`/topic/room/${mockRoomId}`, {
      type: 'GAME_STARTED',
      payload: { gameId: mockGameState.gameId },
    })
    // Small delay then push initial game state so GameView receives ROLE_REVEAL
    setTimeout(() => pushGameStateUpdate(), 100)
    return [200]
  })

  // ── Debug: skip role reveal → Sheriff Election ────────────────────────────────
  mock.onPost('/debug/role/skip').reply(() => {
    confirmedUserIds = new Set()
    mockGameState = {
      ...mockGameState,
      phase: 'SHERIFF_ELECTION',
      roleReveal: undefined,
      sheriffElection: { ...MOCK_SHERIFF_SIGNUP },
    }
    pushGameStateUpdate()
    return [200]
  })

  // ── Debug: mark all roles as confirmed (used to test no-sheriff Start Night button) ─
  mock.onPost('/debug/role/confirm-all').reply(() => {
    if (!mockGameState.roleReveal) return [400, { error: 'Not in ROLE_REVEAL phase' }]
    mockGameState = {
      ...mockGameState,
      roleReveal: {
        ...mockGameState.roleReveal,
        confirmedCount: mockGameState.roleReveal.totalCount,
      },
    }
    pushGameStateUpdate()
    return [200]
  })

  // ── Debug: skip role reveal → Night WAITING (no-sheriff flow) ───────────────
  mock.onPost('/debug/role/skip-night').reply(() => {
    confirmedUserIds = new Set()
    mockGameState = {
      ...mockGameState,
      phase: 'NIGHT',
      roleReveal: undefined,
      nightPhase: { subPhase: 'WAITING', dayNumber: 1 },
      audioSequence: phaseAudio('NIGHT', 'WAITING'),
    }
    pushGameStateUpdate()
    // Simulate 5-second server timer → WEREWOLF_PICK
    setTimeout(() => {
      if (mockGameState.phase === 'NIGHT' && mockGameState.nightPhase?.subPhase === 'WAITING') {
        mockGameState = {
          ...mockGameState,
          nightPhase: { ...mockGameState.nightPhase!, subPhase: 'WEREWOLF_PICK' },
          audioSequence: nightSubPhaseAudio('WAITING', 'WEREWOLF_PICK'),
        }
        pushGameStateUpdate()
      }
    }, 5_000)
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
    mockGameState = {
      ...mockGameState,
      phase: 'DAY',
      sheriffElection: undefined,
      dayPhase: makeDayHidden(),
    }
    pushGameStateUpdate()
    return [200]
  })

  // ── Debug: Day Phase scenario (switches mock user role for testing) ───────────
  // POST /debug/day/scenario { scenario: string }
  const DAY_SCENARIOS: Record<string, GameState> = {
    HOST_HIDDEN: MOCK_DAY_SCENARIO_HOST_HIDDEN,
    HOST_REVEALED: MOCK_DAY_SCENARIO_HOST_REVEALED,
    DEAD: MOCK_DAY_SCENARIO_DEAD,
    ALIVE_HIDDEN: MOCK_DAY_SCENARIO_ALIVE_HIDDEN,
    ALIVE_REVEALED: MOCK_DAY_SCENARIO_ALIVE_REVEALED,
    GUEST: MOCK_DAY_SCENARIO_GUEST,
  }
  mock.onPost('/debug/day/scenario').reply((config) => {
    const { scenario } = JSON.parse(config.data ?? '{}')
    if (!DAY_SCENARIOS[scenario]) return [400, { error: 'Unknown scenario' }]
    // Use factory for fresh deadlines on each debug call
    mockGameState = makeDayScenario(scenario as Parameters<typeof makeDayScenario>[0])
    pushGameStateUpdate()
    return [200]
  })

  // ── Debug: Day Phase screens ──────────────────────────────────────────────────
  // POST /debug/day/phase { preset: 'HIDDEN' | 'REVEALED' }
  const DAY_PRESET_FACTORIES: Record<string, () => DayPhaseState> = {
    HIDDEN: makeDayHidden,
    REVEALED: makeDayRevealed,
  }
  mock.onPost('/debug/day/phase').reply((config) => {
    const { preset } = JSON.parse(config.data ?? '{}')
    const factory = DAY_PRESET_FACTORIES[preset]
    if (!factory) return [400, { error: 'Unknown preset' }]
    mockGameState = { ...mockGameState, phase: 'DAY', dayPhase: factory() }
    pushGameStateUpdate()
    return [200]
  })

  // POST /debug/day/reveal — host reveals result to all players
  mock.onPost('/debug/day/reveal').reply(() => {
    mockGameState = {
      ...mockGameState,
      phase: 'DAY',
      dayPhase: makeDayRevealed(),
    }
    pushGameStateUpdate()
    return [200]
  })

  // ── Debug: Night Phase scenario ──────────────────────────────────────────────
  // POST /debug/night/scenario { scenario: 'WEREWOLF' | 'SEER_PICK' | 'SEER_RESULT' | 'WITCH' | 'GUARD' | 'WAITING' | 'SEER_IDLE' }
  mock.onPost('/debug/night/scenario').reply((config) => {
    const { scenario } = JSON.parse(config.data ?? '{}')
    try {
      mockGameState = makeNightScenario(scenario as Parameters<typeof makeNightScenario>[0])
      // Attach audio sequence matching the loaded subPhase
      const sub = mockGameState.nightPhase?.subPhase ?? 'WAITING'
      mockGameState.audioSequence = nightSubPhaseAudio(null, sub)
    } catch {
      return [400, { error: 'Unknown scenario' }]
    }
    pushGameStateUpdate()
    return [200]
  })

  // POST /debug/night/advance — end night, transition to next Day phase
  mock.onPost('/debug/night/advance').reply(() => {
    const nextDay = (mockGameState.dayNumber ?? 1) + 1
    mockGameState = {
      ...mockGameState,
      phase: 'DAY',
      dayNumber: nextDay,
      nightPhase: undefined,
      dayPhase: makeDayHidden(),
      audioSequence: phaseAudio('DAY'),
    }
    pushGameStateUpdate()
    return [200]
  })

  // ── Debug: Voting Phase scenario ─────────────────────────────────────────────
  mock.onPost('/debug/voting/scenario').reply((config) => {
    const { scenario } = JSON.parse(config.data ?? '{}')
    const prevHostId = mockGameState.hostId
    const prevPlayers = mockGameState.players
    try {
      const scenarioState = makeVotingScenario(scenario as Parameters<typeof makeVotingScenario>[0])
      // Preserve hostId and alive/dead states from the prior day scenario,
      // but merge any per-player overrides from the scenario (e.g. canVote, idiotRevealed).
      const mergedPlayers = prevPlayers.map((p) => {
        const override = scenarioState.players.find((sp) => sp.userId === p.userId)
        // Preserve isAlive from prior scenario (e.g. Dead day scenario)
        return override ? { ...p, ...override, isAlive: p.isAlive } : p
      })
      mockGameState = { ...scenarioState, hostId: prevHostId, players: mergedPlayers }
    } catch {
      return [400, { error: 'Unknown scenario' }]
    }
    pushGameStateUpdate()
    return [200]
  })

  // ── Debug: Game Over ─────────────────────────────────────────────────────────
  mock.onPost('/debug/game/over').reply((config) => {
    const { winner } = JSON.parse(config.data ?? '{}')
    mockGameState = winner === 'WEREWOLF' ? { ...MOCK_GAME_RESULT_WOLVES } : { ...MOCK_GAME_RESULT }
    pushGameStateUpdate()
    mockStompClient.fireNow(`/topic/game/${mockGameState.gameId}`, { type: 'GAME_OVER' })
    return [200]
  })

  mock.onPost('/debug/voting/advance').reply(() => {
    const nextDay = (mockGameState.dayNumber ?? 1) + 1
    mockGameState = {
      ...mockGameState,
      phase: 'NIGHT',
      dayNumber: nextDay,
      votingPhase: undefined,
      nightPhase: { subPhase: 'WAITING', dayNumber: nextDay },
      audioSequence: phaseAudio('NIGHT', 'WAITING'),
    }
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
  mock.onGet(/\/game\/[^/]+\/state/).reply(() => [200, mockGameState])
  mock.onGet(/\/game\/result/).reply((_config) => [200, MOCK_GAME_RESULT])
  mock.onPost('/game/action').reply((config) => {
    const { actionType, targetId, payload } = JSON.parse(config.data ?? '{}')
    if (mockGameState.phase === 'ROLE_REVEAL') {
      if (actionType === 'START_NIGHT') {
        mockGameState = {
          ...mockGameState,
          phase: 'NIGHT',
          roleReveal: undefined,
          nightPhase: { subPhase: 'WAITING', dayNumber: 1 },
          audioSequence: phaseAudio('NIGHT', 'WAITING'),
        }
        pushGameStateUpdate()
        // Simulate the 5-second server-side timer → advance to WEREWOLF_PICK
        setTimeout(() => {
          if (mockGameState.phase === 'NIGHT' && mockGameState.nightPhase?.subPhase === 'WAITING') {
            mockGameState = {
              ...mockGameState,
              nightPhase: { ...mockGameState.nightPhase!, subPhase: 'WEREWOLF_PICK' },
              audioSequence: nightSubPhaseAudio('WAITING', 'WEREWOLF_PICK'),
            }
            pushGameStateUpdate()
          }
        }, 5_000)
      } else if (actionType === 'CONFIRM_ROLE') {
        const userId = MOCK_LOGIN.user.userId
        if (!confirmedUserIds.has(userId)) {
          confirmedUserIds.add(userId)
          const total = mockGameState.roleReveal?.totalCount ?? 1
          const confirmed = confirmedUserIds.size
          mockGameState = {
            ...mockGameState,
            roleReveal: { ...mockGameState.roleReveal!, confirmedCount: confirmed },
          }
          if (confirmed >= total && mockGameState.hasSheriff !== false) {
            // All confirmed and sheriff enabled → advance to Sheriff Election
            mockGameState = {
              ...mockGameState,
              phase: 'SHERIFF_ELECTION',
              roleReveal: undefined,
              sheriffElection: { ...MOCK_SHERIFF_SIGNUP },
            }
          }
          // When hasSheriff=false: stay in ROLE_REVEAL — host will click "Start Night"
          pushGameStateUpdate()
        }
      }
    } else if (mockGameState.phase === 'SHERIFF_ELECTION' && mockGameState.sheriffElection) {
      const e = mockGameState.sheriffElection
      if (actionType === 'SHERIFF_CAMPAIGN') {
        const existing = e.candidates.find((c) => c.userId === MOCK_LOGIN.user.userId)
        if (!existing) {
          mockGameState = {
            ...mockGameState,
            sheriffElection: {
              ...e,
              candidates: [
                ...e.candidates,
                { userId: 'u1', nickname: '我', avatar: '⭐', status: 'RUNNING' as const },
              ],
              hasPassed: false,
            },
          }
          pushGameStateUpdate()
        } else if (existing.status === 'QUIT') {
          // Player passed earlier but changed their mind — allow re-running
          mockGameState = {
            ...mockGameState,
            sheriffElection: {
              ...e,
              candidates: e.candidates.map((c) =>
                c.userId === 'u1' ? { ...c, status: 'RUNNING' as const } : c,
              ),
              hasPassed: false,
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
      } else if (actionType === 'SHERIFF_QUIT') {
        mockGameState = {
          ...mockGameState,
          sheriffElection: {
            ...e,
            candidates: e.candidates.filter((c) => c.userId !== MOCK_LOGIN.user.userId),
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_QUIT_CAMPAIGN') {
        const t = e.voteProgress?.total ?? 8
        mockGameState = {
          ...mockGameState,
          sheriffElection: {
            ...e,
            candidates: e.candidates.map((c) =>
              c.userId === 'u1' ? { ...c, status: 'QUIT' as const } : c,
            ),
            canVote: false,
            allVoted: true,
            voteProgress: { voted: t, total: t },
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_START_SPEECH') {
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
      } else if (actionType === 'SHERIFF_ADVANCE_SPEECH') {
        const order = e.speakingOrder ?? []
        const currentIdx = order.indexOf(e.currentSpeakerId ?? '')
        const nextIdx = currentIdx + 1
        if (nextIdx >= order.length) {
          mockGameState = {
            ...mockGameState,
            sheriffElection: { ...e, subPhase: 'VOTING', currentSpeakerId: undefined },
          }
        } else {
          mockGameState = {
            ...mockGameState,
            sheriffElection: { ...e, currentSpeakerId: order[nextIdx] },
          }
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_VOTE') {
        const t = e.voteProgress?.total ?? 8
        mockGameState = {
          ...mockGameState,
          sheriffElection: {
            ...e,
            myVote: targetId,
            abstained: false,
            allVoted: true,
            voteProgress: { voted: t, total: t },
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_ABSTAIN') {
        const t = e.voteProgress?.total ?? 8
        mockGameState = {
          ...mockGameState,
          sheriffElection: {
            ...e,
            myVote: undefined,
            abstained: true,
            allVoted: true,
            voteProgress: { voted: t, total: t },
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_REVEAL_RESULT') {
        mockGameState = {
          ...mockGameState,
          sheriffElection: { ...e, subPhase: 'RESULT', result: MOCK_SHERIFF_RESULT.result },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SHERIFF_APPOINT') {
        const appointed = e.candidates.find((c) => c.userId === targetId)
        mockGameState = {
          ...mockGameState,
          sheriffElection: {
            ...e,
            subPhase: 'RESULT',
            result: {
              ...MOCK_SHERIFF_RESULT.result!,
              sheriffId: targetId ?? '',
              sheriffNickname: appointed?.nickname ?? '',
              sheriffAvatar: appointed?.avatar,
            },
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'START_NIGHT') {
        mockGameState = {
          ...mockGameState,
          phase: 'NIGHT',
          sheriffElection: undefined,
          nightPhase: { subPhase: 'WAITING', dayNumber: 1 },
          audioSequence: phaseAudio('NIGHT', 'WAITING'),
        }
        pushGameStateUpdate()
      }
    } else if (mockGameState.phase === 'NIGHT' && mockGameState.nightPhase) {
      const np = mockGameState.nightPhase
      if (actionType === 'NIGHT_SELECT' || actionType === 'WOLF_SELECT') {
        mockGameState = {
          ...mockGameState,
          nightPhase: { ...np, selectedTargetId: targetId },
        }
        pushGameStateUpdate()
      } else if (actionType === 'SEER_CHECK') {
        // Seer checks a player → show result (include current check in history)
        if (targetId) {
          const target = mockGameState.players.find((p) => p.userId === targetId)
          const isWerewolf = MOCK_ROLE_ASSIGNMENTS[targetId] === 'WEREWOLF'
          const prevHistory = np.seerResult?.history ?? []
          const currentEntry = {
            round: np.dayNumber,
            nickname: target?.nickname ?? '',
            isWerewolf,
          }
          mockGameState = {
            ...mockGameState,
            nightPhase: {
              ...np,
              subPhase: 'SEER_RESULT',
              selectedTargetId: undefined,
              seerResult: {
                checkedPlayerId: targetId,
                checkedNickname: target?.nickname ?? '',
                checkedSeatIndex: target?.seatIndex ?? 0,
                isWerewolf,
                history: [...prevHistory, currentEntry],
              },
            },
            audioSequence: nightSubPhaseAudio('SEER_PICK', 'SEER_RESULT'),
          }
          pushGameStateUpdate()
        }
      } else if (actionType === 'SEER_CONFIRM') {
        // Seer done — transition to WAITING
        mockGameState = {
          ...mockGameState,
          nightPhase: { ...np, subPhase: 'WAITING', selectedTargetId: undefined },
          audioSequence: nightSubPhaseAudio(np.subPhase, 'WAITING'),
        }
        pushGameStateUpdate()
      } else if (actionType === 'WOLF_KILL' || actionType === 'GUARD_PROTECT') {
        // Wolf attack or guard protect confirmed — switch to WAITING
        mockGameState = {
          ...mockGameState,
          nightPhase: { ...np, subPhase: 'WAITING', selectedTargetId: undefined },
          audioSequence: nightSubPhaseAudio(np.subPhase, 'WAITING'),
        }
        pushGameStateUpdate()
      } else if (actionType === 'NIGHT_CONFIRM') {
        // Legacy: kept for backward compatibility
        if (np.subPhase === 'SEER_PICK' && targetId) {
          const target = mockGameState.players.find((p) => p.userId === targetId)
          const isWerewolf = MOCK_ROLE_ASSIGNMENTS[targetId] === 'WEREWOLF'
          const prevHistory = np.seerResult?.history ?? []
          const currentEntry = {
            round: np.dayNumber,
            nickname: target?.nickname ?? '',
            isWerewolf,
          }
          mockGameState = {
            ...mockGameState,
            nightPhase: {
              ...np,
              subPhase: 'SEER_RESULT',
              selectedTargetId: undefined,
              seerResult: {
                checkedPlayerId: targetId,
                checkedNickname: target?.nickname ?? '',
                checkedSeatIndex: target?.seatIndex ?? 0,
                isWerewolf,
                history: [...prevHistory, currentEntry],
              },
            },
            audioSequence: nightSubPhaseAudio('SEER_PICK', 'SEER_RESULT'),
          }
        } else {
          mockGameState = {
            ...mockGameState,
            nightPhase: { ...np, subPhase: 'WAITING', selectedTargetId: undefined },
            audioSequence: nightSubPhaseAudio(np.subPhase, 'WAITING'),
          }
        }
        pushGameStateUpdate()
      } else if (actionType === 'WITCH_ACT') {
        // Unified witch action — each action submits immediately (one action per round)
        const useAntidote = payload?.useAntidote ?? false
        const poisonTargetUserId = payload?.poisonTargetUserId ?? null
        mockGameState = {
          ...mockGameState,
          nightPhase: {
            ...np,
            antidoteDecided: true,
            antidoteUsed: useAntidote,
            poisonDecided: true,
            poisonUsed: !!poisonTargetUserId,
            selectedTargetId: undefined,
            subPhase: 'WAITING',
          },
          audioSequence: nightSubPhaseAudio('WITCH_ACT', 'WAITING'),
        }
        pushGameStateUpdate()
      } else if (actionType === 'NIGHT_WITCH_USE_ANTIDOTE') {
        // Legacy: kept for backward compatibility
        mockGameState = {
          ...mockGameState,
          nightPhase: {
            ...np,
            antidoteDecided: true,
            antidoteUsed: true,
            poisonDecided: true,
            subPhase: 'WAITING',
          },
          audioSequence: nightSubPhaseAudio('WITCH_ACT', 'WAITING'),
        }
        pushGameStateUpdate()
      } else if (actionType === 'NIGHT_WITCH_PASS_ANTIDOTE') {
        const updated = { ...np, antidoteDecided: true, antidoteUsed: false }
        const goToWaiting = updated.poisonDecided
        mockGameState = {
          ...mockGameState,
          nightPhase: goToWaiting ? { ...updated, subPhase: 'WAITING' } : updated,
          ...(goToWaiting ? { audioSequence: nightSubPhaseAudio('WITCH_ACT', 'WAITING') } : {}),
        }
        pushGameStateUpdate()
      } else if (actionType === 'NIGHT_WITCH_USE_POISON') {
        mockGameState = {
          ...mockGameState,
          nightPhase: {
            ...np,
            poisonDecided: true,
            poisonUsed: true,
            antidoteDecided: true,
            selectedTargetId: undefined,
            subPhase: 'WAITING',
          },
          audioSequence: nightSubPhaseAudio('WITCH_ACT', 'WAITING'),
        }
        pushGameStateUpdate()
      } else if (actionType === 'NIGHT_WITCH_PASS_POISON') {
        const updated = { ...np, poisonDecided: true, poisonUsed: false }
        const goToWaiting = updated.antidoteDecided
        mockGameState = {
          ...mockGameState,
          nightPhase: goToWaiting ? { ...updated, subPhase: 'WAITING' } : updated,
          ...(goToWaiting ? { audioSequence: nightSubPhaseAudio('WITCH_ACT', 'WAITING') } : {}),
        }
        pushGameStateUpdate()
      }
    } else if (mockGameState.phase === 'DAY') {
      if (actionType === 'REVEAL_NIGHT_RESULT') {
        mockGameState = {
          ...mockGameState,
          dayPhase: makeDayRevealed(),
        }
        pushGameStateUpdate()
      } else if (actionType === 'SELECT_PLAYER') {
        mockGameState = {
          ...mockGameState,
          dayPhase: mockGameState.dayPhase
            ? { ...mockGameState.dayPhase, selectedPlayerId: targetId }
            : undefined,
        }
        pushGameStateUpdate()
      } else if (actionType === 'DAY_ADVANCE') {
        const voting = makeVotingScenario('VOTING')
        mockGameState = {
          ...voting,
          hostId: mockGameState.hostId,
          players: mockGameState.players,
        }
        pushGameStateUpdate()
      }
    } else if (mockGameState.phase === 'VOTING' && mockGameState.votingPhase) {
      const vp = mockGameState.votingPhase
      if (actionType === 'VOTING_SELECT') {
        mockGameState = {
          ...mockGameState,
          votingPhase: { ...vp, selectedPlayerId: targetId },
        }
        pushGameStateUpdate()
      } else if (actionType === 'VOTING_VOTE') {
        const myId = MOCK_LOGIN.user.userId
        const alreadyIn = vp.votedPlayerIds?.includes(myId)
        mockGameState = {
          ...mockGameState,
          votingPhase: {
            ...vp,
            myVote: targetId,
            canVote: false,
            votedPlayerIds: alreadyIn ? vp.votedPlayerIds : [...(vp.votedPlayerIds ?? []), myId],
            votesSubmitted: alreadyIn ? vp.votesSubmitted : (vp.votesSubmitted ?? 0) + 1,
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'VOTING_SKIP') {
        const myId = MOCK_LOGIN.user.userId
        const alreadyIn = vp.votedPlayerIds?.includes(myId)
        mockGameState = {
          ...mockGameState,
          votingPhase: {
            ...vp,
            myVoteSkipped: true,
            canVote: false,
            votedPlayerIds: alreadyIn ? vp.votedPlayerIds : [...(vp.votedPlayerIds ?? []), myId],
            votesSubmitted: alreadyIn ? vp.votesSubmitted : (vp.votesSubmitted ?? 0) + 1,
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'VOTING_REVEAL_TALLY') {
        mockGameState = {
          ...mockGameState,
          votingPhase: {
            ...vp,
            tallyRevealed: true,
            revealDeadline: Date.now() + 30_000,
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'VOTING_UNVOTE') {
        const myId = MOCK_LOGIN.user.userId
        mockGameState = {
          ...mockGameState,
          votingPhase: {
            ...vp,
            myVote: undefined,
            myVoteSkipped: undefined,
            canVote: true,
            votedPlayerIds: vp.votedPlayerIds?.filter((id) => id !== myId),
            votesSubmitted: Math.max(0, (vp.votesSubmitted ?? 1) - 1),
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'VOTING_CONTINUE') {
        if (vp.subPhase === 'BADGE_HANDOVER') {
          const eliminatedSheriff = mockGameState.players.find(
            (p) => p.userId === vp.eliminatedPlayerId,
          )
          const badgeDone =
            vp.badgeDestroyed || (eliminatedSheriff ? !eliminatedSheriff.isSheriff : false)
          if (badgeDone) {
            // Badge done — advance to NIGHT
            const nextDay = (mockGameState.dayNumber ?? 1) + 1
            mockGameState = {
              ...mockGameState,
              phase: 'NIGHT',
              dayNumber: nextDay,
              votingPhase: undefined,
              nightPhase: { subPhase: 'WAITING', dayNumber: nextDay },
            }
          }
        } else {
          // VOTING (tallyRevealed) → HUNTER_SHOOT or BADGE_HANDOVER or NIGHT (skip VOTE_RESULT)
          if (vp.eliminatedRole === 'HUNTER') {
            mockGameState = {
              ...mockGameState,
              votingPhase: { ...vp, subPhase: 'HUNTER_SHOOT', selectedPlayerId: undefined },
            }
          } else if (vp.eliminatedPlayerId && vp.eliminatedPlayerId === mockGameState.sheriff) {
            mockGameState = {
              ...mockGameState,
              votingPhase: { ...vp, subPhase: 'BADGE_HANDOVER', selectedPlayerId: undefined },
            }
          } else {
            const nextDay = (mockGameState.dayNumber ?? 1) + 1
            mockGameState = {
              ...mockGameState,
              phase: 'NIGHT',
              dayNumber: nextDay,
              votingPhase: undefined,
              nightPhase: { subPhase: 'WAITING', dayNumber: nextDay },
            }
          }
        }
        pushGameStateUpdate()
      } else if (actionType === 'HUNTER_SHOOT' || actionType === 'HUNTER_PASS') {
        // After hunter acts, check if eliminated was also sheriff
        if (vp.eliminatedPlayerId && vp.eliminatedPlayerId === mockGameState.sheriff) {
          mockGameState = {
            ...mockGameState,
            votingPhase: { ...vp, subPhase: 'BADGE_HANDOVER', selectedPlayerId: undefined },
          }
        } else {
          const nextDay = (mockGameState.dayNumber ?? 1) + 1
          mockGameState = {
            ...mockGameState,
            phase: 'NIGHT',
            dayNumber: nextDay,
            votingPhase: undefined,
            nightPhase: { subPhase: 'WAITING', dayNumber: nextDay },
          }
        }
        pushGameStateUpdate()
      } else if (actionType === 'BADGE_PASS') {
        mockGameState = {
          ...mockGameState,
          sheriff: targetId,
          players: mockGameState.players.map((p) => {
            if (p.userId === targetId) return { ...p, sheriff: true }
            if (p.userId === vp.eliminatedPlayerId) return { ...p, sheriff: false }
            return p
          }),
          votingPhase: {
            ...vp,
            selectedPlayerId: undefined,
          },
        }
        pushGameStateUpdate()
      } else if (actionType === 'BADGE_DESTROY') {
        mockGameState = {
          ...mockGameState,
          sheriff: undefined,
          votingPhase: {
            ...vp,
            // Stay on BADGE_HANDOVER; badgeDestroyed triggers the "destroyed" UI
            badgeDestroyed: true,
            selectedPlayerId: undefined,
          },
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

  // ── window.__debug console helper ────────────────────────────────────────────
  ;(globalThis as Record<string, unknown>).__debug = {
    // Room
    ready: (userId: string, ready = true) => http.post('/debug/ready', { userId, ready }),
    addPlayer: () => http.post('/debug/room/add-player'),

    // Game start
    gameStart: (opts?: { hasSheriff?: boolean }) => http.post('/debug/game/start', opts ?? {}),

    // Role Reveal
    roleSkip: () => http.post('/debug/role/skip'),
    roleSkipNight: () => http.post('/debug/role/skip-night'),

    // Sheriff Election
    sheriffPhase: (preset: string) => http.post('/debug/sheriff/phase', { preset }),
    sheriffCandidate: (userId: string, nickname: string, avatar: string) =>
      http.post('/debug/sheriff/candidate', { userId, nickname, avatar, action: 'RUN' }),
    sheriffRemove: (userId: string) =>
      http.post('/debug/sheriff/candidate', { userId, action: 'REMOVE' }),
    sheriffExit: () => http.post('/debug/sheriff/exit'),

    // Day Phase
    dayScenario: (scenario: string) => http.post('/debug/day/scenario', { scenario }),
    dayPhase: (preset: string) => http.post('/debug/day/phase', { preset }),
    dayReveal: () => http.post('/debug/day/reveal'),

    // Night Phase
    nightScenario: (scenario: string) => http.post('/debug/night/scenario', { scenario }),
    nightAdvance: () => http.post('/debug/night/advance'),

    // Voting Phase
    votingScenario: (scenario: string) => http.post('/debug/voting/scenario', { scenario }),
    votingAdvance: () => http.post('/debug/voting/advance'),
    votingUnvote: () => http.post('/game/action', { actionType: 'VOTING_UNVOTE' }),

    // Game Over
    gameOver: (winner: string) => http.post('/debug/game/over', { winner }),
  }

  console.warn('[mock] active — set VITE_MOCK=false in .env.development to use real backend')
  console.warn('[mock] __debug helper available — type __debug in the console to see all commands')
}

export { mockStompClient }
