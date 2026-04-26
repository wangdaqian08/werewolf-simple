/**
 * DayPhase component tests.
 *
 * Key regression: the game log (📋) button must be hidden during RESULT_HIDDEN
 * to prevent players from reading elimination history before the host reveals it.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DayPhase from '@/components/DayPhase.vue'
import type { DayPhaseState, GamePlayer } from '@/types'

const PLAYERS: GamePlayer[] = [
  { userId: 'u1', nickname: 'Alice', seatIndex: 1, isAlive: true, isSheriff: false },
  { userId: 'u2', nickname: 'Bob', seatIndex: 2, isAlive: false, isSheriff: false },
]

function makeDay(subPhase: 'RESULT_HIDDEN' | 'RESULT_REVEALED'): DayPhaseState {
  return {
    subPhase,
    dayNumber: 2,
    phaseDeadline: Date.now() + 60000,
    phaseStarted: Date.now() - 5000,
    canVote: true,
    nightResult: { killedPlayers: [] },
  }
}

const BASE_PROPS = {
  gameId: 1,
  players: PLAYERS,
  myUserId: 'u1',
  isHost: false,
  actionPending: false,
}

describe('DayPhase — game log button visibility', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('RESULT_HIDDEN: log button is NOT rendered (prevents spoiling night result)', () => {
    const wrapper = mount(DayPhase, {
      props: { ...BASE_PROPS, dayPhase: makeDay('RESULT_HIDDEN') },
    })
    expect(wrapper.find('.log-fab').exists()).toBe(false)
  })

  it('RESULT_REVEALED: log button IS rendered', () => {
    const wrapper = mount(DayPhase, {
      props: { ...BASE_PROPS, dayPhase: makeDay('RESULT_REVEALED') },
    })
    expect(wrapper.find('.log-fab').exists()).toBe(true)
  })
})

describe('DayPhase — observability testids (action-observability sentinel)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const peacefulDay: DayPhaseState = {
    subPhase: 'RESULT_REVEALED',
    dayNumber: 2,
    phaseDeadline: Date.now() + 60000,
    phaseStarted: Date.now() - 5000,
    canVote: true,
    nightResult: { killedPlayers: [] },
  }

  it('peaceful night surfaces day-banner-peaceful testid', () => {
    const wrapper = mount(DayPhase, {
      props: { ...BASE_PROPS, dayPhase: peacefulDay },
    })
    expect(wrapper.find('[data-testid="day-banner-peaceful"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="day-banner-kill"]').exists()).toBe(false)
  })

  it('kill night surfaces day-banner-kill testid + per-seat testids', () => {
    const dayWithKill: DayPhaseState = {
      ...peacefulDay,
      nightResult: {
        killedPlayers: [
          { killedPlayerId: 'u-killed', killedSeatIndex: 5, killedNickname: 'Eve' },
        ],
      },
    }
    const wrapper = mount(DayPhase, {
      props: { ...BASE_PROPS, dayPhase: dayWithKill },
    })
    expect(wrapper.find('[data-testid="day-banner-kill"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="day-banner-peaceful"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="day-killed-seat-5"]').exists()).toBe(true)
  })

  it('multiple kills each get their own per-seat testid', () => {
    const dayWithKills: DayPhaseState = {
      ...peacefulDay,
      nightResult: {
        killedPlayers: [
          { killedPlayerId: 'a', killedSeatIndex: 3, killedNickname: 'Ann' },
          { killedPlayerId: 'b', killedSeatIndex: 7, killedNickname: 'Bea' },
        ],
      },
    }
    const wrapper = mount(DayPhase, {
      props: { ...BASE_PROPS, dayPhase: dayWithKills },
    })
    expect(wrapper.find('[data-testid="day-killed-seat-3"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="day-killed-seat-7"]').exists()).toBe(true)
  })

  it('RESULT_HIDDEN renders neither banner (so result is not leaked)', () => {
    const wrapper = mount(DayPhase, {
      props: { ...BASE_PROPS, dayPhase: makeDay('RESULT_HIDDEN') },
    })
    expect(wrapper.find('[data-testid="day-banner-peaceful"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="day-banner-kill"]').exists()).toBe(false)
  })
})
