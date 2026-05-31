export interface WolfBounds {
  min: number
  max: number
  default: number
}

export function wolfBounds(totalPlayers: number): WolfBounds {
  const max = Math.floor(totalPlayers / 3)
  const min = Math.max(1, max - 1)
  return { min, max, default: max }
}

export function clampWolfCount(totalPlayers: number, wolfCount: number): number {
  const { min, max } = wolfBounds(totalPlayers)
  return Math.min(Math.max(wolfCount, min), max)
}
