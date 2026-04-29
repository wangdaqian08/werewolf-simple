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
        <div class="code-num" data-testid="room-code">{{ roomStore.room.roomCode }}</div>
      </div>

      <!-- Player count + ready breakdown -->
      <div class="player-count">
        <span class="count-ready">{{ displayReadyCount }}</span>
        <span>/ {{ roomStore.room.config.totalPlayers }} 玩家</span>
        <template v-if="notReadyGuestCount > 0">
          <span class="count-sep">·</span>
          <span class="count-wait"
            >{{ notReadyGuestCount }}
            {{ notReadyGuestCount === 1 ? 'player not ready' : 'players not ready' }}</span
          >
        </template>
      </div>

      <!-- Player grid (4/3/2 columns based on nickname length) -->
      <section :class="gridClass">
        <PlayerSlot
          v-for="seat in totalSeats"
          :key="seat"
          :seat="seat"
          :nickname="playerAtSeat(seat)?.nickname"
          :avatar="playerAtSeat(seat)?.avatar"
          :variant="slotVariant(seat)"
          mode="room"
          @click="handleSeatClick(seat)"
        >
          <!--
            Host-only: × button on every occupied non-host seat.
            Stops click propagation so the surrounding seat-click (seat
            select / claim) doesn't fire. Available only during the
            WAITING stage; the button isn't rendered after Start Game
            because the room view isn't shown then.
          -->
          <template v-if="canKick(seat)" #overlay>
            <button
              class="kick-btn"
              :data-testid="`kick-player-${seat}`"
              :aria-label="`Kick ${playerAtSeat(seat)?.nickname}`"
              @click.stop="handleKickPlayer(seat)"
            >
              ×
            </button>
          </template>
        </PlayerSlot>
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
        <div class="debug-divider" />
        <button class="debug-btn" @click="debugAddPlayer">+ Add Player</button>
        <div class="debug-divider" />
        <button class="debug-btn debug-start-btn" @click="handleStartGame">
          ▶ Debug: Launch Game
        </button>
      </div>

      <!-- Status bar -->
      <div v-if="isHost" :class="canStart ? 'status-ok' : 'status-wait'" class="status-bar">
        <template v-if="canStart">✓ All ready! 开始游戏</template>
        <template v-else
          >✓ {{ displayReadyCount }} / {{ roomStore.room.config.totalPlayers }} ready · Waiting for
          players to be ready</template
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
import { computed, onMounted, onUnmounted, ref } from 'vue'
import http from '@/services/http'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useUserStore } from '@/stores/userStore'
import { useRoomStore } from '@/stores/roomStore'
import { roomService } from '@/services/roomService'
import { gameService } from '@/services/gameService'
import {
  createStompClient,
  disconnectStomp,
  getStompClient,
  subscribeToTopic,
} from '@/services/stompClient'
import PlayerSlot from '@/components/PlayerSlot.vue'
import { useNavigationGuard } from '@/composables/useNavigationGuard'
import { useRoomStatus } from '@/composables/useRoomStatus'
import { useConnectionLifecycle } from '@/composables/useConnectionLifecycle'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const roomStore = useRoomStore()

const isMock = import.meta.env.VITE_MOCK === 'true'

useNavigationGuard()

const loading = ref(true)

const { room } = storeToRefs(roomStore)

// Adapt grid columns to the longest nickname present
const gridClass = computed(() => {
  const names = roomStore.room?.players.map((p) => p.nickname ?? '') ?? []
  const veryLong = names.filter((n) => n.length > 10).length
  const long = names.filter((n) => n.length > 6).length
  if (veryLong >= 3) return 'player-grid player-grid-2'
  if (long >= 3) return 'player-grid player-grid-3'
  return 'player-grid'
})
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
  await roomService.claimSeat(seat, roomStore.room!.roomId)
  // Optimistic update: real backend would push ROOM_UPDATE via STOMP
  if (userStore.userId) {
    roomStore.updateSeatIndex(userStore.userId, seat)
  }
}

// Host can kick: occupied seat, not the host themselves. Backend also
// validates this server-side; the predicate just controls whether the
// × button renders so the host doesn't see the option on themselves.
function canKick(seat: number): boolean {
  if (!isHost.value) return false
  const player = playerAtSeat(seat)
  if (!player) return false
  return player.userId !== userStore.userId && !player.isHost
}

async function handleKickPlayer(seat: number) {
  if (!roomStore.room) return
  const player = playerAtSeat(seat)
  if (!player) return
  try {
    await roomService.kickPlayer(roomStore.room.roomId, player.userId)
    // STOMP PLAYER_KICKED + ROOM_UPDATE will land via the topic subscription;
    // local store updates from there. No optimistic update — keeps the
    // single source of truth on the server.
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error('[RoomView] kick failed', err)
  }
}

async function handleReady(ready: boolean) {
  await roomService.setReady(ready, roomStore.room!.roomId)
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

async function debugAddPlayer() {
  await http.post('/debug/room/add-player')
}

async function handleStartGame() {
  if (isMock) {
    await http.post('/debug/game/start')
    return
  }
  if (!roomStore.room) return
  await gameService.startGame(Number(roomStore.room.roomId))
}

// Re-fetch room status and redirect if a game is already in progress.
// Does NOT update the room store — avoids resetting local UI state
// (selected seat, pending ready toggle, etc.).
async function checkActiveGame() {
  if (!roomStore.room) return
  try {
    const room = await roomService.getRoom(roomStore.room.roomId)
    if (room.status === 'IN_GAME' && room.activeGameId) {
      router.push({ name: 'game', params: { gameId: room.activeGameId } })
    }
  } catch {
    /* room may have been deleted — ignore, user can still leave manually */
  }
}

useConnectionLifecycle({
  onResume: () => {
    // iOS Safari may have frozen the WebSocket while we were backgrounded.
    // Force-reconnect so the auto-reconnect path fires `onConnect`, which
    // re-subscribes and calls checkActiveGame for us. If the client is
    // already inactive (initial mount, post-leave), just probe room state.
    const client = getStompClient()
    if (client?.active) {
      client.forceDisconnect()
    } else {
      void checkActiveGame()
    }
  },
})

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

  // Trigger 1 — on mount: if the room already has an active game (page refresh
  // after missing the GAME_STARTED event), redirect immediately.
  if (roomStore.room?.status === 'IN_GAME' && roomStore.room.activeGameId) {
    router.push({ name: 'game', params: { gameId: roomStore.room.activeGameId } })
    return
  }

  if (userStore.token && roomStore.room) {
    const client = createStompClient(userStore.token)
    let isFirstConnect = true
    client.onConnect = () => {
      // Trigger 2 — STOMP reconnect: broker does not replay missed events.
      // Re-check room status on every reconnect to catch a GAME_STARTED we missed.
      if (!isFirstConnect) {
        checkActiveGame()
      }
      isFirstConnect = false

      subscribeToTopic(`/topic/room/${roomStore.room!.roomId}`, (msg: { body: string }) => {
        const data = JSON.parse(msg.body)
        if (data.type === 'ROOM_UPDATE') {
          roomStore.updatePlayers(data.payload.players)
        }
        if (data.type === 'GAME_STARTED') {
          router.push({ name: 'game', params: { gameId: data.payload.gameId } })
        }
        if (data.type === 'PLAYER_KICKED') {
          // If THIS browser was the kicked player, clear local state and
          // bounce to lobby. The backend has already deleted our row, so
          // any further STOMP/HTTP calls would fail anyway. Other players'
          // browsers ignore this event; the trailing ROOM_UPDATE will
          // refresh their player list.
          if (data.payload.userId === userStore.userId) {
            roomStore.clearRoom()
            disconnectStomp()
            router.push({ name: 'lobby' })
          }
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
  padding: 1rem;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
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
  padding: 0.375rem 0.75rem;
  text-align: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
}

.code-lbl {
  font-size: 0.625rem;
  letter-spacing: 0.1em;
  color: var(--muted);
  text-transform: uppercase;
}

.code-num {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.25rem;
  font-weight: 700;
  letter-spacing: 0.25em;
  color: var(--red);
}

/* Player count */
.player-count {
  font-size: 0.6875rem;
  color: var(--muted);
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

/* Grid: 4 columns (default) / 3 / 2 based on nickname length */
.player-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.375rem;
  margin-bottom: 0.75rem;
  flex: 1;
}
.player-grid-3 {
  grid-template-columns: repeat(3, 1fr);
}
.player-grid-2 {
  grid-template-columns: repeat(2, 1fr);
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

.debug-divider {
  height: 1px;
  background: var(--border-l);
  margin: 0.375rem 0;
}

.debug-start-btn {
  width: 100%;
  text-align: center;
  color: var(--red);
  border-color: rgba(181, 37, 26, 0.3);
}

.debug-start-btn:hover {
  border-color: var(--red);
  color: var(--red);
}

/* Host-only kick button overlaid on a player's seat card. */
.kick-btn {
  position: absolute;
  top: 0.125rem;
  right: 0.125rem;
  width: 1.125rem;
  height: 1.125rem;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--card);
  color: var(--muted);
  font-size: 0.875rem;
  line-height: 1;
  font-family: inherit;
  cursor: pointer;
  padding: 0;
  z-index: 1;
}
.kick-btn:hover {
  border-color: var(--red);
  color: var(--red);
}
</style>
