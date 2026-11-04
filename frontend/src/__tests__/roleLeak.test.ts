import { describe, expect, it } from 'vitest'
import { findRoleLeaks, type PayloadLog } from '../../e2e/real/helpers/role-leak'

const log = (...bodies: unknown[]): PayloadLog => ({
  bodies: bodies.map((b) => ({ src: 'test', body: JSON.stringify(b) })),
})

const mediumState = {
  nightPhase: { mediumResult: { checkedPlayerId: 'u2', role: 'WITCH', history: [] } },
}
const mediumPush = { type: 'MediumResult', gameId: 1, checkedUserId: 'u2', role: 'MEDIUM' }
const mechState = {
  nightPhase: {
    mechWolf: {
      learnedRole: 'WITCH',
      learnedPlayerId: 'u3',
      result: { kind: 'LEARN', targetPlayerId: 'u3', role: 'WITCH' },
    },
  },
}

describe('findRoleLeaks', () => {
  it("flags another player's id paired with a new role", () => {
    const leaks = findRoleLeaks(
      log({ players: [{ userId: 'u2', role: 'MECHANICAL_WOLF' }] }),
      'me',
      'VILLAGER',
    )
    expect(leaks).toEqual([{ src: 'test', id: 'u2', role: 'MECHANICAL_WOLF', key: 'role' }])
  })

  it('exempts mediumResult and the MediumResult push only on the medium page', () => {
    expect(findRoleLeaks(log(mediumState, mediumPush), 'me', 'MEDIUM')).toEqual([])
  })

  it('exempts mechWolf only on the mechanical wolf page', () => {
    expect(findRoleLeaks(log(mechState), 'me', 'MECHANICAL_WOLF')).toEqual([])
  })

  it('never skips a subtree when the viewer role is unknown', () => {
    const unknown = undefined as unknown as string
    const leaks = findRoleLeaks(log(mediumState, mediumPush, mechState), 'me', unknown)
    expect(leaks).toHaveLength(4)
  })

  it("reports another role's own result as a leak", () => {
    const medium = [
      { src: 'test', id: 'u2', role: 'WITCH', key: 'role' },
      { src: 'test', id: 'u2', role: 'MEDIUM', key: 'role' },
    ]
    const mech = [
      { src: 'test', id: 'u3', role: 'WITCH', key: 'learnedRole' },
      { src: 'test', id: 'u3', role: 'WITCH', key: 'role' },
    ]
    expect(findRoleLeaks(log(mediumState, mediumPush), 'me', 'MECHANICAL_WOLF')).toEqual(medium)
    expect(findRoleLeaks(log(mechState), 'me', 'MEDIUM')).toEqual(mech)
    expect(findRoleLeaks(log(mediumState, mediumPush, mechState), 'me', 'VILLAGER')).toEqual([
      ...medium,
      ...mech,
    ])
  })
})
