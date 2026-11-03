import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import MediumTurn from '@/components/MediumTurn.vue'
import { roleName } from '@/utils/roleDefinitions'
import type { GamePlayer } from '@/types'

const p = (userId: string, seatIndex: number, isAlive = true): GamePlayer => ({
  userId,
  nickname: userId,
  seatIndex,
  isAlive,
  isSheriff: false,
})
const players = [p('me', 1), p('a', 2), p('dead', 3, false)]

describe('MediumTurn', () => {
  it('emits MEDIUM_CHECK with the chosen target', async () => {
    const w = mount(MediumTurn, {
      props: { subPhase: 'MEDIUM_PICK', players, myUserId: 'me' },
    })
    expect(w.find('[data-testid="medium-confirm"]').attributes('disabled')).toBeDefined()
    await w.find('[data-testid="medium-target-2"]').trigger('click')
    await w.find('[data-testid="medium-confirm"]').trigger('click')
    expect(w.emitted('action')).toEqual([['MEDIUM_CHECK', 'a']])
  })

  it('cannot pick self', () => {
    const w = mount(MediumTurn, {
      props: { subPhase: 'MEDIUM_PICK', players, myUserId: 'me' },
    })
    expect(w.find('[data-testid="medium-target-1"]').exists()).toBe(false)
  })

  it('result shows role name and history, and confirm emits MEDIUM_CONFIRM', async () => {
    const w = mount(MediumTurn, {
      props: {
        subPhase: 'MEDIUM_RESULT',
        players,
        myUserId: 'me',
        mediumResult: {
          checkedPlayerId: 'a',
          checkedNickname: 'a',
          checkedSeatIndex: 2,
          role: 'WITCH',
          history: [{ round: 1, nickname: 'x', role: 'SEER' }],
        },
      },
    })
    const r = w.find('[data-testid="medium-result"]')
    expect(r.attributes('data-role')).toBe('WITCH')
    expect(r.attributes('data-checked-seat')).toBe('2')
    expect(r.text()).toContain(roleName('WITCH'))
    expect(w.text()).toContain(roleName('SEER')) // history row
    await w.find('[data-testid="medium-result-confirm"]').trigger('click')
    expect(w.emitted('action')).toEqual([['MEDIUM_CONFIRM']])
  })
})
