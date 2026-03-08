import { ref } from 'vue'
import { describe, expect, it } from 'vitest'
import { useRoomStatus } from '@/composables/useRoomStatus'
import type { Room, RoomPlayer } from '@/types'

// ── Helpers ───────────────────────────────────────────────────────────────────

function makeRoom(overrides: Partial<Room> = {}): Room {
  return {
    roomId: 'r1',
    roomCode: 'ABC',
    hostId: 'host',
    status: 'WAITING',
    config: { totalPlayers: 6, roles: ['WEREWOLF', 'VILLAGER'] },
    players: [],
    ...overrides,
  }
}

function p(userId: string, seatIndex: number, opts: Partial<RoomPlayer> = {}): RoomPlayer {
  return { userId, nickname: userId, seatIndex, status: 'NOT_READY', isHost: false, ...opts }
}

function setup(room: Room | null, myUserId: string | null) {
  return useRoomStatus(ref(room), ref(myUserId))
}

// ── isHost ────────────────────────────────────────────────────────────────────

describe('isHost', () => {
  it('true when my userId matches the room hostId', () => {
    const { isHost } = setup(makeRoom({ hostId: 'u1' }), 'u1')
    expect(isHost.value).toBe(true)
  })

  it('false when I am a guest', () => {
    const { isHost } = setup(makeRoom({ hostId: 'u1' }), 'u2')
    expect(isHost.value).toBe(false)
  })
})

// ── iAmReady ──────────────────────────────────────────────────────────────────

describe('iAmReady', () => {
  it('false before the player readies up', () => {
    const room = makeRoom({ players: [p('u1', 1, { status: 'NOT_READY' })] })
    const { iAmReady } = setup(room, 'u1')
    expect(iAmReady.value).toBe(false)
  })

  it('true after the player readies up', () => {
    const room = makeRoom({ players: [p('u1', 1, { status: 'READY' })] })
    const { iAmReady } = setup(room, 'u1')
    expect(iAmReady.value).toBe(true)
  })
})

// ── displayReadyCount ─────────────────────────────────────────────────────────

describe('displayReadyCount', () => {
  it('starts at 1 in host view (host counts as ready, no guests yet)', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', 1, { isHost: true })],
    })
    const { displayReadyCount } = setup(room, 'host')
    expect(displayReadyCount.value).toBe(1)
  })

  // Regression: host was not counted as +1 when viewing as a guest
  it('counts host as +1 even in guest view', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [
        p('host', 1, { isHost: true }),
        p('u1', 2), // me (not ready)
        p('u2', 3, { status: 'READY' }), // other guest ready
      ],
    })
    const { displayReadyCount } = setup(room, 'u1')
    // host(+1) + 1 ready guest = 2
    expect(displayReadyCount.value).toBe(2)
  })

  it('increments when a guest readies up', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [
        p('host', 1, { isHost: true }),
        p('u1', 2, { status: 'READY' }),
        p('u2', 3, { status: 'READY' }),
      ],
    })
    const { displayReadyCount } = setup(room, 'u1')
    // host(+1) + 2 ready guests = 3
    expect(displayReadyCount.value).toBe(3)
  })
})

// ── notReadyGuestCount ────────────────────────────────────────────────────────

describe('notReadyGuestCount', () => {
  // Regression: was counting only not-ready guests already in the room,
  // ignoring empty seats — caused e.g. "12/6 玩家" display bug
  it('includes empty seats in the waiting count', () => {
    const room = makeRoom({
      hostId: 'host',
      config: { totalPlayers: 6, roles: [] },
      players: [
        p('host', 1, { isHost: true }),
        // seats 2-6 empty
      ],
    })
    // displayReadyCount = 0 ready guests + 1 host = 1
    // notReady = 6 - 1 = 5
    const { notReadyGuestCount } = setup(room, 'host')
    expect(notReadyGuestCount.value).toBe(5)
  })

  it('decreases as guests ready up', () => {
    const room = makeRoom({
      hostId: 'host',
      config: { totalPlayers: 4, roles: [] },
      players: [
        p('host', 1, { isHost: true }),
        p('u1', 2, { status: 'READY' }),
        p('u2', 3, { status: 'READY' }),
        p('u3', 4, { status: 'NOT_READY' }),
      ],
    })
    // displayReadyCount = 2 + 1 = 3; notReady = 4 - 3 = 1
    const { notReadyGuestCount } = setup(room, 'host')
    expect(notReadyGuestCount.value).toBe(1)
  })

  it('is 0 when all seats are filled and ready', () => {
    const room = makeRoom({
      hostId: 'host',
      config: { totalPlayers: 3, roles: [] },
      players: [
        p('host', 1, { isHost: true }),
        p('u1', 2, { status: 'READY' }),
        p('u2', 3, { status: 'READY' }),
      ],
    })
    const { notReadyGuestCount } = setup(room, 'host')
    expect(notReadyGuestCount.value).toBe(0)
  })
})

// ── canStart ──────────────────────────────────────────────────────────────────

describe('canStart', () => {
  it('false when guests are not ready', () => {
    const room = makeRoom({
      hostId: 'host',
      config: { totalPlayers: 3, roles: [] },
      players: [
        p('host', 1, { isHost: true }),
        p('u1', 2, { status: 'NOT_READY' }),
        p('u2', 3, { status: 'READY' }),
      ],
    })
    const { canStart } = setup(room, 'host')
    expect(canStart.value).toBe(false)
  })

  it('false when seats are empty (room not full)', () => {
    const room = makeRoom({
      hostId: 'host',
      config: { totalPlayers: 4, roles: [] },
      players: [
        p('host', 1, { isHost: true }),
        p('u1', 2, { status: 'READY' }),
        // seats 3 and 4 empty
      ],
    })
    const { canStart } = setup(room, 'host')
    expect(canStart.value).toBe(false)
  })

  it('true when all seats are filled and all guests are ready', () => {
    const room = makeRoom({
      hostId: 'host',
      config: { totalPlayers: 3, roles: [] },
      players: [
        p('host', 1, { isHost: true }),
        p('u1', 2, { status: 'READY' }),
        p('u2', 3, { status: 'READY' }),
      ],
    })
    const { canStart } = setup(room, 'host')
    expect(canStart.value).toBe(true)
  })
})

// ── slotVariant ───────────────────────────────────────────────────────────────

describe('slotVariant', () => {
  it('empty for an unoccupied seat', () => {
    const room = makeRoom({ players: [] })
    const { slotVariant } = setup(room, 'u1')
    expect(slotVariant(1)).toBe('empty')
  })

  it("me-ready for the host's own slot (host never needs to ready-up)", () => {
    const room = makeRoom({
      hostId: 'u1',
      players: [p('u1', 1, { isHost: true, status: 'NOT_READY' })],
    })
    const { slotVariant } = setup(room, 'u1')
    expect(slotVariant(1)).toBe('me-ready')
  })

  it('me for my own slot when I am a not-ready guest', () => {
    const room = makeRoom({
      players: [p('u1', 2, { status: 'NOT_READY' })],
    })
    const { slotVariant } = setup(room, 'u1')
    expect(slotVariant(2)).toBe('me')
  })

  it('me-ready for my own slot when I am a ready guest', () => {
    const room = makeRoom({
      players: [p('u1', 2, { status: 'READY' })],
    })
    const { slotVariant } = setup(room, 'u1')
    expect(slotVariant(2)).toBe('me-ready')
  })

  it('ready for another player who is ready', () => {
    const room = makeRoom({
      players: [p('u2', 3, { status: 'READY' })],
    })
    const { slotVariant } = setup(room, 'u1')
    expect(slotVariant(3)).toBe('ready')
  })

  it('waiting for another player who is not ready', () => {
    const room = makeRoom({
      players: [p('u2', 3, { status: 'NOT_READY' })],
    })
    const { slotVariant } = setup(room, 'u1')
    expect(slotVariant(3)).toBe('waiting')
  })
})
