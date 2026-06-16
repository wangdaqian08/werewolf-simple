import type {
  DayPhaseState,
  DaySubPhase,
  GamePlayer,
  NightPhaseState,
  NightSubPhase,
  PlayerRole,
  SheriffElectionState,
  SheriffSubPhase,
  VoteRoundHistory,
  VotingState,
  VotingSubPhase,
} from '@/types'

export const DEMO_ME_ID = 'u1'

const NICKNAMES = [
  'You',
  'Bob',
  'Carol',
  'Dave',
  'Eve',
  'Frank',
  'Grace',
  'Hank',
  'Ivy',
  'Jack',
  'Kate',
  'Leo',
]
const AVATARS = ['🦊', '🐻', '🐰', '🐯', '🐱', '🐶', '🐼', '🦁', '🐸', '🐵', '🦄', '🐧']

export function buildMockPlayers(count = 12): GamePlayer[] {
  return Array.from({ length: count }, (_, i) => ({
    userId: i === 0 ? DEMO_ME_ID : `u${i + 1}`,
    nickname: NICKNAMES[i] ?? `Player ${i + 1}`,
    avatar: AVATARS[i] ?? '🐺',
    seatIndex: i + 1,
    isAlive: true,
    isSheriff: i === 1,
  }))
}

export function buildNightPhaseState(
  role: PlayerRole,
  subPhase: NightSubPhase,
  overrides: Partial<NightPhaseState> = {},
): NightPhaseState {
  const base: NightPhaseState = { subPhase, dayNumber: 1 }
  if (role === 'WEREWOLF') {
    base.teammates = ['Bob']
  }
  if (role === 'WITCH') {
    base.hasAntidote = true
    base.hasPoison = true
    base.attackedPlayerId = 'u3'
    base.attackedNickname = 'Carol'
    base.attackedSeatIndex = 3
  }
  if (role === 'SEER' && subPhase === 'SEER_RESULT') {
    base.seerResult = {
      checkedPlayerId: 'u2',
      checkedNickname: 'Bob',
      checkedSeatIndex: 2,
      isWerewolf: true,
      history: [],
    }
  }
  return { ...base, ...overrides }
}

export function buildDayPhaseState(
  subPhase: DaySubPhase,
  overrides: Partial<DayPhaseState> = {},
): DayPhaseState {
  const phaseStarted = Date.now()
  return {
    subPhase,
    dayNumber: 1,
    phaseDeadline: phaseStarted + 90_000,
    phaseStarted,
    canVote: false,
    nightResult: {
      killedPlayers: [
        { killedPlayerId: 'u3', killedNickname: 'Carol', killedSeatIndex: 3, killedAvatar: '🐰' },
      ],
    },
    ...overrides,
  }
}

export function buildVotingPhaseState(
  subPhase: VotingSubPhase,
  overrides: Partial<VotingState> = {},
): VotingState {
  const phaseStarted = Date.now()
  const tally =
    subPhase === 'VOTE_RESULT' || subPhase === 'HUNTER_SHOOT'
      ? [
          {
            playerId: 'u3',
            nickname: 'Carol',
            seatIndex: 3,
            avatar: '🐰',
            votes: 5,
            voters: [
              { userId: 'u1', nickname: 'You', seatIndex: 1, avatar: '🦊' },
              { userId: 'u2', nickname: 'Bob', seatIndex: 2, avatar: '🐻' },
              { userId: 'u4', nickname: 'Dave', seatIndex: 4, avatar: '🐯' },
              { userId: 'u5', nickname: 'Eve', seatIndex: 5, avatar: '🐱' },
              { userId: 'u6', nickname: 'Frank', seatIndex: 6, avatar: '🐶' },
            ],
          },
          {
            playerId: 'u7',
            nickname: 'Grace',
            seatIndex: 7,
            avatar: '🐼',
            votes: 3,
            voters: [
              { userId: 'u7', nickname: 'Grace', seatIndex: 7, avatar: '🐼' },
              { userId: 'u8', nickname: 'Hank', seatIndex: 8, avatar: '🦁' },
              { userId: 'u9', nickname: 'Ivy', seatIndex: 9, avatar: '🐸' },
            ],
          },
        ]
      : undefined
  return {
    subPhase,
    dayNumber: 1,
    phaseDeadline: phaseStarted + 60_000,
    phaseStarted,
    canVote: subPhase === 'VOTING' || subPhase === 'RE_VOTING',
    totalVoters: 11,
    votesSubmitted: subPhase === 'VOTING' ? 4 : 11,
    tallyRevealed: subPhase === 'VOTE_RESULT' || subPhase === 'HUNTER_SHOOT',
    tally,
    eliminatedPlayerId:
      subPhase === 'VOTE_RESULT' || subPhase === 'HUNTER_SHOOT' ? 'u3' : undefined,
    eliminatedNickname: 'Carol',
    eliminatedSeatIndex: 3,
    eliminatedAvatar: '🐰',
    eliminatedRole: 'WEREWOLF',
    ...overrides,
  }
}

export function buildSheriffElectionState(
  subPhase: SheriffSubPhase,
  overrides: Partial<SheriffElectionState> = {},
): SheriffElectionState {
  const candidates = [
    { userId: 'u1', nickname: 'You', avatar: '🦊', status: 'RUNNING' as const },
    { userId: 'u2', nickname: 'Bob', avatar: '🐻', status: 'RUNNING' as const },
    { userId: 'u4', nickname: 'Dave', avatar: '🐯', status: 'RUNNING' as const },
  ]
  return {
    subPhase,
    timeRemaining: 60,
    candidates,
    speakingOrder: ['u1', 'u2', 'u4'],
    currentSpeakerId: subPhase === 'SPEECH' ? 'u1' : undefined,
    canVote: true,
    allVoted: subPhase === 'RESULT',
    voteProgress: subPhase === 'VOTING' ? { voted: 4, total: 9 } : undefined,
    result:
      subPhase === 'RESULT'
        ? {
            sheriffId: 'u2',
            sheriffNickname: 'Bob',
            sheriffAvatar: '🐻',
            tally: [
              { candidateId: 'u2', nickname: 'Bob', seatIndex: 2, votes: 5, voters: [] },
              { candidateId: 'u1', nickname: 'You', seatIndex: 1, votes: 3, voters: [] },
              { candidateId: 'u4', nickname: 'Dave', seatIndex: 4, votes: 1, voters: [] },
            ],
            abstainCount: 0,
            abstainVoters: [],
          }
        : undefined,
    ...overrides,
  }
}

export function buildVoteHistory(): VoteRoundHistory[] {
  return [
    {
      dayNumber: 1,
      tally: [],
      eliminatedPlayerId: 'u3',
      eliminatedNickname: 'Carol',
      eliminatedSeatIndex: 3,
      eliminatedAvatar: '🐰',
      eliminatedRole: 'WEREWOLF',
    },
  ]
}
