<template>
  <div class="room-wrap">
    <!-- Back -->
    <button class="back-btn" @click="handleLeave">← 离开</button>

    <!-- Loading -->
    <div v-if="loading" class="loading">Loading room...</div>

    <div v-else-if="roomStore.room" class="room-card">
      <!-- Title -->
      <div :class="canStart ? 'room-title-ready' : ''" class="room-title">
        {{ canStart ? '准备就绪 / Ready to Start' : '等待中 / Waiting' }}
      </div>

      <!-- Room code -->
      <div class="code-card">
        <div class="code-lbl">Room Code</div>
        <div class="code-num">{{ roomStore.room.roomCode }}</div>
      </div>

      <!-- Player count + ready breakdown -->
      <div class="player-count">
        <span class="count-ready">{{ displayReadyCount }}</span>
        <span>/ {{ roomStore.room.config.totalPlayers }} 玩家</span>
        <template v-if="notReadyGuestCount > 0">
          <span class="count-sep">·</span>
          <span class="count-wait">{{ notReadyGuestCount }} waiting</span>
        </template>
      </div>

      <!-- Player grid (4 columns, square slots) -->
      <section class="player-grid">
        <PlayerSlot
          v-for="seat in totalSeats"
          :key="seat"
          :seat="seat"
          :nickname="playerAtSeat(seat)?.nickname"
          :avatar="playerAtSeat(seat)?.avatar"
          :variant="slotVariant(seat)"
          mode="room"
          @click="handleSeatClick(seat)"
        />
      </section>

      <!-- Debug panel (mock mode only) -->
      <div v-if="isMock" class="debug-panel">
        <div class="debug-title">🛠 Debug — Toggle Ready</div>
        <div class="debug-list">
          <div v-for="p in roomStore.room.players" :key="p.userId" class="debug-row">
            <span class="debug-name">{{ p.nickname }}</span>
            <span
              :class="p.status === 'READY' ? 'debug-ready' : 'debug-not-ready'"
              class="debug-status"
            >
              {{ p.status === 'READY' ? 'Ready' : 'Not Ready' }}
            </span>
            <button class="debug-btn" @click="debugToggleReady(p.userId, p.status !== 'READY')">
              {{ p.status === 'READY' ? 'Undo' : 'Ready' }}
            </button>
          </div>
        </div>
      </div>

      <!-- Status bar -->
      <div v-if="isHost" :class="canStart ? 'status-ok' : 'status-wait'" class="status-bar">
        <template v-if="canStart">✓ All ready! 开始游戏</template>
        <template v-else
          >✓ {{ displayReadyCount }} / {{ roomStore.room.config.totalPlayers }} ready · Need
          {{ notReadyGuestCount }} more to start</template
        >
      </div>
      <div v-else class="status-bar" :class="iAmReady ? 'status-ok' : 'status-neutral'">
        <template v-if="iAmReady"
          >✓ 你已准备 · You are ready — {{ displayReadyCount }} /
          {{ roomStore.room.config.totalPlayers }} ready</template
        >
        <template v-else
          >{{ displayReadyCount }} / {{ roomStore.room.config.totalPlayers }} players
          ready</template
        >
      </div>

      <!-- Action button -->
      <div class="room-footer">
        <!-- Host: start game -->
        <button
          v-if="isHost"
          :disabled="!canStart"
          class="btn btn-primary"
          @click="handleStartGame"
        >
          开始游戏 / Start Game
        </button>
        <!-- Guest: ready toggle (disabled until a seat number is picked) -->
        <template v-else>
          <button
            v-if="!iAmReady"
            class="btn btn-gold"
            :disabled="!hasPickedSeat"
            @click="handleReady(true)"
          >
            准备 / Ready
          </button>
          <button v-else class="btn btn-outline" @click="handleReady(false)">
            取消准备 / Undo Ready
          </button>
        </template>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { onMounted, onUnmounted, ref } from 'vue'
import http from '@/services/http'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useUserStore } from '@/stores/userStore'
import { useRoomStore } from '@/stores/roomStore'
import { roomService } from '@/services/roomService'
import { createStompClient, disconnectStomp, subscribeToTopic } from '@/services/stompClient'
import PlayerSlot from '@/components/PlayerSlot.vue'
import { useNavigationGuard } from '@/composables/useNavigationGuard'
import { useRoomStatus } from '@/composables/useRoomStatus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const roomStore = useRoomStore()

const isMock = import.meta.env.VITE_MOCK === 'true'

useNavigationGuard()

const loading = ref(true)

const { room } = storeToRefs(roomStore)
const { userId } = storeToRefs(userStore)

const {
  isHost,
  iAmReady,
  totalSeats,
  displayReadyCount,
  notReadyGuestCount,
  canStart,
  hasPickedSeat,
  canSelectSeat,
  playerAtSeat,
  slotVariant,
} = useRoomStatus(room, userId)

async function handleSeatClick(seat: number) {
  if (!canSelectSeat(seat)) return
  await roomService.claimSeat(seat)
  // Optimistic update: real backend would push ROOM_UPDATE via STOMP
  if (userStore.userId) {
    roomStore.updateSeatIndex(userStore.userId, seat)
  }
}

async function handleReady(ready: boolean) {
  await roomService.setReady(ready)
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

async function debugToggleReady(userId: string, ready: boolean) {
  await http.post('/debug/ready', { userId, ready })
}

async function handleStartGame() {
  // Host triggers game start via backend — backend pushes GAME_STARTED via STOMP
}

onMounted(async () => {
  const roomId = route.params.roomId as string
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
  align-items: flex-start;
  min-height: 100dvh;
  background: var(--bg);
  padding: 2rem 1rem 1.5rem;
}

.back-btn {
  background: none;
  border: none;
  color: var(--muted);
  font-size: 0.8125rem;
  cursor: pointer;
  font-family: inherit;
  padding: 0;
  align-self: flex-start;
  margin-bottom: 0.75rem;
  white-space: nowrap;
}

.room-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 1.5rem;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  flex: 1;
}

.loading {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: var(--muted);
}

.room-title {
  font-size: 1.0625rem;
  font-weight: 500;
  color: var(--text);
  margin-bottom: 0.75rem;
  transition: color 0.3s;
}

.room-title-ready {
  color: var(--green);
}

/* Room code block */
.code-card {
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.375rem;
  padding: 0.875rem;
  text-align: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  margin-bottom: 0.75rem;
}

.code-lbl {
  font-size: 0.625rem;
  letter-spacing: 0.2em;
  color: var(--muted);
  text-transform: uppercase;
  margin-bottom: 0.25rem;
}

.code-num {
  font-family: 'Noto Serif SC', serif;
  font-size: 2.25rem;
  font-weight: 700;
  letter-spacing: 0.3em;
  color: var(--red);
}

/* Player count */
.player-count {
  font-size: 0.6875rem;
  color: var(--muted);
  margin-bottom: 0.5rem;
  display: flex;
  align-items: center;
  gap: 0.375rem;
}

.count-sep {
  color: var(--border);
}
.count-ready {
  color: var(--green);
  font-weight: 500;
}
.count-wait {
  color: var(--muted);
}

/* 4-column grid */
.player-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.375rem;
  margin-bottom: 0.75rem;
  flex: 1;
}

/* Status bar */
.status-bar {
  font-size: 0.6875rem;
  padding: 0.5rem 0.625rem;
  border-radius: 0.375rem;
  margin-bottom: 0.75rem;
}

.status-ok {
  background: rgba(45, 106, 63, 0.06);
  border: 1px solid rgba(45, 106, 63, 0.2);
  color: var(--green);
}

.status-wait {
  background: rgba(45, 106, 63, 0.06);
  border: 1px solid rgba(45, 106, 63, 0.2);
  color: var(--muted);
}

.status-neutral {
  background: var(--paper);
  border: 1px solid var(--border-l);
  color: var(--muted);
  text-align: center;
}

/* Footer */
.room-footer {
  margin-top: auto;
}

/* Debug panel */
.debug-panel {
  border: 1px dashed var(--border);
  border-radius: 0.375rem;
  padding: 0.625rem 0.75rem;
  background: rgba(160, 120, 48, 0.04);
}

.debug-title {
  font-size: 0.625rem;
  letter-spacing: 0.1em;
  color: var(--gold);
  text-transform: uppercase;
  margin-bottom: 0.5rem;
}

.debug-list {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.debug-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.75rem;
}

.debug-name {
  flex: 1;
  color: var(--text);
}

.debug-status {
  font-size: 0.625rem;
  min-width: 4rem;
  text-align: right;
}

.debug-ready {
  color: var(--green);
}

.debug-not-ready {
  color: var(--muted);
}

.debug-btn {
  background: none;
  border: 1px solid var(--border);
  border-radius: 0.25rem;
  padding: 0.125rem 0.5rem;
  font-size: 0.625rem;
  color: var(--muted);
  cursor: pointer;
  font-family: inherit;
}

.debug-btn:hover {
  border-color: var(--gold);
  color: var(--gold);
}
</style>
