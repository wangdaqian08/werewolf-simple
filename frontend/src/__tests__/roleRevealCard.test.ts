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
    expect(wrapper.text()).toContain('帶走一名玩家')
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
      '夜晚與狼隊一起襲擊。出局時（被投出、夜晚死亡或被獵人帶走）可開槍帶走一名玩家；被女巫毒死或自爆時無法開槍。',
    )
    expect(wrapper.text()).toContain('Alice')
  })
})
