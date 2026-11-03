import { describe, expect, it } from 'vitest'
import { isPackWolfRole, isWolfRole, roleDefinition, roleName } from '@/utils/roleDefinitions'

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
    expect(roleDefinition('WHITE_WOLF_KING')).toMatchObject({ required: false })
    expect(roleName('WHITE_WOLF_KING')).toBe('白狼王')
  })

  it('defines 黑狼王 as an optional wolf role', () => {
    expect(roleDefinition('BLACK_WOLF_KING')).toMatchObject({ required: false, wolf: true })
    expect(roleName('BLACK_WOLF_KING')).toBe('黑狼王')
  })

  it('机械狼 is a wolf but not in the pack; 通灵师 is an optional good role', () => {
    expect(isWolfRole('MECHANICAL_WOLF')).toBe(true)
    expect(isPackWolfRole('MECHANICAL_WOLF')).toBe(false)
    expect(isPackWolfRole('WEREWOLF')).toBe(true)
    expect(isPackWolfRole('BLACK_WOLF_KING')).toBe(true)
    expect(isPackWolfRole('SEER')).toBe(false)
    expect(isPackWolfRole(undefined)).toBe(false)
    expect(roleDefinition('MECHANICAL_WOLF')).toMatchObject({ required: false, wolf: true })
    expect(roleDefinition('MEDIUM')).toMatchObject({ required: false })
    expect(isWolfRole('MEDIUM')).toBe(false)
    expect(roleName('MECHANICAL_WOLF')).toBe('機械狼')
    expect(roleName('MEDIUM')).toBe('通靈師')
  })
})
