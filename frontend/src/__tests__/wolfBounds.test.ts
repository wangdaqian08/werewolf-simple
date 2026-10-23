import { describe, expect, it } from 'vitest'
import { wolfBounds } from '@/utils/wolfBounds'

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
