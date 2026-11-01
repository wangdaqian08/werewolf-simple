import { describe, expect, it } from 'vitest'
import { wolfBounds, clampWolfCount } from '@/utils/wolfBounds'

describe('wolfBounds', () => {
  it('allows one below and one above the canonical floor(N/3) for N=6..15', () => {
    const table: Array<[number, number, number, number]> = [
      [6, 1, 3, 2],
      [7, 1, 3, 2],
      [8, 1, 3, 2],
      [9, 2, 4, 3],
      [10, 2, 4, 3],
      [11, 2, 4, 3],
      [12, 3, 5, 4],
      [15, 4, 6, 5],
    ]
    for (const [n, min, max, def] of table) {
      const b = wolfBounds(n)
      expect(b, `bounds(${n})`).toEqual({ min, max, default: def })
    }
  })
})

describe('clampWolfCount', () => {
  it('clamps values back into the bounds for the given player count', () => {
    expect(clampWolfCount(6, 4)).toBe(3)
    expect(clampWolfCount(6, 0)).toBe(1)
    expect(clampWolfCount(9, 1)).toBe(2)
    expect(clampWolfCount(9, 5)).toBe(4)
    expect(clampWolfCount(12, 2)).toBe(3)
    expect(clampWolfCount(12, 6)).toBe(5)
  })

  it('leaves in-bounds values untouched', () => {
    expect(clampWolfCount(6, 2)).toBe(2)
    expect(clampWolfCount(9, 3)).toBe(3)
    expect(clampWolfCount(12, 3)).toBe(3)
  })
})
