import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useRoomStore } from '@/stores/roomStore'
import type { Room } from '@/types'

function freshRoom(): Room {
  return {
    roomId: 'room-001',
    roomCode: 'ABC123',
    hostId: 'u1',
    status: 'WAITING',
    config: { totalPlayers: 9, roles: ['WEREWOLF', 'VILLAGER'] },
    players: [
      { userId: 'u1', nickname: 'Host', seatIndex: 1, status: 'NOT_READY', isHost: true },
      { userId: 'u2', nickname: 'Alice', seatIndex: 2, status: 'NOT_READY', isHost: false },
      { userId: 'u3', nickname: 'Bob', seatIndex: 3, status: 'READY', isHost: false },
    ],
  }
}

describe('roomStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('starts with no room', () => {
    const store = useRoomStore()
    expect(store.room).toBeNull()
  })

  it('setRoom() stores the room', () => {
    const store = useRoomStore()
    store.setRoom(freshRoom())
    expect(store.room?.roomId).toBe('room-001')
    expect(store.room?.players).toHaveLength(3)
  })

  it('clearRoom() sets room back to null', () => {
    const store = useRoomStore()
    store.setRoom(freshRoom())
    store.clearRoom()
    expect(store.room).toBeNull()
  })

  it('updatePlayers() replaces entire player list', () => {
    const store = useRoomStore()
    store.setRoom(freshRoom())
    store.updatePlayers([
      { userId: 'u1', nickname: 'Host', seatIndex: 1, status: 'READY', isHost: true },
    ])
    expect(store.room?.players).toHaveLength(1)
    expect(store.room?.players[0]?.status).toBe('READY')
  })

  it('updateMyStatus() updates only the matching player', () => {
    const store = useRoomStore()
    store.setRoom(freshRoom())
    store.updateMyStatus('u2', 'READY')
    // players: [host(0), alice(1), bob(2)] — alice is index 1
    expect(store.room?.players[1]?.status).toBe('READY') // alice updated
    expect(store.room?.players[0]?.status).toBe('NOT_READY') // host unchanged
  })

  it('updateMyStatus() does not affect other players', () => {
    const store = useRoomStore()
    store.setRoom(freshRoom())
    store.updateMyStatus('u2', 'READY')
    expect(store.room?.players[2]?.status).toBe('READY') // bob stays READY
  })

  it('updateSeatIndex() assigns a seat number to the matching player', () => {
    const store = useRoomStore()
    store.setRoom(freshRoom())
    store.updateSeatIndex('u2', 5)
    expect(store.room?.players[1]?.seatIndex).toBe(5)
    expect(store.room?.players[0]?.seatIndex).toBe(1) // host unchanged
  })

  it('updateMyStatus() does nothing when userId not found', () => {
    const store = useRoomStore()
    store.setRoom(freshRoom())
    store.updateMyStatus('u99', 'READY') // non-existent userId
    // No player should have unexpectedly changed status
    expect(store.room?.players[0]?.status).toBe('NOT_READY') // host
    expect(store.room?.players[1]?.status).toBe('NOT_READY') // alice
    expect(store.room?.players[2]?.status).toBe('READY') // bob (was already READY)
  })
})
