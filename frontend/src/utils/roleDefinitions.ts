import { getLocale, i18n, t } from '@/i18n'

export interface RoleDefinition {
  id: string
  emoji: string
  required: boolean
  wolf?: boolean // wolf camp (mirrors backend PlayerRole.isWolf)
}

export const ROLE_DEFINITIONS: readonly RoleDefinition[] = [
  { id: 'WEREWOLF', emoji: '🐺', required: true, wolf: true },
  { id: 'VILLAGER', emoji: '🧑‍🌾', required: true },
  // Kings take one of the wolf seats (not a god seat); both kings may be on (wolves ≥ kings + 1).
  {
    id: 'WHITE_WOLF_KING',
    emoji: '👑',
    required: false,
    wolf: true,
  },
  {
    id: 'BLACK_WOLF_KING',
    emoji: '🖤',
    required: false,
    wolf: true,
  },
  // Kept apart from the pack: it takes a wolf seat but wolves and it never see each other.
  { id: 'MECHANICAL_WOLF', emoji: '🤖', required: false, wolf: true },
  { id: 'SEER', emoji: '🔮', required: false },
  { id: 'MEDIUM', emoji: '👁️', required: false },
  { id: 'WITCH', emoji: '🧙‍♀️', required: false },
  { id: 'HUNTER', emoji: '🏹', required: false },
  { id: 'GUARD', emoji: '🛡️', required: false },
  { id: 'IDIOT', emoji: '🃏', required: false },
  // Appended last: fitRolesToBoard turns gods on from the front, so default boards stay unchanged.
  { id: 'KNIGHT', emoji: '⚔️', required: false },
] as const

export function roleDefinition(id: string): RoleDefinition | undefined {
  return ROLE_DEFINITIONS.find((r) => r.id === id)
}

export function isWolfRole(id: string | null | undefined): boolean {
  return !!id && roleDefinition(id)?.wolf === true
}

/** Wolf camp excluding the mechanical wolf, which is not part of the wolf pack (mirrors backend). */
export function isPackWolfRole(id: string | null | undefined): boolean {
  return isWolfRole(id) && id !== 'MECHANICAL_WOLF'
}

/** Localised role name for the active language (e.g. 狼人 / Werewolf). */
export function roleName(id: string | null | undefined): string {
  return id && roleDefinition(id) ? t(`role.${id}`) : (id ?? '')
}

/**
 * Small secondary tag shown under/next to the role name on role cards: the
 * English name in Chinese mode (the cards' original bilingual look), nothing in
 * English mode where it would just repeat the name.
 */
export function roleTag(id: string | null | undefined): string {
  return id && getLocale() === 'zh' ? t(`role.${id}`, {}, { locale: 'en' }).toUpperCase() : ''
}

/** One-paragraph ability blurb; `short` selects the compact wording where one exists. */
export function roleDescription(id: string | null | undefined, short = false): string {
  if (!id || !roleDefinition(id)) return ''
  const shortKey = `role.descShort.${id}`
  return short && i18n.global.te(shortKey) ? t(shortKey) : t(`role.desc.${id}`)
}
