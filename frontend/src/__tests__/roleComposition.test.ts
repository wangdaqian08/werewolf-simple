import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RoleComposition from '@/components/RoleComposition.vue'

function chipMap(wrapper: ReturnType<typeof mount>) {
  return Object.fromEntries(
    wrapper
      .findAll('[data-role]')
      .map((el) => [el.attributes('data-role'), Number(el.attributes('data-count'))]),
  )
}

describe('RoleComposition', () => {
  it('renders 6p Classic-ish: 2 wolves + Seer + Witch + 2 villagers', () => {
    const wrapper = mount(RoleComposition, {
      props: {
        totalPlayers: 6,
        wolfCount: 2,
        roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH'],
      },
    })
    expect(chipMap(wrapper)).toEqual({
      WEREWOLF: 2,
      SEER: 1,
      WITCH: 1,
      VILLAGER: 2,
    })
    expect(wrapper.attributes('data-testid')).toBe('role-composition')
  })

  it('renders 9p with 3 wolves + 3 gods + 3 villagers', () => {
    const wrapper = mount(RoleComposition, {
      props: {
        totalPlayers: 9,
        wolfCount: 3,
        roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER'],
      },
    })
    expect(chipMap(wrapper)).toEqual({
      WEREWOLF: 3,
      SEER: 1,
      WITCH: 1,
      HUNTER: 1,
      VILLAGER: 3,
    })
  })

  it('omits the villager chip when villagers = 0', () => {
    const wrapper = mount(RoleComposition, {
      props: {
        totalPlayers: 6,
        wolfCount: 2,
        // 2 wolves + 4 gods = 6 seats, 0 villagers
        roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD'],
      },
    })
    expect(chipMap(wrapper)).toEqual({
      WEREWOLF: 2,
      SEER: 1,
      WITCH: 1,
      HUNTER: 1,
      GUARD: 1,
    })
  })

  it('renders chips in a stable order: wolves → gods (defined order) → villagers', () => {
    const wrapper = mount(RoleComposition, {
      props: {
        totalPlayers: 9,
        wolfCount: 3,
        // Pass gods in reversed order; component should re-order them by ROLE_DEFINITIONS
        roles: ['WEREWOLF', 'VILLAGER', 'WITCH', 'SEER'],
      },
    })
    const ordered = wrapper.findAll('[data-role]').map((el) => el.attributes('data-role'))
    expect(ordered).toEqual(['WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'])
  })
})
