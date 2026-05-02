import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NightPhase from '@/components/NightPhase.vue'
import type { GamePlayer, NightPhaseState, PlayerRole } from '@/types'

// Contract under test: ONLY dead players see "你已经出局 / You are eliminated"
// during NIGHT. The user's edge case: a guard who was wolf-attacked on night 1
// is still alive=true in DB during their own GUARD_PICK turn (kills are
// deferred until host reveal at dawn — see NightOrchestrator.applyNightKills),
// so the eliminated banner must NOT show. On the NEXT night, the guard's
// alive=false and the banner SHOULD show.

describe('NightPhase - eliminated banner gating', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const mkPlayer = (overrides: Partial<GamePlayer> = {}): GamePlayer => ({
    userId: 'u1',
    nickname: 'Alice',
    seatIndex: 1,
    isAlive: true,
    isSheriff: false,
    ...overrides,
  })

  const mkNight = (overrides: Partial<NightPhaseState> = {}): NightPhaseState => ({
    subPhase: 'WEREWOLF_PICK',
    dayNumber: 1,
    ...overrides,
  })

  const mountNight = (props: {
    nightPhase: NightPhaseState
    players: GamePlayer[]
    myUserId: string
    myRole?: PlayerRole
  }) => mount(NightPhase, { props })

  it('guard wolf-attacked on N1 still sees their action UI on N1 (alive=true in DB until reveal)', () => {
    // The wolf has already chosen to kill the guard, but kills are deferred
    // until host REVEAL_NIGHT_RESULT. During GUARD_PICK on this same night,
    // the guard's alive=true, so they should see the protect UI, NOT the
    // eliminated banner.
    const guard = mkPlayer({ userId: 'g1', nickname: 'Guard', seatIndex: 4, isAlive: true })
    const wolf = mkPlayer({ userId: 'w1', nickname: 'Wolf', seatIndex: 2 })
    const others = [mkPlayer({ userId: 'v1', nickname: 'V1', seatIndex: 3 })]

    const wrapper = mountNight({
      nightPhase: mkNight({ subPhase: 'GUARD_PICK', dayNumber: 1 }),
      players: [guard, wolf, ...others],
      myUserId: 'g1',
      myRole: 'GUARD',
    })

    expect(wrapper.html()).not.toContain('你已经出局')
    expect(wrapper.html()).not.toContain('You are eliminated')
    expect(wrapper.find('[data-testid="guard-confirm-protect"]').exists()).toBe(true)
  })

  it('dead guard on N2 sees the eliminated banner (alive=false after host revealed N1 kills)', () => {
    // After day 1 reveal, the wolf-attacked guard is now alive=false. On
    // the next night their dashboard must show "你已经出局 / You are eliminated"
    // and NOT show the protect UI.
    const guard = mkPlayer({ userId: 'g1', nickname: 'Guard', seatIndex: 4, isAlive: false })
    const wolf = mkPlayer({ userId: 'w1', nickname: 'Wolf', seatIndex: 2 })

    const wrapper = mountNight({
      nightPhase: mkNight({ subPhase: 'GUARD_PICK', dayNumber: 2 }),
      players: [guard, wolf],
      myUserId: 'g1',
      myRole: 'GUARD',
    })

    expect(wrapper.html()).toContain('你已经出局')
    expect(wrapper.html()).toContain('You are eliminated')
    expect(wrapper.find('[data-testid="guard-confirm-protect"]').exists()).toBe(false)
  })

  it('alive non-actor (villager during WEREWOLF_PICK) does NOT see the eliminated banner', () => {
    const villager = mkPlayer({ userId: 'v1', isAlive: true })
    const wolf = mkPlayer({ userId: 'w1', seatIndex: 2 })

    const wrapper = mountNight({
      nightPhase: mkNight({ subPhase: 'WEREWOLF_PICK', dayNumber: 1 }),
      players: [villager, wolf],
      myUserId: 'v1',
      myRole: 'VILLAGER',
    })

    expect(wrapper.html()).not.toContain('你已经出局')
    expect(wrapper.html()).not.toContain('You are eliminated')
  })

  it('alive actor on their own turn does NOT see the eliminated banner (regression guard for werewolf)', () => {
    const wolf = mkPlayer({ userId: 'w1', isAlive: true, role: 'WEREWOLF' })
    const target = mkPlayer({ userId: 'v1', seatIndex: 2 })

    const wrapper = mountNight({
      nightPhase: mkNight({ subPhase: 'WEREWOLF_PICK', dayNumber: 1, teammates: [] }),
      players: [wolf, target],
      myUserId: 'w1',
      myRole: 'WEREWOLF',
    })

    expect(wrapper.html()).not.toContain('你已经出局')
  })

  it('dead actor sees the eliminated banner across all sub-phases', () => {
    const subPhases: NightPhaseState['subPhase'][] = [
      'WAITING',
      'WEREWOLF_PICK',
      'SEER_PICK',
      'WITCH_ACT',
      'GUARD_PICK',
    ]
    for (const subPhase of subPhases) {
      const me = mkPlayer({ userId: 'me', isAlive: false })
      const wrapper = mountNight({
        nightPhase: mkNight({ subPhase, dayNumber: 2 }),
        players: [me, mkPlayer({ userId: 'a1', seatIndex: 2 })],
        myUserId: 'me',
        myRole: 'VILLAGER',
      })
      expect(wrapper.html(), `subPhase=${subPhase}`).toContain('你已经出局')
    }
  })
})
