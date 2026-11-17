import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GargoyleTurn from '@/components/GargoyleTurn.vue'
import { roleName } from '@/utils/roleDefinitions'
import type { GamePlayer, NightPhaseState } from '@/types'

const p = (userId: string, seatIndex: number, isAlive = true): GamePlayer => ({
  userId,
  nickname: userId,
  seatIndex,
  isAlive,
  isSheriff: false,
})
const players = [p('me', 1), p('a', 2), p('b', 3), p('dead', 4, false)]
type Gargoyle = NonNullable<NightPhaseState['gargoyle']>
const state = (over: Partial<Gargoyle> = {}): Gargoyle => ({
  checkedPlayerIds: [],
  result: null,
  history: [],
  ...over,
})
const mountPick = (gargoyle: Gargoyle = state(), actionPending = false) =>
  mount(GargoyleTurn, {
    props: { subPhase: 'GARGOYLE_PICK', gargoyle, players, myUserId: 'me', actionPending },
  })
const target = (w: ReturnType<typeof mountPick>, seat: number) =>
  w.find(`[data-testid="gargoyle-target-${seat}"]`)
const isDisabled = (w: ReturnType<typeof mountPick>, seat: number) =>
  target(w, seat).attributes('disabled') !== undefined

describe('GargoyleTurn', () => {
  it('emits GARGOYLE_CHECK with the chosen target', async () => {
    const w = mountPick()
    expect(w.find('[data-testid="gargoyle-turn"]').exists()).toBe(true)
    expect(w.find('[data-testid="gargoyle-confirm"]').attributes('disabled')).toBeDefined()
    await target(w, 2).trigger('click')
    await w.find('[data-testid="gargoyle-confirm"]').trigger('click')
    expect(w.emitted('action')).toEqual([['GARGOYLE_CHECK', 'a']])
  })

  it('self, dead players and players already checked are disabled', async () => {
    const w = mountPick(state({ checkedPlayerIds: ['b'] }))
    expect(isDisabled(w, 1)).toBe(true) // self
    expect(isDisabled(w, 3)).toBe(true) // checked on an earlier night
    expect(isDisabled(w, 4)).toBe(true) // dead
    expect(isDisabled(w, 2)).toBe(false)
    await target(w, 3).trigger('click')
    expect(w.find('[data-testid="gargoyle-confirm"]').attributes('disabled')).toBeDefined()
  })

  it('skip emits GARGOYLE_SKIP, even with every player already checked', async () => {
    const w = mountPick(state({ checkedPlayerIds: ['a', 'b'] }))
    await w.find('[data-testid="gargoyle-skip"]').trigger('click')
    expect(w.emitted('action')).toEqual([['GARGOYLE_SKIP']])
  })

  it('actionPending disables skip, confirm and the result confirm', async () => {
    const w = mountPick(state(), true)
    await target(w, 2).trigger('click')
    expect(w.find('[data-testid="gargoyle-confirm"]').attributes('disabled')).toBeDefined()
    expect(w.find('[data-testid="gargoyle-skip"]').attributes('disabled')).toBeDefined()
    const r = mount(GargoyleTurn, {
      props: {
        subPhase: 'GARGOYLE_RESULT',
        gargoyle: state({ result: { kind: 'SKIP' } }),
        players,
        myUserId: 'me',
        actionPending: true,
      },
    })
    expect(r.find('[data-testid="gargoyle-result-confirm"]').attributes('disabled')).toBeDefined()
  })

  it('result shows the exact role and history, and confirm emits GARGOYLE_CONFIRM', async () => {
    const w = mount(GargoyleTurn, {
      props: {
        subPhase: 'GARGOYLE_RESULT',
        players,
        myUserId: 'me',
        gargoyle: state({
          checkedPlayerIds: ['b', 'a'],
          result: {
            kind: 'CHECK',
            targetPlayerId: 'a',
            targetNickname: 'a',
            targetSeatIndex: 2,
            role: 'WHITE_WOLF_KING',
          },
          history: [{ round: 1, nickname: 'b', seatIndex: 3, role: 'SEER' }],
        }),
      },
    })
    const r = w.find('[data-testid="gargoyle-result"]')
    expect(r.attributes('data-kind')).toBe('CHECK')
    expect(r.attributes('data-role')).toBe('WHITE_WOLF_KING')
    expect(r.attributes('data-checked-seat')).toBe('2')
    expect(r.text()).toContain(roleName('WHITE_WOLF_KING'))
    expect(w.findAll('[data-testid="gargoyle-history-entry"]')).toHaveLength(1)
    expect(w.find('[data-testid="gargoyle-history"]').text()).toContain(roleName('SEER'))
    await w.find('[data-testid="gargoyle-result-confirm"]').trigger('click')
    expect(w.emitted('action')).toEqual([['GARGOYLE_CONFIRM']])
  })

  it('a skipped night reads as skipped', () => {
    const w = mount(GargoyleTurn, {
      props: {
        subPhase: 'GARGOYLE_RESULT',
        players,
        myUserId: 'me',
        gargoyle: state({ result: { kind: 'SKIP' } }),
      },
    })
    const r = w.find('[data-testid="gargoyle-result"]')
    expect(r.attributes('data-kind')).toBe('SKIP')
    expect(r.text()).toContain('今晚未查驗')
  })

  it('the result confirm is there even when the result is missing', () => {
    const w = mount(GargoyleTurn, {
      props: { subPhase: 'GARGOYLE_RESULT', players, myUserId: 'me' },
    })
    expect(w.find('[data-testid="gargoyle-result"]').exists()).toBe(false)
    expect(w.find('[data-testid="gargoyle-result-confirm"]').exists()).toBe(true)
  })
})
