import { describe, expect, it } from 'vitest'
import { formatMoney } from '@/utils/money'

describe('formatMoney', () => {
  it('shows AUD as A$ with two decimals, whatever the case of the code', () => {
    expect(formatMoney(499, 'aud')).toBe('A$4.99')
    expect(formatMoney(999, 'AUD')).toBe('A$9.99')
  })

  it('formats a non-AUD amount with its code, never a bare $', () => {
    expect(formatMoney(499, 'usd')).toBe('4.99 USD')
    expect(formatMoney(999, 'eur')).toBe('9.99 EUR')
  })

  it('keeps zero- and three-decimal currencies exact', () => {
    expect(formatMoney(500, 'jpy')).toBe('500 JPY')
    expect(formatMoney(1500, 'kwd')).toBe('1.500 KWD')
  })
})
