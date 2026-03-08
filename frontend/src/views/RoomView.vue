<template>
  <div class="room-wrap">
    <!-- Header -->
    <header class="room-header">
      <button class="back-btn" @click="handleLeave">← 离开</button>
      <div v-if="roomStore.room" class="room-code">
        {{ roomStore.room.roomCode }}
      </div>
      <div v-if="roomStore.room" class="player-count">
        {{ roomStore.room.players.length }} / {{ roomStore.room.config.totalPlayers }}
      </div>
    </header>

    <!-- Loading state -->
    <div v-if="loading" class="loading">Loading room...</div>

    <!-- Room content -->
    <template v-else-if="roomStore.room">
      <!-- Player grid -->
      <section class="player-grid">
        <PlayerSlot
          v-for="seat in totalSeats"
          :key="seat"
          :seat="seat"
          :nickname="playerAtSeat(seat)?.nickname"
          :variant="slotVariant(seat)"
        >
          <template v-if="playerAtSeat(seat)" #badge>
            <div v-if="playerAtSeat(seat)!.isHost" class="seat-badge host">HOST</div>
            <div v-else-if="playerAtSeat(seat)!.status === 'READY'" class="seat-badge ready">✓</div>
          </template>
        </PlayerSlot>
      </section>

      <!-- Role config (host only) -->
      <section v-if="isHost" class="role-config">
        <h3>角色配置 / Roles</h3>
        <p class="config-note">Backend auto-balances role counts based on total players.</p>
        <!-- Role toggles would go here in Phase 2 -->
        <div class="roles-placeholder">Role configuration panel</div>
      </section>

      <!-- Actions -->
      <footer class="room-footer">
        <!-- Host: start game -->
        <button
          v-if="isHost"
          :disabled="!canStart"
          class="btn btn-primary"
          @click="handleStartGame"
        >
          开始游戏 / Start Game
        </button>
        <!-- Guest: ready toggle -->
        <template v-else>
          <button
            v-if="myPlayer?.status !== 'READY'"
            class="btn btn-gold"
            @click="handleReady(true)"
          >
            准备 / Ready
          </button>
          <button v-else class="btn btn-outline" @click="handleReady(false)">
            取消准备 / Undo Ready
          </button>
        </template>
      </footer>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { useRoomStore } from '@/stores/roomStore'
import { roomService } from '@/services/roomService'
import { createStompClient, disconnectStomp, subscribeToTopic } from '@/services/stompClient'
import PlayerSlot from '@/components/PlayerSlot.vue'
import { useNavigationGuard } from '@/composables/useNavigationGuard'
import type { RoomPlayer } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const roomStore = useRoomStore()

useNavigationGuard()

const loading = ref(true)

const isHost = computed(() => roomStore.room?.hostId === userStore.userId)

const myPlayer = computed(() =>
  roomStore.room?.players.find((p: RoomPlayer) => p.userId === userStore.userId),
)

const totalSeats = computed(() =>
  roomStore.room ? Array.from({ length: roomStore.room.config.totalPlayers }, (_, i) => i + 1) : [],
)

const canStart = computed(() => {
  const room = roomStore.room
  if (!room) return false
  const guests = room.players.filter((p: RoomPlayer) => !p.isHost)
  return guests.every((p: RoomPlayer) => p.status === 'READY') && room.players.length >= 4
})

function playerAtSeat(seat: number): RoomPlayer | undefined {
  return roomStore.room?.players.find((p) => p.seatIndex === seat)
}

function slotVariant(seat: number) {
  const p = playerAtSeat(seat)
  if (!p) return 'empty' as const
  if (p.userId === userStore.userId) return 'me' as const
  if (p.status === 'READY') return 'ready' as const
  return 'waiting' as const
}

async function handleReady(ready: boolean) {
  await roomService.setReady(ready)
  // Optimistic update — backend will confirm via STOMP ROOM_UPDATE
  if (userStore.userId) {
    roomStore.updateMyStatus(userStore.userId, ready ? 'READY' : 'NOT_READY')
  }
}

async function handleLeave() {
  await roomService.leaveRoom()
  roomStore.clearRoom()
  disconnectStomp()
  router.push({ name: 'lobby' })
}

async function handleStartGame() {
  // Host triggers game start via backend — backend pushes game started event via STOMP
}

onMounted(async () => {
  const roomId = route.params.roomId as string
  // Only fetch if we don't already have room data (e.g. direct URL load / page refresh)
  if (!roomStore.room) {
    try {
      const room = await roomService.getRoom(roomId)
      roomStore.setRoom(room)
    } catch {
      router.push({ name: 'lobby' })
      return
    }
  }
  loading.value = false

  // Connect STOMP
  if (userStore.token && roomStore.room) {
    const client = createStompClient(userStore.token)
    client.onConnect = () => {
      subscribeToTopic(`/topic/room/${roomStore.room!.roomId}`, (msg: { body: string }) => {
        const data = JSON.parse(msg.body)
        if (data.type === 'ROOM_UPDATE') {
          roomStore.updatePlayers(data.payload.players)
        }
        if (data.type === 'GAME_STARTED') {
          router.push({ name: 'game', params: { gameId: data.payload.gameId } })
        }
      })
    }
    client.activate()
  }
})

onUnmounted(() => {
  disconnectStomp()
})
</script>

<style scoped>
.room-wrap {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: var(--bg);
}

.room-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--border-l);
  background: var(--paper);
}

.back-btn {
  background: none;
  border: none;
  color: var(--muted);
  font-size: 0.875rem;
  cursor: pointer;
  font-family: inherit;
  padding: 0;
}

.room-code {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.25rem;
  font-weight: 700;
  letter-spacing: 0.15em;
  color: var(--text);
}

.player-count {
  font-size: 0.875rem;
  color: var(--muted);
}

.loading {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: var(--muted);
}

.player-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.625rem;
  padding: 1rem;
  flex: 1;
}

.seat-badge {
  font-size: 0.6rem;
  font-weight: 700;
  padding: 0.125rem 0.375rem;
  border-radius: 0.25rem;
  letter-spacing: 0.05em;
}

.seat-badge.host {
  background: var(--red);
  color: #fff;
}

.seat-badge.ready {
  background: var(--green);
  color: #fff;
}

.role-config {
  padding: 0 1rem 1rem;
}

.role-config h3 {
  font-size: 0.875rem;
  color: var(--muted);
  margin: 0 0 0.5rem;
}

.config-note {
  font-size: 0.75rem;
  color: var(--muted);
  margin: 0 0 0.75rem;
}

.roles-placeholder {
  background: var(--paper);
  border: 1px dashed var(--border);
  border-radius: 0.5rem;
  padding: 1rem;
  text-align: center;
  color: var(--muted);
  font-size: 0.875rem;
}

.room-footer {
  padding: 1rem;
  border-top: 1px solid var(--border-l);
  background: var(--paper);
}
</style>
