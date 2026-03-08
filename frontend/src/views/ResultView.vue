<template>
  <div class="result-wrap">
    <div class="result-card">
      <div class="outcome-icon">{{ outcomeIcon }}</div>
      <h1 class="outcome-title serif">{{ outcomeTitle }}</h1>
      <p class="outcome-sub">{{ outcomeSub }}</p>

      <!-- Role reveal grid -->
      <section v-if="gameStore.state" class="role-grid">
        <div v-for="player in gameStore.state.players" :key="player.userId" class="role-slot">
          <div class="role-name">{{ player.nickname }}</div>
          <div :class="`role-${player.role?.toLowerCase()}`" class="role-badge">
            {{ player.role ?? '?' }}
          </div>
        </div>
      </section>

      <button class="btn btn-primary back-btn" @click="goLobby">返回大厅 / Back to Lobby</button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useGameStore } from '@/stores/gameStore'

const router = useRouter()
const gameStore = useGameStore()

// Derive win/loss from backend game state
const myRole = computed(() => gameStore.state?.myRole)
const winner = computed(() => gameStore.state?.winner)

const outcomeIcon = computed(() => {
  if (!winner.value) return '🎲'
  if (winner.value === 'WEREWOLF') return myRole.value === 'WEREWOLF' ? '🐺' : '💀'
  return myRole.value === 'WEREWOLF' ? '💀' : '🌟'
})

const outcomeTitle = computed(() => {
  if (!winner.value) return '游戏结束'
  return winner.value === 'WEREWOLF' ? '狼人获胜 Werewolves Win' : '村民获胜 Villagers Win'
})

const outcomeSub = computed(() => {
  if (!winner.value) return ''
  const isWinner =
    (winner.value === 'WEREWOLF' && myRole.value === 'WEREWOLF') ||
    (winner.value === 'VILLAGER' && myRole.value !== 'WEREWOLF')
  return isWinner ? 'You won!' : 'You lost.'
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

.result-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 2rem 1.5rem;
  width: 100%;
  max-width: 360px;
  text-align: center;
}

.outcome-icon {
  font-size: 3rem;
  margin-bottom: 0.5rem;
}

.outcome-title {
  font-size: 1.5rem;
  color: var(--red);
  margin: 0 0 0.25rem;
}

.outcome-sub {
  color: var(--muted);
  margin: 0 0 1.5rem;
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.5rem;
  margin-bottom: 1.5rem;
  text-align: center;
}

.role-slot {
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.5rem;
  padding: 0.5rem 0.25rem;
}

.role-name {
  font-size: 0.7rem;
  color: var(--muted);
  margin-bottom: 0.25rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-badge {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 0.125rem 0.375rem;
  border-radius: 0.25rem;
  display: inline-block;
}

.role-werewolf {
  background: var(--red);
  color: #fff;
}

.role-villager {
  background: var(--muted);
  color: #fff;
}

.role-seer {
  background: var(--gold);
  color: #fff;
}

.role-witch {
  background: var(--green);
  color: #fff;
}

.role-hunter {
  background: #6b4c2a;
  color: #fff;
}

.role-guard {
  background: #3a5f8a;
  color: #fff;
}

.role-idiot {
  background: #888;
  color: #fff;
}

.back-btn {
  margin-top: 0;
}
</style>
