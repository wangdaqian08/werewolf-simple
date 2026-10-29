<template>
  <div class="sheriff-wrap">
    <!-- Header: phase chip + countdown arc (SPEECH only) -->
    <div class="sheriff-header">
      <div class="phase-chip">{{ phaseChipLabel }}</div>
      <LanguageToggle inline />
      <CountdownArc
        v-if="election.subPhase === 'SPEECH'"
        :remaining-ms="timer?.remainingMs ?? 0"
        :duration-ms="timer?.durationMs ?? 0"
        :running="timer?.running ?? false"
        :is-host="isHost"
        :presets="timerPresets"
        @start-timer="(s) => emit('start-timer', s)"
        @stop-timer="emit('stop-timer')"
      />
    </div>

    <!-- Below-header row: Action chip on the right (wolf self-destruct) -->
    <div class="below-header-row">
      <div />
      <ActionMenu
        phase="SHERIFF_ELECTION"
        :sub-phase="election.subPhase"
        :my-role="myRole"
        :is-alive="isAlive ?? false"
        :players="players"
        :my-user-id="myUserId"
        @self-destruct="(target) => emit('self-destruct', target)"
      />
    </div>

    <!-- ── SIGNUP ── -->
    <!--
      Identities are deliberately hidden during SIGNUP: who joined the campaign
      affects everyone's decision (especially werewolves piling on a known
      strong claim). Players see only the aggregate count + decision progress.
      The campaign auto-advances to SPEECH once every alive player has decided —
      there is no host "Start Campaign" button anymore.
    -->
    <template v-if="election.subPhase === 'SIGNUP'">
      <div class="info-banner">
        <div class="info-title">{{ $t('sheriff.sheriffElection') }}</div>
        <p class="info-body">
          <i18n-t keypath="sheriff.signupInfo" tag="span">
            <template #weight><b class="gold">1.5×</b></template>
          </i18n-t>
        </p>
      </div>

      <div class="signup-stats">
        <div class="stat-row">
          <span class="stat-label">{{ $t('sheriff.decided') }}</span>
          <span class="stat-value gold" data-testid="sheriff-decision-progress"
            >{{ election.decisionProgress?.decided ?? 0 }} /
            {{ election.decisionProgress?.total ?? 0 }}</span
          >
        </div>
      </div>

      <div class="spacer" />
      <div class="action-footer">
        <template v-if="iAmCandidate">
          <button
            class="btn btn-danger-outline"
            data-testid="sheriff-withdraw"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('withdraw')"
          >
            {{ $t('sheriff.withdraw') }}
          </button>
        </template>
        <template v-else>
          <button
            class="btn btn-gold"
            data-testid="sheriff-run"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('run')"
          >
            {{ $t('sheriff.runForSheriff') }}
          </button>
          <button
            v-if="!election.hasPassed"
            class="btn btn-outline"
            data-testid="sheriff-pass"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('pass')"
          >
            {{ $t('sheriff.pass') }}
          </button>
        </template>
      </div>
    </template>

    <!-- ── SPEECH - My Turn ── -->
    <template v-else-if="election.subPhase === 'SPEECH' && election.currentSpeakerId === myUserId">
      <div class="your-turn-box">
        <div class="yt-tag">{{ $t('sheriff.yourTurn') }}</div>
        <div class="yt-title">{{ $t('sheriff.speechPrompt') }}</div>
        <div v-if="$i18n.locale === 'zh'" class="yt-sub">Make your case to the village</div>
      </div>

      <div class="section-label">{{ $t('sheriff.speakingOrder') }}</div>
      <div class="speaking-list">
        <div
          v-for="(uid, idx) in election.speakingOrder"
          :key="uid"
          :class="speakingRowClass(uid, idx)"
          class="speaking-row"
        >
          <span class="speak-icon-cell">{{ speakingIcon(uid) }}</span>
          <span
            class="speak-name-cell"
            :class="{ 'speak-active': uid === election.currentSpeakerId }"
          >
            {{ speakerLabel(uid, idx) }}
          </span>
        </div>
      </div>

      <div class="spacer" />
      <template v-if="iAmCandidate">
        <div class="quit-warning">{{ $t('sheriff.quitWarning') }}</div>
        <div class="action-footer">
          <button
            class="btn btn-danger-outline"
            data-testid="sheriff-quit"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('quit')"
          >
            {{ $t('sheriff.quitCampaign') }}
          </button>
          <template v-if="isHost">
            <div class="host-divider" />
            <button
              class="btn btn-primary"
              data-testid="sheriff-advance-speech"
              :class="{ 'is-loading': actionPending }"
              :disabled="actionPending"
              @click="emit('advanceSpeech')"
            >
              {{ $t('sheriff.nextSpeaker') }}
            </button>
          </template>
        </div>
      </template>
      <template v-else-if="isHost">
        <div class="action-footer">
          <button
            class="btn btn-primary"
            data-testid="sheriff-advance-speech"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('advanceSpeech')"
          >
            {{ $t('sheriff.nextSpeaker') }}
          </button>
        </div>
      </template>
    </template>

    <!-- ── SPEECH - Audience ── -->
    <template v-else-if="election.subPhase === 'SPEECH'">
      <div v-if="currentSpeaker" class="speaker-hero">
        <Avatar
          class="speaker-avatar-big"
          :avatar="currentSpeaker.avatar"
          :nickname="currentSpeaker.nickname"
          emoji="😊"
          size="lg"
        />
        <div class="speaker-name-big">{{ currentSpeaker.nickname }}</div>
        <div class="speaker-now-label">{{ $t('sheriff.speakingNowLabel') }}</div>
      </div>

      <div class="section-label">{{ $t('sheriff.speakingOrder') }}</div>
      <div class="speaking-list">
        <div
          v-for="(uid, idx) in election.speakingOrder"
          :key="uid"
          :class="speakingRowClass(uid, idx)"
          class="speaking-row"
        >
          <span class="speak-icon-cell">{{ speakingIcon(uid) }}</span>
          <span
            class="speak-name-cell"
            :class="{ 'speak-active': uid === election.currentSpeakerId }"
          >
            {{ speakerLabel(uid, idx) }}
          </span>
        </div>
      </div>

      <div class="spacer" />
      <div class="action-footer">
        <template v-if="iAmCandidate">
          <div class="quit-warning">{{ $t('sheriff.quitWarning') }}</div>
          <button
            class="btn btn-danger-outline"
            data-testid="sheriff-quit"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('quit')"
          >
            {{ $t('sheriff.quitCampaign') }}
          </button>
        </template>
        <button v-else class="btn btn-secondary" disabled>
          {{ $t('sheriff.waitingForVote') }}
        </button>
        <template v-if="isHost">
          <div class="host-divider" />
          <button
            class="btn btn-primary"
            data-testid="sheriff-advance-speech"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('advanceSpeech')"
          >
            {{ $t('sheriff.nextSpeaker') }}
          </button>
        </template>
      </div>
    </template>

    <!-- ── VOTING ── -->
    <template v-else-if="election.subPhase === 'VOTING'">
      <div class="info-banner-sm">
        <div class="muted sm">{{ $t('sheriff.pickPrompt') }}</div>
        <div v-if="$i18n.locale === 'zh'" class="gold bold sm">
          Speeches done — vote for sheriff
        </div>
      </div>

      <div class="section-label">
        {{ $t('sheriff.candidates', { n: runningCandidates.length }) }}
      </div>
      <div class="vote-list">
        <div
          v-for="c in runningCandidates"
          :key="c.userId"
          :class="{
            'vote-row-selected':
              !election.abstained && (election.myVote ?? selectedId) === c.userId,
            'vote-row-self': c.userId === myUserId,
          }"
          class="vote-row"
          @click="canSelectVote(c.userId) && (selectedId = c.userId)"
        >
          <Avatar
            class="cand-avatar"
            :avatar="c.avatar"
            :nickname="c.nickname"
            emoji="😊"
            size="sm"
          />
          <div class="cand-info-col">
            <span class="cand-name">
              <span v-if="seatByUserId.get(c.userId) != null" class="cand-seat"
                >{{ $t('common.seat', { n: seatByUserId.get(c.userId) }) }} · </span
              >{{ c.nickname }}
            </span>
            <span class="cand-sub-status">{{
              election.myVote === c.userId
                ? $t('sheriff.votedMark')
                : !election.abstained && selectedId === c.userId
                  ? $t('sheriff.selectedMark')
                  : c.userId === myUserId
                    ? $t('sheriff.self')
                    : $t('sheriff.candidate')
            }}</span>
          </div>
          <div
            v-if="!election.abstained && (election.myVote === c.userId || selectedId === c.userId)"
            class="check-circle"
          >
            ✓
          </div>
        </div>
      </div>

      <div v-if="quitCandidates.length" class="quit-note">
        {{
          $t('sheriff.quitNote', {
            names: quitCandidates.map((c) => c.nickname).join($t('common.listSep')),
          })
        }}
      </div>

      <div class="spacer" />
      <div class="action-footer">
        <!-- Already voted / abstained — locked state -->
        <template v-if="election.myVote || election.abstained">
          <div class="voted-badge">
            <span v-if="election.myVote">
              {{
                $t('sheriff.voted', {
                  name: runningCandidates.find((c) => c.userId === election.myVote)?.nickname,
                })
              }}
            </span>
            <span v-else>{{ $t('sheriff.abstained') }}</span>
          </div>
        </template>
        <!-- Can still vote -->
        <template v-else-if="election.canVote !== false">
          <button
            class="btn btn-gold"
            data-testid="sheriff-vote"
            :class="{ 'is-loading': actionPending }"
            :disabled="!selectedId || actionPending"
            @click="selectedId && emit('vote', selectedId)"
          >
            {{ $t('sheriff.confirmVote') }}
          </button>
          <button
            class="btn btn-outline"
            data-testid="sheriff-abstain"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('abstain')"
          >
            {{ $t('sheriff.giveUpVote') }}
          </button>
        </template>
        <!-- Cannot vote: RUNNING candidate or speech-quitter -->
        <button
          v-else-if="cannotVoteReason === 'CANDIDATE'"
          class="btn btn-secondary"
          data-testid="sheriff-candidate-no-vote"
          disabled
        >
          {{ $t('sheriff.candidatesCanTVote') }}
        </button>
        <button v-else class="btn btn-secondary" data-testid="sheriff-vote-forfeited" disabled>
          {{ $t('sheriff.voteForfeited') }}
        </button>

        <template v-if="isHost">
          <div class="host-divider" />
          <div v-if="election.voteProgress" class="vote-progress-label">
            {{ election.voteProgress.voted }}/{{ election.voteProgress.total }} voted
          </div>
          <button
            class="btn btn-primary"
            data-testid="sheriff-reveal-result"
            :class="{ 'is-loading': actionPending }"
            :disabled="!election.allVoted || actionPending"
            @click="emit('revealResult')"
          >
            {{ $t('sheriff.revealResult') }}
          </button>
        </template>
      </div>
    </template>

    <!-- ── TIED ── -->
    <template v-else-if="election.subPhase === 'TIED' && election.result">
      <div class="info-banner-sm">
        <div class="gold bold sm">{{ $t('sheriff.tiedAppoint') }}</div>
        <div v-if="$i18n.locale === 'zh'" class="muted sm">Tie vote — host appoints sheriff</div>
      </div>

      <!-- Vote tally, sorted high→low -->
      <div class="vote-columns">
        <div
          v-for="t in sortedTally"
          :key="t.candidateId"
          class="vote-col"
          :class="t.votes === maxVotes ? 'vote-col-winner' : ''"
        >
          <div class="vote-col-head">
            <Avatar
              class="vote-col-avatar"
              :avatar="election.candidates.find((c) => c.userId === t.candidateId)?.avatar"
              :nickname="t.nickname"
              emoji="😊"
              size="sm"
            />
            <div class="vote-col-cname">{{ t.nickname }}</div>
            <div
              class="vote-col-count"
              :class="t.votes === maxVotes ? 'tally-winner' : 'tally-muted'"
            >
              {{ t.votes }}
            </div>
          </div>
          <div class="vote-col-body">
            <div v-for="v in t.voters" :key="v.userId" class="vcol-row">
              <Avatar
                class="vcol-avatar"
                :avatar="v.avatar"
                :nickname="v.nickname"
                emoji="😊"
                size="sm"
              />
              <span class="vcol-seat">{{ v.seatIndex }}</span>
              <span class="vcol-name">{{ v.nickname }}</span>
            </div>
          </div>
        </div>
        <div class="vote-col vote-col-abstain">
          <div class="vote-col-head">
            <div class="vote-col-avatar">—</div>
            <div class="vote-col-cname">{{ $t('vote.abstainCol') }}</div>
            <div class="vote-col-count tally-abstain">{{ election.result.abstainCount }}</div>
          </div>
          <div class="vote-col-body">
            <div v-if="!election.result.abstainVoters.length" class="vcol-row vcol-empty">
              {{ $t('vote.none') }}
            </div>
            <div v-for="v in election.result.abstainVoters" :key="v.userId" class="vcol-row">
              <Avatar
                class="vcol-avatar"
                :avatar="v.avatar"
                :nickname="v.nickname"
                emoji="😊"
                size="sm"
              />
              <span class="vcol-seat">{{ v.seatIndex }}</span>
              <span class="vcol-name">{{ v.nickname }}</span>
            </div>
          </div>
        </div>

        <div v-if="quitCandidates.length" class="vote-col vote-col-quit">
          <div class="vote-col-head">
            <div class="vote-col-avatar">❌</div>
            <div class="vote-col-cname">{{ $t('sheriff.quitCol') }}</div>
            <div class="vote-col-count tally-muted">—</div>
          </div>
          <div class="vote-col-body">
            <div v-for="c in quitCandidates" :key="c.userId" class="vcol-row">
              <Avatar
                class="vcol-avatar"
                :avatar="c.avatar"
                :nickname="c.nickname"
                emoji="😊"
                size="sm"
              />
              <span class="vcol-name">{{ c.nickname }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="spacer" />
      <template v-if="isHost">
        <div class="section-label">{{ $t('sheriff.appointSheriff') }}</div>
        <div class="vote-list">
          <div
            v-for="c in tiedCandidates"
            :key="c.userId"
            :class="{ 'vote-row-selected': appointTarget === c.userId }"
            class="vote-row"
            @click="appointTarget = c.userId"
          >
            <Avatar
              class="cand-avatar"
              :avatar="c.avatar"
              :nickname="c.nickname"
              emoji="😊"
              size="sm"
            />
            <div class="cand-info-col">
              <span class="cand-name">{{ c.nickname }}</span>
            </div>
          </div>
        </div>
        <div class="action-footer">
          <button
            class="btn btn-primary"
            data-testid="sheriff-appoint"
            :class="{ 'is-loading': actionPending }"
            :disabled="!appointTarget || actionPending"
            @click="emit('appoint', appointTarget!)"
          >
            {{ $t('sheriff.appointSheriff2') }}
          </button>
        </div>
      </template>
      <div v-else class="action-footer">
        <button class="btn btn-secondary" disabled>{{ $t('sheriff.waitingForHost') }}</button>
      </div>
    </template>

    <!-- ── RESULT ── -->
    <template v-else-if="election.subPhase === 'RESULT' && election.result">
      <div class="result-content">
        <div class="result-medal">🏅</div>
        <div class="result-title-cn">{{ $t('sheriff.elected') }}</div>
        <div v-if="$i18n.locale === 'zh'" class="result-title-en">Sheriff Elected</div>
        <div class="winner-card">
          <Avatar
            class="winner-avatar"
            :avatar="election.result.sheriffAvatar"
            :nickname="election.result.sheriffNickname"
            emoji="😊"
            size="lg"
          />
          <div class="winner-info">
            <div class="winner-name">{{ election.result.sheriffNickname }}</div>
            <div class="winner-badge-label">{{ $t('sheriff.badgeLabel') }}</div>
          </div>
        </div>
        <div class="power-note">
          <i18n-t keypath="sheriff.powerNote" tag="span">
            <template #weight><b class="gold">1.5×</b></template>
          </i18n-t>
        </div>
        <!-- Vote columns: one per candidate + abstain, sorted high→low -->
        <div class="vote-columns">
          <div
            v-for="t in sortedTally"
            :key="t.candidateId"
            class="vote-col"
            :class="t.votes === maxVotes ? 'vote-col-winner' : ''"
          >
            <div class="vote-col-head">
              <Avatar
                class="vote-col-avatar"
                :avatar="election.candidates.find((c) => c.userId === t.candidateId)?.avatar"
                :nickname="t.nickname"
                emoji="😊"
                size="sm"
              />
              <div class="vote-col-cname">{{ t.nickname }}</div>
              <div
                class="vote-col-count"
                :class="t.votes === maxVotes ? 'tally-winner' : 'tally-muted'"
              >
                {{ t.votes }}
              </div>
            </div>
            <div class="vote-col-body">
              <div v-for="v in t.voters" :key="v.userId" class="vcol-row">
                <Avatar
                  class="vcol-avatar"
                  :avatar="v.avatar"
                  :nickname="v.nickname"
                  emoji="😊"
                  size="sm"
                />
                <span class="vcol-seat">{{ v.seatIndex }}</span>
                <span class="vcol-name">{{ v.nickname }}</span>
              </div>
            </div>
          </div>

          <div class="vote-col vote-col-abstain">
            <div class="vote-col-head">
              <div class="vote-col-avatar">—</div>
              <div class="vote-col-cname">{{ $t('vote.abstainCol') }}</div>
              <div class="vote-col-count tally-abstain">{{ election.result.abstainCount }}</div>
            </div>
            <div class="vote-col-body">
              <div v-if="!election.result.abstainVoters.length" class="vcol-row vcol-empty">
                {{ $t('vote.none') }}
              </div>
              <div v-for="v in election.result.abstainVoters" :key="v.userId" class="vcol-row">
                <Avatar
                  class="vcol-avatar"
                  :avatar="v.avatar"
                  :nickname="v.nickname"
                  emoji="😊"
                  size="sm"
                />
                <span class="vcol-seat">{{ v.seatIndex }}</span>
                <span class="vcol-name">{{ v.nickname }}</span>
              </div>
            </div>
          </div>

          <div v-if="quitCandidates.length" class="vote-col vote-col-quit">
            <div class="vote-col-head">
              <div class="vote-col-avatar">❌</div>
              <div class="vote-col-cname">{{ $t('sheriff.quitCol') }}</div>
              <div class="vote-col-count tally-muted">—</div>
            </div>
            <div class="vote-col-body">
              <div v-for="c in quitCandidates" :key="c.userId" class="vcol-row">
                <Avatar
                  class="vcol-avatar"
                  :avatar="c.avatar"
                  :nickname="c.nickname"
                  emoji="😊"
                  size="sm"
                />
                <span class="vcol-name">{{ c.nickname }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
      <!--
        Variant B: host dismisses the RESULT screen by clicking 显示结果.
        That fires SHERIFF_END_RESULT, which transitions the game to
        DAY_DISCUSSION/RESULT_HIDDEN. The host then clicks the existing
        reveal-night-result button on the day-discussion screen to apply
        the deferred N1 kills.
      -->
      <div v-if="isHost" class="action-footer">
        <button
          class="btn btn-primary"
          data-testid="sheriff-end-result"
          :class="{ 'is-loading': actionPending }"
          :disabled="actionPending"
          @click="emit('endResult')"
        >
          {{ $t('sheriff.showResult') }}
        </button>
      </div>
      <div v-else class="action-footer">
        <button class="btn btn-secondary" disabled>{{ $t('sheriff.waitingForHost2') }}</button>
      </div>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { t as tr } from '@/i18n'
import { computed, ref } from 'vue'
import Avatar from '@/components/Avatar.vue'
import ActionMenu from '@/components/ActionMenu.vue'
import CountdownArc from '@/components/CountdownArc.vue'
import LanguageToggle from '@/components/LanguageToggle.vue'
import type {
  GamePlayer,
  PlayerRole,
  SheriffCandidate,
  SheriffElectionState,
  TimerState,
} from '@/types'

const props = defineProps<{
  election: SheriffElectionState
  myUserId: string
  isHost: boolean
  players?: GamePlayer[]
  timer?: TimerState | null
  timerPresets?: number[]
  myRole?: PlayerRole
  isAlive?: boolean
  actionPending?: boolean
}>()

const emit = defineEmits<{
  run: []
  pass: []
  withdraw: []
  quit: []
  vote: [userId: string]
  abstain: []
  advanceSpeech: []
  revealResult: []
  endResult: []
  appoint: [userId: string]
  'self-destruct': [targetUserId?: string]
  'start-timer': [seconds: number]
  'stop-timer': []
}>()

const iAmCandidate = computed(() =>
  props.election.candidates.some((c) => c.userId === props.myUserId && c.status === 'RUNNING'),
)

const myCandidateStatus = computed(
  () => props.election.candidates.find((c) => c.userId === props.myUserId)?.status ?? null,
)

const cannotVoteReason = computed<'CANDIDATE' | 'QUIT_SPEECH' | null>(() => {
  if (props.election.canVote !== false) return null
  return myCandidateStatus.value === 'RUNNING' ? 'CANDIDATE' : 'QUIT_SPEECH'
})

const runningCandidates = computed(() =>
  props.election.candidates.filter((c) => c.status === 'RUNNING'),
)

const quitCandidates = computed(() =>
  props.election.candidates.filter(
    (c) => c.status === 'QUIT' && props.election.speakingOrder.includes(c.userId),
  ),
)

const candidateMap = computed(() => {
  const m = new Map<string, SheriffCandidate>()
  props.election.candidates.forEach((c) => m.set(c.userId, c))
  return m
})

const seatByUserId = computed(() => {
  const m = new Map<string, number>()
  props.players?.forEach((p) => m.set(p.userId, p.seatIndex))
  return m
})

const maxVotes = computed(() =>
  props.election.result ? Math.max(...props.election.result.tally.map((t) => t.votes)) : 0,
)

const appointTarget = ref<string | undefined>(undefined)
const selectedId = ref<string | undefined>(undefined)

function canSelectVote(userId: string): boolean {
  return (
    props.election.canVote !== false &&
    !props.election.myVote &&
    !props.election.abstained &&
    userId !== props.myUserId
  )
}

const sortedTally = computed(() =>
  [...(props.election.result?.tally ?? [])].sort((a, b) => b.votes - a.votes),
)

const tiedCandidates = computed(() => {
  const tally = props.election.result?.tally ?? []
  return tally
    .filter((t) => t.votes === maxVotes.value)
    .map((t) => runningCandidates.value.find((c) => c.userId === t.candidateId))
    .filter((c): c is SheriffCandidate => c !== undefined)
})

const currentSpeaker = computed(() =>
  props.election.currentSpeakerId
    ? candidateMap.value.get(props.election.currentSpeakerId)
    : undefined,
)

const phaseChipLabel = computed(() => {
  switch (props.election.subPhase) {
    case 'SIGNUP':
      return tr('sheriff.chip.signup')
    case 'SPEECH':
      return tr('sheriff.chip.speech')
    case 'VOTING':
      return tr('sheriff.chip.voting')
    case 'RESULT':
      return tr('sheriff.chip.result')
    case 'TIED':
      return tr('sheriff.chip.tied')
    default:
      return tr('sheriff.chip.default')
  }
})

function candidateStatus(uid: string): 'RUNNING' | 'QUIT' | undefined {
  return candidateMap.value.get(uid)?.status
}

function speakingRowClass(uid: string, idx: number) {
  const isCurrent = uid === props.election.currentSpeakerId
  const isQuit = candidateStatus(uid) === 'QUIT'
  const currentIdx = props.election.speakingOrder.indexOf(props.election.currentSpeakerId ?? '')
  const isNext = !isCurrent && !isQuit && idx === currentIdx + 1

  return {
    'speaking-row-current': isCurrent,
    'speaking-row-quit': isQuit,
    'speaking-row-next': isNext,
    'speaking-row-pending': !isCurrent && !isQuit && !isNext,
  }
}

function speakingIcon(uid: string) {
  const isQuit = candidateStatus(uid) === 'QUIT'
  const isCurrent = uid === props.election.currentSpeakerId
  const currentIdx = props.election.speakingOrder.indexOf(props.election.currentSpeakerId ?? '')
  const myIdx = props.election.speakingOrder.indexOf(uid)

  if (isQuit) return '❌'
  if (isCurrent) return '🎤'
  if (myIdx > currentIdx) return '⏳'
  return candidateMap.value.get(uid)?.avatar ?? '😊'
}

function speakerLabel(uid: string, idx: number) {
  const c = candidateMap.value.get(uid)
  const name = uid === props.myUserId ? tr('common.me') : (c?.nickname ?? uid)
  const seat = seatByUserId.value.get(uid)
  const seatPrefix = seat != null ? `${tr('common.seat', { n: seat })} ` : ''
  const isCurrent = uid === props.election.currentSpeakerId
  const isQuit = candidateStatus(uid) === 'QUIT'
  const currentIdx = props.election.speakingOrder.indexOf(props.election.currentSpeakerId ?? '')

  if (isCurrent)
    return uid === props.myUserId
      ? `${seatPrefix}${name} · ${tr('sheriff.speakingNow')}`
      : `${seatPrefix}${name} · ${tr('sheriff.speaking')}`
  if (isQuit) return `${seatPrefix}${name} · ${tr('sheriff.quit')}`
  if (idx === currentIdx + 1) return `${seatPrefix}${name} · ${tr('sheriff.next')}`
  return `${seatPrefix}${name}`
}
</script>

<style scoped>
.sheriff-wrap {
  display: flex;
  flex-direction: column;
  flex: 1;
  padding: 0 1.25rem var(--fab-clearance);
  gap: 0.75rem;
  overflow-y: auto;
}

.spacer {
  flex: 1;
}

/* Header */
.below-header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 0 1rem 0.5rem;
}
.sheriff-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 0;
}

.phase-chip {
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 0.25rem;
  padding: 0.375rem 0.75rem;
  color: var(--gold);
  font-size: 0.75rem;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.timer {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--gold);
}

/* Info banner */
.info-banner {
  background: rgba(160, 120, 48, 0.08);
  border-left: 3px solid var(--gold);
  border-radius: 0.25rem;
  padding: 0.5rem 0.75rem;
}

.info-title {
  color: var(--gold);
  font-weight: 700;
  font-size: 0.75rem;
  margin-bottom: 0.25rem;
}

.info-body {
  color: var(--muted);
  font-size: 0.75rem;
  line-height: 1.5;
  margin: 0;
}

/* Section labels */
.section-label {
  font-size: 0.625rem;
  text-transform: uppercase;
  letter-spacing: 0.2em;
  color: var(--muted);
}

/* Candidates */
.candidate-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.cand-row-running {
  background: rgba(160, 120, 48, 0.06);
  border: 1px solid rgba(160, 120, 48, 0.25);
  border-radius: 0.375rem;
  display: flex;
  align-items: center;
  padding: 0 0.75rem;
  gap: 0.5rem;
  height: 3.125rem;
}

.cand-avatar {
  font-size: 1.125rem;
}

.cand-name {
  flex: 1;
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--text);
}

/* SIGNUP stats — count + decision progress, no per-candidate rows */
.signup-stats {
  background: var(--paper);
  border: 1px solid var(--border-l);
  border-radius: 0.5rem;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.stat-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.stat-label {
  font-size: 0.75rem;
  color: var(--muted);
  letter-spacing: 0.05em;
}

.stat-value {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--text);
}

/* Action footer */
.action-footer {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.host-divider {
  height: 1px;
  background: var(--border-l);
  margin: 0.25rem 0;
}

/* Your turn box */
.your-turn-box {
  background: rgba(160, 120, 48, 0.08);
  border: 1px solid rgba(160, 120, 48, 0.25);
  border-radius: 0.5rem;
  padding: 0.9375rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.yt-tag {
  font-size: 0.625rem;
  text-transform: uppercase;
  letter-spacing: 0.2em;
  color: var(--gold);
}

.yt-title {
  font-size: 0.9375rem;
  font-weight: 700;
  color: var(--text);
}

.yt-sub {
  font-size: 0.6875rem;
  color: var(--muted);
}

/* Speaking list */
.speaking-list {
  display: flex;
  flex-direction: column;
}

.speaking-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0 0.8125rem;
  height: 2.75rem;
  border-radius: 0.375rem;
  border: 1px solid var(--border-l);
  background: var(--paper);
}

.speaking-row-current {
  background: rgba(160, 120, 48, 0.12);
  border: 2px solid rgba(160, 120, 48, 0.5);
  opacity: 1;
}

.speaking-row-quit {
  background: rgba(181, 37, 26, 0.05);
  border: 1px solid rgba(181, 37, 26, 0.18);
  opacity: 1;
}

.speaking-row-next {
  opacity: 0.6;
}

.speaking-row-pending {
  opacity: 0.4;
}

.speak-icon-cell {
  font-size: 0.875rem;
  width: 1.25rem;
  text-align: center;
}

.speak-name-cell {
  font-size: 0.8125rem;
  color: var(--muted);
}

.speak-active {
  color: var(--gold);
  font-weight: 700;
}

/* Quit warning */
.quit-warning {
  background: rgba(181, 37, 26, 0.05);
  border: 1px solid rgba(181, 37, 26, 0.12);
  border-radius: 0.3125rem;
  padding: 0.5625rem 0.6875rem;
  font-size: 0.6875rem;
  color: var(--muted);
}

/* Danger outline button */
.btn-danger-outline {
  background: none;
  border: 1px solid rgba(181, 37, 26, 0.35);
  color: var(--red);
  border-radius: 0.375rem;
  padding: 0.9375rem;
  font-size: 0.875rem;
  font-weight: 500;
  text-align: center;
  cursor: pointer;
  font-family: inherit;
  letter-spacing: 0.03em;
}

/* Speaker hero (audience view) */
.speaker-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 1.5rem 0 0.75rem;
  gap: 0.5rem;
}

.speaker-avatar-big {
  font-size: 3rem;
}

.speaker-name-big {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.125rem;
  font-weight: 700;
  color: var(--text);
}

.speaker-now-label {
  font-size: 0.6875rem;
  letter-spacing: 0.2em;
  color: var(--gold);
  text-transform: uppercase;
}

/* Info banner small */
.info-banner-sm {
  background: rgba(160, 120, 48, 0.06);
  border-left: 3px solid var(--gold);
  border-radius: 0.25rem;
  padding: 0.4375rem 0.75rem;
}

.sm {
  font-size: 0.75rem;
  line-height: 1.5;
}

/* Vote list */
.vote-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.vote-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.875rem;
  border-radius: 0.5rem;
  background: var(--paper);
  border: 1px solid var(--border-l);
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}

.vote-row:hover {
  border-color: var(--gold);
}

.vote-row-selected {
  background: rgba(160, 120, 48, 0.1);
  border: 2px solid var(--gold);
}

.cand-info-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.cand-sub-status {
  font-size: 0.625rem;
  letter-spacing: 0.1em;
  color: var(--gold);
}

.check-circle {
  width: 1.375rem;
  height: 1.375rem;
  background: var(--gold);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 0.75rem;
}

/* Quit note */
.quit-note {
  background: rgba(181, 37, 26, 0.04);
  border: 1px solid rgba(181, 37, 26, 0.1);
  border-radius: 0.3125rem;
  padding: 0.5rem 0.6875rem;
  font-size: 0.6875rem;
  color: var(--muted);
}

/* Result */
.result-content {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 0.5rem;
  padding: 0.5rem 0;
}

.result-content > .result-medal,
.result-content > .result-title-cn,
.result-content > .result-title-en,
.result-content > .winner-card {
  align-self: center;
}

.result-medal {
  font-size: 2rem;
}

.result-title-cn {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.375rem;
  font-weight: 700;
  color: var(--gold);
  letter-spacing: 0.2em;
}

.result-title-en {
  font-size: 0.8125rem;
  color: var(--muted);
}

.winner-card {
  background: rgba(160, 120, 48, 0.08);
  border: 1px solid rgba(160, 120, 48, 0.25);
  border-radius: 0.625rem;
  display: flex;
  align-items: center;
  gap: 0.875rem;
  padding: 1rem 1.5rem;
}

.winner-avatar {
  font-size: 2.25rem;
}

.winner-info {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.winner-name {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.125rem;
  font-weight: 700;
  color: var(--text);
}

.winner-badge-label {
  font-size: 0.6875rem;
  letter-spacing: 0.2em;
  color: var(--gold);
}

.power-note {
  background: var(--paper);
  border: 1px solid var(--border-l);
  border-radius: 0.375rem;
  padding: 0.625rem 1rem;
  font-size: 0.75rem;
  color: var(--muted);
  text-align: center;
  width: 100%;
  box-sizing: border-box;
}

.tally-abstain {
  color: var(--muted);
}

/* Vote columns — shared styles in game.css; only abstain/quit variants are local */
.vote-col-abstain {
  opacity: 0.7;
}

.vote-col-quit {
  opacity: 0.5;
  text-decoration: line-through;
}

/* Voted badge (locked state) */
.voted-badge {
  background: rgba(45, 106, 63, 0.08);
  border: 1px solid rgba(45, 106, 63, 0.25);
  border-radius: 0.375rem;
  padding: 0.75rem 1rem;
  font-size: 0.875rem;
  color: var(--green);
  font-weight: 500;
  text-align: center;
}

/* Vote progress label */
.vote-progress-label {
  font-size: 0.6875rem;
  color: var(--muted);
  text-align: center;
  letter-spacing: 0.05em;
}

/* Self-row (cannot select self) */
.vote-row-self {
  cursor: not-allowed;
  opacity: 0.5;
}

/* Utilities */
.gold {
  color: var(--gold);
}

.muted {
  color: var(--muted);
}

.bold {
  font-weight: 700;
}
</style>
