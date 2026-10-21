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

describe('RoleRevealCard — 黑狼王', () => {
  it('shows the 黑狼王 name, wolf styling and its shot rule', () => {
    const wrapper = mount(RoleRevealCard, {
      props: { role: 'BLACK_WOLF_KING', teammates: ['Alice'], revealed: true },
    })
    expect(wrapper.text()).toContain('黑狼王')
    expect(wrapper.find('.role-label-wolf').exists()).toBe(true)
    expect(wrapper.text()).toContain(
      '夜晚与狼队一起袭击。出局时（被投出、夜晚死亡或被猎人带走）可开枪带走一名玩家；被女巫毒死或自爆时无法开枪。',
    )
    expect(wrapper.text()).toContain('Alice')
  })
})
