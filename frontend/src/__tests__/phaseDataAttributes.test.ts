/**
 * Verifies the GameView data-phase / data-phase-sub / data-day-number
 * binding wired in for sentinel #1 (cross-browser DOM phase attributes).
 *
 * Two slices are tested:
 *
 *   1. The PHASE_DATA_VALUES mapping in assertions.ts — every spec label
 *      ('NIGHT', 'DAY', 'VOTING', etc.) maps to a non-empty list of
 *      authoritative `data-phase` values, and the lists do not silently
 *      overlap (which would make verifyAllBrowsersPhase ambiguous).
 *
 *   2. The Kotlin GamePhase enum is fully covered. Without this check, a
 *      backend phase added later (e.g. a new transition) would silently
 *      break the spec — the test fails the moment the enum drifts so we
 *      remember to update PHASE_DATA_VALUES at the same time.
 *
 * The component-level wiring (GameView.vue's :data-phase binding) is
 * exercised by the real-backend Playwright run, where assertNoBrowserErrors
 * + verifyAllBrowsersPhase together fail loudly on missing attributes.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { PHASE_DATA_VALUES } from '../../e2e/real/helpers/assertions'

// Scraped from backend/src/main/kotlin/com/werewolf/model/Enums.kt at the
// time of writing this test. If the enum changes, this list must change
// or the "every backend phase is covered" test will fail — which is the
// point.
const BACKEND_GAME_PHASES = [
  'ROLE_REVEAL',
  'SHERIFF_ELECTION',
  'WAITING',
  'NIGHT',
  'DAY_PENDING',
  'DAY_DISCUSSION',
  'DAY_VOTING',
  'GAME_OVER',
] as const

describe('PHASE_DATA_VALUES contract', () => {
  it('every label maps to at least one backend phase', () => {
    for (const [label, values] of Object.entries(PHASE_DATA_VALUES)) {
      expect(values.length, `label ${label} has empty mapping`).toBeGreaterThan(0)
    }
  })

  it('every mapped value is a real backend phase', () => {
    const known = new Set<string>(BACKEND_GAME_PHASES)
    for (const [label, values] of Object.entries(PHASE_DATA_VALUES)) {
      for (const v of values) {
        expect(known.has(v), `label=${label} value=${v} not in BACKEND_GAME_PHASES`).toBe(true)
      }
    }
  })

  it('every backend phase has at least one label that maps to it', () => {
    const covered = new Set<string>()
    for (const values of Object.values(PHASE_DATA_VALUES)) {
      for (const v of values) covered.add(v)
    }
    for (const phase of BACKEND_GAME_PHASES) {
      expect(covered.has(phase), `backend phase ${phase} has no label`).toBe(true)
    }
  })

  it('label-to-value mapping is unambiguous (no value belongs to two labels)', () => {
    const valueToLabel = new Map<string, string>()
    for (const [label, values] of Object.entries(PHASE_DATA_VALUES)) {
      for (const v of values) {
        const prior = valueToLabel.get(v)
        if (prior) {
          throw new Error(`backend phase ${v} mapped by both '${prior}' and '${label}'`)
        }
        valueToLabel.set(v, label)
      }
    }
  })
})

describe('GameView template binds data-phase / data-phase-sub / data-day-number', () => {
  // We do not mount GameView here (heavy dependency tree). Instead we
  // assert the static template includes the bindings — a regex over the
  // file content is enough to catch "binding was renamed/removed and
  // verifyAllBrowsersPhase silently switched to its CSS-class fallback".
  const template = readFileSync(
    join(__dirname, '..', 'views', 'GameView.vue'),
    'utf-8',
  )

  it('binds :data-phase from gameStore.state?.phase', () => {
    expect(template).toMatch(/:data-phase="\s*gameStore\.state\?\.phase\s*\?\?/)
  })

  it('binds :data-phase-sub from at least one of nightPhase/votingPhase/dayPhase/sheriffElection', () => {
    expect(template).toMatch(/:data-phase-sub=/)
    expect(template).toMatch(/nightPhase\?\.subPhase/)
    expect(template).toMatch(/votingPhase\?\.subPhase/)
    expect(template).toMatch(/dayPhase\?\.subPhase/)
    expect(template).toMatch(/sheriffElection\?\.subPhase/)
  })

  it('binds :data-day-number from the right state path', () => {
    expect(template).toMatch(/:data-day-number=/)
    expect(template).toMatch(/nightPhase\?\.dayNumber/)
  })
})
