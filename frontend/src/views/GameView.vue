<template>
  <div :class="{ 'night-mode': isNight }" class="game-wrap">
    <!-- Phase header -->
    <header class="game-header">
      <div class="phase-label">{{ phaseLabel }}</div>
      <div class="day-badge">Day {{ gameStore.state?.dayNumber ?? 1 }}</div>
    </header>

    <!-- Player grid -->
    <section v-if="gameStore.state" class="player-grid">
      <PlayerSlot
        v-for="player in gameStore.state.players"
        :key="player.userId"
        :seat="player.seatIndex"
        :nickname="player.nickname"
        :variant="playerSlotVariant(player)"
        @click="onPlayerTap(player)"
      >
        <template v-if="player.isSheriff" #top>
          <div class="sheriff-badge">⭐</div>
        </template>
        <template v-if="!player.isAlive" #overlay>
          <div class="dead-overlay">✕</div>
        </template>
      </PlayerSlot>
    </section>

    <!-- Event log -->
    <section v-if="gameStore.state?.events.length" class="event-log">
      <div v-for="(event, i) in visibleEvents" :key="i" class="event-item">
        {{ event.message }}
      </div>
    </section>

    <!-- Action panel — rendered by backend phase -->
    <footer class="action-panel">
      <p class="action-hint">{{ actionHint }}</p>
      <!-- Actions are driven by backend phase; placeholder for now -->
    </footer>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { useGameStore } from '@/stores/gameStore'
import { gameService } from '@/services/gameService'
import { createStompClient, disconnectStomp, subscribeToTopic } from '@/services/stompClient'
import PlayerSlot from '@/components/PlayerSlot.vue'
import type { GamePlayer } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const gameStore = useGameStore()

const isNight = computed(() => gameStore.state?.phase === 'NIGHT')

const phaseLabel = computed(() => {
  switch (gameStore.state?.phase) {
    case 'SHERIFF_ELECTION':
      return '警长竞选 Sheriff Election'
    case 'DAY':
      return '白天 Day Phase'
    case 'VOTING':
      return '投票 Voting'
    case 'NIGHT':
      return '黑夜 Night Phase'
    case 'GAME_OVER':
      return '游戏结束 Game Over'
    default:
      return ''
  }
})

const actionHint = computed(() => {
  switch (gameStore.state?.phase) {
    case 'SHERIFF_ELECTION':
      return 'Waiting for sheriff election...'
    case 'DAY':
      return 'Listen to discussions...'
    case 'VOTING':
      return 'Tap a player to vote'
    case 'NIGHT':
      return 'Night falls... close your eyes'
    default:
      return ''
  }
})

const visibleEvents = computed(() => (gameStore.state?.events ?? []).slice(-5).reverse())

function playerSlotVariant(player: GamePlayer) {
  if (!player.isAlive) return 'dead' as const
  if (player.userId === userStore.userId) return 'me' as const
  return 'alive' as const
}

function onPlayerTap(player: GamePlayer) {
  if (!player.isAlive) return
  // Send action to backend — backend validates whether this tap is a valid game action
  gameService.submitAction({ actionType: 'SELECT_PLAYER', targetId: player.userId })
}

onMounted(async () => {
  const gameId = route.params.gameId as string
  try {
    const state = await gameService.getState(gameId)
    gameStore.setState(state)
  } catch {
    router.push({ name: 'lobby' })
    return
  }

  if (userStore.token) {
    const client = createStompClient(userStore.token)
    client.onConnect = () => {
      subscribeToTopic(`/topic/game/${gameId}`, (msg: { body: string }) => {
        const data = JSON.parse(msg.body)
        if (data.type === 'GAME_STATE_UPDATE') {
          gameStore.setState(data.payload)
        }
        if (data.type === 'GAME_EVENT') {
          gameStore.addEvent(data.payload)
        }
        if (data.type === 'GAME_OVER') {
          router.push({ name: 'result', params: { gameId } })
        }
      })
      // Private channel for role-specific info (night actions, etc.)
      subscribeToTopic('/user/queue/private', (msg: { body: string }) => {
        const data = JSON.parse(msg.body)
        gameStore.addEvent(data)
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
.game-wrap {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: var(--bg);
  transition: background 0.5s;
}

.night-mode {
  background: var(--ink);
  color: #f5f0e8;
}

.game-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.phase-label {
  font-family: 'Noto Serif SC', serif;
  font-size: 1rem;
  font-weight: 600;
}

.day-badge {
  font-size: 0.75rem;
  background: var(--red);
  color: #fff;
  padding: 0.25rem 0.625rem;
  border-radius: 1rem;
}

.player-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.625rem;
  padding: 1rem;
  flex: 1;
}

.sheriff-badge {
  position: absolute;
  top: 4px;
  right: 6px;
  font-size: 0.75rem;
}

.dead-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.5rem;
  color: var(--muted);
}

.event-log {
  padding: 0 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  max-height: 120px;
  overflow-y: auto;
}

.event-item {
  font-size: 0.8rem;
  color: var(--muted);
  padding: 0.25rem 0;
  border-bottom: 1px solid var(--border-l);
}

.action-panel {
  padding: 1rem;
  border-top: 1px solid var(--border-l);
  background: var(--paper);
  min-height: 80px;
}

.night-mode .action-panel {
  background: rgba(255, 255, 255, 0.05);
  border-top-color: rgba(255, 255, 255, 0.1);
}

.action-hint {
  text-align: center;
  color: var(--muted);
  font-size: 0.875rem;
  margin: 0;
}
</style>
