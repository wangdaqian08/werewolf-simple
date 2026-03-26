<template>
  <div class="sheriff-wrap">
    <!-- Header: phase chip + timer -->
    <div class="sheriff-header">
      <div class="phase-chip">{{ phaseChipLabel }}</div>
      <div class="timer">{{ election.subPhase !== 'RESULT' ? election.timeRemaining : '' }}</div>
      <div v-if="election.subPhase === 'RESULT'" class="timer-result">Result</div>
    </div>

    <!-- ── SIGNUP ── -->
    <template v-if="election.subPhase === 'SIGNUP'">
      <div class="info-banner">
        <div class="info-title">警长竞选开始 · Sheriff Election</div>
        <p class="info-body">
          Candidates sign up now. Sheriff gets <b class="gold">1.5×</b> voting power at elimination.
        </p>
      </div>

      <div class="section-label">Candidates so far ({{ election.candidates.length }})</div>
      <div class="candidate-list">
        <div v-for="c in election.candidates" :key="c.userId" class="cand-row-running">
          <span class="cand-avatar">{{ c.avatar ?? '😊' }}</span>
          <span class="cand-name">{{ c.nickname }}</span>
          <span class="running-badge">RUNNING</span>
        </div>
      </div>

      <div class="spacer" />
      <div class="action-footer">
        <template v-if="iAmCandidate">
          <button class="btn btn-danger-outline" @click="emit('withdraw')">撤回 / Withdraw</button>
        </template>
        <template v-else-if="election.hasPassed">
          <button class="btn btn-secondary" disabled>已放弃 / Passed</button>
        </template>
        <template v-else>
          <button class="btn btn-gold" @click="emit('run')">参选 / Run for Sheriff</button>
          <button class="btn btn-outline" @click="emit('pass')">放弃 / Pass</button>
        </template>
        <template v-if="isHost">
          <div class="host-divider" />
          <button
            class="btn btn-primary"
            :disabled="election.candidates.length === 0"
            @click="emit('startCampaign')"
          >
            开始演讲 / Start Campaign
          </button>
        </template>
      </div>
    </template>

    <!-- ── SPEECH - My Turn ── -->
    <template v-else-if="election.subPhase === 'SPEECH' && election.currentSpeakerId === myUserId">
      <div class="your-turn-box">
        <div class="yt-tag">Your Turn</div>
        <div class="yt-title">发表你的竞选宣言</div>
        <div class="yt-sub">Make your case to the village</div>
      </div>

      <div class="section-label">Speaking order</div>
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
      <div class="quit-warning">⚠ Quitting forfeits your right to vote for sheriff.</div>
      <div class="action-footer">
        <button class="btn btn-danger-outline" @click="emit('quit')">
          退出竞选 / Quit Campaign
        </button>
      </div>
    </template>

    <!-- ── SPEECH - Audience ── -->
    <template v-else-if="election.subPhase === 'SPEECH'">
      <div v-if="currentSpeaker" class="speaker-hero">
        <div class="speaker-avatar-big">{{ currentSpeaker.avatar ?? '😊' }}</div>
        <div class="speaker-name-big">{{ currentSpeaker.nickname }}</div>
        <div class="speaker-now-label">SPEAKING NOW</div>
      </div>

      <div class="section-label">Speaking order</div>
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
        <button class="btn btn-secondary" disabled>等待投票 / Waiting for vote…</button>
        <template v-if="isHost">
          <div class="host-divider" />
          <button class="btn btn-primary" @click="emit('advanceSpeech')">
            下一位 / Next Speaker
          </button>
        </template>
      </div>
    </template>

    <!-- ── VOTING ── -->
    <template v-else-if="election.subPhase === 'VOTING'">
      <div class="info-banner-sm">
        <div class="muted sm">演讲结束，选出你心中的警长。</div>
        <div class="gold bold sm">Speeches done — vote for sheriff</div>
      </div>

      <div class="section-label">候选人 / Candidates ({{ runningCandidates.length }})</div>
      <div class="vote-list">
        <div
          v-for="c in runningCandidates"
          :key="c.userId"
          :class="{ 'vote-row-selected': election.myVote === c.userId }"
          class="vote-row"
          @click="election.canVote !== false && emit('vote', c.userId)"
        >
          <span class="cand-avatar">{{ c.avatar ?? '😊' }}</span>
          <div class="cand-info-col">
            <span class="cand-name">{{ c.nickname }}</span>
            <span class="cand-sub-status">{{
              election.myVote === c.userId ? 'SELECTED ✓' : '候选人'
            }}</span>
          </div>
          <div v-if="election.myVote === c.userId" class="check-circle">✓</div>
        </div>
      </div>

      <div v-if="quitCandidates.length" class="quit-note">
        {{ quitCandidates.map((c) => c.nickname).join(', ') }} quit campaign — cannot vote.
      </div>

      <div class="spacer" />
      <div class="action-footer">
        <template v-if="election.canVote !== false">
          <button
            class="btn btn-gold"
            :disabled="!election.myVote && !election.abstained"
            @click="emit('confirmVote')"
          >
            确认投票 / Confirm Vote
          </button>
          <button class="btn btn-outline" @click="emit('abstain')">放弃投票 / Give Up Vote</button>
        </template>
        <button v-else class="btn btn-secondary" disabled>已放弃投票 / Vote forfeited</button>
        <template v-if="isHost">
          <div class="host-divider" />
          <button class="btn btn-primary" @click="emit('revealResult')">
            揭晓结果 / Reveal Result
          </button>
        </template>
      </div>
    </template>

    <!-- ── RESULT ── -->
    <template v-else-if="election.subPhase === 'RESULT' && election.result">
      <div class="result-content">
        <div class="result-medal">🏅</div>
        <div class="result-title-cn">警长当选</div>
        <div class="result-title-en">Sheriff Elected</div>
        <div class="winner-card">
          <span class="winner-avatar">{{ election.result.sheriffAvatar ?? '😊' }}</span>
          <div class="winner-info">
            <div class="winner-name">{{ election.result.sheriffNickname }}</div>
            <div class="winner-badge-label">⭐ SHERIFF</div>
          </div>
        </div>
        <div class="power-note">
          Sheriff's vote counts as <b class="gold">1.5×</b> during eliminations
        </div>
        <!-- Vote columns: one per candidate + abstain -->
        <div class="vote-columns">
          <div
            v-for="t in election.result.tally"
            :key="t.candidateId"
            class="vote-col"
            :class="t.votes === maxVotes ? 'vote-col-winner' : ''"
          >
            <div class="vote-col-head">
              <div class="vote-col-avatar">
                {{ election.candidates.find((c) => c.userId === t.candidateId)?.avatar ?? '😊' }}
              </div>
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
                <span class="vcol-avatar">{{ v.avatar ?? '😊' }}</span>
                <span class="vcol-seat">{{ v.seatIndex }}</span>
                <span class="vcol-name">{{ v.nickname }}</span>
              </div>
            </div>
          </div>

          <div v-if="election.result.abstainCount > 0" class="vote-col vote-col-abstain">
            <div class="vote-col-head">
              <div class="vote-col-avatar">—</div>
              <div class="vote-col-cname">弃票</div>
              <div class="vote-col-count tally-abstain">{{ election.result.abstainCount }}</div>
            </div>
            <div class="vote-col-body">
              <div v-for="v in election.result.abstainVoters" :key="v.userId" class="vcol-row">
                <span class="vcol-avatar">{{ v.avatar ?? '😊' }}</span>
                <span class="vcol-seat">{{ v.seatIndex }}</span>
                <span class="vcol-name">{{ v.nickname }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import type { SheriffCandidate, SheriffElectionState } from '@/types'

const props = defineProps<{
  election: SheriffElectionState
  myUserId: string
  isHost: boolean
}>()

const emit = defineEmits<{
  run: []
  pass: []
  withdraw: []
  startCampaign: []
  quit: []
  vote: [userId: string]
  confirmVote: []
  abstain: []
  advanceSpeech: []
  revealResult: []
}>()

const iAmCandidate = computed(() =>
  props.election.candidates.some((c) => c.userId === props.myUserId),
)

const runningCandidates = computed(() =>
  props.election.candidates.filter((c) => c.status === 'RUNNING'),
)

const quitCandidates = computed(() => props.election.candidates.filter((c) => c.status === 'QUIT'))

const candidateMap = computed(() => {
  const m = new Map<string, SheriffCandidate>()
  props.election.candidates.forEach((c) => m.set(c.userId, c))
  return m
})

const maxVotes = computed(() =>
  props.election.result ? Math.max(...props.election.result.tally.map((t) => t.votes)) : 0,
)

const currentSpeaker = computed(() =>
  props.election.currentSpeakerId
    ? candidateMap.value.get(props.election.currentSpeakerId)
    : undefined,
)

const phaseChipLabel = computed(() => {
  switch (props.election.subPhase) {
    case 'SIGNUP':
      return '👮‍♂️ 竞选警长'
    case 'SPEECH':
      return '👮‍♂️ 竞选演讲'
    case 'VOTING':
      return '👮‍ 投票选警长'
    case 'RESULT':
      return '👮‍ 警长产生'
    default:
      return '👮‍ 警长竞选'
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
  const name = uid === props.myUserId ? '我' : (c?.nickname ?? uid)
  const isCurrent = uid === props.election.currentSpeakerId
  const isQuit = candidateStatus(uid) === 'QUIT'
  const currentIdx = props.election.speakingOrder.indexOf(props.election.currentSpeakerId ?? '')

  if (isCurrent) return uid === props.myUserId ? '我 · speaking now' : `${name} · speaking`
  if (isQuit) return `${name} · Quit`
  if (idx === currentIdx + 1) return `${name} · next`
  return name
}
</script>

<style scoped>
.sheriff-wrap {
  display: flex;
  flex-direction: column;
  flex: 1;
  padding: 0 1.25rem 1.5rem;
  gap: 0.75rem;
  overflow-y: auto;
}

.spacer {
  flex: 1;
}

/* Header */
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

.timer-result {
  font-size: 0.6875rem;
  color: var(--muted);
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

.running-badge {
  font-size: 0.625rem;
  letter-spacing: 0.1em;
  color: var(--gold);
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

/* Vote columns — shared styles in game.css; only abstain variant is local */
.vote-col-abstain {
  opacity: 0.7;
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
