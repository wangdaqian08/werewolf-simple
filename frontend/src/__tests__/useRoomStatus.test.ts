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
  it('0 when host has no seat and no guests yet', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', null as unknown as number, { isHost: true })],
    })
    const { displayReadyCount } = setup(room, 'host')
    expect(displayReadyCount.value).toBe(0)
  })

  it('1 when host has picked a seat and no guests yet', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', 1, { isHost: true })],
    })
    const { displayReadyCount } = setup(room, 'host')
    expect(displayReadyCount.value).toBe(1)
  })

  it('counts host only when seated, even in guest view', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [
        p('host', 1, { isHost: true }), // host has seat
        p('u1', 2), // me (not ready)
        p('u2', 3, { status: 'READY' }), // other guest ready
      ],
    })
    const { displayReadyCount } = setup(room, 'u1')
    // host(seated, +1) + 1 ready guest = 2
    expect(displayReadyCount.value).toBe(2)
  })

  it('does not count host before they pick a seat', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [
        p('host', null as unknown as number, { isHost: true }), // host has no seat
        p('u1', 1, { status: 'READY' }),
      ],
    })
    const { displayReadyCount } = setup(room, 'u1')
    // host unsated (0) + 1 ready guest = 1
    expect(displayReadyCount.value).toBe(1)
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
    // host(seated, +1) + 2 ready guests = 3
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
        p('host', 1, { isHost: true }), // host has seat
        // seats 2-6 empty
      ],
    })
    // displayReadyCount = 0 ready guests + 1 (host seated) = 1
    // notReady = 6 - 1 = 5
    const { notReadyGuestCount } = setup(room, 'host')
    expect(notReadyGuestCount.value).toBe(5)
  })

  it('counts unseated host as waiting (not yet ready)', () => {
    const room = makeRoom({
      hostId: 'host',
      config: { totalPlayers: 3, roles: [] },
      players: [
        p('host', null as unknown as number, { isHost: true }), // host has no seat
        p('u1', 1, { status: 'READY' }),
        p('u2', 2, { status: 'READY' }),
      ],
    })
    // displayReadyCount = 2 ready guests + 0 (host unseated) = 2
    // notReady = 3 - 2 = 1 (seat 3 for host)
    const { notReadyGuestCount } = setup(room, 'host')
    expect(notReadyGuestCount.value).toBe(1)
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

  it('false when host has not picked a seat yet (even if all guests are ready)', () => {
    const room = makeRoom({
      hostId: 'host',
      config: { totalPlayers: 3, roles: [] },
      players: [
        p('host', null as unknown as number, { isHost: true }), // host no seat
        p('u1', 1, { status: 'READY' }),
        p('u2', 2, { status: 'READY' }),
      ],
    })
    const { canStart } = setup(room, 'host')
    expect(canStart.value).toBe(false)
  })
})

// ── seat selection ────────────────────────────────────────────────────────────

describe('hasPickedSeat', () => {
  it('false when guest has no seatIndex', () => {
    const room = makeRoom({ players: [p('u1', null as unknown as number)] })
    const { hasPickedSeat } = setup(room, 'u1')
    expect(hasPickedSeat.value).toBe(false)
  })

  it('true when guest has claimed a seat', () => {
    const room = makeRoom({ players: [p('u1', 3)] })
    const { hasPickedSeat } = setup(room, 'u1')
    expect(hasPickedSeat.value).toBe(true)
  })
})

describe('canSelectSeat', () => {
  it('true for an empty seat when not yet ready', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', 1, { isHost: true })],
    })
    const { canSelectSeat } = setup(room, 'u1')
    expect(canSelectSeat(2)).toBe(true)
  })

  it('true even when player already has a seat (allows changing number)', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', 1, { isHost: true }), p('u1', 3)],
    })
    const { canSelectSeat } = setup(room, 'u1')
    expect(canSelectSeat(2)).toBe(true) // can move from seat 3 to seat 2
  })

  it('true for the host (host can also pick a number)', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', 1, { isHost: true, status: 'NOT_READY' })],
    })
    const { canSelectSeat } = setup(room, 'host')
    expect(canSelectSeat(2)).toBe(true)
  })

  it('false when the seat is already taken by someone else', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', 1, { isHost: true }), p('u2', 2)],
    })
    const { canSelectSeat } = setup(room, 'u1')
    expect(canSelectSeat(2)).toBe(false)
  })

  it('false when player is ready (seat locked)', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', 1, { isHost: true }), p('u1', 3, { status: 'READY' })],
    })
    const { canSelectSeat } = setup(room, 'u1')
    expect(canSelectSeat(2)).toBe(false)
  })
})

// ── slotVariant ───────────────────────────────────────────────────────────────

describe('slotVariant', () => {
  it('selectable for an empty seat when guest has not picked yet', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', 1, { isHost: true })],
    })
    const { slotVariant } = setup(room, 'u1') // u1 is a guest with no seat
    expect(slotVariant(2)).toBe('selectable')
  })

  it('selectable for an empty seat even when player already picked one (can change)', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', 1, { isHost: true }), p('u1', 3)],
    })
    const { slotVariant } = setup(room, 'u1')
    expect(slotVariant(2)).toBe('selectable') // can move from 3 to 2
  })

  it('empty (not selectable) when player is ready (seat locked)', () => {
    const room = makeRoom({
      hostId: 'host',
      players: [p('host', 1, { isHost: true }), p('u1', 3, { status: 'READY' })],
    })
    const { slotVariant } = setup(room, 'u1')
    expect(slotVariant(2)).toBe('empty') // locked after ready
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
