import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import NightPhase from '@/components/NightPhase.vue'
import type { GamePlayer, NightPhaseState } from '@/types'

describe('actionPending disables all confirm buttons', () => {
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
  })

  const players: GamePlayer[] = [
    {
      userId: 'wolf1',
      nickname: 'Wolf1',
      avatar: '🐺',
      seatIndex: 1,
      role: 'WEREWOLF',
      isAlive: true,
      isSheriff: false,
      canVote: true,
    },
    {
      userId: 'villager1',
      nickname: 'Villager1',
      avatar: '👤',
      seatIndex: 2,
      role: 'VILLAGER',
      isAlive: true,
      isSheriff: false,
      canVote: true,
    },
    {
      userId: 'seer1',
      nickname: 'Seer1',
      avatar: '🔮',
      seatIndex: 3,
      role: 'SEER',
      isAlive: true,
      isSheriff: false,
      canVote: true,
    },
    {
      userId: 'guard1',
      nickname: 'Guard1',
      avatar: '🛡',
      seatIndex: 4,
      role: 'GUARD',
      isAlive: true,
      isSheriff: false,
      canVote: true,
    },
  ]

  function mountNight(
    subPhase: NightPhaseState['subPhase'],
    myUserId: string,
    myRole: GamePlayer['role'],
    actionPending: boolean,
    extra: Partial<NightPhaseState> = {},
  ) {
    const nightPhase: NightPhaseState = {
      subPhase,
      dayNumber: 1,
      selectedTargetId: 'villager1',
      ...extra,
    }
    return mount(NightPhase, {
      props: { nightPhase, players, myUserId, myRole, actionPending },
      global: { plugins: [pinia] },
    })
  }

  it('werewolf confirm button disabled when actionPending=true', () => {
    const w = mountNight('WEREWOLF_PICK', 'wolf1', 'WEREWOLF', true)
    const btn = w.find('button.btn-danger')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('werewolf confirm button enabled when actionPending=false and target selected', () => {
    const w = mountNight('WEREWOLF_PICK', 'wolf1', 'WEREWOLF', false)
    const btn = w.find('button.btn-danger')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeUndefined()
  })

  it('seer check button disabled when actionPending=true', () => {
    const w = mountNight('SEER_PICK', 'seer1', 'SEER', true)
    const btn = w.find('button.btn-danger')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('seer done button disabled when actionPending=true', () => {
    const w = mountNight('SEER_RESULT', 'seer1', 'SEER', true, {
      seerResult: {
        checkedPlayerId: 'villager1',
        checkedNickname: 'Villager1',
        checkedSeatIndex: 2,
        isWerewolf: false,
        history: [],
      },
    })
    const btn = w.find('button.btn-secondary')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('guard confirm button disabled when actionPending=true', () => {
    const w = mountNight('GUARD_PICK', 'guard1', 'GUARD', true)
    const btn = w.find('button.btn-danger')
    expect(btn.exists()).toBe(true)
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('witch antidote/pass buttons disabled when actionPending=true', () => {
    mountNight('WITCH_ACT', 'witch1', 'WITCH', true, {
      hasAntidote: true,
      attackedPlayerId: 'villager1',
      attackedNickname: 'Villager1',
      attackedSeatIndex: 2,
    })
    // Need a witch player
    const witchPlayers = [
      ...players,
      {
        userId: 'witch1',
        nickname: 'Witch1',
        avatar: '🧙',
        seatIndex: 5,
        role: 'WITCH' as const,
        isAlive: true,
        isSheriff: false,
        canVote: true,
      },
    ]
    const nightPhase: NightPhaseState = {
      subPhase: 'WITCH_ACT',
      dayNumber: 1,
      hasAntidote: true,
      attackedPlayerId: 'villager1',
      attackedNickname: 'Villager1',
      attackedSeatIndex: 2,
    }
    const wrapper = mount(NightPhase, {
      props: {
        nightPhase,
        players: witchPlayers,
        myUserId: 'witch1',
        myRole: 'WITCH',
        actionPending: true,
      },
      global: { plugins: [pinia] },
    })
    const buttons = wrapper.findAll('button.ws-btn')
    expect(buttons.length).toBeGreaterThanOrEqual(2)
    buttons.forEach((btn) => {
      expect(btn.attributes('disabled')).toBeDefined()
    })
  })
})
