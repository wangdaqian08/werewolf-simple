import { describe, expect, it } from 'vitest'
import { isWolfRole, roleDefinition } from '@/utils/roleDefinitions'

describe('roleDefinitions', () => {
  it('isWolfRole is true only for wolf-camp roles', () => {
    expect(isWolfRole('WEREWOLF')).toBe(true)
    expect(isWolfRole('WHITE_WOLF_KING')).toBe(true)
    expect(isWolfRole('BLACK_WOLF_KING')).toBe(true)
    for (const role of ['VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT']) {
      expect(isWolfRole(role), role).toBe(false)
    }
    expect(isWolfRole(undefined)).toBe(false)
  })

  it('defines 白狼王 as an optional role', () => {
    expect(roleDefinition('WHITE_WOLF_KING')).toMatchObject({ nameZh: '白狼王', required: false })
  })

  it('defines 黑狼王 as an optional wolf role', () => {
    expect(roleDefinition('BLACK_WOLF_KING')).toMatchObject({
      nameZh: '黑狼王',
      required: false,
      wolf: true,
    })
  })
})
