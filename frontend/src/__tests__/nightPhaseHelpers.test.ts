import { describe, expect, it } from 'vitest'
import {
  guardVariant,
  isGuardTarget,
  isPoisonTarget,
  isSeerTarget,
  isWolfTarget,
  poisonVariant,
  seerVariant,
  wolfVariant,
} from '@/utils/nightPhaseHelpers'
import type { GamePlayer, NightPhaseState } from '@/types'

// ── Fixtures ──────────────────────────────────────────────────────────────────

function player(overrides: Partial<GamePlayer> = {}): GamePlayer {
  return {
    userId: 'u1',
    nickname: 'Alice',
    seatIndex: 1,
    isAlive: true,
    isSheriff: false,
    ...overrides,
  }
}

const ME = 'u1'
const OTHER = 'u2'

// ── wolfVariant ───────────────────────────────────────────────────────────────

describe('wolfVariant', () => {
  const state: Pick<NightPhaseState, 'selectedTargetId' | 'teammates'> = {
    selectedTargetId: undefined,
    teammates: ['Bob'],
  }

  it('dead player → dead', () => {
    expect(wolfVariant(player({ isAlive: false }), state, ME)).toBe('dead')
  })

  it('selected player → selected', () => {
    expect(wolfVariant(player({ userId: OTHER }), { ...state, selectedTargetId: OTHER }, ME)).toBe(
      'selected',
    )
  })

  it('teammate (alive, not selected) → teammate', () => {
    expect(wolfVariant(player({ userId: OTHER, nickname: 'Bob' }), state, ME)).toBe('teammate')
  })

  it('self → waiting (wolves cannot target themselves)', () => {
    expect(wolfVariant(player({ userId: ME }), state, ME)).toBe('waiting')
  })

  it('normal alive other player → alive', () => {
    expect(wolfVariant(player({ userId: OTHER, nickname: 'Carol' }), state, ME)).toBe('alive')
  })

  it('dead takes priority over teammate', () => {
    expect(wolfVariant(player({ userId: OTHER, nickname: 'Bob', isAlive: false }), state, ME)).toBe(
      'dead',
    )
  })
})

// ── isWolfTarget ──────────────────────────────────────────────────────────────

describe('isWolfTarget', () => {
  it('alive other player → true', () => {
    expect(isWolfTarget(player({ userId: OTHER }), ME)).toBe(true)
  })

  it('self → false', () => {
    expect(isWolfTarget(player({ userId: ME }), ME)).toBe(false)
  })

  it('dead player → false', () => {
    expect(isWolfTarget(player({ userId: OTHER, isAlive: false }), ME)).toBe(false)
  })

  it('teammate is a valid wolf target', () => {
    // Wolves CAN attack their own teammates (unusual but valid)
    expect(isWolfTarget(player({ userId: OTHER, nickname: 'Bob' }), ME)).toBe(true)
  })
})

// ── seerVariant ───────────────────────────────────────────────────────────────

describe('seerVariant', () => {
  const state: Pick<NightPhaseState, 'selectedTargetId'> = { selectedTargetId: undefined }

  it('dead player → dead', () => {
    expect(seerVariant(player({ isAlive: false }), state, ME)).toBe('dead')
  })

  it('selected player → selected', () => {
    expect(seerVariant(player({ userId: OTHER }), { selectedTargetId: OTHER }, ME)).toBe('selected')
  })

  it('self → waiting (seer cannot check themselves)', () => {
    expect(seerVariant(player({ userId: ME }), state, ME)).toBe('waiting')
  })

  it('normal alive other player → alive', () => {
    expect(seerVariant(player({ userId: OTHER }), state, ME)).toBe('alive')
  })
})

// ── isSeerTarget ──────────────────────────────────────────────────────────────

describe('isSeerTarget', () => {
  it('alive other player → true', () => {
    expect(isSeerTarget(player({ userId: OTHER }), ME)).toBe(true)
  })

  it('self → false', () => {
    expect(isSeerTarget(player({ userId: ME }), ME)).toBe(false)
  })

  it('dead player → false', () => {
    expect(isSeerTarget(player({ userId: OTHER, isAlive: false }), ME)).toBe(false)
  })
})

// ── guardVariant ──────────────────────────────────────────────────────────────

describe('guardVariant', () => {
  const state: Pick<NightPhaseState, 'selectedTargetId' | 'previousGuardTargetId'> = {
    selectedTargetId: undefined,
    previousGuardTargetId: 'u5',
  }

  it('dead player → dead', () => {
    expect(guardVariant(player({ isAlive: false }), state)).toBe('dead')
  })

  it('selected player → selected', () => {
    expect(guardVariant(player({ userId: OTHER }), { ...state, selectedTargetId: OTHER })).toBe(
      'selected',
    )
  })

  it('previously guarded player → dead (dimmed, unselectable)', () => {
    expect(guardVariant(player({ userId: 'u5' }), state)).toBe('dead')
  })

  it('normal alive player → alive', () => {
    expect(guardVariant(player({ userId: OTHER }), state)).toBe('alive')
  })

  it('dead takes priority over previously guarded', () => {
    expect(guardVariant(player({ userId: 'u5', isAlive: false }), state)).toBe('dead')
  })
})

// ── isGuardTarget ─────────────────────────────────────────────────────────────

describe('isGuardTarget', () => {
  const state: Pick<NightPhaseState, 'previousGuardTargetId'> = { previousGuardTargetId: 'u5' }

  it('alive non-previous player → true', () => {
    expect(isGuardTarget(player({ userId: OTHER }), state)).toBe(true)
  })

  it('previously guarded player → false (cannot repeat)', () => {
    expect(isGuardTarget(player({ userId: 'u5' }), state)).toBe(false)
  })

  it('dead player → false', () => {
    expect(isGuardTarget(player({ userId: OTHER, isAlive: false }), state)).toBe(false)
  })

  it('no previous target → all alive players targetable', () => {
    expect(isGuardTarget(player({ userId: OTHER }), { previousGuardTargetId: undefined })).toBe(
      true,
    )
  })
})

// ── poisonVariant ─────────────────────────────────────────────────────────────

describe('poisonVariant', () => {
  const state: Pick<NightPhaseState, 'selectedTargetId'> = { selectedTargetId: undefined }

  it('dead player → dead', () => {
    expect(poisonVariant(player({ isAlive: false }), state, ME)).toBe('dead')
  })

  it('selected player → selected', () => {
    expect(poisonVariant(player({ userId: OTHER }), { selectedTargetId: OTHER }, ME)).toBe(
      'selected',
    )
  })

  it('self → waiting (witch cannot poison herself)', () => {
    expect(poisonVariant(player({ userId: ME }), state, ME)).toBe('waiting')
  })

  it('normal alive other player → alive', () => {
    expect(poisonVariant(player({ userId: OTHER }), state, ME)).toBe('alive')
  })
})

// ── isPoisonTarget ────────────────────────────────────────────────────────────

describe('isPoisonTarget', () => {
  it('alive other player → true', () => {
    expect(isPoisonTarget(player({ userId: OTHER }), ME)).toBe(true)
  })

  it('self → false', () => {
    expect(isPoisonTarget(player({ userId: ME }), ME)).toBe(false)
  })

  it('dead player → false', () => {
    expect(isPoisonTarget(player({ userId: OTHER, isAlive: false }), ME)).toBe(false)
  })
})
