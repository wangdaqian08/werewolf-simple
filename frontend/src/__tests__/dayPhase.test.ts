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
        killedPlayers: [{ killedPlayerId: 'u-killed', killedSeatIndex: 5, killedNickname: 'Eve' }],
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

describe('DayPhase — daySkipVoting host footer', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const hostProps = { ...BASE_PROPS, isHost: true }

  it('RESULT_REVEALED + daySkipVoting=false → host sees "开始投票" button', () => {
    const wrapper = mount(DayPhase, {
      props: { ...hostProps, dayPhase: makeDay('RESULT_REVEALED'), daySkipVoting: false },
    })
    expect(wrapper.find('[data-testid="day-start-vote"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="day-enter-night"]').exists()).toBe(false)
  })

  it('RESULT_REVEALED + daySkipVoting=true → host sees "进入夜晚" button, not "开始投票"', () => {
    const wrapper = mount(DayPhase, {
      props: { ...hostProps, dayPhase: makeDay('RESULT_REVEALED'), daySkipVoting: true },
    })
    expect(wrapper.find('[data-testid="day-enter-night"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="day-start-vote"]').exists()).toBe(false)
  })

  it('RESULT_REVEALED + daySkipVoting=true → clicking "进入夜晚" emits continueToNight', async () => {
    const wrapper = mount(DayPhase, {
      props: { ...hostProps, dayPhase: makeDay('RESULT_REVEALED'), daySkipVoting: true },
    })
    await wrapper.find('[data-testid="day-enter-night"]').trigger('click')
    expect(wrapper.emitted('continueToNight')).toBeTruthy()
  })
})

describe('DayPhase — below-arch layout (my-role-chip left, log-fab + ActionMenu right-stack)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // Layout regression: a previous iteration stacked the Action chip BELOW my-role-chip on
  // the left. The intended layout is my-role-chip on the left at its original spot, and
  // log-fab + ActionMenu stacked on the right side of the same below-arch row.
  const wolfProps = { ...BASE_PROPS, myRole: 'WEREWOLF' as const, isAlive: true }

  it('renders a below-arch-row sibling between SunArc and the player grid', () => {
    const wrapper = mount(DayPhase, {
      props: { ...wolfProps, dayPhase: makeDay('RESULT_REVEALED') },
    })
    expect(wrapper.find('.below-arch-row').exists()).toBe(true)
  })

  it('my-role-chip is a direct child of below-arch-row (left side, original position)', () => {
    const wrapper = mount(DayPhase, {
      props: { ...wolfProps, dayPhase: makeDay('RESULT_REVEALED') },
    })
    const chip = wrapper.find('.below-arch-row > .my-role-chip')
    expect(chip.exists()).toBe(true)
  })

  it('log-fab and ActionMenu live inside .right-stack on the right of below-arch-row', () => {
    const wrapper = mount(DayPhase, {
      props: { ...wolfProps, dayPhase: makeDay('RESULT_REVEALED') },
    })
    const rightStack = wrapper.find('.below-arch-row > .right-stack')
    expect(rightStack.exists()).toBe(true)
    expect(rightStack.find('.log-fab').exists()).toBe(true)
    expect(rightStack.find('[data-testid="action-menu-btn"]').exists()).toBe(true)
  })

  it('Action chip is NOT placed under my-role-chip (defensive: ensures left col was unbundled)', () => {
    const wrapper = mount(DayPhase, {
      props: { ...wolfProps, dayPhase: makeDay('RESULT_REVEALED') },
    })
    // The deprecated .day-role-action-col / .role-action-col wrappers must not stack chip+menu together.
    expect(wrapper.find('.day-role-action-col').exists()).toBe(false)
    expect(wrapper.find('.role-action-col').exists()).toBe(false)
  })

  it('RESULT_HIDDEN: ActionMenu still rendered (wolves can self-destruct before reveal)', () => {
    const wrapper = mount(DayPhase, {
      props: { ...wolfProps, dayPhase: makeDay('RESULT_HIDDEN') },
    })
    expect(wrapper.find('[data-testid="action-menu-btn"]').exists()).toBe(true)
    // log-fab is hidden in RESULT_HIDDEN to prevent spoilers, ActionMenu is not
    expect(wrapper.find('.log-fab').exists()).toBe(false)
  })
})
