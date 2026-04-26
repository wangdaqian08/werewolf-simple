/**
 * Game-state invariants asserted between every test step.
 *
 * The principle: there are properties of a running werewolf game that
 * MUST always hold, and a single cheap state read after each step
 * surfaces violations the per-step assertions would never catch on
 * their own.
 *
 * Invariants enforced:
 *
 *   1. (dayNumber, phase) rank is monotonic — the game never goes
 *      backwards in time. Phase aliases (DAY_PENDING / DAY_DISCUSSION)
 *      are ranked in their natural order; round number multiplies
 *      so day-2-NIGHT is strictly after day-1-DAY_VOTING.
 *   2. Alive count is monotonic decreasing — players never resurrect.
 *   3. Sub-phase belongs to the parent phase — NightSubPhase only
 *      makes sense in NIGHT, VotingSubPhase in DAY_VOTING, etc.
 *   4. Sheriff (if elected) is alive while the game is running.
 *      Exceptions: the GAME_OVER terminal state and the BADGE_HANDOVER
 *      sub-phase, where the dying sheriff transfers the badge.
 *
 * Use:
 *
 *   let invariants = newInvariantState()
 *   test('1. step', async () => {
 *     // ... gameplay
 *     invariants = await assertGameInvariants(hostPage, gameId, invariants, 'step-1')
 *   })
 *
 * Each call returns the new state; pass it back in for the next step.
 */
import type { Page } from '@playwright/test'

// ── Phase ranking ────────────────────────────────────────────────────────────

/**
 * Per-round phase position. Higher = later within the round.
 * dayNumber multiplies by 1000, so day-2-NIGHT (2000+30=2030) is
 * strictly greater than day-1-DAY_VOTING (1000+60=1060). GAME_OVER is
 * a terminal marker; rank is meaningless once reached.
 */
export const PHASE_RANK: Record<string, number> = Object.freeze({
  ROLE_REVEAL: 0,
  WAITING: 10,
  SHERIFF_ELECTION: 20,
  NIGHT: 30,
  DAY_PENDING: 40,
  DAY_DISCUSSION: 50,
  DAY_VOTING: 60,
  GAME_OVER: 9999,
})

/** Standalone constant so direct access avoids noUncheckedIndexedAccess. */
const GAME_OVER_RANK = 9999

const NIGHT_SUBS = new Set([
  'WAITING',
  'WEREWOLF_PICK',
  'SEER_PICK',
  'SEER_RESULT',
  'WITCH_ACT',
  'GUARD_PICK',
  'COMPLETE',
])

const DAY_SUBS = new Set(['RESULT_HIDDEN', 'RESULT_REVEALED'])

const VOTING_SUBS = new Set([
  'VOTING',
  'RE_VOTING',
  'VOTE_RESULT',
  'HUNTER_SHOOT',
  'BADGE_HANDOVER',
])

const SHERIFF_SUBS = new Set(['SIGNUP', 'SPEECH', 'VOTING', 'RESULT', 'TIED'])

// ── State carried across test steps ──────────────────────────────────────────

export interface GameInvariantState {
  /** Last observed top-level phase, or null before the first call. */
  lastPhase: string | null
  /** Last observed day number (defaults to 1 before the first NIGHT). */
  lastDayNumber: number
  /** Last observed alive-player count, or null before the first call. */
  lastAliveCount: number | null
  /**
   * Composite rank (PHASE_RANK[phase] + dayNumber * 1000); used for the
   * monotonic time-direction check. Initialized at -1 so the very first
   * observation always passes.
   */
  lastPhaseRank: number
}

export const newInvariantState = (): GameInvariantState => ({
  lastPhase: null,
  lastDayNumber: 1,
  lastAliveCount: null,
  lastPhaseRank: -1,
})

// ── Pure rank computation (exported for unit tests) ──────────────────────────

export function computePhaseRank(phase: string, dayNumber: number): number {
  const base = PHASE_RANK[phase]
  if (base === undefined) {
    throw new Error(`computePhaseRank: unknown phase "${phase}"`)
  }
  if (phase === 'GAME_OVER') return GAME_OVER_RANK
  return base + dayNumber * 1000
}

// ── Pure invariant check (exported so tests can drive it without a page) ─────

export interface MinimalGameState {
  phase: string
  nightPhase?: { subPhase?: string; dayNumber?: number } | null
  votingPhase?: { subPhase?: string } | null
  dayPhase?: { subPhase?: string; dayNumber?: number } | null
  sheriffElection?: { subPhase?: string } | null
  players?: Array<{
    userId: string
    isAlive: boolean
    isSheriff?: boolean
    nickname?: string
    seatIndex?: number
  }>
  dayNumber?: number
}

/**
 * Throws if any invariant is violated. Returns the new state for the
 * caller to thread into the next call. The `contextLabel` appears in
 * every thrown error so the failing test step is identifiable.
 */
export function assertGameInvariantsOnState(
  state: MinimalGameState,
  prev: GameInvariantState,
  contextLabel: string,
): GameInvariantState {
  const phase = state.phase
  if (PHASE_RANK[phase] === undefined) {
    throw new Error(`[invariants/${contextLabel}] unknown phase "${phase}"`)
  }
  const dayNumber =
    state.nightPhase?.dayNumber ??
    state.dayPhase?.dayNumber ??
    state.dayNumber ??
    prev.lastDayNumber
  const aliveCount = (state.players ?? []).filter((p) => p.isAlive).length
  const rank = computePhaseRank(phase, dayNumber)

  // 1. Monotonic time direction (skip when entering GAME_OVER, which
  //    overrides ranking).
  if (phase !== 'GAME_OVER' && rank < prev.lastPhaseRank) {
    throw new Error(
      `[invariants/${contextLabel}] phase regressed: ` +
        `${prev.lastPhase}/day${prev.lastDayNumber} (rank ${prev.lastPhaseRank}) → ` +
        `${phase}/day${dayNumber} (rank ${rank})`,
    )
  }

  // 2. Alive count is monotonic decreasing.
  if (prev.lastAliveCount !== null && aliveCount > prev.lastAliveCount) {
    throw new Error(
      `[invariants/${contextLabel}] alive count grew: ` +
        `${prev.lastAliveCount} → ${aliveCount} (resurrection?)`,
    )
  }

  // 3. Sub-phase belongs to its parent phase (when set).
  const nSub = state.nightPhase?.subPhase
  if (phase === 'NIGHT' && nSub && !NIGHT_SUBS.has(nSub)) {
    throw new Error(
      `[invariants/${contextLabel}] phase=NIGHT but unrecognized nightSubPhase="${nSub}"`,
    )
  }
  const dSub = state.dayPhase?.subPhase
  if ((phase === 'DAY_PENDING' || phase === 'DAY_DISCUSSION') && dSub && !DAY_SUBS.has(dSub)) {
    throw new Error(
      `[invariants/${contextLabel}] phase=${phase} but unrecognized daySubPhase="${dSub}"`,
    )
  }
  const vSub = state.votingPhase?.subPhase
  if (phase === 'DAY_VOTING' && vSub && !VOTING_SUBS.has(vSub)) {
    throw new Error(
      `[invariants/${contextLabel}] phase=DAY_VOTING but unrecognized votingSubPhase="${vSub}"`,
    )
  }
  const sSub = state.sheriffElection?.subPhase
  if (phase === 'SHERIFF_ELECTION' && sSub && !SHERIFF_SUBS.has(sSub)) {
    throw new Error(
      `[invariants/${contextLabel}] phase=SHERIFF_ELECTION but unrecognized electionSubPhase="${sSub}"`,
    )
  }

  // 4. Sheriff (if any) is alive while the game is running. The badge can
  //    persist for a moment during BADGE_HANDOVER while it transfers; the
  //    GAME_OVER terminal state is exempt.
  const sheriff = (state.players ?? []).find((p) => p.isSheriff)
  if (sheriff && !sheriff.isAlive) {
    const inHandover = phase === 'DAY_VOTING' && vSub === 'BADGE_HANDOVER'
    if (phase !== 'GAME_OVER' && !inHandover) {
      throw new Error(
        `[invariants/${contextLabel}] sheriff (seat ${sheriff.seatIndex} ${sheriff.nickname}) ` +
          `is dead while phase=${phase}/sub=${vSub ?? '-'} — badge should have transferred`,
      )
    }
  }

  return {
    lastPhase: phase,
    lastDayNumber: dayNumber,
    lastAliveCount: aliveCount,
    lastPhaseRank: rank,
  }
}

// ── Live-page wrapper ────────────────────────────────────────────────────────

/**
 * Fetch /api/game/{id}/state via the host page's JWT and run the pure
 * invariant check on the result. Returns the new state; throws on
 * violation or on /state read failure.
 */
export async function assertGameInvariants(
  hostPage: Page,
  gameId: string,
  prev: GameInvariantState,
  contextLabel: string,
): Promise<GameInvariantState> {
  const state = await hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return null
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return null
    return res.json()
  }, gameId)

  if (!state || typeof state.phase !== 'string') {
    throw new Error(
      `[invariants/${contextLabel}] failed to read /api/game/${gameId}/state — backend down or auth lost`,
    )
  }

  return assertGameInvariantsOnState(state as MinimalGameState, prev, contextLabel)
}
