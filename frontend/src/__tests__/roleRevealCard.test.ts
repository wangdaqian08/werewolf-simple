import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RoleRevealCard from '@/components/RoleRevealCard.vue'

describe('RoleRevealCard — 白狼王', () => {
  it('shows the 白狼王 name, wolf styling and its take rule', () => {
    const wrapper = mount(RoleRevealCard, {
      props: { role: 'WHITE_WOLF_KING', teammates: ['Alice'], revealed: true },
    })
    expect(wrapper.text()).toContain('白狼王')
    expect(wrapper.find('.role-label-wolf').exists()).toBe(true)
    expect(wrapper.text()).toContain('带走一名玩家')
    expect(wrapper.text()).toContain('Alice')
  })
})
