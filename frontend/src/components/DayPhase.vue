<template>
  <div class="day-wrap">
    <!-- Header: pill badge left | large timer right -->
    <header class="day-header">
      <div class="day-pill">第 {{ dayPhase.dayNumber }} 天 · Day {{ dayPhase.dayNumber }}</div>
      <div class="day-timer">{{ formattedTime }}</div>
    </header>

    <!-- Sun arc -->
    <SunArc :phase-deadline="dayPhase.phaseDeadline" :phase-started="dayPhase.phaseStarted" />

    <!-- Fixed-height banner area — always rendered so grid position stays consistent -->
    <div class="banner-area">
      <template v-if="viewRole === 'DEAD'">
        <div class="banner banner-info">
          <span class="banner-icon">☑</span>
          <div>
            <div class="banner-title">你在上一晚被淘汰</div>
            <div class="banner-sub">You were eliminated last night</div>
          </div>
        </div>
        <div
          v-if="dayPhase.subPhase === 'RESULT_REVEALED' && dayPhase.nightResult"
          class="banner banner-kill"
        >
          <span class="banner-avatar">{{ dayPhase.nightResult.killedAvatar ?? '💀' }}</span>
          <div class="banner-kill-text">
            <span class="banner-kill-muted">昨晚</span>
            <span class="banner-kill-red"
              >{{ dayPhase.nightResult.killedSeatIndex }}号 ·
              {{ dayPhase.nightResult.killedNickname }}</span
            >
            <span class="banner-kill-muted">被狼人杀害</span>
          </div>
        </div>
      </template>

      <template
        v-else-if="
          (viewRole === 'ALIVE' || viewRole === 'HOST') &&
          dayPhase.subPhase === 'RESULT_REVEALED' &&
          dayPhase.nightResult
        "
      >
        <div class="banner banner-kill">
          <span class="banner-avatar">{{ dayPhase.nightResult.killedAvatar ?? '💀' }}</span>
          <div class="banner-kill-text">
            <span class="banner-kill-muted">昨晚</span>
            <span class="banner-kill-red"
              >{{ dayPhase.nightResult.killedSeatIndex }}号 ·
              {{ dayPhase.nightResult.killedNickname }}</span
            >
            <span class="banner-kill-muted">被狼人杀害</span>
          </div>
        </div>
      </template>
    </div>

    <!-- Player grid: 4 columns, room-mode compact cards -->
    <section class="player-grid">
      <PlayerSlot
        v-for="player in players"
        :key="player.userId"
        :seat="player.seatIndex"
        :nickname="player.nickname"
        :avatar="player.avatar"
        :variant="slotVariant(player)"
        mode="room"
        @click="onTap(player)"
      >
        <template v-if="!player.isAlive || isKilledAndVisible(player)" #overlay>
          <div class="slot-overlay dead-overlay">✕</div>
        </template>
      </PlayerSlot>
    </section>

    <!-- Footer -->
    <footer class="day-footer">
      <template v-if="viewRole === 'HOST'">
        <button
          v-if="dayPhase.subPhase === 'RESULT_HIDDEN'"
          class="btn btn-primary"
          @click="emit('revealResult')"
        >
          显示结果 · Result
        </button>
        <template v-else>
          <div class="vote-actions">
            <button
              class="btn btn-primary vote-btn"
              :disabled="!dayPhase.selectedPlayerId"
              @click="emit('vote')"
            >
              投票 · Vote
            </button>
            <button class="btn btn-secondary skip-btn" @click="emit('skip')">弃权</button>
          </div>
        </template>
      </template>

      <template v-else-if="viewRole === 'DEAD'">
        <button class="btn btn-secondary" disabled>投票已禁用 · Voting disabled</button>
      </template>

      <template v-else-if="viewRole === 'ALIVE'">
        <template v-if="dayPhase.subPhase === 'RESULT_HIDDEN'">
          <p class="footer-hint">等待房主公布结果 · Waiting for host to reveal the result</p>
        </template>
        <template v-else-if="!dayPhase.canVote">
          <button class="btn btn-secondary" disabled>投票已禁用 · Voting disabled</button>
        </template>
        <template v-else>
          <p class="footer-hint-sm">点击后更新信息将显示 · Tap to select</p>
          <div class="vote-actions">
            <button
              class="btn btn-primary vote-btn"
              :disabled="!dayPhase.selectedPlayerId"
              @click="emit('vote')"
            >
              投票 · Vote
            </button>
            <button class="btn btn-secondary skip-btn" @click="emit('skip')">弃权</button>
          </div>
        </template>
      </template>

      <template v-else>
        <p class="footer-hint">等待房主公布结果 · Waiting for host to reveal the result</p>
      </template>
    </footer>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { DayPhaseState, GamePlayer } from '@/types'
import PlayerSlot from '@/components/PlayerSlot.vue'
import SunArc from '@/components/SunArc.vue'

const props = defineProps<{
  dayPhase: DayPhaseState
  players: GamePlayer[]
  myUserId: string
  isHost: boolean
}>()

const emit = defineEmits<{
  revealResult: []
  vote: []
  skip: []
  selectPlayer: [userId: string]
}>()

type ViewRole = 'HOST' | 'DEAD' | 'ALIVE' | 'GUEST'

const viewRole = computed<ViewRole>(() => {
  if (props.isHost) return 'HOST'
  const me = props.players.find((p) => p.userId === props.myUserId)
  if (!me) return 'GUEST'
  if (!me.isAlive) return 'DEAD'
  return 'ALIVE'
})

const now = ref(Date.now())
let intervalId = 0

onMounted(() => {
  intervalId = window.setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  clearInterval(intervalId)
})

const formattedTime = computed(() => {
  const remaining = Math.max(0, props.dayPhase.phaseDeadline - now.value) / 1000
  const m = Math.floor(remaining / 60)
  const s = Math.floor(remaining % 60)
  return `${m}:${String(s).padStart(2, '0')}`
})

const killedId = computed(() => props.dayPhase.nightResult?.killedPlayerId)

function isKilledAndVisible(player: GamePlayer) {
  return (
    killedId.value === player.userId &&
    (props.isHost || props.dayPhase.subPhase === 'RESULT_REVEALED')
  )
}

function slotVariant(player: GamePlayer) {
  if (isKilledAndVisible(player)) return 'killed' as const
  if (!player.isAlive) return 'dead' as const
  if (player.userId === props.dayPhase.selectedPlayerId) return 'selected' as const
  if (player.userId === props.myUserId) return 'me' as const
  return 'alive' as const
}

function onTap(player: GamePlayer) {
  if (viewRole.value !== 'ALIVE' && viewRole.value !== 'HOST') return
  if (props.dayPhase.subPhase !== 'RESULT_REVEALED') return
  if (!player.isAlive) return
  emit('selectPlayer', player.userId)
}
</script>

<style scoped>
.day-wrap {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: var(--bg);
}

.banner-info {
  background: rgba(138, 122, 101, 0.08);
  border: 1px solid var(--border-l);
}

.banner-icon {
  font-size: 1rem;
  color: var(--muted);
  flex-shrink: 0;
}

.banner-kill-text {
  font-size: 0.75rem;
  font-weight: 500;
  display: flex;
  gap: 0.25rem;
  flex-wrap: wrap;
  align-items: baseline;
}

.banner-kill-muted {
  color: var(--muted);
}

.banner-kill-red {
  color: var(--red);
  font-weight: 600;
}

.footer-hint-sm {
  text-align: center;
  color: var(--muted);
  font-size: 0.6875rem;
  margin: 0;
}
</style>
