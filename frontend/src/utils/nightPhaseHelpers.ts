/**
 * Pure helper functions for NightPhase cell variants and target eligibility.
 * Extracted for unit testability — no Vue/component dependencies.
 */
import type { GamePlayer, NightPhaseState } from '@/types'

export type NightSlotVariant = 'alive' | 'dead' | 'selected' | 'teammate' | 'waiting' | 'empty'

// ── Werewolf ─────────────────────────────────────────────────────────────────

export function wolfVariant(
  p: GamePlayer,
  state: Pick<NightPhaseState, 'selectedTargetId' | 'teammates'>,
  myUserId: string,
): NightSlotVariant {
  if (!p.isAlive) return 'dead'
  if (p.userId === state.selectedTargetId) return 'selected'
  if ((state.teammates ?? []).includes(p.nickname)) return 'teammate'
  if (p.userId === myUserId) return 'waiting'
  return 'alive'
}

export function isWolfTarget(p: GamePlayer, myUserId: string): boolean {
  return p.isAlive && p.userId !== myUserId
}

// ── Seer ──────────────────────────────────────────────────────────────────────

export function seerVariant(
  p: GamePlayer,
  state: Pick<NightPhaseState, 'selectedTargetId'>,
  myUserId: string,
): NightSlotVariant {
  if (!p.isAlive) return 'dead'
  if (p.userId === state.selectedTargetId) return 'selected'
  if (p.userId === myUserId) return 'waiting'
  return 'alive'
}

export function isSeerTarget(p: GamePlayer, myUserId: string): boolean {
  return p.isAlive && p.userId !== myUserId
}

// ── Guard ─────────────────────────────────────────────────────────────────────

export function guardVariant(
  p: GamePlayer,
  state: Pick<NightPhaseState, 'selectedTargetId' | 'previousGuardTargetId'>,
): NightSlotVariant {
  if (!p.isAlive) return 'dead'
  if (p.userId === state.selectedTargetId) return 'selected'
  if (p.userId === state.previousGuardTargetId) return 'dead'
  return 'alive'
}

export function isGuardTarget(
  p: GamePlayer,
  state: Pick<NightPhaseState, 'previousGuardTargetId'>,
): boolean {
  return p.isAlive && p.userId !== state.previousGuardTargetId
}

// ── Witch (poison picker) ─────────────────────────────────────────────────────

export function poisonVariant(
  p: GamePlayer,
  state: Pick<NightPhaseState, 'selectedTargetId'>,
  myUserId: string,
): NightSlotVariant {
  if (!p.isAlive) return 'dead'
  if (p.userId === state.selectedTargetId) return 'selected'
  if (p.userId === myUserId) return 'waiting'
  return 'alive'
}

export function isPoisonTarget(p: GamePlayer, myUserId: string): boolean {
  return p.isAlive && p.userId !== myUserId
}
