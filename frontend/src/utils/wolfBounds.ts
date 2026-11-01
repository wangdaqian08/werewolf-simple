export interface WolfBounds {
  min: number
  max: number
  default: number
}

// Canonical 狼人杀 板子 is floor(N/3) wolves; the host may go one below or one above.
export function wolfBounds(totalPlayers: number): WolfBounds {
  const base = Math.floor(totalPlayers / 3)
  return { min: Math.max(1, base - 1), max: base + 1, default: base }
}

export function clampWolfCount(totalPlayers: number, wolfCount: number): number {
  const { min, max } = wolfBounds(totalPlayers)
  return Math.min(Math.max(wolfCount, min), max)
}
