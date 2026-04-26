/**
 * Unit tests for the pure invariant logic. Drives
 * assertGameInvariantsOnState with hand-rolled MinimalGameState objects
 * and confirms each rule fires (or holds silent) as designed.
 */
import { describe, it, expect } from 'vitest'
import {
  assertGameInvariantsOnState,
  computePhaseRank,
  newInvariantState,
  type MinimalGameState,
} from '../../e2e/real/helpers/invariants'

const baseState = (over: Partial<MinimalGameState> = {}): MinimalGameState => ({
  phase: 'NIGHT',
  nightPhase: { subPhase: 'WEREWOLF_PICK', dayNumber: 1 },
  players: [
    { userId: 'u1', isAlive: true, seatIndex: 1, nickname: 'Alice' },
    { userId: 'u2', isAlive: true, seatIndex: 2, nickname: 'Bob' },
    { userId: 'u3', isAlive: true, seatIndex: 3, nickname: 'Cara' },
  ],
  ...over,
})

describe('computePhaseRank', () => {
  it('orders within a single round', () => {
    expect(computePhaseRank('ROLE_REVEAL', 1)).toBeLessThan(computePhaseRank('NIGHT', 1))
    expect(computePhaseRank('NIGHT', 1)).toBeLessThan(computePhaseRank('DAY_PENDING', 1))
    expect(computePhaseRank('DAY_PENDING', 1)).toBeLessThan(computePhaseRank('DAY_DISCUSSION', 1))
    expect(computePhaseRank('DAY_DISCUSSION', 1)).toBeLessThan(computePhaseRank('DAY_VOTING', 1))
  })

  it('day 2 NIGHT > day 1 DAY_VOTING', () => {
    expect(computePhaseRank('NIGHT', 2)).toBeGreaterThan(computePhaseRank('DAY_VOTING', 1))
  })

  it('throws on unknown phase', () => {
    expect(() => computePhaseRank('FOO_BAR', 1)).toThrow(/unknown phase/)
  })

  it('GAME_OVER is terminal regardless of dayNumber', () => {
    expect(computePhaseRank('GAME_OVER', 1)).toBe(computePhaseRank('GAME_OVER', 99))
  })
})

describe('assertGameInvariantsOnState — happy path', () => {
  it('first call accepts any reasonable state', () => {
    const next = assertGameInvariantsOnState(baseState(), newInvariantState(), 'first')
    expect(next.lastPhase).toBe('NIGHT')
    expect(next.lastDayNumber).toBe(1)
    expect(next.lastAliveCount).toBe(3)
  })

  it('forward NIGHT → DAY_PENDING is allowed', () => {
    let s = newInvariantState()
    s = assertGameInvariantsOnState(baseState(), s, 'night')
    s = assertGameInvariantsOnState(
      baseState({
        phase: 'DAY_PENDING',
        nightPhase: null,
        dayPhase: { subPhase: 'RESULT_HIDDEN', dayNumber: 1 },
      }),
      s,
      'pending',
    )
    expect(s.lastPhase).toBe('DAY_PENDING')
  })

  it('cross-day forward is allowed (day 1 DAY_VOTING → day 2 NIGHT)', () => {
    let s = newInvariantState()
    s = assertGameInvariantsOnState(
      baseState({
        phase: 'DAY_VOTING',
        nightPhase: null,
        votingPhase: { subPhase: 'VOTING' },
        dayPhase: { dayNumber: 1 },
      }),
      s,
      'day1-vote',
    )
    s = assertGameInvariantsOnState(
      baseState({
        phase: 'NIGHT',
        nightPhase: { subPhase: 'WEREWOLF_PICK', dayNumber: 2 },
      }),
      s,
      'day2-night',
    )
    expect(s.lastDayNumber).toBe(2)
  })

  it('alive count decreasing from 3 to 2 is allowed', () => {
    let s = newInvariantState()
    s = assertGameInvariantsOnState(baseState(), s, 'before')
    expect(() =>
      assertGameInvariantsOnState(
        baseState({
          players: [
            { userId: 'u1', isAlive: true, seatIndex: 1 },
            { userId: 'u2', isAlive: false, seatIndex: 2 },
            { userId: 'u3', isAlive: true, seatIndex: 3 },
          ],
        }),
        s,
        'after-night',
      ),
    ).not.toThrow()
  })
})

describe('assertGameInvariantsOnState — violations', () => {
  it('phase regression throws (DAY_VOTING day1 → NIGHT day1)', () => {
    let s = newInvariantState()
    s = assertGameInvariantsOnState(
      baseState({ phase: 'DAY_VOTING', nightPhase: null, dayPhase: { dayNumber: 1 } }),
      s,
      'voted',
    )
    expect(() =>
      assertGameInvariantsOnState(
        baseState({ phase: 'NIGHT', nightPhase: { subPhase: 'WEREWOLF_PICK', dayNumber: 1 } }),
        s,
        'regress',
      ),
    ).toThrow(/regressed/)
  })

  it('alive count growing throws', () => {
    let s = newInvariantState()
    s = assertGameInvariantsOnState(
      baseState({
        players: [
          { userId: 'u1', isAlive: true, seatIndex: 1 },
          { userId: 'u2', isAlive: false, seatIndex: 2 },
        ],
      }),
      s,
      'two-alive',
    )
    expect(() =>
      assertGameInvariantsOnState(
        baseState({
          players: [
            { userId: 'u1', isAlive: true, seatIndex: 1 },
            { userId: 'u2', isAlive: true, seatIndex: 2 },
          ],
        }),
        s,
        'resurrected',
      ),
    ).toThrow(/grew/)
  })

  it('unknown nightSubPhase throws', () => {
    expect(() =>
      assertGameInvariantsOnState(
        baseState({ nightPhase: { subPhase: 'NONSENSE_PICK', dayNumber: 1 } }),
        newInvariantState(),
        'bad-sub',
      ),
    ).toThrow(/unrecognized nightSubPhase/)
  })

  it('unknown votingSubPhase throws when phase=DAY_VOTING', () => {
    expect(() =>
      assertGameInvariantsOnState(
        baseState({
          phase: 'DAY_VOTING',
          nightPhase: null,
          votingPhase: { subPhase: 'NOT_A_REAL_SUB' },
        }),
        newInvariantState(),
        'bad-voting-sub',
      ),
    ).toThrow(/unrecognized votingSubPhase/)
  })

  it('dead sheriff during DAY_DISCUSSION throws', () => {
    expect(() =>
      assertGameInvariantsOnState(
        baseState({
          phase: 'DAY_DISCUSSION',
          nightPhase: null,
          dayPhase: { dayNumber: 1 },
          players: [
            { userId: 'u1', isAlive: false, isSheriff: true, seatIndex: 1, nickname: 'Sheriff' },
            { userId: 'u2', isAlive: true, seatIndex: 2 },
          ],
        }),
        newInvariantState(),
        'dead-sheriff',
      ),
    ).toThrow(/sheriff.*is dead/)
  })

  it('dead sheriff during BADGE_HANDOVER does NOT throw (badge transferring)', () => {
    expect(() =>
      assertGameInvariantsOnState(
        baseState({
          phase: 'DAY_VOTING',
          nightPhase: null,
          votingPhase: { subPhase: 'BADGE_HANDOVER' },
          dayPhase: { dayNumber: 2 },
          players: [
            { userId: 'u1', isAlive: false, isSheriff: true, seatIndex: 1, nickname: 'Sheriff' },
            { userId: 'u2', isAlive: true, seatIndex: 2 },
          ],
        }),
        newInvariantState(),
        'badge-handover',
      ),
    ).not.toThrow()
  })

  it('dead sheriff during GAME_OVER does NOT throw', () => {
    expect(() =>
      assertGameInvariantsOnState(
        baseState({
          phase: 'GAME_OVER',
          nightPhase: null,
          players: [
            { userId: 'u1', isAlive: false, isSheriff: true, seatIndex: 1 },
            { userId: 'u2', isAlive: true, seatIndex: 2 },
          ],
        }),
        newInvariantState(),
        'game-over',
      ),
    ).not.toThrow()
  })

  it('unknown top-level phase throws', () => {
    expect(() =>
      assertGameInvariantsOnState(
        baseState({ phase: 'WEIRD_PHASE' }),
        newInvariantState(),
        'unknown',
      ),
    ).toThrow(/unknown phase/)
  })
})

describe('assertGameInvariantsOnState — context label appears in errors', () => {
  it('error message includes the label so failing step is identifiable', () => {
    expect(() =>
      assertGameInvariantsOnState(
        baseState({ phase: 'WEIRD' }),
        newInvariantState(),
        'step-3-vote',
      ),
    ).toThrow(/step-3-vote/)
  })
})
