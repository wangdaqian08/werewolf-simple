import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PlayerSlot from '@/components/PlayerSlot.vue'

// #6: the seat number must be clearly included on room-mode slots (the day
// voting / night / sheriff grids all use room mode). Render it as the app's
// canonical "N號" so players can identify who they're voting for by seat.
describe('PlayerSlot — room mode seat label', () => {
  it('renders an occupied seat as "N號"', () => {
    const wrapper = mount(PlayerSlot, {
      props: { seat: 5, nickname: 'Alice', mode: 'room', variant: 'alive' },
    })
    expect(wrapper.get('.slot-index').text()).toBe('5號')
  })

  it('renders an empty seat as "N號"', () => {
    const wrapper = mount(PlayerSlot, {
      props: { seat: 3, mode: 'room', variant: 'empty' },
    })
    expect(wrapper.get('.empty-num').text()).toBe('3號')
  })
})
