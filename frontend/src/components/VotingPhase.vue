<template>
  <div class="voting-wrap">
    <!-- ── VOTING screen (includes VOTE_RESULT — merged) ── -->
    <template v-if="isVotingScreen">
      <header class="day-header">
        <div class="day-pill">
          第 {{ votingPhase.dayNumber }} 天
          <span class="day-pill-sep">·</span>
          投票阶段
        </div>
        <div class="day-timer">{{ formattedTime }}</div>
      </header>

      <SunArc
        :phase-deadline="votingPhase.phaseDeadline"
        :phase-started="votingPhase.phaseStarted"
      />

      <!-- RE_VOTING banner -->
      <div v-if="votingPhase.subPhase === 'RE_VOTING'" class="revote-banner">
        🔁 第二轮投票 · Round 2
      </div>

      <!-- Role + history row -->
      <div v-if="myRole || voteHistory?.length" class="role-history-row">
        <button v-if="myRole" class="my-role-chip my-role-locked" @click="showRoleCard = true">
          🔒 身份 · Tap to reveal
        </button>
        <button v-if="voteHistory?.length" class="history-btn" @click="showHistory = true">
          📋 历史
        </button>
      </div>

      <!-- Before reveal: simple vote count -->
      <div v-if="!isRevealed" class="vote-count-bar">
        <div class="tally-chip tally-chip-count">
          <span class="tally-votes">{{ votingPhase.votesSubmitted ?? 0 }}</span>
          <span class="tally-sep">/</span>
          <span class="tally-total">{{ votingPhase.totalVoters ?? '?' }}</span>
          <span class="tally-label">已投票 · voted</span>
        </div>
      </div>

      <!-- After reveal: vote columns showing who voted for whom -->
      <div v-else class="vote-columns-wrap">
        <!-- Eliminated player banner -->
        <div v-if="votingPhase.eliminatedPlayerId" class="banner banner-kill">
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

        <!-- Idiot reveal banner (survived vote, loses voting right) -->
        <div v-else-if="votingPhase.idiotRevealedId" class="banner banner-idiot">
          <span class="elim-banner-avatar">🃏</span>
          <div class="elim-banner-body">
            <span class="elim-banner-tag">白痴翻牌 · IDIOT REVEALED</span>
            <span class="elim-banner-name">
              {{ votingPhase.idiotRevealedNickname }} · 座位 {{ votingPhase.idiotRevealedSeatIndex }}
            </span>
            <span class="elim-banner-role">存活，失去投票权 · Survives, loses vote</span>
          </div>
        </div>

        <div class="vote-columns">
          <div
            v-for="(entry, i) in sortedTally"
            :key="entry.playerId"
            class="vote-col"
            :class="{ 'vote-col-winner': i === 0 }"
          >
            <div class="vote-col-head" :class="{ 'tally-chip-top': i === 0 }">
              <div class="vote-col-avatar">{{ entry.avatar ?? '😊' }}</div>
              <div class="vote-col-cname">{{ entry.nickname }}</div>
              <div class="vote-col-count" :class="i === 0 ? 'tally-winner' : 'tally-muted'">
                {{ entry.votes }}
              </div>
            </div>
            <div class="vote-col-body">
              <div v-for="v in entry.voters" :key="v.userId" class="vcol-row">
                <span class="vcol-avatar">{{ v.avatar ?? '😊' }}</span>
                <span class="vcol-seat">{{ v.seatIndex }}</span>
                <span class="vcol-name">{{ v.nickname }}</span>
              </div>
            </div>
          </div>
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
          <template v-if="player.idiotRevealed" #overlay>
            <div class="slot-overlay idiot-overlay">🃏</div>
          </template>
          <template v-else-if="!player.isAlive" #overlay>
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
            <div class="vote-actions">
              <button class="btn btn-primary vote-btn" @click="emit('continueVoting')">
                继续 / Continue
              </button>
            </div>
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
              <!-- Idiot revealed: host lost voting right -->
              <template v-if="!myCanVote">
                <p class="footer-hint idiot-no-vote">🃏 已揭示白痴 · 无投票权</p>
              </template>
              <template v-else-if="votingPhase.myVote || votingPhase.myVoteSkipped">
                <button class="btn btn-secondary" @click="emit('unvote')">取消投票 · Unvote</button>
              </template>
              <template v-else>
                <div class="vote-actions">
                  <button
                    class="btn btn-primary vote-btn"
                    :disabled="!effectiveSelected"
                    @click="effectiveSelected && emit('vote', effectiveSelected)"
                  >
                    投票 · Vote
                  </button>
                  <button class="btn btn-secondary skip-btn" @click="emit('skipVote')">弃权</button>
                </div>
              </template>
            </template>
            <button class="btn btn-gold" :disabled="!allVotesIn" @click="emit('revealVoting')">
              公布结果 · Reveal
            </button>
          </template>

          <!-- DEAD: voting disabled -->
          <template v-else-if="viewRole === 'DEAD'">
            <button class="btn btn-secondary" disabled>投票已禁用 · Voting disabled</button>
          </template>

          <!-- ALIVE: vote/skip or unvote (or no vote right if idiot revealed) -->
          <template v-else-if="viewRole === 'ALIVE'">
            <template v-if="!myCanVote">
              <button class="btn btn-secondary" disabled>🃏 已揭示白痴 · 无投票权</button>
            </template>
            <template v-else-if="votingPhase.myVote || votingPhase.myVoteSkipped">
              <button class="btn btn-secondary" @click="emit('unvote')">取消投票 · Unvote</button>
            </template>
            <template v-else>
              <div class="vote-actions">
                <button
                  class="btn btn-primary vote-btn"
                  :disabled="!effectiveSelected"
                  @click="effectiveSelected && emit('vote', effectiveSelected)"
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

      <!-- Role card bottom sheet -->
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

      <!-- History bottom sheet — Teleport here so it stays inside isVotingScreen block -->
      <Teleport to="body">
        <div v-if="showHistory" class="history-overlay" @click.self="showHistory = false">
          <div class="history-sheet">
            <div class="history-header">
              <span>投票记录 · Vote History</span>
              <button class="history-close" @click="showHistory = false">✕</button>
            </div>
            <div class="history-body">
              <div v-if="!voteHistory?.length" class="history-empty">暂无历史 · No history yet</div>
              <div
                v-for="round in [...(voteHistory ?? [])].reverse()"
                :key="round.dayNumber"
                class="history-round"
              >
                <div class="history-round-title">
                  第 {{ round.dayNumber }} 天 · Day {{ round.dayNumber }}
                </div>
                <!-- Vote-out banner -->
                <div v-if="round.eliminatedPlayerId" class="banner banner-kill history-banner">
                  <span class="banner-avatar">{{ round.eliminatedAvatar ?? '💀' }}</span>
                  <div>
                    <div class="banner-title">
                      出局 · 座位{{ round.eliminatedSeatIndex }} {{ round.eliminatedNickname }}
                    </div>
                    <div v-if="round.eliminatedRole" class="banner-sub">
                      {{ ROLE_ZH[round.eliminatedRole] }}
                    </div>
                  </div>
                </div>
                <!-- Hunter-shot banner -->
                <div v-if="round.hunterShotPlayerId" class="banner banner-kill history-banner">
                  <span class="banner-avatar">🏹</span>
                  <div>
                    <div class="banner-title">
                      猎人开枪 · 座位{{ round.hunterShotSeatIndex }} {{ round.hunterShotNickname }}
                    </div>
                    <div v-if="round.hunterShotRole" class="banner-sub">
                      {{ ROLE_ZH[round.hunterShotRole] }}
                    </div>
                  </div>
                </div>
                <div class="vote-columns history-columns">
                  <div
                    v-for="(entry, i) in round.tally"
                    :key="entry.playerId"
                    class="vote-col"
                    :class="{ 'vote-col-winner': i === 0 }"
                  >
                    <div class="vote-col-head" :class="{ 'tally-chip-top': i === 0 }">
                      <div class="vote-col-avatar">{{ entry.avatar ?? '😊' }}</div>
                      <div class="vote-col-cname">{{ entry.nickname }}</div>
                      <div class="vote-col-count" :class="i === 0 ? 'tally-winner' : 'tally-muted'">
                        {{ entry.votes }}
                      </div>
                    </div>
                    <div class="vote-col-body">
                      <div v-for="v in entry.voters" :key="v.userId" class="vcol-row">
                        <span class="vcol-avatar">{{ v.avatar }}</span>
                        <span class="vcol-seat">#{{ v.seatIndex }}</span>
                        <span class="vcol-name">{{ v.nickname }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </Teleport>
    </template>

    <!-- ── HUNTER_SHOOT screen ── -->
    <template v-else-if="votingPhase.subPhase === 'HUNTER_SHOOT'">
      <header class="day-header">
        <div class="day-pill">
          第 {{ votingPhase.dayNumber }} 天
          <span class="day-pill-sep">·</span>
          猎人 · Hunter
        </div>
        <div class="day-timer">{{ formattedTime }}</div>
      </header>

      <div class="sun-arc-placeholder" />

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
          <button class="btn btn-secondary skip-btn" @click="emit('hunterPass')">
            放弃 · Pass
          </button>
        </div>
      </footer>
    </template>

    <!-- ── BADGE_HANDOVER screen (includes BADGE_RECEIVED — merged) ── -->
    <template v-else-if="isBadgeScreen">
      <header class="day-header">
        <div class="day-pill">
          第 {{ votingPhase.dayNumber }} 天
          <span class="day-pill-sep">·</span>
          警徽 · Badge
        </div>
        <div class="day-timer">{{ formattedTime }}</div>
      </header>

      <div class="sun-arc-placeholder" />

      <!-- Destroyed message replaces banner when badge is burned -->
      <div class="banner-area">
        <div v-if="votingPhase.badgeDestroyed" class="banner badge-status-burned">
          <span class="banner-avatar">⚔️</span>
          <div>
            <div class="banner-title">警徽已销毁 · Badge Destroyed</div>
          </div>
        </div>
        <div v-else-if="votingPhase.newSheriffId" class="banner badge-status-passed">
          <span class="banner-avatar">⭐</span>
          <div>
            <div class="banner-title">
              警徽已移交给 {{ votingPhase.newSheriffNickname }} · Badge Passed
            </div>
          </div>
        </div>
        <div v-else class="banner banner-gold">
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
            <div class="vote-actions">
              <button class="btn btn-primary vote-btn" @click="emit('continueVoting')">
                → 进入夜晚 / Night
              </button>
            </div>
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
import {computed, onMounted, onUnmounted, ref, watch} from 'vue'
import type {GamePlayer, PlayerRole, VoteRoundHistory, VotingState} from '@/types'
import PlayerSlot from '@/components/PlayerSlot.vue'
import SunArc from '@/components/SunArc.vue'

const props = defineProps<{
  votingPhase: VotingState
  players: GamePlayer[]
  myUserId: string
  isHost: boolean
  myRole?: PlayerRole
  voteHistory?: VoteRoundHistory[]
}>()

const emit = defineEmits<{
  selectPlayer: [userId: string]
  vote: [targetId: string]
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
  () =>
    props.votingPhase.subPhase === 'VOTING' ||
    props.votingPhase.subPhase === 'RE_VOTING' ||
    props.votingPhase.subPhase === 'VOTE_RESULT',
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

// false when idiot has been revealed (permanently lost voting right)
const myCanVote = computed(() => {
  const me = props.players.find((p) => p.userId === props.myUserId)
  return me?.canVote !== false
})

const allVotesIn = computed(
  () =>
    props.votingPhase.votesSubmitted !== undefined &&
    props.votingPhase.totalVoters !== undefined &&
    props.votingPhase.votesSubmitted >= props.votingPhase.totalVoters,
)

// ── Selection — local UI state (server notified but not authoritative for display) ──
const localSelected = ref<string | undefined>(props.votingPhase.selectedPlayerId)

// Reset when a new sub-phase begins
watch(
  () => props.votingPhase.subPhase,
  () => {
    localSelected.value = undefined
  },
)

const effectiveSelected = computed(() => localSelected.value)

function selectPlayer(userId: string) {
  localSelected.value = userId
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
  document.body.style.overflow = ''
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

// ── My Role badge ─────────────────────────────────────────────────────────────
const ROLE_ZH: Record<string, string> = {
  WEREWOLF: '狼人',
  VILLAGER: '村民',
  SEER: '预言家',
  WITCH: '女巫',
  HUNTER: '猎人',
  GUARD: '守卫',
  IDIOT: '白痴',
}

// ── Vote History panel ────────────────────────────────────────────────────────
const showHistory = ref(false)
const showRoleCard = ref(false)

watch([showHistory, showRoleCard], ([h, r]) => {
  document.body.style.overflow = h || r ? 'hidden' : ''
})

// ── Role card metadata ─────────────────────────────────────────────────────────
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

/* SunArc placeholder — keeps layout stable on screens without SunArc */
.sun-arc-placeholder {
  width: 100%;
  height: 3.5rem;
  flex-shrink: 0;
}

/* Before-reveal: simple vote count bar */
.vote-count-bar {
  display: flex;
  align-items: center;
  padding: 0.25rem 1rem 0.5rem;
  min-height: 2.5rem;
}

.tally-chip {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  background: var(--paper);
  border: 1px solid var(--border-l);
  border-radius: 1rem;
  padding: 0.25rem 0.625rem;
  font-size: 0.75rem;
  color: var(--muted);
  white-space: nowrap;
}

.tally-chip-count {
  gap: 0.375rem;
}

.tally-sep {
  color: var(--border);
}

.tally-total {
  font-weight: 600;
  color: var(--text);
}

.tally-label {
  color: var(--muted);
}

/* After-reveal: vote columns (who voted for whom) */
.vote-columns-wrap {
  padding: 0 1rem 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

/* Eliminated player banner (shown above vote columns after reveal) */
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

.banner-gold {
  background: rgba(160, 120, 48, 0.06);
  border-left: 3px solid var(--gold);
  border-radius: 0 0.375rem 0.375rem 0;
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

/* Sheriff pin — absolutely positioned in top-right of card */
.sheriff-pin {
  position: absolute;
  top: 5px;
  right: 8px;
  font-size: 16px;
  line-height: 1;
}

/* Footer — shared styles in game.css */
/* Reveal countdown */
.reveal-countdown {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
  padding: 0.25rem 0;
}

.reveal-countdown-label {
  font-size: 1rem;
  color: var(--muted);
}

.reveal-countdown-time {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--gold);
}

/* Role + history row */
.role-history-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.25rem 1rem 0.375rem;
  min-height: 2.25rem;
}

.my-role-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.625rem;
  border-radius: 1rem;
  font-size: 0.75rem;
  font-weight: 600;
  border: 1px solid currentColor;
}

.my-role-wolf {
  color: var(--red);
  background: rgba(181, 37, 26, 0.08);
}

.my-role-special {
  color: var(--gold);
  background: rgba(160, 120, 48, 0.08);
}

.my-role-guard {
  color: #3b82f6;
  background: rgba(59, 130, 246, 0.08);
}

.my-role-hunter {
  color: #7c5c3a;
  background: rgba(124, 92, 58, 0.08);
}

.my-role-default {
  color: var(--muted);
  background: var(--paper);
  border-color: var(--border-l);
}

.my-role-locked {
  color: var(--muted);
  background: var(--paper);
  border-color: var(--border-l);
  cursor: pointer;
  font-size: 0.75rem;
}

.history-btn {
  background: var(--paper);
  border: 1px solid var(--border-l);
  border-radius: 0.375rem;
  padding: 0.25rem 0.625rem;
  font-size: 0.75rem;
  color: var(--muted);
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
}

.history-btn:hover {
  border-color: var(--border);
  color: var(--text);
}

/* History bottom sheet overlay */
.history-overlay {
  position: fixed;
  inset: 0;
  background: rgba(26, 20, 12, 0.5);
  z-index: 200;
  display: flex;
  align-items: flex-end;
  touch-action: none;
}

.history-sheet {
  width: 100%;
  max-height: 75dvh;
  background: var(--paper);
  border-radius: 1rem 1rem 0 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.875rem 1rem 0.75rem;
  border-bottom: 1px solid var(--border-l);
  font-family: 'Noto Serif SC', serif;
  font-size: 0.9375rem;
  font-weight: 600;
  color: var(--text);
  flex-shrink: 0;
}

.history-close {
  background: none;
  border: none;
  font-size: 1rem;
  color: var(--muted);
  cursor: pointer;
  padding: 0.25rem;
  line-height: 1;
}

.history-body {
  flex: 1;
  overflow-y: auto;
  padding: 0.75rem 1rem 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  overscroll-behavior: contain;
}

.history-empty {
  text-align: center;
  color: var(--muted);
  font-size: 0.875rem;
  padding: 2rem 0;
}

.history-round {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.history-round-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 0.875rem;
  font-weight: 700;
  color: var(--text);
}

.history-elim {
  font-size: 0.75rem;
  color: var(--red);
  padding: 0.25rem 0;
}

.history-columns {
  margin-top: 0.25rem;
}

.history-banner {
  margin-bottom: 0.375rem;
}

/* Role card centered modal */
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

/* Dead / Idiot overlays on player slots */
.dead-overlay {
  background: rgba(26, 20, 12, 0.55);
  color: #fff;
  font-size: 1.25rem;
  font-weight: 700;
}

.idiot-overlay {
  background: rgba(160, 120, 48, 0.75);
  font-size: 1.375rem;
}

/* RE_VOTING banner */
.revote-banner {
  margin: 0.25rem 1rem 0;
  padding: 0.375rem 0.75rem;
  background: rgba(160, 120, 48, 0.12);
  border: 1px solid var(--gold);
  border-radius: 0.5rem;
  color: var(--gold);
  font-size: 0.8125rem;
  font-weight: 600;
  text-align: center;
}

/* Idiot no-vote hint line in footer */
.footer-hint.idiot-no-vote {
  color: var(--gold);
  font-size: 0.8125rem;
  font-weight: 600;
}
</style>
