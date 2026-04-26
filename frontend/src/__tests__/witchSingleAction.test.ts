/**
 * Witch single-action contract.
 *
 * Each witch button click submits a complete `WITCH_ACT` to the backend
 * (useAntidote + poisonTargetUserId in one payload). The frontend must
 * reflect this by hiding the witch UI on the FIRST click — otherwise a
 * second click sends a stale WITCH_ACT during the night-loop's
 * inter-role-gap, racing `queuedActionSignals` and auto-completing the
 * next role's sub-phase before its UI can render.
 *
 * This test mounts NightPhase as the witch and verifies that one click
 * on any of the four turn-ending buttons hides the witch panel
 * (sleep-screen takes over) and emits exactly one event.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NightPhase from '@/components/NightPhase.vue'
import type { GamePlayer, NightPhaseState } from '@/types'

const ME = 'u-witch'

const PLAYERS: GamePlayer[] = [
  { userId: ME, nickname: 'Witch', seatIndex: 1, isAlive: true, isSheriff: false },
  { userId: 'u2', nickname: 'Bot2', seatIndex: 2, isAlive: true, isSheriff: false },
  { userId: 'u3', nickname: 'Bot3', seatIndex: 3, isAlive: true, isSheriff: false },
]

function makeNight(over: Partial<NightPhaseState> = {}): NightPhaseState {
  return {
    subPhase: 'WITCH_ACT',
    dayNumber: 1,
    hasAntidote: true,
    hasPoison: true,
    antidoteDecided: false,
    poisonDecided: false,
    attackedSeatIndex: 2,
    attackedNickname: 'Bot2',
    ...over,
  }
}

const BASE_PROPS = {
  players: PLAYERS,
  myUserId: ME,
  myRole: 'WITCH' as const,
  actionPending: false,
}

describe('Witch — single-click ends the turn', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('clicking witch-antidote hides BOTH antidote and poison panels and emits exactly one witchAntidote event', async () => {
    const wrapper = mount(NightPhase, {
      props: { ...BASE_PROPS, nightPhase: makeNight() },
    })
    expect(wrapper.find('[data-testid="witch-antidote"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="switch-pass-poison"]').exists()).toBe(true)

    await wrapper.find('[data-testid="witch-antidote"]').trigger('click')

    // Both panels must be gone — sleep-screen takes over via NightPhase's
    // hasActed flag. A second click on the still-visible poison panel
    // would race the role-loop with a stale WITCH_ACT during the
    // inter-role-gap (the bug this regression-tests).
    expect(wrapper.find('[data-testid="witch-antidote"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="switch-pass-poison"]').exists()).toBe(false)

    expect(wrapper.emitted('witchAntidote')).toHaveLength(1)
  })

  it('clicking switch-pass-antidote hides everything and emits exactly one witchPassAntidote', async () => {
    const wrapper = mount(NightPhase, {
      props: { ...BASE_PROPS, nightPhase: makeNight() },
    })
    await wrapper.find('[data-testid="switch-pass-antidote"]').trigger('click')

    expect(wrapper.find('[data-testid="witch-antidote"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="switch-pass-poison"]').exists()).toBe(false)
    expect(wrapper.emitted('witchPassAntidote')).toHaveLength(1)
  })

  it('clicking switch-pass-poison hides everything and emits exactly one witchPassPoison', async () => {
    const wrapper = mount(NightPhase, {
      props: { ...BASE_PROPS, nightPhase: makeNight() },
    })
    await wrapper.find('[data-testid="switch-pass-poison"]').trigger('click')

    expect(wrapper.find('[data-testid="switch-pass-poison"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="witch-antidote"]').exists()).toBe(false)
    expect(wrapper.emitted('witchPassPoison')).toHaveLength(1)
  })

  it('clicking witch-poison-confirm (after target select) hides everything and emits exactly one witchPoison', async () => {
    const wrapper = mount(NightPhase, {
      props: { ...BASE_PROPS, nightPhase: makeNight() },
    })
    // Enter poison-select mode
    await wrapper.find('[data-testid="use-poison"]').trigger('click')
    await wrapper.vm.$nextTick()

    // Click a target so the confirm button enables
    await wrapper.find('.player-grid-sm .slot-alive').trigger('click')
    await wrapper.vm.$nextTick()

    await wrapper.find('[data-testid="witch-poison-confirm"]').trigger('click')

    expect(wrapper.find('[data-testid="witch-antidote"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="switch-pass-poison"]').exists()).toBe(false)
    expect(wrapper.emitted('witchPoison')).toHaveLength(1)
  })

  it('attempting a second click after the panel is hidden does NOT emit a second event', async () => {
    const wrapper = mount(NightPhase, {
      props: { ...BASE_PROPS, nightPhase: makeNight() },
    })
    const firstBtn = wrapper.find('[data-testid="witch-antidote"]')
    await firstBtn.trigger('click')

    // The second button is no longer in the DOM. Programmatically attempting
    // to invoke a second emission via the disappeared DOM does nothing.
    const stalePoisonBtn = wrapper.find('[data-testid="switch-pass-poison"]')
    expect(stalePoisonBtn.exists()).toBe(false)

    // The first emission stands alone.
    expect(wrapper.emitted('witchAntidote')).toHaveLength(1)
    expect(wrapper.emitted('witchPassPoison')).toBeUndefined()
  })

  it('when witch has no items (skip path), the sole witch-skip click hides the panel and emits witchSkip', async () => {
    const wrapper = mount(NightPhase, {
      props: {
        ...BASE_PROPS,
        nightPhase: makeNight({
          hasAntidote: false,
          hasPoison: false,
          attackedSeatIndex: undefined,
          attackedNickname: undefined,
        }),
      },
    })
    await wrapper.find('[data-testid="witch-skip"]').trigger('click')

    expect(wrapper.find('[data-testid="witch-skip"]').exists()).toBe(false)
    expect(wrapper.emitted('witchSkip')).toHaveLength(1)
  })
})
