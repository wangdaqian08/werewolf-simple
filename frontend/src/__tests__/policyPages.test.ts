import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

// The three static policy pages Stripe's review and chargeback evidence rely on.
// They are served as-is from public/, so they must stand alone without JavaScript.
const PAGES = ['terms', 'refunds', 'privacy'] as const

function read(page: (typeof PAGES)[number]): string {
  // HTML whitespace is insignificant; prettier wraps long sentences across lines.
  return fs
    .readFileSync(path.resolve(__dirname, `../../public/${page}.html`), 'utf-8')
    .replace(/\s+/g, ' ')
}

describe('policy pages', () => {
  for (const page of PAGES) {
    it(`${page}.html names the business, the contact and the other pages`, () => {
      const html = read(page)
      expect(html).toContain('Blue Vertex')
      expect(html).toContain('mailto:wangdaqian08@gmail.com')
      expect(html).toMatch(/Last updated/i)
      for (const other of PAGES.filter((p) => p !== page)) {
        expect(html).toContain(`href="/${other}.html"`)
      }
      expect(html).not.toMatch(/<script/i)
      expect(html).not.toMatch(/\bno refunds?\b/i)
    })
  }

  it('the refund policy states the change-of-mind rule and the consumer guarantees', () => {
    const html = read('refunds')
    expect(html).toMatch(/change of mind/i)
    expect(html).toMatch(/14 days/)
    expect(html).toMatch(/Australian Consumer Law/)
  })

  it('the terms cover credits, role-pick draws, minors and AUD', () => {
    const html = read('terms')
    for (const s of [/no cash value/i, /random/i, /under 18/i, /AUD/, /Australian Consumer Law/]) {
      expect(html).toMatch(s)
    }
  })
})
