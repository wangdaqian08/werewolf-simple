/**
 * Unit tests for SheriffElection.vue sub-phase rendering.
 *
 * Each sub-phase must render visible, identifiable UI content.
 * These tests catch the class of bug where the backend enum value
 * (e.g. ElectionSubPhase.VOTING → "VOTING") diverges from a frontend
 * string literal (e.g. 'DAY_VOTING'), causing a blank screen.
 */
import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import SheriffElection from '@/components/SheriffElection.vue'
import type { SheriffElectionState } from '@/types'

const CANDIDATES = [
  { userId: 'u2', nickname: 'Alice', avatar: '😊', status: 'RUNNING' as const },
  { userId: 'u3', nickname: 'Bob', avatar: '🐻', status: 'RUNNING' as const },
]

function makeElection(overrides: Partial<SheriffElectionState>): SheriffElectionState {
  return {
    subPhase: 'SIGNUP',
    timeRemaining: 60,
    candidates: CANDIDATES,
    decisionProgress: { decided: CANDIDATES.length, total: 8 },
    speakingOrder: ['u2', 'u3'],
    canVote: true,
    allVoted: false,
    voteProgress: { voted: 0, total: 8 },
    ...overrides,
  }
}

const DEFAULT_PROPS = {
  myUserId: 'u1',
  isHost: true,
  actionPending: false,
}

describe('SheriffElection — sub-phase rendering', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('SIGNUP: renders only decision progress, no candidate identities', () => {
    // 2026-05-11 behaviour change: during SIGNUP, players see only "X / Y
    // decided" — no per-candidate names, no aggregate candidate count, no
    // explanatory text.
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({
          subPhase: 'SIGNUP',
          candidates: [],
          decisionProgress: { decided: 5, total: 8 },
        }),
        ...DEFAULT_PROPS,
      },
    })
    expect(wrapper.find('.sheriff-wrap').exists()).toBe(true)
    // Decision progress is the only metric shown
    expect(wrapper.find('[data-testid="sheriff-decision-progress"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="sheriff-decision-progress"]').text()).toContain('5')
    expect(wrapper.find('[data-testid="sheriff-decision-progress"]').text()).toContain('8')
    // No nicknames leak through
    expect(wrapper.text()).not.toContain('Alice')
    expect(wrapper.text()).not.toContain('Bob')
  })

  it('SIGNUP: never renders the candidate-list with names — even if the backend leaks them', () => {
    // Defence-in-depth: if a backend regression sends names back during SIGNUP,
    // the frontend still hides them. Pulls the rug on any stale snapshot.
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({
          subPhase: 'SIGNUP',
          candidates: CANDIDATES, // backend leaked names
          decisionProgress: { decided: 2, total: 8 },
        }),
        ...DEFAULT_PROPS,
      },
    })
    // The old per-candidate list rendering must be gone from SIGNUP.
    expect(wrapper.find('.candidate-list').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Alice')
    expect(wrapper.text()).not.toContain('Bob')
  })

  it('SIGNUP: does not render a host "Start Campaign" button (auto-transition handles it)', () => {
    // The campaign now only begins once every player has decided, via a backend
    // auto-trigger. The host has no information to base an early-start decision
    // on, so the manual button is removed entirely.
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({
          subPhase: 'SIGNUP',
          candidates: [],
          decisionProgress: { decided: 4, total: 8 },
        }),
        ...DEFAULT_PROPS,
        isHost: true,
      },
    })
    expect(wrapper.find('[data-testid="sheriff-start-campaign"]').exists()).toBe(false)
  })

  it('SIGNUP: shows Withdraw button when I am running (self-row preserved in candidates)', () => {
    // The backend includes the requesting player's own candidate row in SIGNUP
    // so the UI knows whether to show Run-for-Sheriff or Withdraw.
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({
          subPhase: 'SIGNUP',
          candidates: [{ userId: 'u1', nickname: 'Me', avatar: '🙂', status: 'RUNNING' }],
          decisionProgress: { decided: 5, total: 8 },
        }),
        ...DEFAULT_PROPS,
        myUserId: 'u1',
      },
    })
    expect(wrapper.find('[data-testid="sheriff-withdraw"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="sheriff-run"]').exists()).toBe(false)
  })

  it('SIGNUP: shows Run/Pass buttons when I have not decided yet', () => {
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({
          subPhase: 'SIGNUP',
          candidates: [],
          decisionProgress: { decided: 3, total: 8 },
        }),
        ...DEFAULT_PROPS,
        myUserId: 'u1',
      },
    })
    expect(wrapper.find('[data-testid="sheriff-run"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="sheriff-pass"]').exists()).toBe(true)
  })

  it('SPEECH (not current speaker): renders speech UI', () => {
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({ subPhase: 'SPEECH', currentSpeakerId: 'u2' }),
        ...DEFAULT_PROPS,
        myUserId: 'u1', // not the speaker
      },
    })
    expect(wrapper.find('.sheriff-wrap').exists()).toBe(true)
    // Should show some speech-phase content, not a blank sheriff-wrap
    expect(wrapper.text().length).toBeGreaterThan(10)
  })

  it('VOTING: renders candidate vote list — catches "VOTING" vs "DAY_VOTING" mismatch', () => {
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({ subPhase: 'VOTING', canVote: true }),
        ...DEFAULT_PROPS,
        myUserId: 'u1',
      },
    })
    expect(wrapper.find('.sheriff-wrap').exists()).toBe(true)
    // Must show the voting UI, not a blank screen
    expect(wrapper.find('.vote-list').exists()).toBe(true)
    // Candidates must be visible
    expect(wrapper.text()).toContain('Alice')
    expect(wrapper.text()).toContain('Bob')
  })

  it('RESULT: renders result screen', () => {
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({
          subPhase: 'RESULT',
          result: {
            sheriffId: 'u2',
            sheriffNickname: 'Alice',
            sheriffAvatar: '😊',
            tally: [],
            abstainCount: 0,
            abstainVoters: [],
          },
        }),
        ...DEFAULT_PROPS,
      },
    })
    expect(wrapper.find('.sheriff-wrap').exists()).toBe(true)
    expect(wrapper.text()).toContain('Alice')
  })

  it('TIED: renders tied result screen', () => {
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({
          subPhase: 'TIED',
          result: {
            sheriffId: 'u2',
            sheriffNickname: 'Alice',
            sheriffAvatar: '😊',
            tally: [],
            abstainCount: 0,
            abstainVoters: [],
          },
        }),
        ...DEFAULT_PROPS,
      },
    })
    expect(wrapper.find('.sheriff-wrap').exists()).toBe(true)
    expect(wrapper.text().length).toBeGreaterThan(10)
  })
})

describe('SheriffElection — VOTING sub-phase interactions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('non-candidate can click a candidate row to select', async () => {
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({ subPhase: 'VOTING', canVote: true }),
        ...DEFAULT_PROPS,
        myUserId: 'u1', // not a candidate
      },
    })
    const rows = wrapper.findAll('.vote-row')
    expect(rows.length).toBeGreaterThan(0)
    expect(rows[0]).toBeDefined()
    await rows[0]!.trigger('click')
    // After clicking, the row should be selected (not error out)
    expect(wrapper.find('.vote-row-selected').exists()).toBe(true)
  })

  it('host sees Confirm Vote button when a candidate is selected', async () => {
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({ subPhase: 'VOTING', canVote: true }),
        ...DEFAULT_PROPS,
        myUserId: 'u1',
        isHost: false, // regular alive player
      },
    })
    // 2 RUNNING candidates (Alice + Bob) → 2 vote-rows
    expect(wrapper.findAll('.vote-row')).toHaveLength(2)
    await wrapper.findAll('.vote-row')![0]!.trigger('click')
    expect(wrapper.find('[data-testid="sheriff-vote"]').exists()).toBe(true)
  })

  it('candidate cannot vote for themselves', async () => {
    const wrapper = mount(SheriffElection, {
      props: {
        election: makeElection({ subPhase: 'VOTING', canVote: true }),
        ...DEFAULT_PROPS,
        myUserId: 'u2', // IS a candidate
        isHost: false,
      },
    })
    // u2's own row should have vote-row-self class
    const selfRow = wrapper.find('.vote-row-self')
    expect(selfRow.exists()).toBe(true)
    await selfRow.trigger('click')
    // Confirm Vote button should NOT appear (self-select blocked)
    expect(wrapper.find('[data-testid="sheriff-confirm-vote"]').exists()).toBe(false)
  })
})
