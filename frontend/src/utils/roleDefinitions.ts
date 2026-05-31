export interface RoleDefinition {
  id: string
  nameZh: string
  nameEn: string
  emoji: string
  required: boolean
}

export const ROLE_DEFINITIONS: readonly RoleDefinition[] = [
  { id: 'WEREWOLF', nameZh: '狼人', nameEn: 'Werewolf', emoji: '🐺', required: true },
  { id: 'VILLAGER', nameZh: '村民', nameEn: 'Villager', emoji: '🧑‍🌾', required: true },
  { id: 'SEER', nameZh: '预言家', nameEn: 'Seer', emoji: '🔮', required: false },
  { id: 'WITCH', nameZh: '女巫', nameEn: 'Witch', emoji: '🧙‍♀️', required: false },
  { id: 'HUNTER', nameZh: '猎人', nameEn: 'Hunter', emoji: '🏹', required: false },
  { id: 'GUARD', nameZh: '守卫', nameEn: 'Guard', emoji: '🛡️', required: false },
  { id: 'IDIOT', nameZh: '白痴', nameEn: 'Idiot', emoji: '🃏', required: false },
] as const

export function roleDefinition(id: string): RoleDefinition | undefined {
  return ROLE_DEFINITIONS.find((r) => r.id === id)
}
