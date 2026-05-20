<template>
  <div class="day-wrap">
    <!-- Header: pill badge left | countdown arc right -->
    <header class="day-header">
      <div class="day-pill">第 {{ dayPhase.dayNumber }} 天 · Day {{ dayPhase.dayNumber }}</div>
      <CountdownArc
        :remaining-ms="timer?.remainingMs ?? 0"
        :duration-ms="timer?.durationMs ?? 0"
        :running="timer?.running ?? false"
        :is-host="isHost"
        @start-timer="(s) => emit('start-timer', s)"
        @stop-timer="emit('stop-timer')"
      />
    </header>

    <!-- Sun arc -->
    <SunArc :phase-deadline="dayPhase.phaseDeadline" :phase-started="dayPhase.phaseStarted" />

    <!-- Below-arch row: my-role-chip on left, log-fab + Action stacked on right -->
    <div class="below-arch-row">
      <button v-if="myRole" class="my-role-chip my-role-locked" @click="showRoleCard = true">
        🔒 身份 · Tap to reveal
      </button>
      <div v-else />
      <div class="right-stack">
        <button
          v-if="dayPhase.subPhase !== 'RESULT_HIDDEN'"
          class="log-fab"
          aria-label="游戏记录"
          data-testid="log-fab"
          @click="showLog = true"
        >
          <span class="log-fab-icon" aria-hidden="true">📋</span>
          <span class="log-fab-label">游戏记录</span>
        </button>
        <ActionMenu
          v-if="myRole"
          phase="DAY_DISCUSSION"
          :sub-phase="dayPhase.subPhase"
          :my-role="myRole"
          :is-alive="isAlive"
          @self-destruct="emit('self-destruct')"
        />
      </div>
    </div>

    <!-- Fixed-height banner area — always rendered so grid position stays consistent -->
    <div class="banner-area">
      <template v-if="viewRole === 'DEAD'">
        <div class="banner banner-info" data-testid="day-banner-self-eliminated">
          <span class="banner-icon">☑</span>
          <div>
            <div class="banner-title">你在上一晚被淘汰</div>
            <div class="banner-sub">You were eliminated last night</div>
          </div>
        </div>
        <div
          v-if="dayPhase.subPhase === 'RESULT_REVEALED' && killedPlayers.length > 0"
          class="banner banner-kill"
          data-testid="day-banner-kill"
        >
          <span class="banner-avatar">💀</span>
          <div class="banner-kill-text">
            <span class="banner-kill-muted">昨晚</span>
            <template v-for="(killed, idx) in killedPlayers" :key="killed.killedPlayerId">
              <span v-if="idx > 0" class="banner-kill-muted">、</span>
              <span
                class="banner-kill-red"
                :data-testid="`day-killed-seat-${killed.killedSeatIndex}`"
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
        <div
          v-if="killedPlayers.length > 0"
          class="banner banner-kill"
          data-testid="day-banner-kill"
        >
          <span class="banner-avatar">💀</span>
          <div class="banner-kill-text">
            <span class="banner-kill-muted">昨晚</span>
            <template v-for="(killed, idx) in killedPlayers" :key="killed.killedPlayerId">
              <span v-if="idx > 0" class="banner-kill-muted">、</span>
              <span
                class="banner-kill-red"
                :data-testid="`day-killed-seat-${killed.killedSeatIndex}`"
                >{{ killed.killedSeatIndex }}号 · {{ killed.killedNickname }}</span
              >
            </template>
            <span class="banner-kill-muted">出局了</span>
          </div>
        </div>
        <div v-else class="banner banner-info" data-testid="day-banner-peaceful">
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

    <!-- Action log drawer -->
    <ActionLogDrawer :game-id="gameId" :open="showLog" @close="showLog = false" />

    <!-- Footer -->
    <footer class="day-footer">
      <template v-if="viewRole === 'HOST'">
        <div v-if="dayPhase.subPhase === 'RESULT_HIDDEN'" class="vote-actions">
          <button
            class="btn btn-primary vote-btn"
            data-testid="day-reveal-result"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('revealResult')"
          >
            显示结果 · Result
          </button>
        </div>
        <div v-else-if="dayPhase.subPhase === 'RESULT_REVEALED'" class="vote-actions">
          <!-- When a wolf self-destructed, skip voting and go to night -->
          <button
            v-if="daySkipVoting"
            class="btn btn-primary vote-btn"
            data-testid="day-enter-night"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('continueToNight')"
          >
            进入夜晚 · Night
          </button>
          <button
            v-else
            class="btn btn-gold vote-btn"
            data-testid="day-start-vote"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('startVote')"
          >
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

    <!-- Role card bottom sheet — same as VotingPhase so the my-role-chip works during DAY_DISCUSSION -->
    <Teleport to="body">
      <div v-if="showRoleCard" class="role-card-overlay" @click.self="showRoleCard = false">
        <div class="role-card-sheet">
          <div class="role-card-header">
            <span>你的身份 · My Role</span>
            <button class="history-close" @click="showRoleCard = false">✕</button>
          </div>
          <div v-if="myRole && ROLE_META[myRole]" class="role-card-body">
            <div class="rc-emoji">{{ ROLE_META[myRole]?.emoji }}</div>
            <div class="rc-name-zh">{{ ROLE_META[myRole]?.nameZh }}</div>
            <div class="rc-label" :class="`rc-label-${ROLE_META[myRole]?.team}`">
              {{ ROLE_META[myRole]?.nameEn }}
            </div>
            <p class="rc-desc">{{ ROLE_META[myRole]?.description }}</p>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'
import type { DayPhaseState, GamePlayer, PlayerRole, TimerState } from '@/types'
import PlayerSlot from '@/components/PlayerSlot.vue'
import SunArc from '@/components/SunArc.vue'
import ActionLogDrawer from '@/components/ActionLogDrawer.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import CountdownArc from '@/components/CountdownArc.vue'

const props = defineProps<{
  gameId: number
  dayPhase: DayPhaseState
  players: GamePlayer[]
  myUserId: string
  isHost: boolean
  timer?: TimerState | null
  myRole?: PlayerRole
  isAlive?: boolean
  daySkipVoting?: boolean
  actionPending?: boolean
}>()

const showLog = ref(false)
const showRoleCard = ref(false)

interface RoleMeta {
  nameZh: string
  nameEn: string
  emoji: string
  team: string
  description: string
}
const ROLE_META: Record<string, RoleMeta> = {
  WEREWOLF: {
    nameZh: '狼人',
    nameEn: 'WEREWOLF',
    emoji: '🐺',
    team: 'wolf',
    description: '每晚与狼队商议，袭击一名村民。',
  },
  VILLAGER: {
    nameZh: '村民',
    nameEn: 'VILLAGER',
    emoji: '🌾',
    team: 'village',
    description: '通过讨论和投票找出狼人，保护村庄。',
  },
  SEER: {
    nameZh: '预言家',
    nameEn: 'SEER',
    emoji: '🔭',
    team: 'special',
    description: '每晚可查验一名玩家，得知其是否为狼人。',
  },
  WITCH: {
    nameZh: '女巫',
    nameEn: 'WITCH',
    emoji: '🔮',
    team: 'special',
    description: '拥有一瓶解药和一瓶毒药，各可使用一次。',
  },
  HUNTER: {
    nameZh: '猎人',
    nameEn: 'HUNTER',
    emoji: '🏹',
    team: 'special',
    description: '死亡时可开枪带走一名玩家。',
  },
  GUARD: {
    nameZh: '守卫',
    nameEn: 'GUARD',
    emoji: '🛡️',
    team: 'special',
    description: '每晚保护一名玩家免受狼人袭击。',
  },
  IDIOT: {
    nameZh: '白痴',
    nameEn: 'IDIOT',
    emoji: '🃏',
    team: 'special',
    description: '被投票驱逐时揭示身份，免于出局但失去投票权。',
  },
}

const emit = defineEmits<{
  revealResult: []
  startVote: []
  vote: [targetId: string]
  skip: []
  selectPlayer: [userId: string]
  'self-destruct': []
  continueToNight: []
  'start-timer': [seconds: number]
  'stop-timer': []
}>()

type ViewRole = 'HOST' | 'DEAD' | 'ALIVE' | 'GUEST'

const viewRole = computed<ViewRole>(() => {
  if (props.isHost) return 'HOST'
  const me = props.players.find((p) => p.userId === props.myUserId)
  if (!me) return 'GUEST'
  if (!me.isAlive) return 'DEAD'
  return 'ALIVE'
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
  grid-template-columns: repeat(auto-fit, minmax(min(85px, 47%), 1fr));
  gap: 0.5rem;
  padding: 0 1rem 1rem;
}

.below-arch-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 0 1rem 0.5rem;
}

.right-stack {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}

.log-fab {
  width: auto;
  padding: 6px 12px;
  gap: 6px;
  border-radius: 999px;
  background: var(--paper, #f5f0e8);
  border: 1px solid var(--border, #ccc2b0);
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}
.log-fab-icon {
  font-size: 16px;
}
.log-fab-label {
  font-size: 13px;
  color: var(--text, #1a140c);
  font-weight: 500;
}

.my-role-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 5px 10px;
  background: var(--paper, #f5f0e8);
  border: 1px solid var(--border, #ccc2b0);
  border-radius: 999px;
  font-size: 12px;
  color: var(--muted, #8a7a65);
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.my-role-locked {
  font-style: italic;
}

/* Role card bottom sheet — mirrors VotingPhase.vue so the my-role-chip works during DAY_DISCUSSION */
.role-card-overlay {
  position: fixed;
  inset: 0;
  background: rgba(26, 20, 12, 0.55);
  z-index: 201;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
  touch-action: none;
}
.role-card-sheet {
  width: 100%;
  max-width: 320px;
  background: var(--paper);
  border-radius: 1rem;
  overflow: hidden;
  box-shadow: 0 8px 32px rgba(26, 20, 12, 0.3);
}
.role-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.875rem 1rem 0.75rem;
  border-bottom: 1px solid var(--border-l);
  font-family: 'Noto Serif SC', serif;
  font-size: 0.9375rem;
  font-weight: 600;
}
.history-close {
  background: transparent;
  border: none;
  font-size: 1.125rem;
  color: var(--muted);
  cursor: pointer;
  padding: 0.25rem 0.5rem;
}
.role-card-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 1.75rem 1.5rem 2rem;
}
.rc-emoji {
  font-size: 3.5rem;
  line-height: 1;
  margin-bottom: 0.5rem;
}
.rc-name-zh {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text);
}
.rc-label {
  font-size: 0.625rem;
  letter-spacing: 0.2em;
  font-weight: 600;
  margin-bottom: 0.5rem;
}
.rc-label-wolf {
  color: var(--red);
}
.rc-label-village {
  color: var(--green);
}
.rc-label-special {
  color: var(--gold);
}
.rc-desc {
  font-size: 0.8125rem;
  color: var(--muted);
  line-height: 1.6;
  text-align: center;
  margin: 0;
}
</style>
