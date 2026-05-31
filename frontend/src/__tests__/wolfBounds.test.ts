import { describe, expect, it } from 'vitest'
import { wolfBounds, clampWolfCount } from '@/utils/wolfBounds'

describe('wolfBounds', () => {
  it('matches the canonical plus-minus-one rule for N=6..12', () => {
    const table: Array<[number, number, number, number]> = [
      [6, 1, 2, 2],
      [7, 1, 2, 2],
      [8, 1, 2, 2],
      [9, 2, 3, 3],
      [10, 2, 3, 3],
      [11, 2, 3, 3],
      [12, 3, 4, 4],
    ]
    for (const [n, min, max, def] of table) {
      const b = wolfBounds(n)
      expect(b, `bounds(${n})`).toEqual({ min, max, default: def })
    }
  })
})

describe('clampWolfCount', () => {
  it('clamps values back into the bounds for the given player count', () => {
    expect(clampWolfCount(6, 3)).toBe(2)
    expect(clampWolfCount(6, 0)).toBe(1)
    expect(clampWolfCount(9, 1)).toBe(2)
    expect(clampWolfCount(9, 5)).toBe(3)
    expect(clampWolfCount(12, 2)).toBe(3)
    expect(clampWolfCount(12, 4)).toBe(4)
  })

  it('leaves in-bounds values untouched', () => {
    expect(clampWolfCount(6, 2)).toBe(2)
    expect(clampWolfCount(9, 3)).toBe(3)
    expect(clampWolfCount(12, 3)).toBe(3)
  })
})
