import { computed } from 'vue'
import type { Ref } from 'vue'
import type { Room, RoomPlayer } from '@/types'

type SlotVariant = 'empty' | 'selectable' | 'me' | 'me-ready' | 'ready' | 'waiting'

export function useRoomStatus(room: Ref<Room | null>, userId: Ref<string | null>) {
  const isHost = computed(() => room.value?.hostId === userId.value)

  const myPlayer = computed(() =>
    room.value?.players.find((p: RoomPlayer) => p.userId === userId.value),
  )

  const iAmReady = computed(() => myPlayer.value?.status === 'READY')

  const totalSeats = computed(() =>
    room.value ? Array.from({ length: room.value.config.totalPlayers }, (_, i) => i + 1) : [],
  )

  const hostPlayer = computed(() => room.value?.players.find((p: RoomPlayer) => p.isHost) ?? null)
  const guests = computed(() => room.value?.players.filter((p: RoomPlayer) => !p.isHost) ?? [])
  const guestCount = computed(() => guests.value.length)
  const readyGuestCount = computed(() => guests.value.filter((p) => p.status === 'READY').length)

  // Count of "ready" seats: guests who are READY + host only when they have picked a seat.
  // Host without a seat does not count — they must pick like everyone else.
  const displayReadyCount = computed(
    () => readyGuestCount.value + (hostPlayer.value?.seatIndex != null ? 1 : 0),
  )

  // Remaining seats that still need to be filled and readied (empty seats + NOT_READY guests + unseated host).
  const notReadyGuestCount = computed(
    () => (room.value?.config.totalPlayers ?? 0) - displayReadyCount.value,
  )

  const canStart = computed(() => guestCount.value > 0 && notReadyGuestCount.value === 0)

  // Seat selection
  const mySeat = computed(() => myPlayer.value?.seatIndex ?? null)
  const hasPickedSeat = computed(() => mySeat.value !== null)

  function playerAtSeat(seat: number): RoomPlayer | undefined {
    return room.value?.players.find((p) => p.seatIndex === seat)
  }

  // Whether this seat can be claimed by the current user.
  // Any player (host or guest) may change their number as long as they are not yet ready,
  // and the target seat is not taken by someone else.
  function canSelectSeat(seat: number): boolean {
    if (!userId.value || !room.value) return false
    if (iAmReady.value) return false // locked once ready
    const occupant = playerAtSeat(seat)
    return !occupant // only empty seats are selectable
  }

  function slotVariant(seat: number): SlotVariant {
    const p = playerAtSeat(seat)
    if (!p) {
      return canSelectSeat(seat) ? 'selectable' : 'empty'
    }
    if (p.userId === userId.value) {
      // Host always shows ready styling (controls start, never needs to ready-up).
      // Guest shows ready styling only when READY.
      return p.isHost || p.status === 'READY' ? 'me-ready' : 'me'
    }
    return p.status === 'READY' ? 'ready' : 'waiting'
  }

  return {
    isHost,
    myPlayer,
    iAmReady,
    totalSeats,
    guests,
    displayReadyCount,
    notReadyGuestCount,
    canStart,
    mySeat,
    hasPickedSeat,
    canSelectSeat,
    playerAtSeat,
    slotVariant,
  }
}
