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
        gameId: 1,
        votingPhase,
        players,
        myUserId: sheriffUserId, // Current user is the dying sheriff
        currentSheriffUserId: sheriffUserId,
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
        gameId: 1,
        votingPhase,
        players,
        myUserId: otherPlayerId, // Current user is NOT the dying sheriff
        currentSheriffUserId: sheriffUserId,
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
        gameId: 1,
        votingPhase,
        players,
        myUserId: hostId, // Current user is the host but NOT the dying sheriff
        currentSheriffUserId: sheriffUserId,
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

  // Removed: "shows badge passed message after badge is handed over".
  // The previous test set subPhase=BADGE_HANDOVER while the player flags
  // already reflected a completed pass — a state the backend never actually
  // emits. handleBadge atomically updates both player.sheriff flags AND
  // sub-phase=VOTE_RESULT in the same transaction, and `broadcastGameAfterCommit`
  // defers STOMP fan-out until the commit lands, so by the time any client
  // observes the new flags subPhase is already VOTE_RESULT (covered by the
  // "host can continue to night after badge is handed over (VOTE_RESULT phase)"
  // test below).

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
        gameId: 1,
        votingPhase,
        players,
        myUserId: 'player-2',
        // After destroy, backend cleared game.sheriffUserId.
        currentSheriffUserId: null,
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

  // The "你已出局 · Eliminated · 选择警徽继承人" banner is addressed to "you"
  // and only the dying sheriff is in fact eliminated. It must not show to
  // alive viewers — they already see the footer hint "等待警长移交警徽".
  it('alive non-sheriff does NOT see "you are eliminated" banner during BADGE_HANDOVER', async () => {
    const sheriffUserId = 'sheriff-1'
    const aliveOtherId = 'player-2'
    const votingPhase = createVotingPhase('BADGE_HANDOVER', sheriffUserId)
    const players = createPlayers(sheriffUserId)

    const wrapper = mount(VotingPhase, {
      global: { plugins: [pinia, router] },
      props: {
        gameId: 1,
        votingPhase,
        players,
        myUserId: aliveOtherId,
        currentSheriffUserId: sheriffUserId,
        isHost: false,
      },
    })

    await wrapper.vm.$nextTick()

    expect(wrapper.html()).not.toContain('你已出局')
    expect(wrapper.html()).not.toContain('You are eliminated')
    expect(wrapper.html()).toContain('等待警长移交警徽')
  })

  it('alive host does NOT see "you are eliminated" banner during BADGE_HANDOVER', async () => {
    const sheriffUserId = 'sheriff-1'
    const hostId = 'player-2'
    const votingPhase = createVotingPhase('BADGE_HANDOVER', sheriffUserId)
    const players = createPlayers(sheriffUserId)

    const wrapper = mount(VotingPhase, {
      global: { plugins: [pinia, router] },
      props: {
        gameId: 1,
        votingPhase,
        players,
        myUserId: hostId,
        currentSheriffUserId: sheriffUserId,
        isHost: true,
      },
    })

    await wrapper.vm.$nextTick()

    expect(wrapper.html()).not.toContain('你已出局')
  })

  it('eliminated sheriff DOES see "you are eliminated · choose heir" banner during BADGE_HANDOVER', async () => {
    const sheriffUserId = 'sheriff-1'
    const votingPhase = createVotingPhase('BADGE_HANDOVER', sheriffUserId)
    const players = createPlayers(sheriffUserId)

    const wrapper = mount(VotingPhase, {
      global: { plugins: [pinia, router] },
      props: {
        gameId: 1,
        votingPhase,
        players,
        myUserId: sheriffUserId,
        currentSheriffUserId: sheriffUserId,
        isHost: false,
      },
    })

    await wrapper.vm.$nextTick()

    expect(wrapper.html()).toContain('你已出局')
    expect(wrapper.html()).toContain('选择警徽继承人')
  })

  it('hunter shoots sheriff: the dying sheriff (not the voted-out hunter) sees the pass-badge UI', async () => {
    // 2026-05-12 bug repro (game 23 in production): hunter was voted out and
    // shot the sheriff. Frontend previously gated `isEliminatedSheriff` on
    // `eliminatedPlayerId === myUserId`, which is the *hunter's* id in this
    // path — so the sheriff (the actual badge-holder who must pass) never saw
    // the pass-badge button and the host was offered the Night button
    // prematurely. backend then rejected VOTING_CONTINUE with
    // "Not in VOTE_RESULT sub-phase".
    const hunterUserId = 'hunter-1' // voted out → eliminatedPlayerId
    const sheriffUserId = 'sheriff-1' // shot by hunter → currentSheriffUserId
    const votingPhase = createVotingPhase('BADGE_HANDOVER', hunterUserId)
    const players = [
      {
        userId: hunterUserId,
        nickname: 'Hunter',
        avatar: '🏹',
        seatIndex: 1,
        role: 'HUNTER' as const,
        isAlive: false,
        isSheriff: false,
        canVote: true,
        idiotRevealed: false,
      },
      {
        userId: sheriffUserId,
        nickname: 'Sheriff',
        avatar: '⭐',
        seatIndex: 2,
        role: 'VILLAGER' as const,
        // Deferred-kill: still alive during BADGE_HANDOVER per the 国标 cadence
        // the backend now implements.
        isAlive: true,
        isSheriff: true,
        canVote: true,
        idiotRevealed: false,
      },
    ]

    // Mount as the dying sheriff: they must see Pass + Destroy buttons.
    const sheriffWrapper = mount(VotingPhase, {
      global: { plugins: [pinia, router] },
      props: {
        gameId: 1,
        votingPhase,
        players,
        myUserId: sheriffUserId,
        currentSheriffUserId: sheriffUserId,
        isHost: false,
      },
    })
    await sheriffWrapper.vm.$nextTick()
    const sheriffButtons = sheriffWrapper.findAll('button').map((b) => b.text())
    expect(sheriffButtons).toContain('移交警徽 / Pass Badge')
    expect(sheriffButtons).toContain('销毁')

    // And the host must NOT yet see "Night" — badge handover blocks it.
    const hostWrapper = mount(VotingPhase, {
      global: { plugins: [pinia, router] },
      props: {
        gameId: 1,
        votingPhase,
        players,
        myUserId: 'host-x',
        currentSheriffUserId: sheriffUserId,
        isHost: true,
      },
    })
    await hostWrapper.vm.$nextTick()
    const hostButtons = hostWrapper.findAll('button').map((b) => b.text())
    expect(hostButtons).not.toContain('→ 进入夜晚 / Night')
    expect(hostWrapper.html()).toContain('等待警长移交警徽')
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
        gameId: 1,
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
        gameId: 1,
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

// ── Layout regression: my-role-chip left, log-fab + ActionMenu in right-stack ────
describe('VotingPhase — below-arch layout', () => {
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

  const baseVoting: VotingState = {
    dayNumber: 1,
    subPhase: 'VOTING',
    phaseStarted: Date.now() - 10000,
    phaseDeadline: Date.now() + 60000,
    canVote: true,
    votesSubmitted: 0,
    totalVoters: 6,
    tallyRevealed: false,
    tally: [],
    badgeDestroyed: false,
  } as unknown as VotingState

  const wolfPlayers: GamePlayer[] = [
    {
      userId: 'wolf-1',
      nickname: 'Wolf',
      avatar: '🐺',
      seatIndex: 1,
      role: 'WEREWOLF' as const,
      isAlive: true,
      isSheriff: false,
      canVote: true,
      idiotRevealed: false,
    },
    {
      userId: 'p-2',
      nickname: 'P2',
      avatar: '😊',
      seatIndex: 2,
      role: 'VILLAGER' as const,
      isAlive: true,
      isSheriff: false,
      canVote: true,
      idiotRevealed: false,
    },
  ]

  function mountVoting() {
    return mount(VotingPhase, {
      global: { plugins: [pinia, router] },
      props: {
        gameId: 1,
        votingPhase: baseVoting,
        players: wolfPlayers,
        myUserId: 'wolf-1',
        isHost: false,
        myRole: 'WEREWOLF',
        isAlive: true,
      },
    })
  }

  it('my-role-chip is a direct child of role-history-row (left side, original spot)', () => {
    const wrapper = mountVoting()
    const chip = wrapper.find('.role-history-row > .my-role-chip')
    expect(chip.exists()).toBe(true)
  })

  it('log-fab + ActionMenu live inside .right-stack inside role-history-row', () => {
    const wrapper = mountVoting()
    const rightStack = wrapper.find('.role-history-row > .right-stack')
    expect(rightStack.exists()).toBe(true)
    expect(rightStack.find('.log-fab').exists()).toBe(true)
    expect(rightStack.find('[data-testid="action-menu-btn"]').exists()).toBe(true)
  })

  it('Action chip is NOT bundled under my-role-chip on the left', () => {
    const wrapper = mountVoting()
    // The old role-action-col wrapper must be gone — ActionMenu lives on the right now.
    expect(wrapper.find('.role-action-col').exists()).toBe(false)
  })
})
