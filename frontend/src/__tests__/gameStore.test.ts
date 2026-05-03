import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useGameStore } from '@/stores/gameStore'
import type { GameEvent, GameState } from '@/types'

// Factory so each test gets a fresh copy — prevents shared array mutation across tests
function freshState(): GameState {
  return {
    gameId: 'game-001',
    phase: 'DAY_DISCUSSION',
    dayNumber: 1,
    myRole: 'SEER',
    sheriff: 'u2',
    players: [
      { userId: 'u1', nickname: 'You', seatIndex: 1, isAlive: true, isSheriff: false },
      { userId: 'u2', nickname: 'Alice', seatIndex: 2, isAlive: true, isSheriff: true },
    ],
    events: [],
  }
}

const MOCK_EVENT: GameEvent = {
  type: 'VOTE_CAST',
  message: 'Alice voted for Bob.',
  timestamp: Date.now(),
}

describe('gameStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('starts with no state', () => {
    const store = useGameStore()
    expect(store.state).toBeNull()
  })

  it('setState() stores full game state', () => {
    const store = useGameStore()
    store.setState(freshState())
    expect(store.state?.gameId).toBe('game-001')
    expect(store.state?.phase).toBe('DAY_DISCUSSION')
    expect(store.state?.players).toHaveLength(2)
  })

  it('setState() replaces previous state entirely', () => {
    const store = useGameStore()
    store.setState(freshState())
    store.setState({ ...freshState(), phase: 'NIGHT', dayNumber: 2 })
    expect(store.state?.phase).toBe('NIGHT')
    expect(store.state?.dayNumber).toBe(2)
  })

  it('addEvent() appends to the events array', () => {
    const store = useGameStore()
    store.setState(freshState())
    store.addEvent(MOCK_EVENT)
    expect(store.state?.events).toHaveLength(1)
    expect(store.state?.events[0]?.message).toBe('Alice voted for Bob.')
  })

  it('addEvent() does not replace previous events', () => {
    const store = useGameStore()
    store.setState(freshState())
    store.addEvent(MOCK_EVENT)
    store.addEvent({ ...MOCK_EVENT, message: 'Bob voted for Carol.' })
    expect(store.state?.events).toHaveLength(2)
  })

  it('clearGame() sets state to null', () => {
    const store = useGameStore()
    store.setState(freshState())
    store.clearGame()
    expect(store.state).toBeNull()
  })

  // ── audioSequence preservation across polled-state writes ──────────────────
  // Backend AudioService.calculateGameStateAudio returns an empty audioSequence
  // for steady-states (NIGHT in a sub-phase, DAY_DISCUSSION post-reveal, etc.).
  // STOMP `AudioSequence` events push the live audio onto the store; HTTP
  // `getGameState` polls (fired after PhaseChanged / NightSubPhaseChanged)
  // would otherwise overwrite that live audio with empty. The tests below lock
  // in the fix: setState only adopts incoming audioSequence when it carries
  // files; otherwise the live one is preserved.

  it('setState() preserves existing audioSequence when incoming is undefined', () => {
    const store = useGameStore()
    const live = {
      id: 'g1-1234-witch_open_eyes.mp3',
      phase: 'NIGHT' as const,
      subPhase: '',
      audioFiles: ['witch_open_eyes.mp3'],
      priority: 5,
      timestamp: 1234,
    }
    store.setState({ ...freshState(), audioSequence: live })
    // Polled state arrives with NO audioSequence (steady-state poll).
    store.setState({ ...freshState(), phase: 'NIGHT' })
    expect(store.state?.audioSequence).toEqual(live)
  })

  it('setState() preserves existing audioSequence when incoming has empty audioFiles', () => {
    const store = useGameStore()
    const live = {
      id: 'g1-1234-wolf_close_eyes.mp3',
      phase: 'NIGHT' as const,
      subPhase: '',
      audioFiles: ['wolf_close_eyes.mp3'],
      priority: 5,
      timestamp: 1234,
    }
    store.setState({ ...freshState(), audioSequence: live })
    // Polled state from calculateGameStateAudio for NIGHT in sub-phase.
    store.setState({
      ...freshState(),
      phase: 'NIGHT',
      audioSequence: {
        id: 'g1-5678-STATE-NIGHT',
        phase: 'NIGHT',
        subPhase: 'WEREWOLF_PICK',
        audioFiles: [],
        priority: 0,
        timestamp: 5678,
      },
    })
    expect(store.state?.audioSequence).toEqual(live)
  })

  it('setState() adopts incoming audioSequence when it carries files', () => {
    const store = useGameStore()
    store.setState({
      ...freshState(),
      audioSequence: {
        id: 'g1-1234-wolf_close_eyes.mp3',
        phase: 'NIGHT' as const,
        subPhase: '',
        audioFiles: ['wolf_close_eyes.mp3'],
        priority: 5,
        timestamp: 1234,
      },
    })
    const next = {
      id: 'g1-5678-witch_open_eyes.mp3',
      phase: 'NIGHT' as const,
      subPhase: '',
      audioFiles: ['witch_open_eyes.mp3'],
      priority: 5,
      timestamp: 5678,
    }
    store.setState({ ...freshState(), audioSequence: next })
    expect(store.state?.audioSequence).toEqual(next)
  })
})
