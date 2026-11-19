import { describe, expect, it } from 'vitest'
import { i18n } from '@/i18n'
import {
  BLOCKED_WITH,
  ROLE_DEFINITIONS,
  excludedRoles,
  isPackWolfRole,
  isWolfRole,
  roleDefinition,
  roleName,
} from '@/utils/roleDefinitions'

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

  it('GRAVEKEEPER is defined last, as a non-wolf optional role', () => {
    expect(ROLE_DEFINITIONS[ROLE_DEFINITIONS.length - 1]?.id).toBe('GRAVEKEEPER')
    expect(roleDefinition('GRAVEKEEPER')).toMatchObject({ required: false })
    expect(isWolfRole('GRAVEKEEPER')).toBe(false)
    expect(roleName('GRAVEKEEPER')).toBe('守墓人')
  })

  it('石像鬼 is an optional wolf, not in the pack, and excludes only 機械狼 (both ways)', () => {
    expect(roleDefinition('GARGOYLE')).toMatchObject({ required: false, wolf: true })
    expect(isWolfRole('GARGOYLE')).toBe(true)
    expect(isPackWolfRole('GARGOYLE')).toBe(false)
    expect(roleName('GARGOYLE')).toBe('石像鬼')
    expect(excludedRoles('GARGOYLE')).toEqual(['MECHANICAL_WOLF'])
    expect(BLOCKED_WITH.MECHANICAL_WOLF).toContain('GARGOYLE')
    for (const [role, others] of Object.entries(BLOCKED_WITH))
      if (role !== 'MECHANICAL_WOLF') expect(others, role).not.toContain('GARGOYLE')
  })

  it('石像鬼 has its name, intro and rules in both locales', () => {
    for (const locale of ['zh', 'en'] as const) {
      for (const key of ['role.GARGOYLE', 'role.desc.GARGOYLE', 'role.rules.GARGOYLE'])
        expect(i18n.global.te(key, locale), `${locale} ${key}`).toBe(true)
    }
    expect(i18n.global.t('role.desc.GARGOYLE', {}, { locale: 'zh' })).toBe(
      '狼人陣營。每晚可查驗一名玩家的具體身份；不能查自己，整局不能重複查驗同一人。與狼隊互不相認，不能自爆；預言家、守墓人查驗為狼人。其他狼人全部出局後才獲得狼刀，當晚先驗後刀。',
    )
    expect(i18n.global.t('role.desc.GARGOYLE', {}, { locale: 'en' })).toBe(
      "Wolf camp. Each night, may check one player's exact role; not itself, and never the same player twice in a game. It and the pack do not know each other; it cannot self-destruct; the seer and the gravekeeper see it as a wolf. Gets the wolf kill only once every other wolf is out, and checks before it kills that night.",
    )
  })

  it('狼美人 is an optional pack wolf with no excluded roles', () => {
    expect(roleDefinition('WOLF_BEAUTY')).toMatchObject({ required: false, wolf: true })
    expect(isWolfRole('WOLF_BEAUTY')).toBe(true)
    expect(isPackWolfRole('WOLF_BEAUTY')).toBe(true)
    expect(roleName('WOLF_BEAUTY')).toBe('狼美人')
    expect(excludedRoles('WOLF_BEAUTY')).toEqual([])
    for (const [role, others] of Object.entries(BLOCKED_WITH))
      expect(others, role).not.toContain('WOLF_BEAUTY')
  })

  it('狼美人 has its name, intros and rules in both locales', () => {
    for (const locale of ['zh', 'en'] as const) {
      for (const key of [
        'role.WOLF_BEAUTY',
        'role.desc.WOLF_BEAUTY',
        'role.descShort.WOLF_BEAUTY',
        'role.rules.WOLF_BEAUTY',
      ])
        expect(i18n.global.te(key, locale), `${locale} ${key}`).toBe(true)
    }
    expect(i18n.global.t('role.descShort.WOLF_BEAUTY', {}, { locale: 'zh' })).toBe(
      '每晚魅惑一名玩家；你出局時，被魅惑的玩家殉情。',
    )
    expect(i18n.global.t('role.desc.WOLF_BEAUTY', {}, { locale: 'zh' })).toBe(
      '夜晚與狼隊一起襲擊，第二晚起每晚可魅惑一名玩家。你出局時，最近一晚被魅惑的玩家隨之殉情。',
    )
    expect(i18n.global.t('role.desc.WOLF_BEAUTY', {}, { locale: 'en' })).toBe(
      'Kills with the pack at night, and from night 2 may charm one player each night. When you go out, the player charmed on the latest night goes out with you.',
    )
  })
})
