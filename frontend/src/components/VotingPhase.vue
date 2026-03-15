<template>
  <div class="voting-wrap">
    <!-- ── VOTING screen (includes VOTE_RESULT — merged) ── -->
    <template v-if="isVotingScreen">
      <header class="day-header">
        <div class="day-pill">第 {{ votingPhase.dayNumber }} 天 · Day {{ votingPhase.dayNumber }}</div>
        <div class="day-timer">{{ formattedTime }}</div>
      </header>

      <SunArc :phase-deadline="votingPhase.phaseDeadline" :phase-started="votingPhase.phaseStarted" />

      <!-- Tally / vote-count row -->
      <div class="tally-row">
        <template v-if="!isRevealed">
          <div class="tally-chip tally-chip-count">
            <span class="tally-votes">{{ votingPhase.votesSubmitted ?? 0 }}</span>
            <span class="tally-sep">/</span>
            <span class="tally-total">{{ votingPhase.totalVoters ?? '?' }}</span>
            <span class="tally-label">已投票 · voted</span>
          </div>
        </template>
        <template v-else>
          <div
            v-for="(entry, i) in sortedTally"
            :key="entry.playerId"
            class="tally-chip"
            :class="{ 'tally-chip-top': i === 0 }"
          >
            <span class="tally-votes">{{ entry.votes }}票</span>
            <span class="tally-dot">·</span>
            <span class="tally-name">{{ entry.nickname }}</span>
          </div>
        </template>
      </div>

      <!-- Eliminated player card — shown after tally revealed -->
      <div
        v-if="isRevealed && votingPhase.eliminatedPlayerId"
        class="elim-banner"
      >
        <span class="elim-banner-avatar">{{ votingPhase.eliminatedAvatar ?? '💀' }}</span>
        <div class="elim-banner-body">
          <span class="elim-banner-tag">出局 · ELIMINATED</span>
          <span class="elim-banner-name">
            {{ votingPhase.eliminatedNickname }} · 座位 {{ votingPhase.eliminatedSeatIndex }}
          </span>
          <span v-if="votingPhase.eliminatedRole" class="elim-banner-role">
            {{ roleDisplay(votingPhase.eliminatedRole) }}
          </span>
        </div>
      </div>

      <!-- Player grid -->
      <section class="player-grid">
        <PlayerSlot
          v-for="player in players"
          :key="player.userId"
          :seat="player.seatIndex"
          :nickname="player.nickname"
          :avatar="player.avatar"
          :variant="votingSlotVariant(player)"
          mode="room"
          @click="onVotingTap(player)"
        >
          <template v-if="!player.isAlive" #overlay>
            <div class="slot-overlay dead-overlay">✕</div>
          </template>
        </PlayerSlot>
      </section>

      <!-- Footer -->
      <footer class="voting-footer">
        <!-- After reveal: host has countdown + continue; others wait -->
        <template v-if="isRevealed">
          <template v-if="isHost">
            <div class="reveal-countdown">
              <span class="reveal-countdown-label">自动继续 · Auto in</span>
              <span class="reveal-countdown-time">{{ formattedRevealTime }}</span>
            </div>
            <button class="btn btn-primary" @click="emit('continueVoting')">继续 / Continue</button>
          </template>
          <template v-else>
            <p class="footer-hint">等待房主继续 · Waiting for host…</p>
          </template>
        </template>

        <!-- Before reveal -->
        <template v-else>
          <!-- HOST: own vote (if alive) + reveal button -->
          <template v-if="viewRole === 'HOST'">
            <template v-if="hostIsAlive">
              <template v-if="votingPhase.myVote || votingPhase.myVoteSkipped">
                <button class="btn btn-secondary" @click="emit('unvote')">
                  取消投票 · Unvote
                </button>
              </template>
              <template v-else>
                <div class="vote-actions">
                  <button
                    class="btn btn-primary vote-btn"
                    :disabled="!effectiveSelected"
                    @click="emit('vote')"
                  >
                    投票 · Vote
                  </button>
                  <button class="btn btn-secondary skip-btn" @click="emit('skipVote')">弃权</button>
                </div>
              </template>
            </template>
            <button
              class="btn btn-gold reveal-btn"
              :disabled="!allVotesIn"
              @click="emit('revealVoting')"
            >
              公布结果 · Reveal
            </button>
          </template>

          <!-- DEAD: voting disabled -->
          <template v-else-if="viewRole === 'DEAD'">
            <button class="btn btn-secondary" disabled>投票已禁用 · Voting disabled</button>
          </template>

          <!-- ALIVE: vote/skip or unvote -->
          <template v-else-if="viewRole === 'ALIVE'">
            <template v-if="votingPhase.myVote || votingPhase.myVoteSkipped">
              <button class="btn btn-secondary" @click="emit('unvote')">
                取消投票 · Unvote
              </button>
            </template>
            <template v-else>
              <div class="vote-actions">
                <button
                  class="btn btn-primary vote-btn"
                  :disabled="!effectiveSelected"
                  @click="emit('vote')"
                >
                  投票 · Vote
                </button>
                <button class="btn btn-secondary skip-btn" @click="emit('skipVote')">弃权</button>
              </div>
            </template>
          </template>

          <!-- GUEST: spectator -->
          <template v-else>
            <p class="footer-hint">等待投票结束 · Waiting…</p>
          </template>
        </template>
      </footer>
    </template>

    <!-- ── HUNTER_SHOOT screen ── -->
    <template v-else-if="votingPhase.subPhase === 'HUNTER_SHOOT'">
      <header class="day-header">
        <div class="day-pill">第 {{ votingPhase.dayNumber }} 天 · Day {{ votingPhase.dayNumber }}</div>
        <div class="day-timer">{{ formattedTime }}</div>
      </header>

      <div class="banner-area">
        <div class="banner banner-kill">
          <span class="banner-avatar">🔫</span>
          <div>
            <div class="banner-title">猎人出局 · Hunter Eliminated</div>
            <div class="banner-sub">你可以选择射杀一名玩家 — may fire one shot</div>
          </div>
        </div>
      </div>

      <section class="player-grid">
        <PlayerSlot
          v-for="player in players"
          :key="player.userId"
          :seat="player.seatIndex"
          :nickname="player.nickname"
          :avatar="player.avatar"
          :variant="shootSlotVariant(player)"
          mode="room"
          @click="onHunterTap(player)"
        >
          <template
            v-if="!player.isAlive || player.userId === votingPhase.eliminatedPlayerId"
            #overlay
          >
            <div class="slot-overlay dead-overlay">✕</div>
          </template>
        </PlayerSlot>
      </section>

      <footer class="voting-footer">
        <div class="vote-actions">
          <button
            class="btn btn-danger vote-btn"
            :disabled="!effectiveSelected"
            @click="effectiveSelected && emit('hunterShoot', effectiveSelected)"
          >
            开枪 · Shoot
          </button>
          <button class="btn btn-secondary skip-btn" @click="emit('hunterPass')">放弃 · Pass</button>
        </div>
      </footer>
    </template>

    <!-- ── BADGE_HANDOVER screen (includes BADGE_RECEIVED — merged) ── -->
    <template v-else-if="isBadgeScreen">
      <header class="day-header">
        <div class="day-pill">第 {{ votingPhase.dayNumber }} 天 · Day {{ votingPhase.dayNumber }}</div>
        <div class="day-timer">{{ formattedTime }}</div>
      </header>

      <!-- Destroyed message replaces banner when badge is burned -->
      <div v-if="votingPhase.badgeDestroyed" class="badge-status-msg badge-status-burned">
        <span class="badge-status-icon">⚔️</span>
        <span>警徽已销毁 · Badge Destroyed</span>
      </div>
      <!-- Passed message when badge is given -->
      <div v-else-if="votingPhase.newSheriffId" class="badge-status-msg badge-status-passed">
        <span class="badge-status-icon">⭐</span>
        <span>警徽已移交给 {{ votingPhase.newSheriffNickname }} · Badge Passed</span>
      </div>
      <!-- Default banner when choosing -->
      <div v-else class="banner-area">
        <div class="banner banner-gold">
          <span class="banner-avatar">⭐</span>
          <div>
            <div class="banner-title">你已出局 · Eliminated</div>
            <div class="banner-sub">选择警徽继承人 / Choose badge heir</div>
          </div>
        </div>
      </div>

      <section class="player-grid">
        <PlayerSlot
          v-for="player in players"
          :key="player.userId"
          :seat="player.seatIndex"
          :nickname="player.nickname"
          :avatar="player.avatar"
          :variant="badgeSlotVariant(player)"
          mode="room"
          @click="onBadgeTap(player)"
        >
          <!-- Star on new sheriff's card -->
          <template v-if="player.userId === votingPhase.newSheriffId" #badge>
            <span class="sheriff-pin">⭐</span>
          </template>
          <template
            v-if="!player.isAlive || player.userId === votingPhase.eliminatedPlayerId"
            #overlay
          >
            <div class="slot-overlay dead-overlay">✕</div>
          </template>
        </PlayerSlot>
      </section>

      <footer class="voting-footer">
        <!-- After badge action (passed or destroyed): host can continue to night -->
        <template v-if="badgeDone">
          <template v-if="isHost">
            <button class="btn btn-primary" @click="emit('continueVoting')">
              → 进入夜晚 / Night
            </button>
          </template>
          <template v-else>
            <p class="footer-hint">等待继续 · Waiting…</p>
          </template>
        </template>
        <!-- Choosing heir -->
        <template v-else>
          <div class="vote-actions">
            <button
              class="btn btn-gold vote-btn"
              :disabled="!effectiveSelected"
              @click="effectiveSelected && emit('passBadge', effectiveSelected)"
            >
              移交警徽 / Pass Badge
            </button>
            <button class="btn btn-secondary skip-btn" @click="emit('destroyBadge')">销毁</button>
          </div>
        </template>
      </footer>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type { GamePlayer, PlayerRole, VotingState } from '@/types'
import PlayerSlot from '@/components/PlayerSlot.vue'
import SunArc from '@/components/SunArc.vue'

const props = defineProps<{
  votingPhase: VotingState
  players: GamePlayer[]
  myUserId: string
  isHost: boolean
}>()

const emit = defineEmits<{
  selectPlayer: [userId: string]
  vote: []
  skipVote: []
  unvote: []
  revealVoting: []
  continueVoting: []
  hunterShoot: [userId: string]
  hunterPass: []
  passBadge: [userId: string]
  destroyBadge: []
}>()

// ── Screen grouping ───────────────────────────────────────────────────────────
// VOTE_RESULT is merged into the VOTING screen (shown when tallyRevealed)
const isVotingScreen = computed(
  () => props.votingPhase.subPhase === 'VOTING' || props.votingPhase.subPhase === 'VOTE_RESULT',
)
// BADGE_RECEIVED is merged into the BADGE_HANDOVER screen
const isBadgeScreen = computed(
  () =>
    props.votingPhase.subPhase === 'BADGE_HANDOVER' ||
    props.votingPhase.subPhase === 'BADGE_RECEIVED',
)

// Tally/result is revealed when tallyRevealed flag is set or we're on VOTE_RESULT sub-phase
const isRevealed = computed(
  () => props.votingPhase.tallyRevealed || props.votingPhase.subPhase === 'VOTE_RESULT',
)

// Badge screen: post-action states
const badgeDone = computed(
  () => !!(props.votingPhase.newSheriffId || props.votingPhase.badgeDestroyed),
)

// ── View role ─────────────────────────────────────────────────────────────────
type ViewRole = 'HOST' | 'DEAD' | 'ALIVE' | 'GUEST'

const viewRole = computed<ViewRole>(() => {
  if (props.isHost) return 'HOST'
  const me = props.players.find((p) => p.userId === props.myUserId)
  if (!me) return 'GUEST'
  if (!me.isAlive) return 'DEAD'
  return 'ALIVE'
})

const hostIsAlive = computed(() => {
  const me = props.players.find((p) => p.userId === props.myUserId)
  return me?.isAlive ?? false
})

const allVotesIn = computed(
  () =>
    props.votingPhase.votesSubmitted !== undefined &&
    props.votingPhase.totalVoters !== undefined &&
    props.votingPhase.votesSubmitted >= props.votingPhase.totalVoters,
)

// ── Optimistic selection ──────────────────────────────────────────────────────
const optimisticSelected = ref<string | undefined>(undefined)

watch(
  () => props.votingPhase.selectedPlayerId,
  () => {
    optimisticSelected.value = undefined
  },
)

const effectiveSelected = computed(
  () => optimisticSelected.value ?? props.votingPhase.selectedPlayerId,
)

function selectPlayer(userId: string) {
  optimisticSelected.value = userId
  emit('selectPlayer', userId)
}

// ── Timer ─────────────────────────────────────────────────────────────────────
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
  const remaining = Math.max(0, props.votingPhase.phaseDeadline - now.value) / 1000
  const m = Math.floor(remaining / 60)
  const s = Math.floor(remaining % 60)
  return `${m}:${String(s).padStart(2, '0')}`
})

const formattedRevealTime = computed(() => {
  if (!props.votingPhase.revealDeadline) return '0:30'
  const remaining = Math.max(0, props.votingPhase.revealDeadline - now.value) / 1000
  const m = Math.floor(remaining / 60)
  const s = Math.floor(remaining % 60)
  return `${m}:${String(s).padStart(2, '0')}`
})

// ── Sorted tally ──────────────────────────────────────────────────────────────
const sortedTally = computed(() =>
  [...(props.votingPhase.tally ?? [])].sort((a, b) => b.votes - a.votes),
)

// ── Role display ──────────────────────────────────────────────────────────────
const ROLE_DISPLAY: Record<PlayerRole, string> = {
  WEREWOLF: '🐺 狼人',
  VILLAGER: '👤 村民',
  SEER: '🔮 预言家',
  WITCH: '🧪 女巫',
  HUNTER: '🔫 猎人',
  GUARD: '🛡 守卫',
  IDIOT: '🃏 白痴',
}

function roleDisplay(role: PlayerRole) {
  return ROLE_DISPLAY[role] ?? role
}

// ── Slot variants ─────────────────────────────────────────────────────────────
function votingSlotVariant(player: GamePlayer) {
  if (!player.isAlive) return 'dead' as const
  const myVoted = !!(props.votingPhase.myVote || props.votingPhase.myVoteSkipped)
  // Only show gold selection before the player has submitted their vote
  if (!myVoted && player.userId === effectiveSelected.value) return 'selected' as const
  const hasVoted = props.votingPhase.votedPlayerIds?.includes(player.userId)
  if (hasVoted) {
    return player.userId === props.myUserId ? ('me-ready' as const) : ('ready' as const)
  }
  if (player.userId === props.myUserId) return 'me' as const
  return 'alive' as const
}

function shootSlotVariant(player: GamePlayer) {
  if (!player.isAlive || player.userId === props.votingPhase.eliminatedPlayerId)
    return 'dead' as const
  if (player.userId === effectiveSelected.value) return 'selected' as const
  if (player.userId === props.myUserId) return 'me' as const
  return 'alive' as const
}

function badgeSlotVariant(player: GamePlayer) {
  if (!player.isAlive || player.userId === props.votingPhase.eliminatedPlayerId)
    return 'dead' as const
  // New sheriff gets green styling
  if (player.userId === props.votingPhase.newSheriffId) {
    return player.userId === props.myUserId ? ('me-ready' as const) : ('ready' as const)
  }
  if (player.userId === effectiveSelected.value) return 'selected' as const
  if (player.userId === props.myUserId) return 'me' as const
  return 'alive' as const
}

// ── Tap handlers ──────────────────────────────────────────────────────────────
function onVotingTap(player: GamePlayer) {
  if (!player.isAlive) return
  if (!props.votingPhase.canVote) return
  selectPlayer(player.userId)
}

function onHunterTap(player: GamePlayer) {
  if (!player.isAlive) return
  if (player.userId === props.votingPhase.eliminatedPlayerId) return
  selectPlayer(player.userId)
}

function onBadgeTap(player: GamePlayer) {
  if (badgeDone.value) return // badge already actioned
  if (!player.isAlive) return
  if (player.userId === props.votingPhase.eliminatedPlayerId) return
  selectPlayer(player.userId)
}
</script>

<style scoped>
.voting-wrap {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: var(--bg);
}

/* Header — same as DayPhase */
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

/* Tally row */
.tally-row {
  display: flex;
  gap: 0.375rem;
  padding: 0.375rem 1rem;
  overflow-x: auto;
  min-height: 2rem;
  scrollbar-width: none;
}

.tally-row::-webkit-scrollbar {
  display: none;
}

.tally-chip {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  background: #fff;
  border: 1px solid var(--border-l);
  border-radius: 1rem;
  padding: 0.25rem 0.625rem;
  font-size: 0.75rem;
  color: var(--muted);
  white-space: nowrap;
  flex-shrink: 0;
}

.tally-chip-count {
  gap: 0.375rem;
}

.tally-chip-top {
  background: rgba(181, 37, 26, 0.06);
  border-color: var(--red);
  color: var(--red);
  font-weight: 600;
}

.tally-sep,
.tally-dot {
  color: var(--border);
}

.tally-total {
  font-weight: 600;
  color: var(--text);
}

.tally-label {
  color: var(--muted);
}

/* Eliminated banner (inline, between tally and grid) */
.elim-banner {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin: 0 1rem 0.25rem;
  padding: 0.5rem 0.75rem;
  background: rgba(181, 37, 26, 0.06);
  border-left: 3px solid var(--red);
  border-radius: 0 0.375rem 0.375rem 0;
}

.elim-banner-avatar {
  font-size: 1.5rem;
  flex-shrink: 0;
}

.elim-banner-body {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.elim-banner-tag {
  font-size: 0.625rem;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--red);
  font-weight: 600;
}

.elim-banner-name {
  font-family: 'Noto Serif SC', serif;
  font-size: 0.9375rem;
  font-weight: 700;
  color: var(--text);
}

.elim-banner-role {
  font-size: 0.75rem;
  color: var(--muted);
}

/* Badge status messages (replaces banner after action) */
.badge-status-msg {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0 1rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 500;
}

.badge-status-burned {
  background: rgba(138, 122, 101, 0.1);
  border-left: 3px solid var(--muted);
  border-radius: 0 0.375rem 0.375rem 0;
  color: var(--muted);
}

.badge-status-passed {
  background: rgba(45, 106, 63, 0.08);
  border-left: 3px solid var(--green);
  border-radius: 0 0.375rem 0.375rem 0;
  color: var(--green);
}

.badge-status-icon {
  font-size: 1rem;
  flex-shrink: 0;
}

/* Banner area — same as DayPhase */
.banner-area {
  min-height: 2.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  padding: 0 1rem;
  justify-content: center;
}

.banner {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.75rem;
  border-radius: 0.375rem;
}

.banner-kill {
  background: rgba(181, 37, 26, 0.06);
  border-left: 3px solid var(--red);
  border-radius: 0 0.375rem 0.375rem 0;
}

.banner-gold {
  background: rgba(160, 120, 48, 0.06);
  border-left: 3px solid var(--gold);
  border-radius: 0 0.375rem 0.375rem 0;
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

.banner-sub {
  font-size: 0.625rem;
  color: var(--muted);
  margin-top: 0.125rem;
}

/* Player grid */
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
  font-size: 1.25rem;
  color: var(--muted);
  background: rgba(255, 255, 255, 0.5);
}

/* Sheriff pin — absolutely positioned in top-right of card */
.sheriff-pin {
  position: absolute;
  top: 5px;
  right: 8px;
  font-size: 16px;
  line-height: 1;
}

/* Footer */
.voting-footer {
  padding: 0.75rem 1rem 2.5rem;
  border-top: 1px solid var(--border-l);
  background: var(--paper);
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
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

.reveal-btn {
  width: 100%;
}

.footer-hint {
  text-align: center;
  color: var(--muted);
  font-size: 0.75rem;
  margin: 0;
  padding: 0.375rem 0;
}

/* Reveal countdown */
.reveal-countdown {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 0.25rem 0;
}

.reveal-countdown-label {
  font-size: 0.75rem;
  color: var(--muted);
}

.reveal-countdown-time {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--gold);
}
</style>
