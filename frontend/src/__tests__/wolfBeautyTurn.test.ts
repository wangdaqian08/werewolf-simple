import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import WolfBeautyTurn from '@/components/WolfBeautyTurn.vue'
import type { GamePlayer, NightPhaseState } from '@/types'

const p = (userId: string, seatIndex: number, isAlive = true): GamePlayer => ({
  userId,
  nickname: userId,
  seatIndex,
  isAlive,
  isSheriff: false,
})
// me = the 狼美人; w = a pack wolf; last = last night's target; dead; a, b = free targets
const players = [p('me', 1), p('w', 2), p('last', 3), p('dead', 4, false), p('a', 5), p('b', 6)]
type WolfBeauty = NonNullable<NightPhaseState['wolfBeauty']>
const state = (over: Partial<WolfBeauty> = {}): WolfBeauty => ({
  canCharm: true,
  lastNightTargetUserId: 'last',
  result: null,
  history: [],
  packUserIds: ['w'],
  ...over,
})
const mountTurn = (wolfBeauty: WolfBeauty = state(), actionPending = false) =>
  mount(WolfBeautyTurn, { props: { wolfBeauty, players, myUserId: 'me', actionPending } })
type W = ReturnType<typeof mountTurn>
const target = (w: W, seat: number) => w.find(`[data-testid="wolf-beauty-target-${seat}"]`)
const isDisabled = (w: W, seat: number) => target(w, seat).attributes('disabled') !== undefined
const btn = (w: W, id: string) => w.find(`[data-testid="wolf-beauty-${id}"]`)

describe('WolfBeautyTurn', () => {
  it('greys self, the dead, the pack and last night’s target; the rest can be charmed', () => {
    const w = mountTurn()
    expect(w.find('[data-testid="wolf-beauty-turn"]').exists()).toBe(true)
    expect(isDisabled(w, 1)).toBe(true) // self
    expect(isDisabled(w, 2)).toBe(true) // pack wolf
    expect(isDisabled(w, 3)).toBe(true) // charmed last night
    expect(isDisabled(w, 4)).toBe(true) // dead
    expect(isDisabled(w, 5)).toBe(false)
    expect(isDisabled(w, 6)).toBe(false)
    // The reason is shown on the greyed pack wolf and last night's target.
    expect(target(w, 2).text()).toContain('狼隊友')
    expect(target(w, 3).text()).toContain('昨晚已魅惑')
  })

  it('confirm emits WOLF_BEAUTY_CHARM with the chosen player', async () => {
    const w = mountTurn()
    expect(btn(w, 'confirm').attributes('disabled')).toBeDefined()
    await target(w, 5).trigger('click')
    await btn(w, 'confirm').trigger('click')
    expect(w.emitted('action')).toEqual([['WOLF_BEAUTY_CHARM', 'a']])
  })

  it('skip emits WOLF_BEAUTY_SKIP', async () => {
    const w = mountTurn()
    await btn(w, 'skip').trigger('click')
    expect(w.emitted('action')).toEqual([['WOLF_BEAUTY_SKIP']])
  })

  it('night 1: only 「首夜不能魅惑」 and 確認, which sends WOLF_BEAUTY_SKIP', async () => {
    const w = mountTurn(state({ canCharm: false, lastNightTargetUserId: null }))
    expect(w.text()).toContain('首夜不能魅惑')
    expect(w.find('[data-testid^="wolf-beauty-target-"]').exists()).toBe(false)
    expect(btn(w, 'confirm').exists()).toBe(false)
    expect(btn(w, 'skip').exists()).toBe(false)
    await btn(w, 'n1-confirm').trigger('click')
    expect(w.emitted('action')).toEqual([['WOLF_BEAUTY_SKIP']])
  })

  it('actionPending disables every button', async () => {
    const w = mountTurn(state(), true)
    await target(w, 5).trigger('click')
    expect(btn(w, 'confirm').attributes('disabled')).toBeDefined()
    expect(btn(w, 'skip').attributes('disabled')).toBeDefined()
    const n1 = mountTurn(state({ canCharm: false }), true)
    expect(btn(n1, 'n1-confirm').attributes('disabled')).toBeDefined()
  })
})
