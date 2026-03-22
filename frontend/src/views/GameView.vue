<template>
  <div :class="{ 'night-mode': isNight }" class="game-wrap">
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
      </div>
    </template>

    <!-- Sheriff Election phase -->
    <template
      v-else-if="gameStore.state?.phase === 'SHERIFF_ELECTION' && gameStore.state?.sheriffElection"
    >
      <SheriffElection
        :election="gameStore.state.sheriffElection"
        :my-user-id="userStore.userId ?? ''"
        :is-host="isHost"
        @run="handleSheriffRun"
        @pass="handleSheriffPass"
        @withdraw="handleSheriffWithdraw"
        @start-campaign="handleSheriffStartCampaign"
        @quit="handleSheriffQuit"
        @vote="handleSheriffVote"
        @confirm-vote="handleSheriffConfirmVote"
        @abstain="handleSheriffAbstain"
        @advance-speech="handleSheriffAdvanceSpeech"
        @reveal-result="handleSheriffRevealResult"
      />
    </template>

    <!-- Night phase -->
    <template v-else-if="gameStore.state?.phase === 'NIGHT' && gameStore.state?.nightPhase">
      <NightPhase
        :night-phase="gameStore.state.nightPhase"
        :players="gameStore.state.players"
        :my-user-id="userStore.userId ?? ''"
        :my-role="gameStore.state.myRole"
        @select-player="handleNightSelect"
        @confirm="handleNightConfirm"
        @witch-antidote="handleWitchAntidote"
        @witch-pass-antidote="handleWitchPassAntidote"
        @witch-poison="handleWitchPoison"
        @witch-pass-poison="handleWitchPassPoison"
      />
    </template>

    <!-- Voting phase -->
    <template v-else-if="gameStore.state?.phase === 'VOTING' && gameStore.state?.votingPhase">
      <VotingPhase
        :voting-phase="gameStore.state.votingPhase"
        :players="gameStore.state.players"
        :my-user-id="userStore.userId ?? ''"
        :is-host="isHost"
        :my-role="gameStore.state?.myRole"
        :vote-history="gameStore.state?.voteHistory"
        @select-player="handleVotingSelect"
        @vote="handleVotingVote"
        @skip-vote="handleVotingSkip"
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
    <template v-else-if="gameStore.state?.phase === 'DAY' && gameStore.state?.dayPhase">
      <DayPhase
        :day-phase="gameStore.state.dayPhase"
        :players="gameStore.state.players"
        :my-user-id="userStore.userId ?? ''"
        :is-host="isHost"
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
        <button class="debug-btn" @click="debugStartGame">Role Reveal</button>
        <button class="debug-btn" @click="debugSkipRole">Skip → Sheriff</button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Day Scenarios</div>
      <div class="debug-btns" data-testid="debug-day-scenario-btns">
        <button class="debug-btn" @click="debugScenario('HOST_HIDDEN')">Host·Hidden</button>
        <button class="debug-btn" @click="debugScenario('HOST_REVEALED')">Host·Revealed</button>
        <button class="debug-btn" @click="debugScenario('DEAD')">Dead</button>
        <button class="debug-btn" @click="debugScenario('ALIVE_HIDDEN')">Alive·Hidden</button>
        <button class="debug-btn" @click="debugScenario('ALIVE_REVEALED')">Alive·Revealed</button>
        <button class="debug-btn" @click="debugScenario('GUEST')">Guest</button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Day Phase</div>
      <div class="debug-btns" data-testid="debug-day-btns">
        <button class="debug-btn" @click="debugDay('HIDDEN')">Hidden</button>
        <button class="debug-btn" @click="debugDay('REVEALED')">Revealed</button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Night Screens</div>
      <div class="debug-btns">
        <button class="debug-btn" @click="debugNight('WEREWOLF')">Werewolf</button>
        <button class="debug-btn" @click="debugNight('SEER_PICK')">Seer: Pick</button>
        <button class="debug-btn" @click="debugNight('SEER_RESULT')">Seer: Result</button>
        <button class="debug-btn" @click="debugNight('WITCH')">Witch</button>
        <button class="debug-btn" @click="debugNight('GUARD')">Guard</button>
        <button class="debug-btn" @click="debugNight('WAITING')">Waiting</button>
        <button class="debug-btn debug-btn-exit" @click="debugNightAdvance">→ Day</button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Voting Screens</div>
      <div class="debug-btns" data-testid="debug-voting-btns">
        <button class="debug-btn" @click="debugVoting('VOTING')">Voting</button>
        <button class="debug-btn" @click="debugVoting('VOTING_VOTED')">Voted</button>
        <button class="debug-btn" @click="debugVoting('VOTING_REVEALED')">Revealed</button>
        <button class="debug-btn" @click="debugVoting('HUNTER_SHOOT')">Hunter</button>
        <button class="debug-btn" @click="debugVoting('BADGE_HANDOVER')">Badge: Pick</button>
        <button class="debug-btn" @click="debugVoting('BADGE_SHERIFF')">Badge: Sheriff</button>
        <button class="debug-btn" @click="debugVoting('BADGE_BURNED')">Badge: Burned</button>
        <button class="debug-btn" @click="debugVoting('VOTING_NO_HISTORY')">No History</button>
        <button class="debug-btn" @click="debugVoting('VOTING_NO_DATA')">No Data</button>
        <button class="debug-btn debug-btn-exit" @click="debugVotingAdvance">→ Night</button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Game Over</div>
      <div class="debug-btns">
        <button class="debug-btn debug-btn-exit" @click="debugGameOver('VILLAGER')">
          → Village Wins
        </button>
        <button class="debug-btn debug-btn-exit" @click="debugGameOver('WEREWOLF')">
          → Wolf Wins
        </button>
      </div>
      <div class="debug-title" style="margin-top: 0.5rem">🛠 Debug — Sheriff Screens</div>
      <div class="debug-btns" data-testid="debug-sheriff-btns">
        <button class="debug-btn" @click="debugSheriff('SIGNUP')">Sign-up</button>
        <button class="debug-btn" @click="debugSheriff('SPEECH_CANDIDATE')">Speech: Me</button>
        <button class="debug-btn" @click="debugSheriff('SPEECH_AUDIENCE')">Speech: Watch</button>
        <button class="debug-btn" @click="debugSheriff('VOTING')">Voting</button>
        <button class="debug-btn" @click="debugSheriff('RESULT')">Result</button>
        <button class="debug-btn debug-btn-exit" @click="debugExitSheriff">← Day</button>
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
import type { GamePlayer } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const gameStore = useGameStore()
const roomStore = useRoomStore()

const isMock = import.meta.env.VITE_MOCK === 'true'
const hasConfirmedRole = ref(false)
const isRoleRevealed = ref(false)

// Wraps gameService.submitAction to always include the gameId from the route
async function action(req: Omit<import('@/types').GameActionRequest, 'gameId'>) {
  return gameService.submitAction({ ...req, gameId: parseInt(route.params.gameId as string) })
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
    case 'DAY':
      return '白天 Day Phase'
    case 'VOTING':
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
    case 'DAY':
      return 'Listen to discussions...'
    case 'VOTING':
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
async function handleSheriffConfirmVote() {
  await action({ actionType: 'SHERIFF_CONFIRM_VOTE' })
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

async function handleRevealResult() {
  await action({ actionType: 'REVEAL_NIGHT_RESULT' })
}
async function handleStartVote() {
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
  await action({ actionType: 'NIGHT_SELECT', targetId: userId })
}
async function handleNightConfirm(targetId?: string) {
  await action({ actionType: 'NIGHT_CONFIRM', targetId })
}
async function handleWitchAntidote() {
  await action({ actionType: 'NIGHT_WITCH_USE_ANTIDOTE' })
}
async function handleWitchPassAntidote() {
  await action({ actionType: 'NIGHT_WITCH_PASS_ANTIDOTE' })
}
async function handleWitchPoison(targetId: string) {
  await action({ actionType: 'NIGHT_WITCH_USE_POISON', targetId })
}
async function handleWitchPassPoison() {
  await action({ actionType: 'NIGHT_WITCH_PASS_POISON' })
}

async function handleVotingSelect(userId: string) {
  await action({ actionType: 'VOTING_SELECT', targetId: userId })
}
async function handleVotingVote(targetId: string) {
  await action({ actionType: 'VOTING_VOTE', targetId })
}
async function handleVotingSkip() {
  await action({ actionType: 'VOTING_SKIP' })
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
          const state = await gameService.getState(gameId)
          gameStore.setState(state)
        }
        // Both mock (GAME_OVER) and real backend (GameOver) navigate to result
        if (data.type === 'GAME_OVER' || data.type === 'GameOver') {
          router.push({ name: 'result', params: { gameId } })
        }
      })
      // Private channel for role-specific info (night actions, etc.)
      subscribeToTopic('/user/queue/private', (msg: { body: string }) => {
        const data = JSON.parse(msg.body)
        gameStore.addEvent(data)
      })
    }
    client.activate()
  }
})

onUnmounted(() => {
  disconnectStomp()
})
</script>

<style scoped>
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
