<template>
  <div class="day-wrap">
    <!-- Header: pill badge left | countdown arc right -->
    <header class="day-header">
      <div class="day-pill">{{ $t('day.pill', { n: dayPhase.dayNumber }) }}</div>
      <LanguageToggle inline />
      <CountdownArc
        :remaining-ms="timer?.remainingMs ?? 0"
        :duration-ms="timer?.durationMs ?? 0"
        :running="timer?.running ?? false"
        :is-host="isHost"
        :presets="timerPresets"
        @start-timer="(s) => emit('start-timer', s)"
        @stop-timer="emit('stop-timer')"
      />
    </header>

    <!-- Sun arc -->
    <SunArc :phase-deadline="dayPhase.phaseDeadline" :phase-started="dayPhase.phaseStarted" />

    <!-- Below-arch row: my-role-chip on left, log-fab + Action stacked on right -->
    <div class="below-arch-row">
      <button
        v-if="myRole"
        class="toolbar-btn my-role-chip my-role-locked"
        data-testid="my-role-chip"
        @click="showRoleCard = true"
      >
        <span class="toolbar-icon" aria-hidden="true">🔒</span>
        <span>{{ $t('day.tapToReveal') }}</span>
      </button>
      <div v-else />
      <div class="right-stack">
        <button
          v-if="dayPhase.subPhase !== 'RESULT_HIDDEN'"
          class="toolbar-btn log-fab"
          :aria-label="$t('common.gameLog')"
          data-testid="log-fab"
          @click="showLog = true"
        >
          <span class="toolbar-icon" aria-hidden="true">📋</span>
          <span>{{ $t('common.gameLog') }}</span>
        </button>
        <button
          class="toolbar-btn log-fab"
          :aria-label="$t('common.roomInfo')"
          data-testid="settings-fab"
          @click="showSettings = true"
        >
          <span class="toolbar-icon" aria-hidden="true">ℹ️</span>
          <span>{{ $t('common.roomInfo') }}</span>
        </button>
        <ActionMenu
          v-if="myRole"
          phase="DAY_DISCUSSION"
          :sub-phase="dayPhase.subPhase"
          :day-skip-voting="daySkipVoting"
          :my-role="myRole"
          :is-alive="isAlive ?? false"
          :players="players"
          :my-user-id="myUserId"
          @self-destruct="(target) => emit('self-destruct', target)"
        />
      </div>
    </div>

    <!-- Fixed-height banner area — always rendered so grid position stays consistent -->
    <div class="banner-area">
      <!-- Self-destruct (自爆): a wolf blew themselves up this day. Shown in the
           death-banner area for every viewer, independent of the reveal sub-phase
           below, so the 自爆 result is always visible until the next night. -->
      <div v-if="selfDestruct" class="banner banner-kill" data-testid="day-banner-self-destruct">
        <span class="banner-avatar">💥</span>
        <div class="banner-kill-text">
          <span
            class="banner-kill-red"
            :data-testid="`day-self-destruct-seat-${selfDestruct.seatIndex}`"
            >{{
              $t('common.seatDot', { n: selfDestruct.seatIndex, name: selfDestruct.nickname })
            }}</span
          >
          <span class="banner-kill-muted">{{ $t('day.selfDestructed') }}</span>
          <template v-if="selfDestruct.takenSeatIndex != null">
            <span class="banner-kill-muted">{{ $t('day.tookAlong') }}</span>
            <span
              class="banner-kill-red"
              :data-testid="`day-self-destruct-taken-seat-${selfDestruct.takenSeatIndex}`"
              >{{
                $t('common.seatDot', {
                  n: selfDestruct.takenSeatIndex,
                  name: selfDestruct.takenNickname,
                })
              }}</span
            >
          </template>
        </div>
      </div>

      <!-- Private shot (hunter / 黑狼王): only the shooter's own state says
           canShoot, so only the shooter sees this. Everyone else sees the
           ordinary day; the chance ends when the host moves on. -->
      <template v-if="isHunterNightShoot">
        <div
          v-if="killedPlayers.length > 0"
          class="banner banner-kill"
          data-testid="day-banner-kill"
        >
          <span class="banner-avatar">💀</span>
          <div class="banner-kill-text">
            <span class="banner-kill-muted">{{ $t('day.lastNight') }}</span>
            <template v-for="(killed, idx) in killedPlayers" :key="killed.killedPlayerId">
              <span v-if="idx > 0" class="banner-kill-muted">{{ $t('common.listSep') }}</span>
              <span
                class="banner-kill-red"
                :data-testid="`day-killed-seat-${killed.killedSeatIndex}`"
                >{{
                  $t('common.seatDot', { n: killed.killedSeatIndex, name: killed.killedNickname })
                }}</span
              >
            </template>
            <span class="banner-kill-muted">{{ $t('day.eliminated') }}</span>
          </div>
        </div>
        <!-- Shared by the hunter and the 黑狼王: never name the shooter's role. -->
        <div class="banner banner-gold" data-testid="day-hunter-night-banner">
          <span class="banner-avatar">🔫</span>
          <div>
            <div class="banner-title">{{ $t('day.yourShot') }}</div>
            <div class="banner-sub">{{ $t('day.shotSub') }}</div>
          </div>
        </div>
      </template>

      <!-- Badge handover (sheriff killed at night): show this on top of the
           normal reveal banners. The dying sheriff sees the heir prompt; all
           other viewers see a "waiting for sheriff" hint. -->
      <template v-else-if="isBadgeHandover">
        <div
          v-if="killedPlayers.length > 0"
          class="banner banner-kill"
          data-testid="day-banner-kill"
        >
          <span class="banner-avatar">💀</span>
          <div class="banner-kill-text">
            <span class="banner-kill-muted">{{ $t('day.lastNight') }}</span>
            <template v-for="(killed, idx) in killedPlayers" :key="killed.killedPlayerId">
              <span v-if="idx > 0" class="banner-kill-muted">{{ $t('common.listSep') }}</span>
              <span
                class="banner-kill-red"
                :data-testid="`day-killed-seat-${killed.killedSeatIndex}`"
                >{{
                  $t('common.seatDot', { n: killed.killedSeatIndex, name: killed.killedNickname })
                }}</span
              >
            </template>
            <span class="banner-kill-muted">{{ $t('day.eliminated') }}</span>
          </div>
        </div>
        <div
          v-if="isDyingSheriff"
          class="banner banner-gold"
          data-testid="day-badge-eliminated-banner"
        >
          <span class="banner-avatar">⭐</span>
          <div>
            <div class="banner-title">{{ $t('day.eliminated2') }}</div>
            <div class="banner-sub">{{ $t('day.chooseBadgeHeir') }}</div>
          </div>
        </div>
        <div v-else class="banner banner-info" data-testid="day-badge-wait-banner">
          <span class="banner-avatar">⭐</span>
          <div>
            <div class="banner-title">{{ $t('day.sheriffPassing') }}</div>
            <div v-if="$i18n.locale === 'zh'" class="banner-sub">Sheriff is choosing an heir…</div>
          </div>
        </div>
      </template>

      <template v-else-if="viewRole === 'DEAD'">
        <div class="banner banner-info" data-testid="day-banner-self-eliminated">
          <span class="banner-icon">☑</span>
          <div>
            <div class="banner-title">{{ $t('day.youEliminatedLastNight') }}</div>
            <div v-if="$i18n.locale === 'zh'" class="banner-sub">
              You were eliminated last night
            </div>
          </div>
        </div>
        <div
          v-if="dayPhase.subPhase === 'RESULT_REVEALED' && killedPlayers.length > 0"
          class="banner banner-kill"
          data-testid="day-banner-kill"
        >
          <span class="banner-avatar">💀</span>
          <div class="banner-kill-text">
            <span class="banner-kill-muted">{{ $t('day.lastNight') }}</span>
            <template v-for="(killed, idx) in killedPlayers" :key="killed.killedPlayerId">
              <span v-if="idx > 0" class="banner-kill-muted">{{ $t('common.listSep') }}</span>
              <span
                class="banner-kill-red"
                :data-testid="`day-killed-seat-${killed.killedSeatIndex}`"
                >{{
                  $t('common.seatDot', { n: killed.killedSeatIndex, name: killed.killedNickname })
                }}</span
              >
            </template>
            <span class="banner-kill-muted">{{ $t('day.eliminated') }}</span>
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
            <span class="banner-kill-muted">{{ $t('day.lastNight') }}</span>
            <template v-for="(killed, idx) in killedPlayers" :key="killed.killedPlayerId">
              <span v-if="idx > 0" class="banner-kill-muted">{{ $t('common.listSep') }}</span>
              <span
                class="banner-kill-red"
                :data-testid="`day-killed-seat-${killed.killedSeatIndex}`"
                >{{
                  $t('common.seatDot', { n: killed.killedSeatIndex, name: killed.killedNickname })
                }}</span
              >
            </template>
            <span class="banner-kill-muted">{{ $t('day.eliminated') }}</span>
          </div>
        </div>
        <div v-else class="banner banner-info" data-testid="day-banner-peaceful">
          <span class="banner-icon">❤️</span>
          <div>
            <div class="banner-title">{{ $t('day.peacefulNight') }}</div>
            <div v-if="$i18n.locale === 'zh'" class="banner-sub">
              Peaceful night — no one was eliminated
            </div>
          </div>
        </div>
      </template>
    </div>

    <!-- Player grid: 4 columns, room-mode compact cards -->
    <section class="player-grid">
      <PlayerSlot
        v-for="player in players"
        :key="player.userId"
        :self="player.userId === myUserId"
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
        <template
          v-if="!player.isAlive && (dayPhase.subPhase === 'RESULT_REVEALED' || isBadgeHandover)"
          #overlay
        >
          <div class="slot-overlay dead-overlay">✕</div>
        </template>
      </PlayerSlot>
    </section>

    <!-- Action log drawer -->
    <ActionLogDrawer :game-id="gameId" :open="showLog" @close="showLog = false" />
    <GameSettingsModal
      :settings="gameSettings"
      :open="showSettings"
      @close="showSettings = false"
    />

    <!-- Footer -->
    <footer class="day-footer">
      <!-- Private shot: only the shooter sees shoot/pass. It comes before the
           badge handover and the host controls, since the shooter may be the
           dying sheriff or the host. -->
      <template v-if="isHunterNightShoot">
        <div class="vote-actions">
          <button
            class="btn btn-danger vote-btn"
            data-testid="day-hunter-night-shoot"
            :class="{ 'is-loading': actionPending }"
            :disabled="!hunterSelectedId || actionPending"
            @click="hunterSelectedId && emit('hunterShoot', hunterSelectedId)"
          >
            {{ $t('day.shoot') }}
          </button>
          <button
            class="btn btn-secondary skip-btn"
            data-testid="day-hunter-night-pass"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('hunterPass')"
          >
            {{ $t('day.pass') }}
          </button>
        </div>
      </template>

      <!-- BADGE_HANDOVER (sheriff killed at night): dying sheriff sees
           pass/destroy; everyone else (including host) waits. Takes precedence
           over the regular host/dead/alive footer templates because the dying
           sheriff may also be the host. -->
      <template v-else-if="isBadgeHandover">
        <template v-if="isDyingSheriff">
          <div class="vote-actions">
            <button
              class="btn btn-gold vote-btn"
              data-testid="day-badge-pass"
              :class="{ 'is-loading': actionPending }"
              :disabled="!badgeSelectedId || actionPending"
              @click="badgeSelectedId && emit('passBadge', badgeSelectedId)"
            >
              {{ $t('day.passBadge') }}
            </button>
            <button
              class="btn btn-secondary skip-btn"
              data-testid="day-badge-destroy"
              :class="{ 'is-loading': actionPending }"
              :disabled="actionPending"
              @click="emit('destroyBadge')"
            >
              {{ $t('day.destroyBadge') }}
            </button>
          </div>
        </template>
        <template v-else>
          <p class="footer-hint">{{ $t('day.waitingForSheriffTo') }}</p>
        </template>
      </template>

      <template v-else-if="viewRole === 'HOST'">
        <div v-if="dayPhase.subPhase === 'RESULT_HIDDEN'" class="vote-actions">
          <button
            class="btn btn-primary vote-btn"
            data-testid="day-reveal-result"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('revealResult')"
          >
            {{ $t('day.result') }}
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
            {{ $t('day.night') }}
          </button>
          <button
            v-else
            class="btn btn-gold vote-btn"
            data-testid="day-start-vote"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('startVote')"
          >
            {{ $t('day.startVote') }}
          </button>
        </div>
      </template>

      <template v-else-if="viewRole === 'DEAD'">
        <button class="btn btn-secondary" disabled>{{ $t('day.votingDisabled') }}</button>
      </template>

      <template v-else-if="viewRole === 'ALIVE'">
        <template v-if="dayPhase.subPhase === 'RESULT_HIDDEN'">
          <p class="footer-hint">{{ $t('day.waitingForHostTo') }}</p>
        </template>
        <template v-else>
          <p class="footer-hint">{{ $t('day.waitingForHostTo2') }}</p>
        </template>
      </template>

      <template v-else>
        <p class="footer-hint">{{ $t('day.waitingForHostTo') }}</p>
      </template>
    </footer>

    <!-- Role card bottom sheet — same as VotingPhase so the my-role-chip works during DAY_DISCUSSION -->
    <Teleport to="body">
      <div v-if="showRoleCard" class="role-card-overlay" @click.self="showRoleCard = false">
        <div class="role-card-sheet">
          <div class="role-card-header">
            <span>{{ $t('roleCard.title') }}</span>
            <button class="history-close" @click="showRoleCard = false">✕</button>
          </div>
          <div v-if="myRole && ROLE_META[myRole]" class="role-card-body">
            <div class="rc-emoji">{{ ROLE_META[myRole]?.emoji }}</div>
            <div class="rc-name-zh">{{ roleName(myRole) }}</div>
            <div class="rc-label" :class="`rc-label-${ROLE_META[myRole]?.team}`">
              {{ roleTag(myRole) }}
            </div>
            <div v-if="myPlayer" class="rc-identity" data-testid="rc-identity">
              {{ $t('common.seatDot', { n: myPlayer.seatIndex, name: myPlayer.nickname }) }}
            </div>
            <p class="rc-desc" data-testid="role-card-desc">{{ roleDescription(myRole, true) }}</p>
            <RoleHistory :history="roleHistory" />
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script lang="ts" setup>
import LanguageToggle from '@/components/LanguageToggle.vue'
import { roleDescription, roleName, roleTag } from '@/utils/roleDefinitions'
import { computed, ref, watch } from 'vue'
import type {
  DayPhaseState,
  GamePlayer,
  GameSettings,
  PlayerRole,
  TimerState,
  RoleHistory as RoleHistoryData,
} from '@/types'
import PlayerSlot from '@/components/PlayerSlot.vue'
import RoleHistory from '@/components/RoleHistory.vue'
import SunArc from '@/components/SunArc.vue'
import ActionLogDrawer from '@/components/ActionLogDrawer.vue'
import GameSettingsModal from '@/components/GameSettingsModal.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import CountdownArc from '@/components/CountdownArc.vue'

const props = defineProps<{
  gameId: number
  dayPhase: DayPhaseState
  players: GamePlayer[]
  myUserId: string
  isHost: boolean
  timer?: TimerState | null
  roleHistory?: RoleHistoryData | null
  timerPresets?: number[]
  myRole?: PlayerRole
  isAlive?: boolean
  daySkipVoting?: boolean
  sheriffUserId?: string | null
  actionPending?: boolean
  gameSettings?: GameSettings | null
}>()

const isBadgeHandover = computed(() => props.dayPhase.subPhase === 'BADGE_HANDOVER')
const isDyingSheriff = computed(
  () => isBadgeHandover.value && !!props.sheriffUserId && props.sheriffUserId === props.myUserId,
)
const badgeSelectedId = ref<string | null>(null)
function onBadgeTap(player: GamePlayer) {
  if (!isDyingSheriff.value) return
  if (!player.isAlive) return
  if (player.userId === props.myUserId) return
  badgeSelectedId.value = player.userId
}

// A dead shooter's private day-reveal shot: canShoot is set on its own state only.
const isHunterNightShoot = computed(() => !!props.dayPhase.canShoot)
const hunterSelectedId = ref<string | null>(null)
function onHunterTap(player: GamePlayer) {
  if (!isHunterNightShoot.value) return
  if (!player.isAlive) return
  if (player.userId === props.myUserId) return
  hunterSelectedId.value = player.userId
}

const showLog = ref(false)
const showSettings = ref(false)
const showRoleCard = ref(false)
const myPlayer = computed(() => props.players.find((p) => p.userId === props.myUserId))

interface RoleMeta {
  emoji: string
  team: string
}
const ROLE_META: Record<string, RoleMeta> = {
  WEREWOLF: {
    emoji: '🐺',
    team: 'wolf',
  },
  VILLAGER: {
    emoji: '🌾',
    team: 'village',
  },
  SEER: {
    emoji: '🔭',
    team: 'special',
  },
  WITCH: {
    emoji: '🔮',
    team: 'special',
  },
  HUNTER: {
    emoji: '🏹',
    team: 'special',
  },
  GUARD: {
    emoji: '🛡️',
    team: 'special',
  },
  IDIOT: {
    emoji: '🃏',
    team: 'special',
  },
  WHITE_WOLF_KING: {
    emoji: '👑',
    team: 'wolf',
  },
  BLACK_WOLF_KING: {
    emoji: '🖤',
    team: 'wolf',
  },
}

const emit = defineEmits<{
  revealResult: []
  startVote: []
  vote: [targetId: string]
  skip: []
  selectPlayer: [userId: string]
  'self-destruct': [targetUserId?: string]
  continueToNight: []
  passBadge: [userId: string]
  destroyBadge: []
  hunterShoot: [targetId: string]
  hunterPass: []
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
    hunterSelectedId.value = null
  },
)

const killedIds = computed(
  () => props.dayPhase.nightResult?.killedPlayers?.map((k) => k.killedPlayerId) ?? [],
)

const killedPlayers = computed(() => props.dayPhase.nightResult?.killedPlayers ?? [])

// The wolf who self-destructed (自爆) this day — surfaced in the death-banner
// area alongside any night kills. Cleared server-side at night-init.
const selfDestruct = computed(() => props.dayPhase.selfDestruct ?? null)

function isKilledAndVisible(player: GamePlayer) {
  return killedIds.value.includes(player.userId) && props.dayPhase.subPhase === 'RESULT_REVEALED'
}

function slotVariant(player: GamePlayer) {
  if (isHunterNightShoot.value) {
    if (!player.isAlive) return 'dead' as const
    if (player.userId === hunterSelectedId.value) return 'selected' as const
    return 'alive' as const
  }
  if (isBadgeHandover.value) {
    if (!player.isAlive) return 'dead' as const
    if (player.userId === badgeSelectedId.value) return 'selected' as const
    return 'alive' as const
  }
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
  if (isHunterNightShoot.value) {
    onHunterTap(player)
    return
  }
  if (isBadgeHandover.value) {
    onBadgeTap(player)
    return
  }
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

.below-arch-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 6px;
  padding: 0 0.875rem 0.5rem;
}

/* Toolbar buttons sit in one row (wrapping on narrow phones) so the seat
   grid and the footer action stay above the fold. */
.right-stack {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
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
  min-width: 44px;
  min-height: 44px;
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
.rc-identity {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--text);
}
.rc-desc {
  font-size: 0.8125rem;
  color: var(--muted);
  line-height: 1.6;
  text-align: center;
  margin: 0;
}
</style>
