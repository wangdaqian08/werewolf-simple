import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NightPhase from '@/components/NightPhase.vue'
import type { GamePlayer, NightPhaseState, PlayerRole } from '@/types'

// Contract under test: ONLY dead players see "你已經出局"
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

    expect(wrapper.html()).not.toContain('你已經出局')
    expect(wrapper.find('[data-testid="guard-confirm-protect"]').exists()).toBe(true)
  })

  it('dead guard on N2 sees the eliminated banner (alive=false after host revealed N1 kills)', () => {
    // After day 1 reveal, the wolf-attacked guard is now alive=false. On
    // the next night their dashboard must show "你已經出局"
    // and NOT show the protect UI.
    const guard = mkPlayer({ userId: 'g1', nickname: 'Guard', seatIndex: 4, isAlive: false })
    const wolf = mkPlayer({ userId: 'w1', nickname: 'Wolf', seatIndex: 2 })

    const wrapper = mountNight({
      nightPhase: mkNight({ subPhase: 'GUARD_PICK', dayNumber: 2 }),
      players: [guard, wolf],
      myUserId: 'g1',
      myRole: 'GUARD',
    })

    expect(wrapper.html()).toContain('你已經出局')
    expect(wrapper.find('[data-testid="guard-confirm-protect"]').exists()).toBe(false)
  })

  it('witch sees the self-save-blocked note + the antidote button is HIDDEN when self-save is off and she is the wolves victim', () => {
    const witch = mkPlayer({ userId: 'witch1', nickname: 'Witch', seatIndex: 1, isAlive: true })
    const wolf = mkPlayer({ userId: 'w1', nickname: 'Wolf', seatIndex: 2 })

    const wrapper = mountNight({
      // hasAntidote=true so the antidote section actually renders. The
      // wolves' target is the witch herself.
      nightPhase: mkNight({
        subPhase: 'WITCH_ACT',
        dayNumber: 1,
        hasAntidote: true,
        attackedPlayerId: 'witch1',
        attackedNickname: 'Witch',
        attackedSeatIndex: 1,
      }),
      players: [witch, wolf],
      myUserId: 'witch1',
      myRole: 'WITCH',
      // @ts-expect-error — extra prop accepted by the component
      witchSelfSaveAllowed: false,
    })

    // "使用解藥" button must NOT render.
    expect(wrapper.find('[data-testid="witch-antidote"]').exists()).toBe(false)
    // Explanatory note shown in its place.
    expect(wrapper.find('[data-testid="witch-self-save-blocked"]').exists()).toBe(true)
    // "放棄" button still available so the witch can advance.
    expect(wrapper.find('[data-testid="switch-pass-antidote"]').exists()).toBe(true)
  })

  it('witch sees the antidote button when self-save is off but wolves attacked someone else', () => {
    const witch = mkPlayer({ userId: 'witch1', nickname: 'Witch', seatIndex: 1, isAlive: true })
    const other = mkPlayer({ userId: 'other1', nickname: 'Other', seatIndex: 2, isAlive: true })

    const wrapper = mountNight({
      nightPhase: mkNight({
        subPhase: 'WITCH_ACT',
        dayNumber: 1,
        hasAntidote: true,
        attackedPlayerId: 'other1',
        attackedNickname: 'Other',
        attackedSeatIndex: 2,
      }),
      players: [witch, other],
      myUserId: 'witch1',
      myRole: 'WITCH',
      // @ts-expect-error — extra prop accepted by the component
      witchSelfSaveAllowed: false,
    })

    expect(wrapper.find('[data-testid="witch-antidote"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="witch-self-save-blocked"]').exists()).toBe(false)
  })

  it('witch sees the antidote button when self-save is allowed (default), even when wolves attacked her', () => {
    const witch = mkPlayer({ userId: 'witch1', nickname: 'Witch', seatIndex: 1, isAlive: true })
    const filler = mkPlayer({ userId: 'v1', nickname: 'V1', seatIndex: 2, isAlive: true })

    const wrapper = mountNight({
      nightPhase: mkNight({
        subPhase: 'WITCH_ACT',
        dayNumber: 1,
        hasAntidote: true,
        attackedPlayerId: 'witch1',
        attackedNickname: 'Witch',
        attackedSeatIndex: 1,
      }),
      players: [witch, filler],
      myUserId: 'witch1',
      myRole: 'WITCH',
      // No witchSelfSaveAllowed prop → undefined → defaults to allow (existing
      // behavior preserved).
    })

    expect(wrapper.find('[data-testid="witch-antidote"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="witch-self-save-blocked"]').exists()).toBe(false)
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

    expect(wrapper.html()).not.toContain('你已經出局')
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

    expect(wrapper.html()).not.toContain('你已經出局')
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
      expect(wrapper.html(), `subPhase=${subPhase}`).toContain('你已經出局')
    }
  })
})

describe('NightPhase - 白狼王 acts in the wolf turn', () => {
  it('WHITE_WOLF_KING sees the wolf badge and target grid during WEREWOLF_PICK', () => {
    const king: GamePlayer = {
      userId: 'k1',
      nickname: 'King',
      seatIndex: 1,
      isAlive: true,
      isSheriff: false,
    }
    const target: GamePlayer = {
      userId: 'v1',
      nickname: 'Vic',
      seatIndex: 2,
      isAlive: true,
      isSheriff: false,
    }
    const wrapper = mount(NightPhase, {
      props: {
        nightPhase: { subPhase: 'WEREWOLF_PICK', dayNumber: 1, teammates: ['3·Wolf'] },
        players: [king, target],
        myUserId: 'k1',
        myRole: 'WHITE_WOLF_KING',
      },
    })
    expect(wrapper.text()).toContain('白狼王')
    expect(wrapper.text()).toContain('與隊友商議攻擊目標')
    expect(wrapper.text()).toContain('選擇今晚的襲擊目標')
    expect(wrapper.text()).toContain('3·Wolf')
  })
})

describe('NightPhase - 空刀 and witch self-save rules', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const p = (userId: string, seatIndex: number): GamePlayer => ({
    userId,
    nickname: userId,
    seatIndex,
    isAlive: true,
    isSheriff: false,
  })

  it('the 空刀 button is hidden when the room has not enabled 空刀 (default)', () => {
    const wrapper = mount(NightPhase, {
      props: {
        nightPhase: { subPhase: 'WEREWOLF_PICK', dayNumber: 1 } as NightPhaseState,
        players: [p('w1', 1), p('v1', 2)],
        myUserId: 'w1',
        myRole: 'WEREWOLF' as PlayerRole,
      },
    })
    expect(wrapper.find('[data-testid="wolf-skip-kill"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="wolf-confirm-kill"]').exists()).toBe(true)
  })

  it('a wolf can choose 空刀 (no kill) and the click emits wolfSkip', async () => {
    const wrapper = mount(NightPhase, {
      props: {
        nightPhase: { subPhase: 'WEREWOLF_PICK', dayNumber: 1 } as NightPhaseState,
        players: [p('w1', 1), p('v1', 2)],
        myUserId: 'w1',
        myRole: 'WEREWOLF' as PlayerRole,
        noKillAllowed: true,
      },
    })
    await wrapper.find('[data-testid="wolf-skip-kill"]').trigger('click')
    expect(wrapper.emitted('wolfSkip')).toHaveLength(1)
  })

  it('after 空刀 the witch is told no one was attacked and cannot use the antidote', () => {
    const wrapper = mount(NightPhase, {
      props: {
        nightPhase: { subPhase: 'WITCH_ACT', dayNumber: 1, hasAntidote: true } as NightPhaseState,
        players: [p('witch1', 1), p('v1', 2)],
        myUserId: 'witch1',
        myRole: 'WITCH' as PlayerRole,
      },
    })
    expect(wrapper.find('[data-testid="witch-no-victim"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="witch-antidote"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="switch-pass-antidote"]').exists()).toBe(true)
  })

  it('the backend selfSaveBlocked flag hides the antidote and explains the night-1 rule', () => {
    const wrapper = mount(NightPhase, {
      props: {
        nightPhase: {
          subPhase: 'WITCH_ACT',
          dayNumber: 2,
          hasAntidote: true,
          attackedPlayerId: 'witch1',
          attackedNickname: 'witch1',
          attackedSeatIndex: 1,
          selfSaveBlocked: true,
        } as NightPhaseState,
        players: [p('witch1', 1), p('v1', 2)],
        myUserId: 'witch1',
        myRole: 'WITCH' as PlayerRole,
      },
    })
    const note = wrapper.find('[data-testid="witch-self-save-blocked"]')
    expect(note.exists()).toBe(true)
    expect(note.text()).toContain('首夜')
    expect(wrapper.find('[data-testid="witch-antidote"]').exists()).toBe(false)
  })
})

describe('NightPhase - a rejected action gives the turn back', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('after a rejection the guard sees the protect controls again', async () => {
    const guard: GamePlayer = {
      userId: 'g1',
      nickname: 'G',
      seatIndex: 1,
      isAlive: true,
      isSheriff: false,
    }
    const other: GamePlayer = {
      userId: 'v1',
      nickname: 'V',
      seatIndex: 2,
      isAlive: true,
      isSheriff: false,
    }
    const wrapper = mount(NightPhase, {
      props: {
        nightPhase: { subPhase: 'GUARD_PICK', dayNumber: 2 } as NightPhaseState,
        players: [guard, other],
        myUserId: 'g1',
        myRole: 'GUARD' as PlayerRole,
        rejectionCount: 0,
      },
    })
    await wrapper.find('.player-grid [data-seat="2"]').trigger('click')
    await wrapper.find('[data-testid="guard-confirm-protect"]').trigger('click')
    expect(wrapper.find('[data-testid="guard-confirm-protect"]').exists()).toBe(false)

    await wrapper.setProps({ rejectionCount: 1 })
    expect(wrapper.find('[data-testid="guard-confirm-protect"]').exists()).toBe(true)
  })
})
describe('NightPhase - 空守 (guard protects no one)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  const p = (userId: string, seatIndex: number): GamePlayer => ({
    userId,
    nickname: userId,
    seatIndex,
    isAlive: true,
    isSheriff: false,
  })
  const guardProps = (extra: Record<string, unknown> = {}) => ({
    nightPhase: { subPhase: 'GUARD_PICK', dayNumber: 1 } as NightPhaseState,
    players: [p('g1', 1), p('v1', 2)],
    myUserId: 'g1',
    myRole: 'GUARD' as PlayerRole,
    ...extra,
  })

  it('the 空守 button is hidden when the room has not enabled 空守 (default)', () => {
    const wrapper = mount(NightPhase, { props: guardProps() })
    expect(wrapper.find('[data-testid="guard-skip"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="guard-confirm-protect"]').exists()).toBe(true)
  })

  it('the guard can choose 空守 and the click emits guardSkip and ends the turn', async () => {
    const wrapper = mount(NightPhase, { props: guardProps({ guardSkipAllowed: true }) })
    await wrapper.find('[data-testid="guard-skip"]').trigger('click')
    expect(wrapper.emitted('guardSkip')).toHaveLength(1)
    expect(wrapper.find('[data-testid="guard-confirm-protect"]').exists()).toBe(false)
  })
})
