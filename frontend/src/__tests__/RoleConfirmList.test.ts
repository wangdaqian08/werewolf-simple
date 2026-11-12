import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RoleConfirmList from '@/components/RoleConfirmList.vue'
import type { GamePlayer } from '@/types'

const gp = (userId: string, seatIndex: number, nickname: string): GamePlayer => ({
  userId,
  nickname,
  seatIndex,
  isAlive: true,
  isSheriff: false,
})

function mountList(confirmedUserIds: string[]) {
  const players = [gp('u3', 3, 'Carol'), gp('u1', 1, 'Host'), gp('u2', 2, 'Bob')]
  return mount(RoleConfirmList, {
    props: { players, confirmedUserIds },
  })
}

describe('RoleConfirmList', () => {
  it('lists every player in seat order with whether they confirmed', () => {
    const wrapper = mountList(['u1', 'u3'])
    const rows = wrapper.findAll('[data-testid^="role-confirm-row-"]')
    expect(rows.map((r) => r.attributes('data-testid'))).toEqual([
      'role-confirm-row-1',
      'role-confirm-row-2',
      'role-confirm-row-3',
    ])
    expect(rows.map((r) => r.attributes('data-confirmed'))).toEqual(['true', 'false', 'true'])
    expect(rows[1]!.text()).toContain('Bob')
  })

  it('says in words who has not confirmed', () => {
    const wrapper = mountList([])
    const row = wrapper.find('[data-testid="role-confirm-row-2"]')
    expect(row.text()).toMatch(/未確認|Not confirmed/)
  })
})
