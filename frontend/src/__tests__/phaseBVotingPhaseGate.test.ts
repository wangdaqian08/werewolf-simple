/**
 * Phase B contract test: GameView.vue must mount VotingPhase under DAY_DISCUSSION
 * when the sub-phase is HUNTER_SHOOT or BADGE_HANDOVER (the new night-kill
 * routing path).
 *
 * Without this gate, the existing BADGE_HANDOVER / HUNTER_SHOOT UI in
 * VotingPhase.vue stays hidden when a sheriff/hunter dies at night, and the
 * eliminated player has no way to pass the badge or fire the shot.
 *
 * The full mount path is exercised by the Playwright real-backend run; this
 * unit test catches the case where someone removes or simplifies the gate
 * (e.g. "this looks redundant, just check DAY_VOTING").
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const template = readFileSync(join(__dirname, '..', 'views', 'GameView.vue'), 'utf-8')

describe('Phase B — VotingPhase gate under DAY_DISCUSSION', () => {
  it('GameView.vue mounts VotingPhase when DAY_DISCUSSION + dayPhase.subPhase is HUNTER_SHOOT', () => {
    // The gate must include a clause that triggers VotingPhase render when
    // dayPhase.subPhase === 'HUNTER_SHOOT'. We grep for the literal so a
    // refactor that drops/renames the clause fails this test.
    expect(template).toMatch(/dayPhase\?\.subPhase\s*===\s*'HUNTER_SHOOT'/)
  })

  it('GameView.vue mounts VotingPhase when DAY_DISCUSSION + dayPhase.subPhase is BADGE_HANDOVER', () => {
    expect(template).toMatch(/dayPhase\?\.subPhase\s*===\s*'BADGE_HANDOVER'/)
  })

  it('the gate still includes the original DAY_VOTING + votingPhase clause', () => {
    // Sanity: Phase B added an OR branch — the original DAY_VOTING gate
    // must still be there or the vote-out BADGE_HANDOVER UI breaks.
    expect(template).toMatch(/phase\s*===\s*'DAY_VOTING'\s*&&\s*gameStore\.state\?\.votingPhase/)
  })
})
