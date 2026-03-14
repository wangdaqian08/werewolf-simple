<template>
  <div class="day-wrap">
    <!-- Header: pill badge left | large timer right -->
    <header class="day-header">
      <div class="day-pill">第 {{ dayPhase.dayNumber }} 天 · Day {{ dayPhase.dayNumber }}</div>
      <div class="day-timer">{{ formattedTime }}</div>
    </header>

    <!-- Sun arc -->
    <SunArc :time-remaining="dayPhase.timeRemaining" :total-time="dayPhase.totalTime" />

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
          <div class="banner-title-red">
            昨晚 {{ dayPhase.nightResult.killedSeatIndex }}号 ·
            {{ dayPhase.nightResult.killedNickname }} 被狼人杀害
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
          <div class="banner-title-red">
            昨晚 {{ dayPhase.nightResult.killedSeatIndex }}号 ·
            {{ dayPhase.nightResult.killedNickname }} 被狼人杀害
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
          <div class="dead-overlay">✕</div>
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
import { computed } from 'vue'
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

const formattedTime = computed(() => {
  const t = props.dayPhase.timeRemaining
  const m = Math.floor(t / 60)
  const s = t % 60
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

/* Header */
.day-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 1.25rem 0.25rem;
}

.day-pill {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 0.375rem;
  padding: 0.3125rem 0.75rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--muted);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.day-timer {
  font-family: 'Noto Serif SC', serif;
  font-size: 2rem;
  font-weight: 700;
  color: var(--text);
  line-height: 1;
}

/* Banner area — fixed height so grid doesn't shift */
.banner-area {
  min-height: 2.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding: 0 1rem;
  justify-content: center;
}

/* Banners */
.banner {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
}

.banner-info {
  background: rgba(138, 122, 101, 0.08);
  border: 1px solid var(--border-l);
}

.banner-kill {
  background: rgba(181, 37, 26, 0.06);
  border-left: 3px solid var(--red);
  border-radius: 0 0.375rem 0.375rem 0;
}

.banner-icon {
  font-size: 1rem;
  color: var(--muted);
  flex-shrink: 0;
}

.banner-avatar {
  font-size: 1.25rem;
  flex-shrink: 0;
}

.banner-title {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--text);
}

.banner-title-red {
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--red);
}

.banner-sub {
  font-size: 0.625rem;
  color: var(--muted);
  margin-top: 0.125rem;
}

/* Player grid: 4 columns, room-mode cards */
.player-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  column-gap: 0.5rem;
  row-gap: 0.375rem;
  align-content: start;
  padding: 0.375rem 0.875rem;
  flex: 1;
}

.dead-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.25rem;
  color: var(--muted);
  background: rgba(255, 255, 255, 0.5);
  border-radius: inherit;
}

/* Footer */
.day-footer {
  padding: 0.75rem 1rem 1.5rem;
  border-top: 1px solid var(--border-l);
  background: var(--paper);
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.footer-hint {
  text-align: center;
  color: var(--muted);
  font-size: 0.75rem;
  margin: 0;
  padding: 0.375rem 0;
}

.footer-hint-sm {
  text-align: center;
  color: var(--muted);
  font-size: 0.6875rem;
  margin: 0;
}

.vote-actions {
  display: flex;
  gap: 0.5rem;
}

.vote-btn {
  flex: 1;
}

.skip-btn {
  flex: 0 0 4.5rem;
}
</style>
