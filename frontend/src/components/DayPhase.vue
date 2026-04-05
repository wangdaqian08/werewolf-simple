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
          v-if="dayPhase.subPhase === 'RESULT_REVEALED' && killedPlayers.length > 0"
          class="banner banner-kill"
        >
          <span class="banner-avatar">💀</span>
          <div class="banner-kill-text">
            <span class="banner-kill-muted">昨晚</span>
            <template v-for="(killed, idx) in killedPlayers" :key="killed.killedPlayerId">
              <span v-if="idx > 0" class="banner-kill-muted">、</span>
              <span class="banner-kill-red"
                >{{ killed.killedSeatIndex }}号 · {{ killed.killedNickname }}</span
              >
            </template>
            <span class="banner-kill-muted">出局了</span>
          </div>
        </div>
      </template>

      <template
        v-else-if="
          (viewRole === 'ALIVE' || viewRole === 'HOST') && dayPhase.subPhase === 'RESULT_REVEALED'
        "
      >
        <div v-if="killedPlayers.length > 0" class="banner banner-kill">
          <span class="banner-avatar">💀</span>
          <div class="banner-kill-text">
            <span class="banner-kill-muted">昨晚</span>
            <template v-for="(killed, idx) in killedPlayers" :key="killed.killedPlayerId">
              <span v-if="idx > 0" class="banner-kill-muted">、</span>
              <span class="banner-kill-red"
                >{{ killed.killedSeatIndex }}号 · {{ killed.killedNickname }}</span
              >
            </template>
            <span class="banner-kill-muted">出局了</span>
          </div>
        </div>
        <div v-else class="banner banner-info">
          <span class="banner-icon">❤️</span>
          <div>
            <div class="banner-title">昨晚平安夜</div>
            <div class="banner-sub">Peaceful night — no one was eliminated</div>
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
        <template v-if="player.isSheriff" #badge>
          <div class="sheriff-badge">⭐</div>
        </template>
        <template v-if="!player.isAlive && dayPhase.subPhase === 'RESULT_REVEALED'" #overlay>
          <div class="slot-overlay dead-overlay">✕</div>
        </template>
      </PlayerSlot>
    </section>

    <!-- Footer -->
    <footer class="day-footer">
      <template v-if="viewRole === 'HOST'">
        <div v-if="dayPhase.subPhase === 'RESULT_HIDDEN'" class="vote-actions">
          <button class="btn btn-primary vote-btn" @click="emit('revealResult')">
            显示结果 · Result
          </button>
        </div>
        <div v-else-if="dayPhase.subPhase === 'RESULT_REVEALED'" class="vote-actions">
          <button class="btn btn-gold vote-btn" @click="emit('startVote')">
            开始投票 · Start Vote
          </button>
        </div>
      </template>

      <template v-else-if="viewRole === 'DEAD'">
        <button class="btn btn-secondary" disabled>投票已禁用 · Voting disabled</button>
      </template>

      <template v-else-if="viewRole === 'ALIVE'">
        <template v-if="dayPhase.subPhase === 'RESULT_HIDDEN'">
          <p class="footer-hint">等待房主公布结果 · Waiting for host to reveal the result</p>
        </template>
        <template v-else>
          <p class="footer-hint">等待房主开始投票 · Waiting for host to start voting</p>
        </template>
      </template>

      <template v-else>
        <p class="footer-hint">等待房主公布结果 · Waiting for host to reveal the result</p>
      </template>
    </footer>
  </div>
</template>

<script lang="ts" setup>
import {computed, onMounted, onUnmounted, ref, watch} from 'vue'
import type {DayPhaseState, GamePlayer} from '@/types'
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
  startVote: []
  vote: [targetId: string]
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

// Selection is local UI state — server is notified but not authoritative for display
const localSelected = ref<string | undefined>(props.dayPhase.selectedPlayerId)

// Reset when a new action phase begins
watch(
  () => props.dayPhase.subPhase,
  () => {
    localSelected.value = undefined
  },
)

const killedIds = computed(
  () => props.dayPhase.nightResult?.killedPlayers?.map((k) => k.killedPlayerId) ?? [],
)

const killedPlayers = computed(() => props.dayPhase.nightResult?.killedPlayers ?? [])

function isKilledAndVisible(player: GamePlayer) {
  return killedIds.value.includes(player.userId) && props.dayPhase.subPhase === 'RESULT_REVEALED'
}

function slotVariant(player: GamePlayer) {
  if (props.dayPhase.subPhase === 'RESULT_REVEALED') {
    if (isKilledAndVisible(player)) return 'killed' as const
    if (!player.isAlive) return 'dead' as const
    // In RESULT_REVEALED phase, no player should be selected (waiting for host to start vote)
    return 'alive' as const
  }
  if (props.dayPhase.subPhase === 'RESULT_HIDDEN') {
    // In RESULT_HIDDEN phase, no player should be selected (waiting for host to reveal result)
    return 'alive' as const
  }
  if (player.userId === localSelected.value) return 'selected' as const
  return 'alive' as const
}

function onTap(player: GamePlayer) {
  if (viewRole.value !== 'ALIVE') return
  if (!props.dayPhase.canVote) return
  if (!player.isAlive) return
  // Prevent selection in both RESULT_HIDDEN and RESULT_REVEALED phases
  // Players can only select after host starts the voting phase
  if (props.dayPhase.subPhase === 'RESULT_HIDDEN' || props.dayPhase.subPhase === 'RESULT_REVEALED')
    return
  localSelected.value = player.userId
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

.player-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.5rem;
  padding: 0 1rem 1rem;
}
</style>
