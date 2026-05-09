<template>
  <div class="demo-page" data-testid="demo-page">
    <!-- ── Hero ──────────────────────────────────────────────────────────── -->
    <header class="demo-hero">
      <h1 class="demo-title">狼人杀 · Werewolf</h1>
      <p class="demo-tagline">Demo · No login, no backend — scroll through every screen</p>
    </header>

    <!-- ── Sticky in-page nav ─────────────────────────────────────────── -->
    <nav class="demo-nav">
      <a href="#lobby">Lobby</a>
      <a href="#room">Room</a>
      <a href="#roles">Roles</a>
      <a href="#sheriff">Sheriff</a>
      <a href="#night-wolf">Night</a>
      <a href="#day">Day</a>
      <a href="#vote">Vote</a>
      <a href="#end">End</a>
    </nav>

    <!-- ── 1. Lobby (visual recreation) ──────────────────────────────── -->
    <DemoSection
      id="lobby"
      num="1"
      title="Lobby — login"
      subtitle="The first screen players see. OAuth or guest entry."
    >
      <div class="lp-card">
        <h1 class="lp-title">狼人杀</h1>
        <p class="lp-subtitle">Werewolf</p>
        <div class="lp-oauth-section">
          <button class="lp-btn-google">
            <svg
              class="lp-oauth-logo"
              viewBox="0 0 24 24"
              xmlns="http://www.w3.org/2000/svg"
              aria-hidden="true"
            >
              <path
                d="M22.5 12.27c0-.86-.08-1.68-.22-2.47H12v4.68h5.94c-.26 1.4-1.05 2.59-2.24 3.39v2.82h3.62c2.12-1.96 3.34-4.84 3.34-8.42z"
                fill="#4285F4"
              />
              <path
                d="M12 23c3.02 0 5.55-1 7.4-2.71l-3.62-2.82c-1 .67-2.27 1.07-3.78 1.07-2.91 0-5.37-1.96-6.25-4.6H1.99v2.9C3.83 20.51 7.65 23 12 23z"
                fill="#34A853"
              />
              <path
                d="M5.75 13.94c-.22-.67-.35-1.39-.35-2.13s.13-1.46.35-2.13V6.78H1.99A11 11 0 0 0 1 12c0 1.78.43 3.46 1.19 4.94l3.56-3z"
                fill="#FBBC05"
              />
              <path
                d="M12 5.27c1.64 0 3.11.56 4.27 1.67l3.21-3.21C17.55 1.92 15.02 1 12 1 7.65 1 3.83 3.49 1.99 6.78l3.76 2.9c.88-2.64 3.34-4.6 6.25-4.6z"
                fill="#EA4335"
              />
            </svg>
            使用 Google 登录 / Sign in with Google
          </button>
          <button class="lp-btn-wechat">
            <svg
              class="lp-oauth-logo"
              viewBox="0 0 24 24"
              xmlns="http://www.w3.org/2000/svg"
              aria-hidden="true"
            >
              <path
                d="M8.5 4C4.36 4 1 6.92 1 10.5c0 2.06 1.13 3.9 2.88 5.07L3 18l2.62-1.4c.85.21 1.74.32 2.69.32h.42c-.27-.74-.41-1.52-.41-2.34 0-3.42 3.21-6.2 7.18-6.2.21 0 .41.01.62.03C15.39 5.74 12.27 4 8.5 4z"
                fill="#fff"
              />
              <path
                d="M22 14.5c0-2.96-2.84-5.36-6.36-5.36-3.61 0-6.46 2.4-6.46 5.36 0 2.96 2.85 5.36 6.46 5.36.78 0 1.53-.11 2.23-.31L20.18 21l-.5-1.91C21.04 18.04 22 16.36 22 14.5z"
                fill="#fff"
              />
            </svg>
            微信登录 / Sign in with WeChat
          </button>
          <div class="lp-or">or</div>
        </div>
        <button class="lp-guest-toggle">继续以访客身份 / Continue as guest</button>
        <div class="lp-guest-form">
          <div class="lp-field">
            <label>昵称 / Nickname</label>
            <input class="lp-input" placeholder="Enter your nickname" type="text" />
          </div>
          <div class="lp-actions">
            <button class="btn btn-primary lp-full-btn">创建房间 / Create Room</button>
            <div class="lp-or">or</div>
            <div class="lp-join-row">
              <input class="lp-input lp-code-input" placeholder="Room code" type="text" />
              <button class="btn btn-secondary lp-join-btn">加入 / Join</button>
            </div>
          </div>
        </div>
      </div>
    </DemoSection>

    <!-- ── 2. Create room (visual recreation) ─────────────────────────── -->
    <DemoSection
      id="create-room"
      num="2"
      title="Create room — host config"
      subtitle="Pick player count, roles, sheriff option, BGM."
    >
      <div class="cr-card">
        <button class="cr-back">← 返回</button>
        <div class="cr-header">
          <div class="cr-section-lbl">Host</div>
          <h2 class="cr-title">创建房间 / Create Room</h2>
        </div>
        <div class="cr-stepper-card">
          <div class="cr-field-lbl">玩家人数 / Number of Players</div>
          <div class="cr-stepper-row">
            <button class="cr-stepper-btn cr-minus">−</button>
            <div class="cr-stepper-value">
              <span class="cr-stepper-num">9</span>
              <span class="cr-stepper-range">6 – 12</span>
            </div>
            <button class="cr-stepper-btn cr-plus">+</button>
          </div>
        </div>
        <div class="cr-field-lbl">角色配置 / Role Configuration</div>
        <div class="cr-balance-note">Backend auto-balances role counts based on total players.</div>
        <div class="cr-role-list">
          <div class="cr-role-row cr-row-wolf">
            <span class="cr-emoji">🐺</span>
            <div class="cr-role-names"><span class="cr-role-name">狼人 Werewolf</span></div>
            <span class="cr-req cr-req-wolf">REQUIRED</span>
          </div>
          <div class="cr-role-row cr-row-village">
            <span class="cr-emoji">🧑‍🌾</span>
            <div class="cr-role-names"><span class="cr-role-name">村民 Villager</span></div>
            <span class="cr-req cr-req-village">REQUIRED</span>
          </div>
          <div class="cr-role-row cr-row-on">
            <span class="cr-emoji">🔮</span>
            <div class="cr-role-names"><span class="cr-role-name">预言家 Seer</span></div>
            <span class="cr-toggle cr-toggle-on"><span class="cr-toggle-thumb" /></span>
          </div>
          <div class="cr-role-row cr-row-on">
            <span class="cr-emoji">🧙‍♀️</span>
            <div class="cr-role-names"><span class="cr-role-name">女巫 Witch</span></div>
            <span class="cr-toggle cr-toggle-on"><span class="cr-toggle-thumb" /></span>
          </div>
          <div class="cr-role-row cr-row-on">
            <span class="cr-emoji">🏹</span>
            <div class="cr-role-names"><span class="cr-role-name">猎人 Hunter</span></div>
            <span class="cr-toggle cr-toggle-on"><span class="cr-toggle-thumb" /></span>
          </div>
          <div class="cr-role-row cr-row-off">
            <span class="cr-emoji">🛡️</span>
            <div class="cr-role-names">
              <span class="cr-role-name cr-role-muted">守卫 Guard</span>
            </div>
            <span class="cr-toggle cr-toggle-off"><span class="cr-toggle-thumb" /></span>
          </div>
          <div class="cr-role-row cr-row-off">
            <span class="cr-emoji">🃏</span>
            <div class="cr-role-names">
              <span class="cr-role-name cr-role-muted">白痴 Idiot</span>
            </div>
            <span class="cr-toggle cr-toggle-off"><span class="cr-toggle-thumb" /></span>
          </div>
        </div>
        <div class="cr-field-lbl">游戏设置 / Game Settings</div>
        <div class="cr-role-list">
          <div class="cr-role-row cr-row-on">
            <span class="cr-emoji">⭐</span>
            <div class="cr-role-names">
              <span class="cr-role-name">警长竞选 Sheriff Election</span>
            </div>
            <span class="cr-toggle cr-toggle-on"><span class="cr-toggle-thumb" /></span>
          </div>
          <div class="cr-role-row cr-row-on">
            <span class="cr-emoji">⚔️</span>
            <div class="cr-role-names">
              <span class="cr-role-name">胜利条件 Win Condition</span>
              <span class="cr-win-desc">经典：狼人人数 ≥ 好人（Classic: wolves ≥ others）</span>
            </div>
            <span class="cr-toggle cr-toggle-off"><span class="cr-toggle-thumb" /></span>
          </div>
          <div class="cr-role-row cr-row-on">
            <span class="cr-emoji">🎵</span>
            <div class="cr-role-names">
              <span class="cr-role-name">夜晚配乐 Night BGM</span>
              <span class="cr-win-desc">每夜循环播放，旁白时自动降低音量</span>
            </div>
            <span class="cr-bgm-trigger">无 (None) ▾</span>
          </div>
        </div>
        <button class="btn btn-primary">创建房间 / Create Room</button>
      </div>
    </DemoSection>

    <!-- ── 3. Room (waiting) ──────────────────────────────────────────── -->
    <DemoSection
      id="room"
      num="3"
      title="Room — waiting for players"
      subtitle="Join a seat, mark ready. Host starts when everyone is ready."
    >
      <div class="rp-wrap">
        <button class="rp-back">← 离开</button>
        <div class="rp-card">
          <div class="rp-title">等待中 / Waiting</div>
          <div class="rp-code-card">
            <div class="rp-code-lbl">Room Code</div>
            <div class="rp-code-num">ABCD12</div>
          </div>
          <div class="rp-player-count">
            <span class="rp-count-ready">8</span>
            <span>/ 12 玩家</span>
            <span class="rp-count-sep">·</span>
            <span>4 players not ready</span>
          </div>
          <section class="rp-grid">
            <PlayerSlot
              v-for="p in roomPlayers"
              :key="p.userId"
              mode="room"
              :seat="p.seatIndex"
              :nickname="p.nickname"
              :avatar="p.avatar"
              :variant="roomVariant(p)"
            />
          </section>
          <div class="rp-status-bar rp-status-wait">
            ✓ 8 / 12 ready · Waiting for players to be ready
          </div>
          <div class="rp-footer">
            <button class="btn btn-gold">准备 / Ready</button>
          </div>
        </div>
      </div>
    </DemoSection>

    <!-- ── 4. Role reveal (cycle through 7 roles) ─────────────────────── -->
    <DemoSection
      id="roles"
      num="4"
      title="Role reveal — your hidden identity"
      subtitle="Each player sees their role privately at game start."
    >
      <div class="role-cycler">
        <button
          v-for="r in ROLES"
          :key="r"
          :class="['role-chip', { active: currentRole === r }]"
          :data-testid="`demo-role-cycler-${r}`"
          @click="currentRole = r"
        >
          {{ ROLE_LABELS[r] }}
        </button>
      </div>
      <div class="demo-phone">
        <RoleRevealCard
          :role="currentRole"
          :revealed="true"
          :teammates="currentRole === 'WEREWOLF' ? ['Bob'] : []"
        />
      </div>
    </DemoSection>

    <!-- ── 5. Sheriff election (cycle through 4 sub-phases) ─────────── -->
    <DemoSection
      id="sheriff"
      num="5"
      title="Sheriff election — campaign + vote"
      subtitle="Optional badge — sheriff's vote counts 1.5×."
    >
      <div class="subphase-tabs">
        <button
          v-for="sp in SHERIFF_SUBPHASES"
          :key="sp"
          :class="['tab-chip', { active: sheriffSub === sp }]"
          :data-testid="`demo-sheriff-tab-${sp}`"
          @click="sheriffSub = sp"
        >
          {{ sp }}
        </button>
      </div>
      <div class="demo-phone">
        <SheriffElection :election="sheriffState" :my-user-id="DEMO_ME_ID" :is-host="false" />
      </div>
    </DemoSection>

    <!-- ── 6a. Night — Werewolf (interactive) ────────────────────────── -->
    <DemoSection
      id="night-wolf"
      num="6a"
      title="Night — Werewolf attack"
      subtitle="Tap a seat, then confirm to attack tonight's target."
    >
      <div class="demo-phone night">
        <NightPhase
          :key="`wolf-${wolfKey}`"
          :night-phase="wolfState"
          :players="players"
          :my-user-id="DEMO_ME_ID"
          my-role="WEREWOLF"
          @confirm="onWolfConfirm"
        />
      </div>
      <button
        v-if="wolfActed"
        class="btn btn-secondary demo-reset-btn"
        data-testid="demo-reset-night-wolf"
        @click="resetWolf"
      >
        ↺ 再试一次 / Try again
      </button>
    </DemoSection>

    <!-- ── 6b. Night — Seer (interactive) ────────────────────────────── -->
    <DemoSection
      id="night-seer"
      num="6b"
      title="Night — Seer check"
      subtitle="Pick a player to learn their alignment."
    >
      <div class="demo-phone night">
        <NightPhase
          :key="`seer-${seerKey}`"
          :night-phase="seerState"
          :players="players"
          :my-user-id="DEMO_ME_ID"
          my-role="SEER"
          @confirm="onSeerConfirm"
        />
      </div>
      <button
        v-if="seerActed"
        class="btn btn-secondary demo-reset-btn"
        data-testid="demo-reset-night-seer"
        @click="resetSeer"
      >
        ↺ 再试一次 / Try again
      </button>
    </DemoSection>

    <!-- ── 6c. Night — Witch (interactive) ───────────────────────────── -->
    <DemoSection
      id="night-witch"
      num="6c"
      title="Night — Witch antidote / poison"
      subtitle="One save, one kill — use them wisely."
    >
      <div class="demo-phone night">
        <NightPhase
          :key="`witch-${witchKey}`"
          :night-phase="witchState"
          :players="players"
          :my-user-id="DEMO_ME_ID"
          my-role="WITCH"
          @confirm="onWitchConfirm"
          @witch-antidote="onWitchAct"
          @witch-pass-antidote="onWitchAct"
          @witch-poison="onWitchAct"
          @witch-pass-poison="onWitchAct"
          @witch-skip="onWitchAct"
        />
      </div>
      <button
        v-if="witchActed"
        class="btn btn-secondary demo-reset-btn"
        data-testid="demo-reset-night-witch"
        @click="resetWitch"
      >
        ↺ 再试一次 / Try again
      </button>
    </DemoSection>

    <!-- ── 6d. Night — Guard (interactive) ───────────────────────────── -->
    <DemoSection
      id="night-guard"
      num="6d"
      title="Night — Guard protect"
      subtitle="Shield one player from a wolf attack tonight."
    >
      <div class="demo-phone night">
        <NightPhase
          :key="`guard-${guardKey}`"
          :night-phase="guardState"
          :players="players"
          :my-user-id="DEMO_ME_ID"
          my-role="GUARD"
          @confirm="onGuardConfirm"
        />
      </div>
      <button
        v-if="guardActed"
        class="btn btn-secondary demo-reset-btn"
        data-testid="demo-reset-night-guard"
        @click="resetGuard"
      >
        ↺ 再试一次 / Try again
      </button>
    </DemoSection>

    <!-- ── 6e. Night — Villager waiting ──────────────────────────────── -->
    <DemoSection
      id="night-waiting"
      num="6e"
      title="Night — Villager (waiting)"
      subtitle="Players without a night skill see the sleep screen."
    >
      <div class="demo-phone night">
        <NightPhase
          :night-phase="waitingState"
          :players="players"
          :my-user-id="DEMO_ME_ID"
          my-role="VILLAGER"
        />
      </div>
    </DemoSection>

    <!-- ── 7. Day phase (cycle through 2 sub-phases) ─────────────────── -->
    <DemoSection
      id="day"
      num="7"
      title="Day — discussion"
      subtitle="Host reveals deaths, then opens voting."
    >
      <div class="subphase-tabs">
        <button
          v-for="sp in DAY_SUBPHASES"
          :key="sp"
          :class="['tab-chip', { active: daySub === sp }]"
          :data-testid="`demo-day-tab-${sp}`"
          @click="daySub = sp"
        >
          {{ sp }}
        </button>
      </div>
      <div class="demo-phone">
        <DayPhase
          :game-id="0"
          :day-phase="dayState"
          :players="players"
          :my-user-id="DEMO_ME_ID"
          :is-host="true"
        />
      </div>
    </DemoSection>

    <!-- ── 8. Voting phase (cycle through 3 sub-phases) ──────────────── -->
    <DemoSection
      id="vote"
      num="8"
      title="Voting — eliminate a suspect"
      subtitle="Cast a vote, host reveals tally, eliminate the top getter."
    >
      <div class="subphase-tabs">
        <button
          v-for="sp in VOTING_SUBPHASES"
          :key="sp"
          :class="['tab-chip', { active: voteSub === sp }]"
          :data-testid="`demo-vote-tab-${sp}`"
          @click="voteSub = sp"
        >
          {{ sp }}
        </button>
      </div>
      <div class="demo-phone">
        <VotingPhase
          :game-id="0"
          :voting-phase="voteState"
          :players="players"
          :my-user-id="DEMO_ME_ID"
          :is-host="true"
          my-role="SEER"
          :vote-history="voteHistory"
        />
      </div>
    </DemoSection>

    <!-- ── 9. Game over ──────────────────────────────────────────────── -->
    <DemoSection
      id="end"
      num="9"
      title="Game over — winner reveal"
      subtitle="Background inverts on wolf win. All roles exposed."
    >
      <div class="subphase-tabs">
        <button
          :class="['tab-chip', { active: endWinner === 'WEREWOLF' }]"
          data-testid="demo-end-tab-WEREWOLF"
          @click="endWinner = 'WEREWOLF'"
        >
          狼人胜
        </button>
        <button
          :class="['tab-chip', { active: endWinner === 'VILLAGE' }]"
          data-testid="demo-end-tab-VILLAGE"
          @click="endWinner = 'VILLAGE'"
        >
          好人胜
        </button>
      </div>
      <div :class="['end-result-wrap', { 'end-result-wolves': endWinner === 'WEREWOLF' }]">
        <div class="end-result-card">
          <p class="end-game-over-label">GAME OVER</p>
          <h1 class="end-outcome-title">
            {{ endWinner === 'WEREWOLF' ? '狼人胜' : '好人胜' }}
          </h1>
          <p class="end-outcome-sub">
            {{ endWinner === 'WEREWOLF' ? 'WOLVES WIN' : 'VILLAGE WINS' }}
          </p>
          <div class="end-divider-label"><span>ROLES REVEALED</span></div>
          <section class="end-reveal-grid">
            <div
              v-for="p in endPlayers"
              :key="p.userId"
              :class="['end-reveal-card', { 'end-reveal-wolf': p.role === 'WEREWOLF' }]"
            >
              <div class="end-reveal-meta">
                {{ String(p.seatIndex).padStart(2, '0') }} · {{ p.nickname }}
              </div>
              <div class="end-reveal-role">{{ ROLE_LABELS[p.role!] }}</div>
            </div>
          </section>
          <button class="btn btn-danger end-play-again">Play Again</button>
        </div>
      </div>
    </DemoSection>

    <footer class="demo-footer">
      <p>This is a demo. Start a real game from the <a href="/">lobby</a>.</p>
    </footer>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import type { DaySubPhase, GamePlayer, PlayerRole, SheriffSubPhase, VotingSubPhase } from '@/types'
import RoleRevealCard from '@/components/RoleRevealCard.vue'
import SheriffElection from '@/components/SheriffElection.vue'
import NightPhase from '@/components/NightPhase.vue'
import DayPhase from '@/components/DayPhase.vue'
import VotingPhase from '@/components/VotingPhase.vue'
import PlayerSlot from '@/components/PlayerSlot.vue'
import DemoSection from '@/views/demo/DemoSection.vue'
import {
  DEMO_ME_ID,
  buildDayPhaseState,
  buildMockPlayers,
  buildNightPhaseState,
  buildSheriffElectionState,
  buildVoteHistory,
  buildVotingPhaseState,
} from '@/views/demo/demoFixtures'

// ── Static metadata ────────────────────────────────────────────────────────

const ROLES: PlayerRole[] = ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'HUNTER', 'IDIOT', 'VILLAGER']
const ROLE_LABELS: Record<PlayerRole, string> = {
  WEREWOLF: '狼人',
  SEER: '预言家',
  WITCH: '女巫',
  GUARD: '守卫',
  HUNTER: '猎人',
  IDIOT: '白痴',
  VILLAGER: '村民',
}
const SHERIFF_SUBPHASES: SheriffSubPhase[] = ['SIGNUP', 'SPEECH', 'VOTING', 'RESULT']
const DAY_SUBPHASES: DaySubPhase[] = ['RESULT_HIDDEN', 'RESULT_REVEALED']
const VOTING_SUBPHASES: VotingSubPhase[] = ['VOTING', 'VOTE_RESULT', 'HUNTER_SHOOT']

// ── Shared mock players ───────────────────────────────────────────────────

const players = ref<GamePlayer[]>(buildMockPlayers(12))
const roomPlayers = computed(() => players.value)
function roomVariant(p: GamePlayer): 'me' | 'me-ready' | 'ready' | 'waiting' {
  if (p.userId === DEMO_ME_ID) return 'me-ready'
  if (p.seatIndex <= 8) return 'ready'
  return 'waiting'
}

// ── Roles section ────────────────────────────────────────────────────────

const currentRole = ref<PlayerRole>('WEREWOLF')

// ── Sheriff section ──────────────────────────────────────────────────────

const sheriffSub = ref<SheriffSubPhase>('SIGNUP')
const sheriffState = computed(() => buildSheriffElectionState(sheriffSub.value))

// ── Night sections (one ref each so resets are independent) ──────────────

const wolfKey = ref(0)
const wolfActed = ref(false)
const wolfState = ref(buildNightPhaseState('WEREWOLF', 'WEREWOLF_PICK'))

function onWolfConfirm() {
  wolfActed.value = true
}
function resetWolf() {
  wolfActed.value = false
  wolfKey.value++
  wolfState.value = buildNightPhaseState('WEREWOLF', 'WEREWOLF_PICK')
}

const seerKey = ref(0)
const seerActed = ref(false)
const seerState = ref(buildNightPhaseState('SEER', 'SEER_PICK'))

function onSeerConfirm(targetId?: string) {
  if (seerState.value.subPhase === 'SEER_PICK' && targetId) {
    const target = players.value.find((p) => p.userId === targetId)
    seerState.value = {
      ...seerState.value,
      subPhase: 'SEER_RESULT',
      seerResult: {
        checkedPlayerId: targetId,
        checkedNickname: target?.nickname ?? '',
        checkedSeatIndex: target?.seatIndex ?? 0,
        // Bob (seat 2) is the demo werewolf; everyone else is good
        isWerewolf: target?.seatIndex === 2,
        history: [],
      },
    }
  } else {
    seerActed.value = true
  }
}
function resetSeer() {
  seerActed.value = false
  seerKey.value++
  seerState.value = buildNightPhaseState('SEER', 'SEER_PICK')
}

const witchKey = ref(0)
const witchActed = ref(false)
const witchState = ref(buildNightPhaseState('WITCH', 'WITCH_ACT'))

function onWitchAct() {
  witchActed.value = true
}
function onWitchConfirm() {
  witchActed.value = true
}
function resetWitch() {
  witchActed.value = false
  witchKey.value++
  witchState.value = buildNightPhaseState('WITCH', 'WITCH_ACT')
}

const guardKey = ref(0)
const guardActed = ref(false)
const guardState = ref(buildNightPhaseState('GUARD', 'GUARD_PICK'))

function onGuardConfirm() {
  guardActed.value = true
}
function resetGuard() {
  guardActed.value = false
  guardKey.value++
  guardState.value = buildNightPhaseState('GUARD', 'GUARD_PICK')
}

const waitingState = ref(buildNightPhaseState('VILLAGER', 'WAITING'))

// ── Day & voting sections ────────────────────────────────────────────────

const daySub = ref<DaySubPhase>('RESULT_HIDDEN')
const dayState = computed(() => buildDayPhaseState(daySub.value))

const voteSub = ref<VotingSubPhase>('VOTING')
const voteState = computed(() => buildVotingPhaseState(voteSub.value))
const voteHistory = buildVoteHistory()

// ── Game-over section ────────────────────────────────────────────────────

const endWinner = ref<'WEREWOLF' | 'VILLAGE'>('WEREWOLF')

const END_ROLES: PlayerRole[] = [
  'WEREWOLF',
  'WEREWOLF',
  'WEREWOLF',
  'SEER',
  'WITCH',
  'GUARD',
  'HUNTER',
  'IDIOT',
  'VILLAGER',
  'VILLAGER',
  'VILLAGER',
  'VILLAGER',
]

const endPlayers = computed<GamePlayer[]>(() =>
  players.value.map((p, i) => ({ ...p, role: END_ROLES[i] })),
)
</script>

<style scoped>
.demo-page {
  min-height: 100vh;
  background: var(--bg);
  padding: 1rem 0.75rem 3rem;
  max-width: 480px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

/* ── Hero ──────────────────────────────────────────────────────────────── */
.demo-hero {
  text-align: center;
  padding: 1rem 0 0;
}
.demo-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.625rem;
  font-weight: 700;
  margin: 0;
  color: var(--text);
}
.demo-tagline {
  margin: 0.25rem 0 0;
  font-size: 0.8125rem;
  color: var(--muted);
}

/* ── Sticky in-page nav ───────────────────────────────────────────────── */
.demo-nav {
  position: sticky;
  top: 0;
  z-index: 10;
  background: rgba(237, 232, 223, 0.92);
  backdrop-filter: blur(6px);
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  padding: 0.5rem;
  border: 1px solid var(--border-l);
  border-radius: 0.5rem;
}
.demo-nav a {
  flex: 1 0 auto;
  text-align: center;
  font-size: 0.6875rem;
  font-weight: 600;
  color: var(--muted);
  text-decoration: none;
  padding: 0.3rem 0.5rem;
  border-radius: 0.3rem;
  background: var(--paper);
  border: 1px solid var(--border-l);
  letter-spacing: 0.04em;
}
.demo-nav a:hover {
  color: var(--red);
  border-color: var(--red);
}

/* ── Phone-frame wrapper ─────────────────────────────────────────────── */
/* Constrain phase components so each section is roughly the design viewport.
   Override min-height: 100dvh from the phase styles so they collapse to
   intrinsic height, which fits cleanly inside a vertical-scroll list. */
.demo-phone {
  border: 1px solid var(--border);
  border-radius: 0.625rem;
  overflow: hidden;
  background: var(--bg);
  position: relative;
}
.demo-phone.night {
  background: var(--ink);
}
.demo-phone :deep(.nw),
.demo-phone :deep(.day-wrap),
.demo-phone :deep(.voting-wrap),
.demo-phone :deep(.sheriff-wrap),
.demo-phone :deep(.reveal-wrap),
.demo-phone :deep(.hunter-wrap) {
  min-height: auto;
}
/* Reveal-wrap has 5.5rem top padding for status-bar room — trim in demo */
.demo-phone :deep(.reveal-wrap) {
  padding-top: 1rem;
}
/* Night-phase header has 3rem top padding for the same reason — trim */
.demo-phone :deep(.nh) {
  padding-top: 1rem;
}
/* DayPhase log FAB is position:fixed — anchor it inside the phone instead */
.demo-phone :deep(.log-fab) {
  position: absolute;
}

/* ── Reset button under interactive sections ─────────────────────────── */
.demo-reset-btn {
  margin-top: 0.625rem;
  width: 100%;
}

/* ── Subphase tabs / role cycler ─────────────────────────────────────── */
.subphase-tabs,
.role-cycler {
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  margin-bottom: 0.625rem;
}
.tab-chip,
.role-chip {
  flex: 1 0 auto;
  font-size: 0.6875rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  padding: 0.4rem 0.6rem;
  background: var(--paper);
  border: 1px solid var(--border-l);
  border-radius: 0.3rem;
  color: var(--muted);
  cursor: pointer;
}
.tab-chip.active,
.role-chip.active {
  background: var(--red);
  border-color: var(--red);
  color: #fff;
}

/* ── Lobby ────────────────────────────────────────────────────────────── */
.lp-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 2rem 1.5rem;
  text-align: center;
}
.lp-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 2rem;
  color: var(--red);
  margin: 0 0 0.25rem;
}
.lp-subtitle {
  color: var(--muted);
  font-size: 0.875rem;
  margin: 0 0 1.5rem;
  letter-spacing: 0.1em;
}
.lp-oauth-section {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
  margin-bottom: 1rem;
}
.lp-btn-google,
.lp-btn-wechat {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.625rem;
  border-radius: 0.5rem;
  padding: 0.75rem 1rem;
  font-family: inherit;
  font-size: 0.9375rem;
  font-weight: 500;
  cursor: pointer;
  min-height: 48px;
  width: 100%;
}
.lp-btn-google {
  background: #ffffff;
  color: #1a140c;
  border: 1px solid var(--border);
}
.lp-btn-wechat {
  background: #1aad19;
  color: #ffffff;
  border: 1px solid #1aad19;
}
.lp-oauth-logo {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}
.lp-or {
  text-align: center;
  color: var(--muted);
  font-size: 0.75rem;
  margin: 0.125rem 0;
}
.lp-guest-toggle {
  display: block;
  width: 100%;
  background: transparent;
  border: none;
  color: var(--muted);
  font-size: 0.875rem;
  font-family: inherit;
  cursor: pointer;
  padding: 0.5rem;
  text-decoration: underline;
}
.lp-guest-form {
  margin-top: 0.75rem;
  text-align: left;
}
.lp-field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  margin-bottom: 1rem;
}
.lp-field label {
  font-size: 0.875rem;
  color: var(--muted);
}
.lp-input {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 0.5rem;
  padding: 0.625rem 0.75rem;
  font-size: 1rem;
  font-family: inherit;
  color: var(--text);
  width: 100%;
  outline: none;
}
.lp-actions {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}
.lp-full-btn {
  width: 100%;
}
.lp-join-row {
  display: flex;
  gap: 0.5rem;
}
.lp-code-input {
  flex: 1;
  width: auto;
  letter-spacing: 0.15em;
  font-weight: 600;
}
.lp-join-btn {
  width: auto;
  padding: 0 1rem;
  white-space: nowrap;
}

/* ── Create-room ─────────────────────────────────────────────────────── */
.cr-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.cr-back {
  background: none;
  border: none;
  color: var(--muted);
  font-size: 0.8125rem;
  cursor: pointer;
  font-family: inherit;
  padding: 0;
  align-self: flex-start;
}
.cr-section-lbl {
  font-size: 0.625rem;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--muted);
  margin-bottom: 0.125rem;
}
.cr-title {
  font-size: 1.0625rem;
  font-weight: 500;
  color: var(--text);
  margin: 0;
}
.cr-stepper-card {
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.5rem;
  padding: 0.875rem 1rem;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.05);
}
.cr-field-lbl {
  font-size: 0.625rem;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: var(--muted);
  margin-bottom: 0.625rem;
}
.cr-stepper-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.cr-stepper-btn {
  width: 38px;
  height: 38px;
  border-radius: 6px;
  border: 1px solid var(--border);
  font-size: 1.25rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--paper);
  color: var(--muted);
  font-family: inherit;
}
.cr-plus {
  background: var(--red);
  color: #fff;
  border-color: var(--red);
}
.cr-stepper-value {
  text-align: center;
}
.cr-stepper-num {
  display: block;
  font-family: 'Noto Serif SC', serif;
  font-size: 2.25rem;
  font-weight: 700;
  color: var(--ink);
  line-height: 1;
}
.cr-stepper-range {
  display: block;
  font-size: 0.625rem;
  color: var(--muted);
  margin-top: 2px;
}
.cr-balance-note {
  font-size: 0.625rem;
  color: var(--muted);
  padding: 0.375rem 0.5625rem;
  background: rgba(45, 106, 63, 0.05);
  border: 1px solid rgba(45, 106, 63, 0.18);
  border-radius: 0.3125rem;
}
.cr-role-list {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}
.cr-role-row {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5625rem 0.75rem;
  border-radius: 0.4375rem;
}
.cr-row-wolf {
  background: rgba(181, 37, 26, 0.06);
  border: 1px solid rgba(181, 37, 26, 0.2);
}
.cr-row-village {
  background: rgba(45, 106, 63, 0.05);
  border: 1px solid rgba(45, 106, 63, 0.18);
}
.cr-row-on {
  background: var(--card);
  border: 1px solid var(--border-l);
}
.cr-row-off {
  background: var(--paper);
  border: 1px solid var(--border-l);
  opacity: 0.7;
}
.cr-emoji {
  font-size: 1.125rem;
}
.cr-role-names {
  flex: 1;
}
.cr-role-name {
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--text);
}
.cr-role-muted {
  color: var(--muted);
}
.cr-win-desc {
  display: block;
  font-size: 0.625rem;
  color: var(--muted);
  margin-top: 1px;
}
.cr-req {
  font-size: 0.625rem;
  letter-spacing: 0.05em;
  font-weight: 600;
}
.cr-req-wolf {
  color: var(--red);
}
.cr-req-village {
  color: var(--green);
}
.cr-toggle {
  display: inline-flex;
  align-items: center;
  width: 36px;
  height: 20px;
  border-radius: 10px;
  flex-shrink: 0;
  position: relative;
}
.cr-toggle-on {
  background: var(--green);
}
.cr-toggle-off {
  background: var(--border);
}
.cr-toggle-thumb {
  display: block;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  position: absolute;
  top: 2px;
}
.cr-toggle-on .cr-toggle-thumb {
  left: calc(100% - 18px);
}
.cr-toggle-off .cr-toggle-thumb {
  left: 2px;
}
.cr-bgm-trigger {
  font-size: 0.75rem;
  padding: 0.4375rem 0.625rem;
  border-radius: 0.375rem;
  border: 1px solid var(--border);
  background: var(--paper);
  color: var(--text);
  font-family: inherit;
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
}

/* ── Room ────────────────────────────────────────────────────────────── */
.rp-wrap {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.rp-back {
  background: none;
  border: none;
  color: var(--muted);
  font-size: 0.8125rem;
  cursor: pointer;
  font-family: inherit;
  padding: 0;
  align-self: flex-start;
}
.rp-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 1rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.rp-title {
  font-size: 1.0625rem;
  font-weight: 500;
  color: var(--text);
}
.rp-code-card {
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.375rem;
  padding: 0.375rem 0.75rem;
  text-align: center;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.5rem;
}
.rp-code-lbl {
  font-size: 0.625rem;
  letter-spacing: 0.1em;
  color: var(--muted);
  text-transform: uppercase;
}
.rp-code-num {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.25rem;
  font-weight: 700;
  letter-spacing: 0.25em;
  color: var(--red);
}
.rp-player-count {
  font-size: 0.6875rem;
  color: var(--muted);
  display: flex;
  align-items: center;
  gap: 0.375rem;
}
.rp-count-ready {
  color: var(--green);
  font-weight: 500;
}
.rp-count-sep {
  color: var(--border);
}
.rp-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 0.375rem;
  margin-bottom: 0.25rem;
}
.rp-status-bar {
  font-size: 0.6875rem;
  padding: 0.5rem 0.625rem;
  border-radius: 0.375rem;
}
.rp-status-wait {
  background: rgba(45, 106, 63, 0.06);
  border: 1px solid rgba(45, 106, 63, 0.2);
  color: var(--muted);
}
.rp-footer .btn {
  width: 100%;
}

/* ── Game-over (faithful ResultView replica) ─────────────────────────── */
.end-result-wrap {
  border-radius: 0.625rem;
  background: var(--bg);
  padding: 0.5rem;
  transition: background 0.3s;
}
.end-result-wolves {
  background: var(--ink);
  color: #f5f0e8;
}
.end-result-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 1.5rem 1rem;
  text-align: center;
}
.end-result-wolves .end-result-card {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.12);
}
.end-game-over-label {
  font-size: 0.7rem;
  letter-spacing: 0.15em;
  color: var(--muted);
  margin: 0 0 0.25rem;
}
.end-result-wolves .end-game-over-label {
  color: rgba(245, 240, 232, 0.55);
}
.end-outcome-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 2.5rem;
  color: var(--green);
  margin: 0 0 0.25rem;
  letter-spacing: 0.1em;
}
.end-result-wolves .end-outcome-title {
  color: var(--red);
}
.end-outcome-sub {
  font-size: 0.875rem;
  font-weight: 600;
  letter-spacing: 0.15em;
  color: var(--muted);
  margin: 0 0 1.5rem;
}
.end-result-wolves .end-outcome-sub {
  color: rgba(245, 240, 232, 0.55);
}
.end-divider-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.875rem;
  font-size: 0.7rem;
  letter-spacing: 0.15em;
  color: var(--muted);
}
.end-result-wolves .end-divider-label {
  color: rgba(245, 240, 232, 0.5);
}
.end-divider-label::before,
.end-divider-label::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--border);
}
.end-result-wolves .end-divider-label::before,
.end-result-wolves .end-divider-label::after {
  background: rgba(255, 255, 255, 0.15);
}
.end-reveal-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(85px, 47%), 1fr));
  gap: 0.5rem;
  margin-bottom: 1.5rem;
}
.end-reveal-card {
  border: 1px solid rgba(45, 106, 63, 0.4);
  background: rgba(45, 106, 63, 0.08);
  border-radius: 0.5rem;
  padding: 0.5rem 0.25rem;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  min-width: 0;
}
.end-reveal-meta {
  font-size: 0.625rem;
  color: var(--muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.end-reveal-role {
  font-size: 1rem;
  font-weight: 600;
  color: var(--text);
  font-family: 'Noto Serif SC', serif;
}
.end-reveal-wolf {
  border-color: var(--red);
  background: rgba(181, 37, 26, 0.1);
}
.end-reveal-wolf .end-reveal-role {
  color: var(--red);
}
.end-result-wolves .end-reveal-card {
  background: rgba(45, 106, 63, 0.16);
  border-color: rgba(45, 106, 63, 0.55);
}
.end-result-wolves .end-reveal-card .end-reveal-meta {
  color: rgba(245, 240, 232, 0.55);
}
.end-result-wolves .end-reveal-card .end-reveal-role {
  color: rgba(245, 240, 232, 0.92);
}
.end-result-wolves .end-reveal-wolf {
  background: rgba(181, 37, 26, 0.22);
  border-color: var(--red);
}
.end-result-wolves .end-reveal-wolf .end-reveal-role {
  color: #ff8a80;
}
.end-play-again {
  width: 100%;
  margin-top: 0;
}

/* ── Footer ──────────────────────────────────────────────────────────── */
.demo-footer {
  text-align: center;
  font-size: 0.75rem;
  color: var(--muted);
  padding: 1rem 0 0;
}
.demo-footer a {
  color: var(--red);
}
</style>
