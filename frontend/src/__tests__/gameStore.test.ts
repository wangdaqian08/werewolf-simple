import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useGameStore } from '@/stores/gameStore'
import type { GameEvent, GameState } from '@/types'

// Factory so each test gets a fresh copy — prevents shared array mutation across tests
function freshState(): GameState {
  return {
    gameId: 'game-001',
    phase: 'DAY',
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
    expect(store.state?.phase).toBe('DAY')
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
})
