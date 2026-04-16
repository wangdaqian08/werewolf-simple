<template>
  <div :class="{ 'night-mode': isNight }" class="game-wrap">
    <!-- Mute/unmute floating button -->
    <button class="audio-mute-btn" :title="isMuted ? 'Unmute' : 'Mute'" @click="toggleMute">
      {{ isMuted ? '🔇' : '🔊' }}
    </button>

    <!-- Role Reveal phase -->
    <template v-if="gameStore.state?.phase === 'ROLE_REVEAL' && gameStore.state?.myRole">
      <RoleRevealCard
        v-if="!hasConfirmedRole"
        :role="gameStore.state.myRole"
        :teammates="gameStore.state.roleReveal?.teammates"
        :revealed="isRoleRevealed"
        @reveal="isRoleRevealed = true"
        @hide="isRoleRevealed = false"
        @confirm="handleRoleConfirm"
      />
      <div v-else class="waiting-screen">
        <div class="waiting-icon">⏳</div>
        <div class="waiting-title">等待其他玩家确认</div>
        <div class="waiting-subtitle">Waiting for others…</div>
        <div class="waiting-count">
          {{ gameStore.state.roleReveal?.confirmedCount ?? 1 }} /
          {{ gameStore.state.roleReveal?.totalCount ?? '?' }}
          confirmed
        </div>
        <button
          v-if="
            isHost &&
            gameStore.state.hasSheriff === false &&
            gameStore.state.roleReveal?.confirmedCount === gameStore.state.roleReveal?.totalCount
          "
          class="btn btn-primary"
          style="margin-top: 1.5rem"
          :disabled="actionPending"
          data-testid="start-night"
          @click="handleStartNight"
        >
          开始夜晚 / Start Night
        </button>
      </div>
    </template>

    <!-- Sheriff Election phase -->
    <template
      v-else-if="gameStore.state?.phase === 'SHERIFF_ELECTION' && gameStore.state?.sheriffElection"
    >
      <SheriffElection
        :key="`sheriff-${gameStore.state.sheriffElection.subPhase}`"
        :election="gameStore.state.sheriffElection"
        :my-user-id="userStore.userId ?? ''"
        :is-host="isHost"
        :action-pending="actionPending"
        @run="handleSheriffRun"
        @pass="handleSheriffPass"
        @withdraw="handleSheriffWithdraw"
        @start-campaign="handleSheriffStartCampaign"
        @quit="handleSheriffQuit"
        @vote="handleSheriffVote"
        @abstain="handleSheriffAbstain"
        @advance-speech="handleSheriffAdvanceSpeech"
        @reveal-result="handleSheriffRevealResult"
        @appoint="handleSheriffAppoint"
        @start-night="handleSheriffStartNight"
      />
    </template>

    <!-- Night phase -->
    <template v-else-if="gameStore.state?.phase === 'NIGHT' && gameStore.state?.nightPhase">
      <NightPhase
        :key="`night-${gameStore.state.nightPhase.subPhase}-${gameStore.state.dayNumber}`"
        :night-phase="gameStore.state.nightPhase"
        :players="gameStore.state.players"
        :my-user-id="userStore.userId ?? ''"
        :my-role="gameStore.state.myRole"
        :action-pending="actionPending"
        @select-player="handleNightSelect"
        @confirm="handleNightConfirm"
        @witch-antidote="handleWitchAntidote"
        @witch-pass-antidote="handleWitchPassAntidote"
        @witch-poison="handleWitchPoison"
        @witch-pass-poison="handleWitchPassPoison"
        @witch-skip="handleWitchSkip"
      />
    </template>

    <!-- Voting phase -->
    <template v-else-if="gameStore.state?.phase === 'DAY_VOTING' && gameStore.state?.votingPhase">
      <VotingPhase
        :key="`voting-${gameStore.state.votingPhase.subPhase}-${gameStore.state.dayNumber}`"
        :voting-phase="gameStore.state.votingPhase"
        :players="gameStore.state.players"
        :my-user-id="userStore.userId ?? ''"
        :is-host="isHost"
        :my-role="gameStore.state?.myRole"
        :vote-history="gameStore.state?.voteHistory"
        :action-pending="actionPending"
        @select-player="handleVotingSelect"
        @vote="handleVotingVote"
        @skip="handleVotingSkip"
        @unvote="handleVotingUnvote"
        @reveal-voting="handleVotingReveal"
        @continue-voting="handleVotingContinue"
        @hunter-shoot="handleHunterShoot"
        @hunter-pass="handleHunterPass"
        @pass-badge="handlePassBadge"
        @destroy-badge="handleDestroyBadge"
      />
    </template>

    <!-- Day phase -->
    <template v-else-if="gameStore.state?.phase === 'DAY_DISCUSSION' && gameStore.state?.dayPhase">
      <DayPhase
        :key="`${gameStore.state.dayPhase.subPhase}-${gameStore.state.dayPhase.dayNumber}`"
        :game-id="Number(route.params.gameId)"
        :day-phase="gameStore.state.dayPhase"
        :players="gameStore.state.players"
        :my-user-id="userStore.userId ?? ''"
        :is-host="isHost"
        :action-pending="actionPending"
        @reveal-result="handleRevealResult"
        @start-vote="handleStartVote"
        @vote="handleDayVote"
        @skip="handleDaySkip"
        @select-player="handleDaySelectPlayer"
      />
    </template>

    <!-- Other game phases -->
    <template v-else>
      <!-- Phase header -->
      <header class="game-header">
        <div class="phase-label">{{ phaseLabel }}</div>
        <div class="day-badge">Day {{ gameStore.state?.dayNumber ?? 1 }}</div>
      </header>

      <!-- Player grid -->
      <section v-if="gameStore.state" class="player-grid">
        <PlayerSlot
          v-for="player in gameStore.state.players"
          :key="player.userId"
          :seat="player.seatIndex"
          :nickname="player.nickname"
          :variant="playerSlotVariant(player)"
          @click="onPlayerTap(player)"
        >
          <template v-if="player.isSheriff" #top>
            <div class="sheriff-badge">⭐</div>
          </template>
          <template v-if="!player.isAlive" #overlay>
            <div class="slot-overlay dead-overlay">✕</div>
          </template>
        </PlayerSlot>
      </section>

      <!-- Event log -->
      <section v-if="gameStore.state?.events?.length" class="event-log">
        <div v-for="(event, i) in visibleEvents" :key="i" class="event-item">
          {{ event.message }}
        </div>
      </section>

      <!-- Action panel — rendered by backend phase -->
      <footer class="action-panel">
        <p class="action-hint">{{ actionHint }}</p>
      </footer>
    </template>

    <!-- Debug panel (mock mode only) -->
    <div v-if="isMock" class="debug-panel">
      <div class="debug-title">🛠 Debug — Role Reveal</div>
      <div class="debug-btns">
        <button class="debug-btn" data-testid="debug-role-reveal" @click="debugStartGame">
          Role Reveal
        </button>
        <button class="debug-btn" data-testid="debug-skip-sheriff" @click="debugSkipRole">
          Skip → Sheriff
        </button>
        <button class="debug-btn" data-testid="debug-skip-night" @click="debugSkipToNight">
          Skip → Night
        </button>
        <button class="debug-btn" data-testid="debug-confirm-all" @click="debugConfirmAll">
          All Confirmed
        </button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Day Scenarios</div>
      <div class="debug-btns" data-testid="debug-day-scenario-btns">
        <button
          class="debug-btn"
          data-testid="debug-scenario-host-hidden"
          @click="debugScenario('HOST_HIDDEN')"
        >
          Host·Hidden
        </button>
        <button
          class="debug-btn"
          data-testid="debug-scenario-host-revealed"
          @click="debugScenario('HOST_REVEALED')"
        >
          Host·Revealed
        </button>
        <button class="debug-btn" data-testid="debug-scenario-dead" @click="debugScenario('DEAD')">
          Dead
        </button>
        <button
          class="debug-btn"
          data-testid="debug-scenario-alive-hidden"
          @click="debugScenario('ALIVE_HIDDEN')"
        >
          Alive·Hidden
        </button>
        <button
          class="debug-btn"
          data-testid="debug-scenario-alive-revealed"
          @click="debugScenario('ALIVE_REVEALED')"
        >
          Alive·Revealed
        </button>
        <button
          class="debug-btn"
          data-testid="debug-scenario-guest"
          @click="debugScenario('GUEST')"
        >
          Guest
        </button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Day Phase</div>
      <div class="debug-btns" data-testid="debug-day-btns">
        <button class="debug-btn" data-testid="debug-day-hidden" @click="debugDay('HIDDEN')">
          Hidden
        </button>
        <button class="debug-btn" data-testid="debug-day-revealed" @click="debugDay('REVEALED')">
          Revealed
        </button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Night Screens</div>
      <div class="debug-btns">
        <button
          class="debug-btn"
          data-testid="debug-night-werewolf"
          @click="debugNight('WEREWOLF')"
        >
          Werewolf
        </button>
        <button
          class="debug-btn"
          data-testid="debug-night-seer-pick"
          @click="debugNight('SEER_PICK')"
        >
          Seer: Pick
        </button>
        <button
          class="debug-btn"
          data-testid="debug-night-seer-result"
          @click="debugNight('SEER_RESULT')"
        >
          Seer: Result
        </button>
        <button class="debug-btn" data-testid="debug-night-witch" @click="debugNight('WITCH')">
          Witch
        </button>
        <button class="debug-btn" data-testid="debug-night-guard" @click="debugNight('GUARD')">
          Guard
        </button>
        <button class="debug-btn" data-testid="debug-night-waiting" @click="debugNight('WAITING')">
          Waiting
        </button>
        <button class="debug-btn" data-testid="debug-night-dead" @click="debugNight('DEAD')">
          Dead Night
        </button>
        <button
          class="debug-btn debug-btn-exit"
          data-testid="debug-night-advance"
          @click="debugNightAdvance"
        >
          → Day
        </button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Voting Screens</div>
      <div class="debug-btns" data-testid="debug-voting-btns">
        <button class="debug-btn" data-testid="debug-voting" @click="debugVoting('DAY_VOTING')">
          Voting
        </button>
        <button
          class="debug-btn"
          data-testid="debug-voting-voted"
          @click="debugVoting('VOTING_VOTED')"
        >
          Voted
        </button>
        <button
          class="debug-btn"
          data-testid="debug-voting-revealed"
          @click="debugVoting('VOTING_REVEALED')"
        >
          Revealed
        </button>
        <button
          class="debug-btn"
          data-testid="debug-voting-hunter"
          @click="debugVoting('HUNTER_SHOOT')"
        >
          Hunter
        </button>
        <button
          class="debug-btn"
          data-testid="debug-voting-badge-handover"
          @click="debugVoting('BADGE_HANDOVER')"
        >
          Badge: Pick
        </button>
        <button
          class="debug-btn"
          data-testid="debug-voting-badge-sheriff"
          @click="debugVoting('BADGE_SHERIFF')"
        >
          Badge: Sheriff
        </button>
        <button
          class="debug-btn"
          data-testid="debug-voting-badge-burned"
          @click="debugVoting('BADGE_BURNED')"
        >
          Badge: Burned
        </button>
        <button
          class="debug-btn"
          data-testid="debug-voting-no-history"
          @click="debugVoting('VOTING_NO_HISTORY')"
        >
          No History
        </button>
        <button
          class="debug-btn"
          data-testid="debug-voting-no-data"
          @click="debugVoting('VOTING_NO_DATA')"
        >
          No Data
        </button>
        <button
          class="debug-btn"
          data-testid="debug-voting-idiot-reveal"
          @click="debugVoting('IDIOT_REVEAL')"
        >
          Idiot Reveal
        </button>
        <button
          class="debug-btn"
          data-testid="debug-voting-re-voting"
          @click="debugVoting('RE_VOTING')"
        >
          Re-Vote
        </button>
        <button
          class="debug-btn debug-btn-exit"
          data-testid="debug-voting-advance"
          @click="debugVotingAdvance"
        >
          → Night
        </button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Game Over</div>
      <div class="debug-btns">
        <button
          class="debug-btn debug-btn-exit"
          data-testid="debug-game-over-village"
          @click="debugGameOver('VILLAGER')"
        >
          → Village Wins
        </button>
        <button
          class="debug-btn debug-btn-exit"
          data-testid="debug-game-over-wolf"
          @click="debugGameOver('WEREWOLF')"
        >
          → Wolf Wins
        </button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Sheriff Screens</div>
      <div class="debug-btns" data-testid="debug-sheriff-btns">
        <button
          class="debug-btn"
          data-testid="debug-sheriff-signup"
          @click="debugSheriff('SIGNUP')"
        >
          Sign-up
        </button>
        <button
          class="debug-btn"
          data-testid="debug-sheriff-speech-candidate"
          @click="debugSheriff('SPEECH_CANDIDATE')"
        >
          Speech: Me
        </button>
        <button
          class="debug-btn"
          data-testid="debug-sheriff-speech-audience"
          @click="debugSheriff('SPEECH_AUDIENCE')"
        >
          Speech: Watch
        </button>
        <button
          class="debug-btn"
          data-testid="debug-sheriff-voting"
          @click="debugSheriff('DAY_VOTING')"
        >
          Voting
        </button>
        <button
          class="debug-btn"
          data-testid="debug-sheriff-result"
          @click="debugSheriff('RESULT')"
        >
          Result
        </button>
        <button
          class="debug-btn debug-btn-exit"
          data-testid="debug-sheriff-exit"
          @click="debugExitSheriff"
        >
          ← Day
        </button>
      </div>
      <template
        v-if="
          gameStore.state?.phase === 'SHERIFF_ELECTION' &&
          gameStore.state?.sheriffElection?.subPhase === 'SIGNUP'
        "
      >
        <div class="debug-title" style="margin-top: 0.5rem">Candidates</div>
        <div class="debug-btns">
          <button class="debug-btn" @click="debugCandidateRun('u2', 'Alice', '😊')">+ Alice</button>
          <button class="debug-btn" @click="debugCandidateRemove('u2')">− Alice</button>
          <button class="debug-btn" @click="debugCandidateRun('u3', 'Bob', '🎭')">+ Bob</button>
          <button class="debug-btn" @click="debugCandidateRemove('u3')">− Bob</button>
          <button class="debug-btn" @click="debugCandidateRun('u6', 'Tom', '🐯')">+ Tom</button>
          <button class="debug-btn" @click="debugCandidateRemove('u6')">− Tom</button>
        </div>
      </template>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { useGameStore } from '@/stores/gameStore'
import { useRoomStore } from '@/stores/roomStore'
import { gameService } from '@/services/gameService'
import { createStompClient, disconnectStomp, subscribeToTopic } from '@/services/stompClient'
import http from '@/services/http'
import PlayerSlot from '@/components/PlayerSlot.vue'
import RoleRevealCard from '@/components/RoleRevealCard.vue'
import SheriffElection from '@/components/SheriffElection.vue'
import DayPhase from '@/components/DayPhase.vue'
import NightPhase from '@/components/NightPhase.vue'
import VotingPhase from '@/components/VotingPhase.vue'
import { useNavigationGuard } from '@/composables/useNavigationGuard'
import { useAudioService } from '@/composables/useAudioService'
import type { GamePlayer } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const gameStore = useGameStore()
const roomStore = useRoomStore()

// Initialize audio service
const { isMuted, toggleMute } = useAudioService()

const isMock = import.meta.env.VITE_MOCK === 'true'
const hasConfirmedRole = ref(false)
const isRoleRevealed = ref(false)
const actionPending = ref(false)

// Wraps gameService.submitAction to always include the gameId from the route
async function action(req: Omit<import('@/types').GameActionRequest, 'gameId'>) {
  actionPending.value = true
  try {
    const gameId = Number(route.params.gameId)
    const res = await gameService.submitAction({ ...req, gameId })
    if (res && !res.success && res.message) {
      console.error('[GameView] Action failed:', res.message)
      ElMessage({ message: res.message, type: 'error', duration: 3000 })
      // If the action failed due to phase mismatch, refresh the state
      if (res.message.includes('phase') || res.message.includes('Phase')) {
        const state = await gameService.getState(gameId.toString())
        gameStore.setState(state)
      }
    }
    return res
  } finally {
    actionPending.value = false
  }
}

const isHost = computed(() => {
  const hostId = gameStore.state?.hostId ?? roomStore.room?.hostId
  return hostId === userStore.userId
})

useNavigationGuard()

const isNight = computed(() => gameStore.state?.phase === 'NIGHT')

const phaseLabel = computed(() => {
  switch (gameStore.state?.phase) {
    case 'SHERIFF_ELECTION':
      return '警长竞选 Sheriff Election'
    case 'DAY_DISCUSSION':
      return '白天 Day Phase'
    case 'DAY_VOTING':
      return '投票 Voting'
    case 'NIGHT':
      return '黑夜 Night Phase'
    case 'GAME_OVER':
      return '游戏结束 Game Over'
    default:
      return ''
  }
})

const actionHint = computed(() => {
  switch (gameStore.state?.phase) {
    case 'SHERIFF_ELECTION':
      return 'Waiting for sheriff election...'
    case 'DAY_DISCUSSION':
      return 'Listen to discussions...'
    case 'DAY_VOTING':
      return 'Tap a player to vote'
    case 'NIGHT':
      return 'Night falls... close your eyes'
    default:
      return ''
  }
})

const visibleEvents = computed(() => (gameStore.state?.events ?? []).slice(-5).reverse())

function playerSlotVariant(player: GamePlayer) {
  if (!player.isAlive) return 'dead' as const
  if (player.userId === userStore.userId) return 'me' as const
  return 'alive' as const
}

async function handleRoleConfirm() {
  hasConfirmedRole.value = true
  await action({ actionType: 'CONFIRM_ROLE' })
  // Re-fetch state so confirmedCount is accurate even if the RoleConfirmed
  // STOMP event arrived before the subscription was established.
  const updated = await gameService.getState(route.params.gameId as string)
  gameStore.setState(updated)
}

async function handleStartNight() {
  await action({ actionType: 'START_NIGHT' })
}

watch(
  () => gameStore.state?.phase,
  (phase) => {
    if (phase !== 'ROLE_REVEAL') {
      hasConfirmedRole.value = false
      isRoleRevealed.value = false
    }
  },
)

async function handleSheriffRun() {
  await action({ actionType: 'SHERIFF_CAMPAIGN' })
}
async function handleSheriffPass() {
  await action({ actionType: 'SHERIFF_PASS' })
}
async function handleSheriffWithdraw() {
  await action({ actionType: 'SHERIFF_QUIT' })
}
async function handleSheriffStartCampaign() {
  await action({ actionType: 'SHERIFF_START_SPEECH' })
}
async function handleSheriffQuit() {
  await action({ actionType: 'SHERIFF_QUIT_CAMPAIGN' })
}
async function handleSheriffVote(userId: string) {
  await action({ actionType: 'SHERIFF_VOTE', targetId: userId })
}
async function handleSheriffAbstain() {
  await action({ actionType: 'SHERIFF_ABSTAIN' })
}
async function handleSheriffAdvanceSpeech() {
  await action({ actionType: 'SHERIFF_ADVANCE_SPEECH' })
}
async function handleSheriffRevealResult() {
  await action({ actionType: 'SHERIFF_REVEAL_RESULT' })
}
async function handleSheriffAppoint(userId: string) {
  await action({ actionType: 'SHERIFF_APPOINT', targetId: userId })
}
async function handleSheriffStartNight() {
  await action({ actionType: 'START_NIGHT' })
}

async function handleRevealResult() {
  await action({ actionType: 'REVEAL_NIGHT_RESULT' })
}
async function handleStartVote() {
  const currentPhase = gameStore.state?.phase
  const currentSubPhase = gameStore.state?.dayPhase?.subPhase

  if (currentPhase !== 'DAY_DISCUSSION') {
    ElMessage({
      message: `当前游戏阶段不正确，无法开始投票。当前阶段: ${currentPhase}`,
      type: 'error',
      duration: 5000,
    })
    // Refresh state to sync with backend
    const gameId = route.params.gameId as string
    const state = await gameService.getState(gameId)
    gameStore.setState(state)
    return
  }

  if (currentSubPhase !== 'RESULT_REVEALED') {
    ElMessage({
      message: `请先公布昨晚的结果`,
      type: 'error',
      duration: 5000,
    })
    return
  }

  await action({ actionType: 'DAY_ADVANCE' })
}
async function handleDayVote(targetId: string) {
  await action({ actionType: 'DAY_VOTE', targetId })
}
async function handleDaySkip() {
  await action({ actionType: 'DAY_SKIP' })
}
async function handleDaySelectPlayer(userId: string) {
  await action({ actionType: 'SELECT_PLAYER', targetId: userId })
}

async function handleNightSelect(userId: string) {
  // Wolf selection is shared with teammates in real-time; other roles select locally only
  if (gameStore.state?.nightPhase?.subPhase === 'WEREWOLF_PICK') {
    await action({ actionType: 'WOLF_SELECT', targetId: userId })
  }
}
async function handleNightConfirm(targetId?: string) {
  //console.log('[GameView] handleNightConfirm called with targetId:', targetId)
  const subPhase = gameStore.state?.nightPhase?.subPhase
  //console.log('[GameView] handleNightConfirm subPhase:', subPhase)
  switch (subPhase) {
    case 'WEREWOLF_PICK':
      if (targetId) await action({ actionType: 'WOLF_KILL', targetId })
      break
    case 'SEER_PICK':
      if (targetId) await action({ actionType: 'SEER_CHECK', targetId })
      break
    case 'SEER_RESULT':
      await action({ actionType: 'SEER_CONFIRM' })
      break
    case 'WITCH_ACT':
      // Witch confirm - submit all decisions to advance phase
      //console.log('[GameView] handleNightConfirm calling trySubmitWitchAct')
      await trySubmitWitchAct()
      break
    case 'GUARD_PICK':
      if (targetId) await action({ actionType: 'GUARD_PROTECT', targetId })
      break
    default:
    //console.log('[GameView] handleNightConfirm unknown subPhase:', subPhase)
  }
}

// Witch decisions - now submits immediately after each action
const witchUseAntidote = ref<boolean | undefined>(undefined)
const witchPoisonTargetId = ref<string | null | undefined>(undefined)

async function handleWitchAntidote() {
  witchUseAntidote.value = true
  witchPoisonTargetId.value = null // Automatically not using poison
  await trySubmitWitchAct()
}

async function handleWitchPassAntidote() {
  witchUseAntidote.value = false
  witchPoisonTargetId.value = null // Automatically not using poison
  await trySubmitWitchAct()
}

async function handleWitchPoison(targetId: string) {
  witchUseAntidote.value = false // Automatically not using antidote
  witchPoisonTargetId.value = targetId
  await trySubmitWitchAct()
}

async function handleWitchPassPoison() {
  witchUseAntidote.value = false
  witchPoisonTargetId.value = null
  await trySubmitWitchAct()
}

async function handleWitchSkip() {
  // When witch has no items, just skip the phase
  await action({
    actionType: 'WITCH_ACT',
    payload: { useAntidote: false, poisonTargetUserId: null },
  })
}

async function trySubmitWitchAct() {
  const nightPhase = gameStore.state?.nightPhase
  if (!nightPhase) {
    return
  }

  // Allow submission as soon as any decision is made
  const hasDecision =
    witchUseAntidote.value !== undefined || witchPoisonTargetId.value !== undefined
  if (!hasDecision) {
    return
  }

  const payload: Record<string, unknown> = {
    useAntidote: witchUseAntidote.value ?? false,
    poisonTargetUserId: witchPoisonTargetId.value,
  }
  await action({ actionType: 'WITCH_ACT', payload })
  witchUseAntidote.value = undefined
  witchPoisonTargetId.value = undefined
}

async function handleVotingSelect(_userId: string) {
  // Selection is local UI state only — no backend call needed
}
async function handleVotingVote(targetId: string) {
  await action({ actionType: 'SUBMIT_VOTE', targetId })
}
async function handleVotingSkip() {
  await action({ actionType: 'SUBMIT_VOTE' }) // no targetId = abstain
}
async function handleVotingUnvote() {
  await action({ actionType: 'VOTING_UNVOTE' })
}
async function handleVotingReveal() {
  await action({ actionType: 'VOTING_REVEAL_TALLY' })
}
async function handleVotingContinue() {
  await action({ actionType: 'VOTING_CONTINUE' })
}
async function handleHunterShoot(userId: string) {
  await action({ actionType: 'HUNTER_SHOOT', targetId: userId })
}
async function handleHunterPass() {
  await action({ actionType: 'HUNTER_PASS' })
}
async function handlePassBadge(userId: string) {
  await action({ actionType: 'BADGE_PASS', targetId: userId })
}
async function handleDestroyBadge() {
  await action({ actionType: 'BADGE_DESTROY' })
}

async function debugVoting(scenario: string) {
  await http.post('/debug/voting/scenario', { scenario })
}
async function debugVotingAdvance() {
  await http.post('/debug/voting/advance')
}

async function debugGameOver(winner: string) {
  await http.post('/debug/game/over', { winner })
}

async function debugNight(scenario: string) {
  await http.post('/debug/night/scenario', { scenario })
}
async function debugNightAdvance() {
  await http.post('/debug/night/advance')
}

async function debugStartGame() {
  await http.post('/debug/game/start')
}
async function debugSkipRole() {
  await http.post('/debug/role/skip')
}
async function debugSkipToNight() {
  await http.post('/debug/role/skip-night')
}
async function debugConfirmAll() {
  await http.post('/debug/role/confirm-all')
}

async function debugScenario(scenario: string) {
  await http.post('/debug/day/scenario', { scenario })
}

async function debugDay(preset: string) {
  await http.post('/debug/day/phase', { preset })
}

async function debugSheriff(preset: string) {
  await http.post('/debug/sheriff/phase', { preset })
}
async function debugExitSheriff() {
  await http.post('/debug/sheriff/exit')
}
async function debugCandidateRun(userId: string, nickname: string, avatar: string) {
  await http.post('/debug/sheriff/candidate', { userId, nickname, avatar, action: 'RUN' })
}
async function debugCandidateRemove(userId: string) {
  await http.post('/debug/sheriff/candidate', { userId, action: 'REMOVE' })
}

function onPlayerTap(player: GamePlayer) {
  if (!player.isAlive) return
  // Send action to backend — backend validates whether this tap is a valid game action
  action({ actionType: 'SELECT_PLAYER', targetId: player.userId })
}

onMounted(async () => {
  const gameId = route.params.gameId as string
  try {
    const state = await gameService.getState(gameId)
    gameStore.setState(state)
  } catch {
    router.push({ name: 'lobby' })
    return
  }

  if (userStore.token) {
    const client = createStompClient(userStore.token)
    client.onConnect = () => {
      subscribeToTopic(`/topic/game/${gameId}`, async (msg: { body: string }) => {
        const data = JSON.parse(msg.body)
        // Mock sends full state snapshots; real backend sends typed domain events
        if (data.type === 'GAME_STATE_UPDATE') {
          gameStore.setState(data.payload)
        }
        if (data.type === 'GAME_EVENT') {
          gameStore.addEvent(data.payload)
        }
        // Real backend: a player confirmed their role → increment counter
        if (data.type === 'RoleConfirmed') {
          gameStore.incrementConfirmedCount()
        }
        // Real backend: phase transition → re-fetch full state (covers ROLE_REVEAL→NIGHT, etc.)
        if (data.type === 'PhaseChanged') {
          // Normalize phase name for backward compatibility
          const normalizedPhase = normalizePhaseName(data.phase)
          
          // Preserve current audioSequence to prevent it from being lost
          const currentAudio = gameStore.state?.audioSequence
          // Small delay to ensure backend transaction has committed before we read state
          await new Promise((r) => setTimeout(r, 100))
          let state = await gameService.getState(gameId)
          // If fetched state doesn't match the event (transaction not committed yet), retry once
          if (normalizedPhase && state.phase !== normalizedPhase) {
            await new Promise((r) => setTimeout(r, 300))
            state = await gameService.getState(gameId)
          }
          // Normalize state phase for consistency
          if (state) {
            state.phase = normalizePhaseName(state.phase) as any
          }
          // Preserve audioSequence - AudioSequence event will update it if needed
          gameStore.setState({ ...state, audioSequence: currentAudio })
        }
        // Real backend: night sub-phase advanced (e.g. WAITING → WEREWOLF_PICK) → re-fetch state
        if (data.type === 'NightSubPhaseChanged') {
          // Small delay to ensure backend transaction has committed before we read state
          await new Promise((r) => setTimeout(r, 100))
          let state = await gameService.getState(gameId)
          // If fetched state doesn't match the event, retry once
          if (data.subPhase && state.nightPhase?.subPhase !== data.subPhase) {
            await new Promise((r) => setTimeout(r, 300))
            state = await gameService.getState(gameId)
          }
          // Preserve audioSequence from STOMP events — getState() no longer includes it
          const currentAudio = gameStore.state?.audioSequence
          gameStore.setState({ ...state, audioSequence: currentAudio })
        }
        // Real backend: audio sequence from backend
        if (data.type === 'AudioSequence') {
          const state = gameStore.state
          if (state) {
            gameStore.setState({
              ...state,
              audioSequence: data.audioSequence,
            })
          }
        }
        // OpenEyes / CloseEyes are informational events from the night orchestrator.
        // Audio is driven exclusively by AudioSequence events (backend-calculated).
        // Handling audio here would duplicate what AudioSequence already plays.
        if (data.type === 'OpenEyes' || data.type === 'CloseEyes') {
          console.log(`[night] ${data.type} for role ${data.role}`)
        }
        // New event-driven approach: RoleAction event
        if (data.type === 'RoleAction') {
          // Handle role action prompt (for future UI enhancements)
          console.log('RoleAction received:', data)
          // This can be used to show action prompts for the current player
        }
        // Real backend: night result (kills) → re-fetch state
        if (data.type === 'NightResult') {
          const state = await gameService.getState(gameId)
          const currentAudio = gameStore.state?.audioSequence
          gameStore.setState({ ...state, audioSequence: currentAudio })
        }
        // Sheriff elected (winner or host appointment) → re-fetch to get updated sheriff state
        if (data.type === 'SheriffElected') {
          const state = await gameService.getState(gameId)
          const currentAudio = gameStore.state?.audioSequence
          gameStore.setState({ ...state, audioSequence: currentAudio })
        }
        // Idiot revealed → re-fetch to get updated canVote/idiotRevealed player state
        if (data.type === 'IdiotRevealed') {
          const state = await gameService.getState(gameId)
          const currentAudio = gameStore.state?.audioSequence
          gameStore.setState({ ...state, audioSequence: currentAudio })
        }
        // Vote cast → re-fetch so votedPlayerIds/votesSubmitted updates for all viewers
        if (data.type === 'VoteSubmitted') {
          const state = await gameService.getState(gameId)
          const currentAudio = gameStore.state?.audioSequence
          gameStore.setState({ ...state, audioSequence: currentAudio })
        }
        // Vote tally revealed → re-fetch to get updated subPhase and tally
        // This is the main event that should trigger UI update; PhaseChanged is also sent but VoteTally is more complete
        if (data.type === 'VoteTally') {
          const state = await gameService.getState(gameId)
          const currentAudio = gameStore.state?.audioSequence
          gameStore.setState({ ...state, audioSequence: currentAudio })
        }
        // Both mock (GAME_OVER) and real backend (GameOver) navigate to result
        if (data.type === 'GAME_OVER' || data.type === 'GameOver') {
          router.push({ name: 'result', params: { gameId } })
        }
      })
      // Private channel for role-specific info (night actions, etc.)
      subscribeToTopic('/user/queue/private', (msg: { body: string }) => {
        const data = JSON.parse(msg.body)
        if (data.type === 'WolfSelectionChanged') {
          gameStore.updateNightPhaseSelection(data.selectedTargetUserId)
        } else {
          gameStore.addEvent(data)
        }
      })
    }
    client.activate()
  }
})

onUnmounted(() => {
  disconnectStomp()
})

// ── Audio file helpers for new event-driven approach ─────────────────────────────
function getOpenEyesAudioFile(role: string): string {
  const audioMap: Record<string, string> = {
    'WEREWOLF': 'wolf_open_eyes.mp3',
    'SEER': 'seer_open_eyes.mp3',
    'WITCH': 'witch_open_eyes.mp3',
    'GUARD': 'guard_open_eyes.mp3'
  }
  return audioMap[role] || ''
}

function getCloseEyesAudioFile(role: string): string {
  const audioMap: Record<string, string> = {
    'WEREWOLF': 'wolf_close_eyes.mp3',
    'SEER': 'seer_close_eyes.mp3',
    'SEER_RESULT': 'seer_close_eyes.mp3',
    'WITCH': 'witch_close_eyes.mp3',
    'GUARD': 'guard_close_eyes.mp3'
  }
  return audioMap[role] || ''
}

// ── Backward compatibility helpers ────────────────────────────────────────────────
/**
 * Map old phase names to new ones for backward compatibility
 */
function normalizePhaseName(phase: string | undefined): string {
  if (!phase) return phase || ''
  const phaseMap: Record<string, string> = {
    'DAY_DISCUSSION': 'DAY_DISCUSSION',
    'DAY_VOTING': 'DAY_VOTING'
  }
  return phaseMap[phase] || phase
}
</script>

<style scoped>
.audio-mute-btn {
  position: fixed;
  bottom: 1rem;
  right: 1rem;
  z-index: 100;
  width: 2.5rem;
  height: 2.5rem;
  border-radius: 50%;
  border: 1px solid var(--border);
  background: var(--card);
  font-size: 1.25rem;
  line-height: 1;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
}

.night-mode .audio-mute-btn {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.2);
}

.game-wrap {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: var(--bg);
  transition: background 0.5s;
}

.night-mode {
  background: var(--ink);
  color: #f5f0e8;
}

.game-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.phase-label {
  font-family: 'Noto Serif SC', serif;
  font-size: 1rem;
  font-weight: 600;
}

.day-badge {
  font-size: 0.75rem;
  background: var(--red);
  color: #fff;
  padding: 0.25rem 0.625rem;
  border-radius: 1rem;
}

.player-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.625rem;
  padding: 1rem;
  flex: 1;
}

.sheriff-badge {
  position: absolute;
  top: 4px;
  right: 6px;
  font-size: 0.75rem;
}

.dead-overlay {
  font-size: 1.5rem;
  color: var(--muted);
}

.event-log {
  padding: 0 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  max-height: 120px;
  overflow-y: auto;
}

.event-item {
  font-size: 0.8rem;
  color: var(--muted);
  padding: 0.25rem 0;
  border-bottom: 1px solid var(--border-l);
}

.action-panel {
  padding: 1rem;
  border-top: 1px solid var(--border-l);
  background: var(--paper);
  min-height: 80px;
}

.night-mode .action-panel {
  background: rgba(255, 255, 255, 0.05);
  border-top-color: rgba(255, 255, 255, 0.1);
}

.action-hint {
  text-align: center;
  color: var(--muted);
  font-size: 0.875rem;
  margin: 0;
}

/* Waiting screen (after role confirmed) */
.waiting-screen {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100dvh;
  gap: 0.75rem;
  background: var(--bg);
  padding: 2rem 1.5rem;
  text-align: center;
}

.waiting-icon {
  font-size: 3rem;
  margin-bottom: 0.5rem;
}

.waiting-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--text);
}

.waiting-subtitle {
  font-size: 0.875rem;
  color: var(--muted);
}

.waiting-count {
  font-size: 1rem;
  color: var(--gold);
  font-weight: 500;
  margin-top: 0.5rem;
}

/* Debug panel */
.debug-panel {
  border: 1px dashed var(--border);
  border-radius: 0.375rem;
  padding: 0.625rem 0.75rem;
  background: rgba(160, 120, 48, 0.04);
  margin: 0.5rem 1rem 0.75rem;
}

.debug-title {
  font-size: 0.625rem;
  letter-spacing: 0.1em;
  color: var(--gold);
  text-transform: uppercase;
  margin-bottom: 0.5rem;
}

.debug-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
}

.debug-btn {
  background: none;
  border: 1px solid var(--border);
  border-radius: 0.25rem;
  padding: 0.25rem 0.625rem;
  font-size: 0.625rem;
  color: var(--muted);
  cursor: pointer;
  font-family: inherit;
}

.debug-btn:hover {
  border-color: var(--gold);
  color: var(--gold);
}

.debug-btn-exit {
  margin-left: auto;
}
</style>
