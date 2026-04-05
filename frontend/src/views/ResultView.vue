<template>
  <div class="result-wrap" :class="{ 'result-wolves': winner === 'WEREWOLF' }">
    <div class="result-card">
      <div class="outcome-icon">{{ outcomeIcon }}</div>
      <h1 class="outcome-title serif">{{ outcomeTitle }}</h1>
      <p class="outcome-sub">{{ outcomeSub }}</p>
      <p class="outcome-desc">{{ outcomeDesc }}</p>

      <!-- Role reveal section -->
      <div v-if="gameStore.state?.players?.length" class="divider-label">
        <span>身份揭露 · ROLES REVEALED</span>
      </div>
      <div v-if="gameStore.state?.players?.length" class="role-pills">
        <div
          v-for="player in gameStore.state.players"
          :key="player.userId"
          class="role-pill"
          :class="rolePillClass(player.role)"
        >
          <span class="pill-avatar">{{ roleIcon(player.role) }}</span>
          <span class="pill-name">{{ displayName(player) }}</span>
          <span class="pill-sep">—</span>
          <span class="pill-role">{{ roleZh(player.role) }}</span>
        </div>
      </div>

      <button class="btn btn-success play-again-btn" @click="goLobby">再来一局 / Play Again</button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useGameStore } from '@/stores/gameStore'
import { useUserStore } from '@/stores/userStore'
import { gameService } from '@/services/gameService'

const route = useRoute()
const router = useRouter()
const gameStore = useGameStore()
const userStore = useUserStore()

onMounted(async () => {
  if (!gameStore.state?.winner) {
    const gameId = Number(route.params.gameId)
    if (gameId) {
      const state = await gameService.getState(gameId.toString())
      gameStore.setState(state)
    }
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

const ROLE_ICON: Record<string, string> = {
  WEREWOLF: '🐺',
  VILLAGER: '👤',
  SEER: '🔮',
  WITCH: '🌿',
  HUNTER: '🏹',
  GUARD: '🛡',
  IDIOT: '🃏',
}

function roleIcon(role?: string): string {
  return role ? (ROLE_ICON[role] ?? '❓') : '❓'
}

function displayName(player: { userId: string; nickname: string }): string {
  return player.userId === userStore.userId ? '我' : player.nickname
}

function roleZh(role?: string): string {
  return role ? (ROLE_ZH[role] ?? role) : '?'
}

function rolePillClass(role?: string): string {
  if (!role) return 'rp-default'
  if (role === 'WEREWOLF') return 'rp-wolf'
  if (role === 'GUARD') return 'rp-guard'
  if (role === 'HUNTER') return 'rp-hunter'
  if (['SEER', 'WITCH', 'IDIOT'].includes(role)) return 'rp-special'
  return 'rp-default'
}

const outcomeIcon = computed(() => {
  if (!winner.value) return '🎲'
  return winner.value === 'WEREWOLF' ? '🌕' : '🌅'
})

const outcomeTitle = computed(() => {
  if (!winner.value) return '游戏结束'
  return winner.value === 'WEREWOLF' ? '狼人胜利' : '村民胜利'
})

const outcomeSub = computed(() => {
  if (!winner.value) return ''
  return winner.value === 'WEREWOLF' ? 'Wolves Win' : 'Village Wins'
})

const outcomeDesc = computed(() => {
  if (!winner.value) return ''
  return winner.value === 'WEREWOLF'
    ? '狼人数量超过村民 / Wolves outnumber the village'
    : '所有狼人已被消灭 / All werewolves eliminated'
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

.outcome-icon {
  font-size: 3rem;
  margin-bottom: 0.5rem;
}

.outcome-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 2rem;
  color: var(--ink);
  margin: 0 0 0.25rem;
}

.result-wolves .outcome-title {
  color: var(--red);
}

.outcome-sub {
  font-size: 1rem;
  font-weight: 600;
  color: var(--green);
  margin: 0 0 0.25rem;
}

.result-wolves .outcome-sub {
  color: var(--red);
}

.outcome-desc {
  font-size: 0.8rem;
  color: var(--muted);
  margin: 0 0 1.25rem;
}

.result-wolves .outcome-desc {
  color: rgba(245, 240, 232, 0.6);
}

.divider-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.875rem;
  font-size: 0.7rem;
  letter-spacing: 0.05em;
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

.role-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  justify-content: center;
  margin-bottom: 1.5rem;
}

.role-pill {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.3rem 0.625rem;
  border-radius: 2rem;
  font-size: 0.75rem;
  border: 1px solid;
}

.rp-wolf {
  background: rgba(181, 37, 26, 0.1);
  border-color: var(--red);
  color: var(--red);
}

.result-wolves .rp-wolf {
  background: rgba(181, 37, 26, 0.25);
}

.rp-special {
  background: rgba(160, 120, 48, 0.1);
  border-color: var(--gold);
  color: var(--gold);
}

.result-wolves .rp-special {
  background: rgba(160, 120, 48, 0.2);
}

.rp-guard {
  background: rgba(59, 130, 246, 0.1);
  border-color: #3b82f6;
  color: #3b82f6;
}

.result-wolves .rp-guard {
  background: rgba(59, 130, 246, 0.2);
}

.rp-hunter {
  background: rgba(120, 80, 40, 0.1);
  border-color: #7c5230;
  color: #7c5230;
}

.result-wolves .rp-hunter {
  background: rgba(180, 130, 80, 0.2);
  border-color: #b48250;
  color: #b48250;
}

.rp-default {
  background: transparent;
  border-color: var(--border);
  color: var(--muted);
}

.result-wolves .rp-default {
  border-color: rgba(255, 255, 255, 0.2);
  color: rgba(245, 240, 232, 0.6);
}

.pill-sep {
  opacity: 0.5;
}

.play-again-btn {
  margin-top: 0;
  width: 100%;
}
</style>
