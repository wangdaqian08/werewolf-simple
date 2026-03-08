import { computed } from 'vue'
import type { Ref } from 'vue'
import type { Room, RoomPlayer } from '@/types'

type SlotVariant = 'empty' | 'me' | 'me-ready' | 'ready' | 'waiting'

export function useRoomStatus(room: Ref<Room | null>, userId: Ref<string | null>) {
  const isHost = computed(() => room.value?.hostId === userId.value)

  const myPlayer = computed(() =>
    room.value?.players.find((p: RoomPlayer) => p.userId === userId.value),
  )

  const iAmReady = computed(() => myPlayer.value?.status === 'READY')

  const totalSeats = computed(() =>
    room.value ? Array.from({ length: room.value.config.totalPlayers }, (_, i) => i + 1) : [],
  )

  const guests = computed(() => room.value?.players.filter((p: RoomPlayer) => !p.isHost) ?? [])
  const guestCount = computed(() => guests.value.length)
  const readyGuestCount = computed(() => guests.value.filter((p) => p.status === 'READY').length)

  // Host always counts as 1 ready (they control start, no ready-up needed).
  // Applies in both host view and guest view — the host slot is always filled.
  const displayReadyCount = computed(() => readyGuestCount.value + 1)

  // Includes empty seats: "how many players still need to join and ready up".
  const notReadyGuestCount = computed(
    () => (room.value?.config.totalPlayers ?? 0) - displayReadyCount.value,
  )

  const canStart = computed(() => guestCount.value > 0 && notReadyGuestCount.value === 0)

  function playerAtSeat(seat: number): RoomPlayer | undefined {
    return room.value?.players.find((p) => p.seatIndex === seat)
  }

  function slotVariant(seat: number): SlotVariant {
    const p = playerAtSeat(seat)
    if (!p) return 'empty'
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
    guestCount,
    readyGuestCount,
    displayReadyCount,
    notReadyGuestCount,
    canStart,
    playerAtSeat,
    slotVariant,
  }
}
