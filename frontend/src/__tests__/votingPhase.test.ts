import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import VotingPhase from '@/components/VotingPhase.vue'
import type { GamePlayer, VotingState } from '@/types'

describe('VotingPhase - Badge Handover UI Bug', () => {
  let pinia: ReturnType<typeof createPinia>
  let router: ReturnType<typeof createRouter>

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)
    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/', component: { template: '<div></div>' } }],
    })
  })

  // Helper to create voting phase state
  const createVotingPhase = (
    subPhase: string,
    eliminatedPlayerId?: string,
    badgeDestroyed = false,
  ): VotingState => ({
    dayNumber: 1,
    subPhase: subPhase as 'BADGE_HANDOVER' | 'BADGE_RECEIVED' | 'VOTE_RESULT',
    phaseStarted: Date.now() - 10000,
    phaseDeadline: Date.now() + 60000,
    canVote: false,
    selectedPlayerId: undefined,
    myVote: undefined,
    myVoteSkipped: false,
    votesSubmitted: 0,
    totalVoters: 0,
    tallyRevealed: false,
    revealDeadline: undefined,
    tally: [],
    eliminatedPlayerId,
    eliminatedNickname: eliminatedPlayerId ? 'Sheriff' : undefined,
    eliminatedSeatIndex: eliminatedPlayerId ? 1 : undefined,
    eliminatedAvatar: '⭐',
    idiotRevealedId: undefined,
    idiotRevealedNickname: undefined,
    idiotRevealedSeatIndex: undefined,
    badgeDestroyed,
  })

  // Helper to create players
  const createPlayers = (sheriffUserId: string): GamePlayer[] => [
    {
      userId: sheriffUserId,
      nickname: 'Sheriff',
      avatar: '⭐',
      seatIndex: 1,
      role: 'VILLAGER' as const,
      isAlive: false,
      isSheriff: true,
      canVote: true,
      idiotRevealed: false,
    },
    {
      userId: 'player-2',
      nickname: 'Player2',
      avatar: '😊',
      seatIndex: 2,
      role: 'VILLAGER' as const,
      isAlive: true,
      isSheriff: false,
      canVote: true,
      idiotRevealed: false,
    },
    {
      userId: 'player-3',
      nickname: 'Player3',
      avatar: '😊',
      seatIndex: 3,
      role: 'VILLAGER' as const,
      isAlive: true,
      isSheriff: false,
      canVote: true,
      idiotRevealed: false,
    },
  ]

  it('only eliminated sheriff can see badge handover buttons', async () => {
    const sheriffUserId = 'sheriff-1'
    const votingPhase = createVotingPhase('BADGE_HANDOVER', sheriffUserId)
    const players = createPlayers(sheriffUserId)

    const wrapper = mount(VotingPhase, {
      global: {
        plugins: [pinia, router],
      },
      props: {
        votingPhase,
        players,
        myUserId: sheriffUserId, // Current user is the eliminated sheriff
        isHost: false,
      },
    })

    await wrapper.vm.$nextTick()

    // Check that badge handover buttons are visible by checking button elements
    const buttons = wrapper.findAll('button')
    const buttonLabels = buttons.map((btn) => btn.text())
    expect(buttonLabels).toContain('移交警徽 / Pass Badge')
    expect(buttonLabels).toContain('销毁')
  })

  it('other players cannot see badge handover buttons', async () => {
    const sheriffUserId = 'sheriff-1'
    const otherPlayerId = 'player-2'
    const votingPhase = createVotingPhase('BADGE_HANDOVER', sheriffUserId)
    const players = createPlayers(sheriffUserId)

    const wrapper = mount(VotingPhase, {
      global: {
        plugins: [pinia, router],
      },
      props: {
        votingPhase,
        players,
        myUserId: otherPlayerId, // Current user is NOT the eliminated sheriff
        isHost: false,
      },
    })

    await wrapper.vm.$nextTick()

    // Check that badge handover buttons are NOT visible
    const buttons = wrapper.findAll('button')
    const buttonLabels = buttons.map((btn) => btn.text())
    expect(buttonLabels).not.toContain('移交警徽 / Pass Badge')
    expect(buttonLabels).not.toContain('销毁')

    // Should show waiting message
    expect(wrapper.html()).toContain('等待警长移交警徽')
  })

  it('host cannot see badge handover buttons if not eliminated sheriff', async () => {
    const sheriffUserId = 'sheriff-1'
    const hostId = 'player-2'
    const votingPhase = createVotingPhase('BADGE_HANDOVER', sheriffUserId)
    const players = createPlayers(sheriffUserId)

    const wrapper = mount(VotingPhase, {
      global: {
        plugins: [pinia, router],
      },
      props: {
        votingPhase,
        players,
        myUserId: hostId, // Current user is the host but NOT the eliminated sheriff
        isHost: true,
      },
    })

    await wrapper.vm.$nextTick()

    // Check that badge handover buttons are NOT visible
    const buttons = wrapper.findAll('button')
    const buttonLabels = buttons.map((btn) => btn.text())
    expect(buttonLabels).not.toContain('移交警徽 / Pass Badge')
    expect(buttonLabels).not.toContain('销毁')

    // Should show waiting message
    expect(wrapper.html()).toContain('等待警长移交警徽')
  })

  it('shows badge passed message after badge is handed over', async () => {
    const sheriffUserId = 'sheriff-1'
    const newSheriffUserId = 'player-2'
    const votingPhase = createVotingPhase('BADGE_HANDOVER', sheriffUserId)
    const players = [
      {
        userId: sheriffUserId,
        nickname: 'Sheriff',
        avatar: '⭐',
        seatIndex: 1,
        role: 'VILLAGER' as const,
        isAlive: false,
        isSheriff: false, // Badge has been handed over
        canVote: true,
        idiotRevealed: false,
      },
      {
        userId: newSheriffUserId,
        nickname: 'NewSheriff',
        avatar: '😊',
        seatIndex: 2,
        role: 'VILLAGER' as const,
        isAlive: true,
        isSheriff: true, // New sheriff
        canVote: true,
        idiotRevealed: false,
      },
      {
        userId: 'player-3',
        nickname: 'Player3',
        avatar: '😊',
        seatIndex: 3,
        role: 'VILLAGER' as const,
        isAlive: true,
        isSheriff: false,
        canVote: true,
        idiotRevealed: false,
      },
    ]

    const wrapper = mount(VotingPhase, {
      global: {
        plugins: [pinia, router],
      },
      props: {
        votingPhase,
        players,
        myUserId: 'player-3',
        isHost: false,
      },
    })

    await wrapper.vm.$nextTick()

    // Check that badge passed message is visible
    expect(wrapper.html()).toContain('警徽已移交给 NewSheriff')
    expect(wrapper.html()).not.toContain('移交警徽')
    expect(wrapper.html()).not.toContain('销毁')
  })

  it('shows badge destroyed message when badge is destroyed', async () => {
    const sheriffUserId = 'sheriff-1'
    const votingPhase = createVotingPhase('BADGE_HANDOVER', sheriffUserId, true) // badgeDestroyed = true
    const players = [
      {
        userId: sheriffUserId,
        nickname: 'Sheriff',
        avatar: '⭐',
        seatIndex: 1,
        role: 'VILLAGER' as const,
        isAlive: false,
        isSheriff: false, // Badge destroyed
        canVote: true,
        idiotRevealed: false,
      },
      {
        userId: 'player-2',
        nickname: 'Player2',
        avatar: '😊',
        seatIndex: 2,
        role: 'VILLAGER' as const,
        isAlive: true,
        isSheriff: false,
        canVote: true,
        idiotRevealed: false,
      },
    ]

    const wrapper = mount(VotingPhase, {
      global: {
        plugins: [pinia, router],
      },
      props: {
        votingPhase,
        players,
        myUserId: 'player-2',
        isHost: false,
      },
    })

    await wrapper.vm.$nextTick()

    // Check that badge destroyed message is visible
    expect(wrapper.html()).toContain('警徽已销毁')

    // Check that badge handover buttons are NOT visible
    const buttons = wrapper.findAll('button')
    const buttonLabels = buttons.map((btn) => btn.text())
    expect(buttonLabels).not.toContain('移交警徽 / Pass Badge')
    expect(buttonLabels).not.toContain('销毁')
  })

  it('host can continue to night when eliminated player is not sheriff', async () => {
    // Bug fix: when a non-sheriff player is eliminated, badgeDone should be true
    // so the host can continue to night
    const sheriffUserId = 'sheriff-1'
    const eliminatedNonSheriffId = 'player-2'
    const votingPhase = createVotingPhase('BADGE_HANDOVER', eliminatedNonSheriffId, false) // eliminatedPlayerId is not sheriff
    const players = [
      {
        userId: sheriffUserId,
        nickname: 'Sheriff',
        avatar: '⭐',
        seatIndex: 1,
        role: 'VILLAGER' as const,
        isAlive: true,
        isSheriff: true,
        canVote: true,
        idiotRevealed: false,
      },
      {
        userId: eliminatedNonSheriffId,
        nickname: 'Eliminated',
        avatar: '😊',
        seatIndex: 2,
        role: 'VILLAGER' as const,
        isAlive: false,
        isSheriff: false, // Eliminated player is NOT sheriff
        canVote: true,
        idiotRevealed: false,
      },
      {
        userId: 'player-3',
        nickname: 'Player3',
        avatar: '😊',
        seatIndex: 3,
        role: 'VILLAGER' as const,
        isAlive: true,
        isSheriff: false,
        canVote: true,
        idiotRevealed: false,
      },
    ]

    const wrapper = mount(VotingPhase, {
      global: {
        plugins: [pinia, router],
      },
      props: {
        votingPhase,
        players,
        myUserId: 'player-3',
        isHost: true, // Current user is host
      },
    })

    await wrapper.vm.$nextTick()

    // Check that host can see the continue button
    const buttons = wrapper.findAll('button')
    const buttonLabels = buttons.map((btn) => btn.text())
    expect(buttonLabels).toContain('→ 进入夜晚 / Night')
  })

  it('host can continue to night after badge is handed over (VOTE_RESULT phase)', async () => {
    // Bug fix: when sheriff passes badge, backend transitions to VOTE_RESULT
    // Host should be able to see the continue button
    const oldSheriffId = 'sheriff-1'
    const newSheriffId = 'player-2'
    // After badge handover, backend transitions to VOTE_RESULT
    const votingPhase = createVotingPhase('VOTE_RESULT', oldSheriffId, false)
    // VOTE_RESULT shows tally, so we need to set tallyRevealed
    const votingPhaseWithTally = { ...votingPhase, tallyRevealed: true }
    const players = [
      {
        userId: oldSheriffId,
        nickname: 'OldSheriff',
        avatar: '⭐',
        seatIndex: 1,
        role: 'VILLAGER' as const,
        isAlive: false,
        isSheriff: false, // Badge has been handed over
        canVote: true,
        idiotRevealed: false,
      },
      {
        userId: newSheriffId,
        nickname: 'NewSheriff',
        avatar: '😊',
        seatIndex: 2,
        role: 'VILLAGER' as const,
        isAlive: true,
        isSheriff: true, // New sheriff
        canVote: true,
        idiotRevealed: false,
      },
      {
        userId: 'player-3',
        nickname: 'Player3',
        avatar: '😊',
        seatIndex: 3,
        role: 'VILLAGER' as const,
        isAlive: true,
        isSheriff: false,
        canVote: true,
        idiotRevealed: false,
      },
    ]

    const wrapper = mount(VotingPhase, {
      global: {
        plugins: [pinia, router],
      },
      props: {
        votingPhase: votingPhaseWithTally,
        players,
        myUserId: 'player-3',
        isHost: true, // Current user is host
      },
    })

    await wrapper.vm.$nextTick()

    // Check that host can see the continue button
    const buttons = wrapper.findAll('button')
    const buttonLabels = buttons.map((btn) => btn.text())
    expect(buttonLabels).toContain('继续 / Continue')
  })

  it('host can continue to night after badge is destroyed (VOTE_RESULT phase)', async () => {
    // Bug fix: when sheriff destroys badge, backend transitions to VOTE_RESULT
    // Host should be able to see the continue button
    const sheriffUserId = 'sheriff-1'
    // After badge destruction, backend transitions to VOTE_RESULT
    const votingPhase = createVotingPhase('VOTE_RESULT', sheriffUserId, true) // badgeDestroyed = true
    // VOTE_RESULT shows tally, so we need to set tallyRevealed
    const votingPhaseWithTally = { ...votingPhase, tallyRevealed: true }
    const players = [
      {
        userId: sheriffUserId,
        nickname: 'Sheriff',
        avatar: '⭐',
        seatIndex: 1,
        role: 'VILLAGER' as const,
        isAlive: false,
        isSheriff: false, // Badge destroyed
        canVote: true,
        idiotRevealed: false,
      },
      {
        userId: 'player-2',
        nickname: 'Player2',
        avatar: '😊',
        seatIndex: 2,
        role: 'VILLAGER' as const,
        isAlive: true,
        isSheriff: false,
        canVote: true,
        idiotRevealed: false,
      },
    ]

    const wrapper = mount(VotingPhase, {
      global: {
        plugins: [pinia, router],
      },
      props: {
        votingPhase: votingPhaseWithTally,
        players,
        myUserId: 'player-2',
        isHost: true, // Current user is host
      },
    })

    await wrapper.vm.$nextTick()

    // Check that host can see the continue button
    const buttons = wrapper.findAll('button')
    const buttonLabels = buttons.map((btn) => btn.text())
    expect(buttonLabels).toContain('继续 / Continue')
  })
})
