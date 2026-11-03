import { describe, expect, it } from 'vitest'
import { findRoleLeaks, type PayloadLog } from '../../e2e/real/helpers/role-leak'

const log = (...bodies: unknown[]): PayloadLog => ({
  bodies: bodies.map((b) => ({ src: 'test', body: JSON.stringify(b) })),
})

describe('findRoleLeaks', () => {
  it("flags another player's id paired with a new role", () => {
    const leaks = findRoleLeaks(log({ players: [{ userId: 'u2', role: 'MECHANICAL_WOLF' }] }), 'me')
    expect(leaks).toEqual([{ src: 'test', id: 'u2', role: 'MECHANICAL_WOLF', key: 'role' }])
  })

  it("exempts the viewer's own mediumResult, mechWolf and MediumResult push", () => {
    const state = {
      nightPhase: {
        mediumResult: { checkedPlayerId: 'u2', role: 'WITCH', history: [] },
        mechWolf: {
          learnedRole: 'WITCH',
          learnedPlayerId: 'u3',
          result: { kind: 'LEARN', targetPlayerId: 'u3', role: 'WITCH' },
        },
      },
    }
    const push = { type: 'MediumResult', gameId: 1, checkedUserId: 'u2', role: 'MEDIUM' }
    expect(findRoleLeaks(log(state, push), 'me')).toEqual([])
  })
})
