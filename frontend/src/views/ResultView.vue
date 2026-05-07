<template>
  <div class="result-wrap" :class="{ 'result-wolves': winner === 'WEREWOLF' }">
    <div class="result-card">
      <p class="game-over-label">GAME OVER</p>
      <h1 class="outcome-title serif">{{ outcomeTitle }}</h1>
      <p class="outcome-sub">{{ outcomeSub }}</p>

      <div v-if="sortedPlayers.length" class="divider-label">
        <span>ROLES REVEALED</span>
      </div>

      <section v-if="sortedPlayers.length" class="reveal-grid">
        <div
          v-for="player in sortedPlayers"
          :key="player.userId"
          class="reveal-card"
          :class="{ 'reveal-wolf': player.role === 'WEREWOLF' }"
          :data-testid="`role-reveal-${player.seatIndex}`"
        >
          <div class="reveal-meta">
            {{ String(player.seatIndex).padStart(2, '0') }} · {{ displayName(player) }}
          </div>
          <div class="reveal-role serif">{{ roleZh(player.role) }}</div>
        </div>
      </section>

      <button class="btn btn-danger play-again-btn" data-testid="play-again" @click="goLobby">
        Play Again
      </button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useGameStore } from '@/stores/gameStore'
import { useUserStore } from '@/stores/userStore'
import { gameService } from '@/services/gameService'
import type { GamePlayer } from '@/types'

const route = useRoute()
const router = useRouter()
const gameStore = useGameStore()
const userStore = useUserStore()

onMounted(async () => {
  const gameId = Number(route.params.gameId)
  if (gameId) {
    const state = await gameService.getState(gameId.toString())
    gameStore.setState(state)
  }
})

const winner = computed(() => gameStore.state?.winner)

const ROLE_ZH: Record<string, string> = {
  WEREWOLF: '狼人',
  VILLAGER: '村民',
  SEER: '预言家',
  WITCH: '女巫',
  HUNTER: '猎人',
  GUARD: '守卫',
  IDIOT: '白痴',
}

function displayName(player: { userId: string; nickname: string }): string {
  return player.userId === userStore.userId ? '我' : player.nickname
}

function roleZh(role?: string): string {
  return role ? (ROLE_ZH[role] ?? role) : '?'
}

const sortedPlayers = computed<GamePlayer[]>(() => {
  const players = gameStore.state?.players ?? []
  return [...players].sort((a, b) => a.seatIndex - b.seatIndex)
})

const outcomeTitle = computed(() => {
  if (!winner.value) return '比赛取消'
  return winner.value === 'WEREWOLF' ? '狼人胜' : '好人胜'
})

const outcomeSub = computed(() => {
  if (!winner.value) return 'Game Cancelled'
  return winner.value === 'WEREWOLF' ? 'WOLVES WIN' : 'VILLAGE WINS'
})

function goLobby() {
  gameStore.clearGame()
  router.push({ name: 'lobby' })
}
</script>

<style scoped>
.result-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
  background: var(--bg);
  padding: 1.5rem;
}

.result-wolves {
  background: var(--ink);
  color: #f5f0e8;
}

.result-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 2rem 1.5rem;
  width: 100%;
  max-width: 380px;
  text-align: center;
}

.result-wolves .result-card {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.12);
}

.game-over-label {
  font-size: 0.7rem;
  letter-spacing: 0.15em;
  color: var(--muted);
  margin: 0 0 0.25rem;
}

.result-wolves .game-over-label {
  color: rgba(245, 240, 232, 0.55);
}

.outcome-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 2.5rem;
  color: var(--green);
  margin: 0 0 0.25rem;
  letter-spacing: 0.1em;
}

.result-wolves .outcome-title {
  color: var(--red);
}

.outcome-sub {
  font-size: 0.875rem;
  font-weight: 600;
  letter-spacing: 0.15em;
  color: var(--muted);
  margin: 0 0 1.5rem;
}

.result-wolves .outcome-sub {
  color: rgba(245, 240, 232, 0.55);
}

.divider-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.875rem;
  font-size: 0.7rem;
  letter-spacing: 0.15em;
  color: var(--muted);
}

.result-wolves .divider-label {
  color: rgba(245, 240, 232, 0.5);
}

.divider-label::before,
.divider-label::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--border);
}

.result-wolves .divider-label::before,
.result-wolves .divider-label::after {
  background: rgba(255, 255, 255, 0.15);
}

.reveal-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(85px, 47%), 1fr));
  gap: 0.5rem;
  margin-bottom: 1.5rem;
}

.reveal-card {
  border: 1px solid rgba(45, 106, 63, 0.4);
  background: rgba(45, 106, 63, 0.08);
  border-radius: 0.5rem;
  padding: 0.5rem 0.25rem;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 0;
}

.reveal-meta {
  font-size: 0.625rem;
  color: var(--muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.reveal-role {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text);
  font-family: 'Noto Serif SC', serif;
}

.reveal-wolf {
  border-color: var(--red);
  background: rgba(181, 37, 26, 0.1);
}

.reveal-wolf .reveal-role {
  color: var(--red);
}

.result-wolves .reveal-card {
  background: rgba(45, 106, 63, 0.16);
  border-color: rgba(45, 106, 63, 0.55);
}

.result-wolves .reveal-card .reveal-meta {
  color: rgba(245, 240, 232, 0.55);
}

.result-wolves .reveal-card .reveal-role {
  color: rgba(245, 240, 232, 0.92);
}

.result-wolves .reveal-wolf {
  background: rgba(181, 37, 26, 0.22);
  border-color: var(--red);
}

.result-wolves .reveal-wolf .reveal-role {
  color: #ff8a80;
}

.play-again-btn {
  margin-top: 0;
  width: 100%;
}
</style>
