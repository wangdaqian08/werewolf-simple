<template>
  <div class="voting-wrap">
    <!-- ── VOTING screen (includes VOTE_RESULT — merged) ── -->
    <template v-if="isVotingScreen">
      <header class="day-header">
        <div class="day-pill">
          {{ $t('day.pill', { n: votingPhase.dayNumber }) }}
          <span class="day-pill-sep">·</span>
          {{ $t('vote.phase') }}
        </div>
        <LanguageToggle inline />
        <div class="day-timer">{{ formattedTime }}</div>
      </header>

      <SunArc
        :phase-deadline="votingPhase.phaseDeadline"
        :phase-started="votingPhase.phaseStarted"
      />

      <!-- RE_VOTING banner -->
      <div v-if="votingPhase.subPhase === 'RE_VOTING'" class="revote-banner">
        {{ $t('vote.round2') }}
        <template v-if="pkStage.length">
          <span data-testid="pk-banner">
            ·
            {{
              $t('vote.pk', { seats: pkSeats.map((s) => $t('common.seat', { n: s })).join(' · ') })
            }}
          </span>
        </template>
      </div>
      <div v-if="pkStage.includes(myUserId)" class="revote-banner" data-testid="pk-stage-note">
        {{ $t('vote.youAreOnThe') }}
      </div>

      <!-- Below-arch row: my-role-chip on left, right-stack (history / log-fab / Action) on right -->
      <div v-if="myRole || voteHistory?.length" class="role-history-row">
        <button
          v-if="myRole"
          class="toolbar-btn my-role-chip my-role-locked"
          data-testid="my-role-chip"
          @click="showRoleCard = true"
        >
          <span class="toolbar-icon" aria-hidden="true">🔒</span>
          <span>{{ $t('vote.tapToReveal') }}</span>
        </button>
        <div v-else />
        <div class="right-stack">
          <button
            v-if="voteHistory?.length"
            class="toolbar-btn history-btn"
            @click="showHistory = true"
          >
            <span class="toolbar-icon" aria-hidden="true">📜</span>
            <span>{{ $t('vote.history') }}</span>
          </button>
          <button
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
            phase="DAY_VOTING"
            :sub-phase="votingPhase.subPhase"
            :my-role="myRole"
            :is-alive="isAlive ?? false"
            :players="players"
            :my-user-id="myUserId"
            @self-destruct="(target) => emit('self-destruct', target)"
          />
        </div>
      </div>

      <!-- Before reveal: simple vote count -->
      <div v-if="!isRevealed" class="vote-count-bar">
        <div class="tally-chip tally-chip-count">
          <span class="tally-votes">{{ votingPhase.votesSubmitted ?? 0 }}</span>
          <span class="tally-sep">/</span>
          <span class="tally-total">{{ votingPhase.totalVoters ?? '?' }}</span>
          <span class="tally-label">{{ $t('vote.voted') }}</span>
        </div>
      </div>

      <!-- After reveal: vote columns showing who voted for whom -->
      <div v-else class="vote-columns-wrap">
        <!-- Eliminated player banner -->
        <div v-if="votingPhase.eliminatedPlayerId" class="banner banner-kill">
          <Avatar
            class="elim-banner-avatar"
            :avatar="votingPhase.eliminatedAvatar"
            :nickname="votingPhase.eliminatedNickname ?? ''"
            emoji="💀"
            size="md"
          />
          <div class="elim-banner-body">
            <span class="elim-banner-tag">{{ $t('vote.eliminated') }}</span>
            <span class="elim-banner-name">
              {{
                $t('vote.nameSeat', {
                  name: votingPhase.eliminatedNickname,
                  n: votingPhase.eliminatedSeatIndex,
                })
              }}
            </span>
          </div>
        </div>

        <!-- Idiot reveal banner (survived vote, loses voting right) -->
        <div v-else-if="votingPhase.idiotRevealedId" class="banner banner-idiot">
          <span class="elim-banner-avatar">🃏</span>
          <div class="elim-banner-body">
            <span class="elim-banner-tag">{{ $t('vote.idiotRevealed') }}</span>
            <span class="elim-banner-name">
              {{
                $t('vote.nameSeat', {
                  name: votingPhase.idiotRevealedNickname,
                  n: votingPhase.idiotRevealedSeatIndex,
                })
              }}
            </span>
            <span class="elim-banner-role">{{ $t('vote.survivesLosesVote') }}</span>
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
              <Avatar
                class="vote-col-avatar"
                :avatar="entry.avatar"
                :nickname="entry.nickname"
                emoji="😊"
                size="sm"
              />
              <div class="vote-col-cname">{{ entry.nickname }}</div>
              <div class="vote-col-count" :class="i === 0 ? 'tally-winner' : 'tally-muted'">
                {{ entry.votes }}
              </div>
            </div>
            <div class="vote-col-body">
              <div v-for="v in entry.voters" :key="v.userId" class="vcol-row">
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
              <div class="vote-col-count tally-abstain">
                {{ votingPhase.abstainVoters?.length ?? 0 }}
              </div>
            </div>
            <div class="vote-col-body">
              <div v-if="!votingPhase.abstainVoters?.length" class="vcol-row vcol-empty">
                {{ $t('vote.none') }}
              </div>
              <div v-for="v in votingPhase.abstainVoters" :key="v.userId" class="vcol-row">
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
        </div>
      </div>

      <!-- Player grid -->
      <section class="player-grid">
        <PlayerSlot
          v-for="player in players"
          :key="player.userId"
          :self="player.userId === myUserId"
          :seat="player.seatIndex"
          :nickname="player.nickname"
          :avatar="player.avatar"
          :variant="votingSlotVariant(player)"
          mode="room"
          @click="onVotingTap(player)"
        >
          <template v-if="player.isSheriff" #badge>
            <div class="sheriff-badge">⭐</div>
          </template>
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
        <!-- After reveal: host must click Continue; others wait. No auto-advance
             on the frontend — the server is authoritative. A pending private
             shot does not hold the host back: continuing ends it. -->
        <template v-if="isRevealed">
          <template v-if="isHost">
            <div class="vote-actions">
              <button
                class="btn btn-primary vote-btn"
                data-testid="voting-continue"
                @click="emit('continueVoting')"
              >
                {{ $t('vote.continue') }}
              </button>
            </div>
          </template>
          <template v-else>
            <p class="footer-hint">{{ $t('vote.waitingForHost') }}</p>
          </template>
        </template>

        <!-- Before reveal -->
        <template v-else>
          <!-- HOST: own vote (if alive) + reveal button -->
          <template v-if="viewRole === 'HOST'">
            <template v-if="hostIsAlive">
              <!-- Idiot revealed: host lost voting right -->
              <template v-if="!myCanVote">
                <p class="footer-hint idiot-no-vote">{{ $t('vote.idiotNoVote') }}</p>
              </template>
              <template v-else-if="votingPhase.myVote || votingPhase.myVoteSkipped">
                <button
                  class="btn btn-secondary"
                  data-testid="voting-unvote"
                  :class="{ 'is-loading': actionPending }"
                  :disabled="actionPending"
                  @click="emit('unvote')"
                >
                  {{ $t('vote.unvote') }}
                </button>
              </template>
              <template v-else>
                <div class="vote-actions">
                  <button
                    class="btn btn-primary vote-btn"
                    data-testid="voting-vote"
                    :class="{ 'is-loading': actionPending }"
                    :disabled="!effectiveSelected || actionPending"
                    @click="effectiveSelected && emit('vote', effectiveSelected)"
                  >
                    {{ $t('vote.vote') }}
                  </button>
                  <button
                    class="btn btn-secondary skip-btn"
                    data-testid="voting-skip"
                    :class="{ 'is-loading': actionPending }"
                    :disabled="actionPending"
                    @click="emit('skip')"
                  >
                    {{ $t('vote.abstain') }}
                  </button>
                </div>
              </template>
            </template>
            <!-- Only show reveal button if subPhase is VOTING or RE_VOTING -->
            <button
              v-if="votingPhase.subPhase === 'VOTING' || votingPhase.subPhase === 'RE_VOTING'"
              class="btn btn-gold"
              data-testid="voting-reveal"
              :class="{ 'is-loading': actionPending }"
              :disabled="!allVotesIn || actionPending"
              @click="emit('revealVoting')"
            >
              {{ $t('vote.reveal') }}
            </button>
          </template>

          <!-- DEAD: voting disabled -->
          <template v-else-if="viewRole === 'DEAD'">
            <button class="btn btn-secondary" disabled>{{ $t('vote.votingDisabled') }}</button>
          </template>

          <!-- ALIVE: vote/skip or unvote (or no vote right if idiot revealed) -->
          <template v-else-if="viewRole === 'ALIVE'">
            <template v-if="!myCanVote">
              <button class="btn btn-secondary" disabled>{{ $t('vote.idiotNoVote') }}</button>
            </template>
            <template v-else-if="votingPhase.myVote || votingPhase.myVoteSkipped">
              <button
                class="btn btn-secondary"
                data-testid="voting-unvote"
                :class="{ 'is-loading': actionPending }"
                :disabled="actionPending"
                @click="emit('unvote')"
              >
                {{ $t('vote.unvote') }}
              </button>
            </template>
            <template v-else>
              <div class="vote-actions">
                <button
                  class="btn btn-primary vote-btn"
                  data-testid="voting-vote"
                  :class="{ 'is-loading': actionPending }"
                  :disabled="!effectiveSelected || actionPending"
                  @click="effectiveSelected && emit('vote', effectiveSelected)"
                >
                  {{ $t('vote.vote') }}
                </button>
                <button
                  class="btn btn-secondary skip-btn"
                  data-testid="voting-skip"
                  :class="{ 'is-loading': actionPending }"
                  :disabled="actionPending"
                  @click="emit('skip')"
                >
                  {{ $t('vote.abstain') }}
                </button>
              </div>
            </template>
          </template>

          <!-- GUEST: spectator -->
          <template v-else>
            <p class="footer-hint">{{ $t('vote.waiting') }}</p>
          </template>
        </template>
      </footer>

      <!-- Role card bottom sheet -->
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
              <p class="rc-desc" data-testid="role-card-desc">
                {{ roleDescription(myRole, true) }}
              </p>
              <RoleHistory :history="roleHistory" />
            </div>
          </div>
        </div>
      </Teleport>

      <!-- History bottom sheet — Teleport here so it stays inside isVotingScreen block -->
      <Teleport to="body">
        <div v-if="showHistory" class="history-overlay" @click.self="showHistory = false">
          <div class="history-sheet">
            <div class="history-header">
              <span>{{ $t('vote.voteHistory') }}</span>
              <button class="history-close" @click="showHistory = false">✕</button>
            </div>
            <div class="history-body">
              <div v-if="!voteHistory?.length" class="history-empty">
                {{ $t('vote.noHistoryYet') }}
              </div>
              <div
                v-for="round in [...(voteHistory ?? [])].reverse()"
                :key="round.dayNumber"
                class="history-round"
              >
                <div class="history-round-title">
                  {{ $t('day.pill', { n: round.dayNumber }) }}
                </div>
                <!-- Vote-out banner -->
                <div v-if="round.eliminatedPlayerId" class="banner banner-kill history-banner">
                  <Avatar
                    class="banner-avatar"
                    :avatar="round.eliminatedAvatar"
                    :nickname="round.eliminatedNickname ?? ''"
                    emoji="💀"
                    size="md"
                  />
                  <div>
                    <div class="banner-title">
                      {{
                        $t('vote.histOut', {
                          n: round.eliminatedSeatIndex,
                          name: round.eliminatedNickname,
                        })
                      }}
                    </div>
                    <div v-if="round.eliminatedRole" class="banner-sub">
                      {{ roleName(round.eliminatedRole) }}
                    </div>
                  </div>
                </div>
                <!-- Shot banner (hunter or 黑狼王 — the role is not named) -->
                <div v-if="round.hunterShotPlayerId" class="banner banner-kill history-banner">
                  <span class="banner-avatar">🔫</span>
                  <div>
                    <div class="banner-title">
                      {{
                        $t('vote.histShot', {
                          n: round.hunterShotSeatIndex,
                          name: round.hunterShotNickname,
                        })
                      }}
                    </div>
                    <div v-if="round.hunterShotRole" class="banner-sub">
                      {{ roleName(round.hunterShotRole) }}
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
                      <Avatar
                        class="vote-col-avatar"
                        :avatar="entry.avatar"
                        :nickname="entry.nickname"
                        emoji="😊"
                        size="sm"
                      />
                      <div class="vote-col-cname">{{ entry.nickname }}</div>
                      <div class="vote-col-count" :class="i === 0 ? 'tally-winner' : 'tally-muted'">
                        {{ entry.votes }}
                      </div>
                    </div>
                    <div class="vote-col-body">
                      <div v-for="v in entry.voters" :key="v.userId" class="vcol-row">
                        <Avatar
                          class="vcol-avatar"
                          :avatar="v.avatar"
                          :nickname="v.nickname"
                          emoji="😊"
                          size="sm"
                        />
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

    <!-- ── Private shot screen (hunter / 黑狼王): only the shooter's own state says canShoot ── -->
    <template v-else-if="isShooter">
      <header class="day-header">
        <div class="day-pill">{{ $t('day.pill', { n: votingPhase.dayNumber }) }}</div>
        <LanguageToggle inline />
        <div class="day-timer">{{ formattedTime }}</div>
      </header>

      <div class="sun-arc-placeholder" />

      <div class="banner-area">
        <div class="banner banner-kill" data-testid="voting-shoot-banner">
          <span class="banner-avatar">🔫</span>
          <div>
            <div class="banner-title">{{ $t('vote.yourShot') }}</div>
            <div class="banner-sub">{{ $t('vote.shotSub') }}</div>
          </div>
        </div>
      </div>

      <section class="player-grid">
        <PlayerSlot
          v-for="player in players"
          :key="player.userId"
          :self="player.userId === myUserId"
          :seat="player.seatIndex"
          :nickname="player.nickname"
          :avatar="player.avatar"
          :variant="shootSlotVariant(player)"
          mode="room"
          @click="onHunterTap(player)"
        >
          <template v-if="player.isSheriff" #badge>
            <div class="sheriff-badge">⭐</div>
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
        <div class="vote-actions">
          <button
            class="btn btn-danger vote-btn"
            data-testid="voting-hunter-shoot"
            :class="{ 'is-loading': actionPending }"
            :disabled="!effectiveSelected || actionPending"
            @click="effectiveSelected && emit('hunterShoot', effectiveSelected)"
          >
            {{ $t('vote.shoot') }}
          </button>
          <button
            class="btn btn-secondary skip-btn"
            data-testid="voting-hunter-pass"
            :class="{ 'is-loading': actionPending }"
            :disabled="actionPending"
            @click="emit('hunterPass')"
          >
            {{ $t('vote.pass') }}
          </button>
        </div>
      </footer>
    </template>

    <!-- ── BADGE_HANDOVER screen (includes BADGE_RECEIVED — merged) ── -->
    <template v-else-if="isBadgeScreen">
      <header class="day-header" data-testid="badge-handover-screen">
        <div class="day-pill">
          {{ $t('day.pill', { n: votingPhase.dayNumber }) }}
          <span class="day-pill-sep">·</span>
          {{ $t('vote.badge') }}
        </div>
        <LanguageToggle inline />
        <div class="day-timer">{{ formattedTime }}</div>
      </header>

      <div class="sun-arc-placeholder" />

      <!-- Destroyed message replaces banner when badge is burned -->
      <div class="banner-area">
        <div v-if="votingPhase.badgeDestroyed" class="banner badge-status-burned">
          <span class="banner-avatar">⚔️</span>
          <div>
            <div class="banner-title">{{ $t('vote.badgeDestroyed') }}</div>
          </div>
        </div>
        <div v-else-if="newSheriffInfo" class="banner badge-status-passed">
          <span class="banner-avatar">⭐</span>
          <div>
            <div class="banner-title">
              {{ $t('vote.badgePassed', { name: newSheriffInfo.nickname }) }}
            </div>
          </div>
        </div>
        <div v-else-if="isEliminatedSheriff" class="banner banner-gold">
          <span class="banner-avatar">⭐</span>
          <div>
            <div class="banner-title">{{ $t('vote.eliminated2') }}</div>
            <div class="banner-sub">{{ $t('vote.chooseBadgeHeir') }}</div>
          </div>
        </div>
      </div>

      <section class="player-grid">
        <PlayerSlot
          v-for="player in players"
          :key="player.userId"
          :self="player.userId === myUserId"
          :seat="player.seatIndex"
          :nickname="player.nickname"
          :avatar="player.avatar"
          :variant="badgeSlotVariant(player)"
          mode="room"
          @click="onBadgeTap(player)"
        >
          <!-- Star on sheriff's card -->
          <template v-if="player.isSheriff" #badge>
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
              <button
                class="btn btn-primary vote-btn"
                :class="{ 'is-loading': actionPending }"
                :disabled="actionPending"
                data-testid="badge-continue-night"
                @click="emit('continueVoting')"
              >
                {{ $t('vote.night') }}
              </button>
            </div>
          </template>
          <template v-else>
            <p class="footer-hint">{{ $t('vote.waiting2') }}</p>
          </template>
        </template>
        <!-- Choosing heir -->
        <template v-else>
          <template v-if="isEliminatedSheriff">
            <div class="vote-actions">
              <button
                class="btn btn-gold vote-btn"
                :class="{ 'is-loading': actionPending }"
                :disabled="!effectiveSelected || actionPending"
                data-testid="badge-pass"
                @click="effectiveSelected && emit('passBadge', effectiveSelected)"
              >
                {{ $t('vote.passBadge') }}
              </button>
              <button
                class="btn btn-secondary skip-btn"
                :class="{ 'is-loading': actionPending }"
                :disabled="actionPending"
                data-testid="badge-destroy"
                @click="emit('destroyBadge')"
              >
                {{ $t('day.destroyBadge') }}
              </button>
            </div>
          </template>
          <template v-else>
            <p class="footer-hint">{{ $t('vote.waitingForSheriffTo') }}</p>
          </template>
        </template>
      </footer>
    </template>

    <!-- Action log drawer -->
    <ActionLogDrawer :game-id="gameId" :open="showLog" @close="showLog = false" />
    <GameSettingsModal
      :settings="gameSettings"
      :open="showSettings"
      @close="showSettings = false"
    />
  </div>
</template>

<script lang="ts" setup>
import LanguageToggle from '@/components/LanguageToggle.vue'
import { roleDescription, roleName, roleTag } from '@/utils/roleDefinitions'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import type {
  GamePlayer,
  GameSettings,
  PlayerRole,
  VoteRoundHistory,
  VotingState,
  RoleHistory as RoleHistoryData,
} from '@/types'
import PlayerSlot from '@/components/PlayerSlot.vue'
import RoleHistory from '@/components/RoleHistory.vue'
import SunArc from '@/components/SunArc.vue'
import ActionLogDrawer from '@/components/ActionLogDrawer.vue'
import GameSettingsModal from '@/components/GameSettingsModal.vue'
import Avatar from '@/components/Avatar.vue'
import ActionMenu from '@/components/ActionMenu.vue'

const props = defineProps<{
  gameId: number
  votingPhase: VotingState
  players: GamePlayer[]
  myUserId: string
  isHost: boolean
  myRole?: PlayerRole
  roleHistory?: RoleHistoryData | null
  isAlive?: boolean
  // userId of the game's current sheriff (null after BADGE_DESTROY).
  // Drives badge-handover UI: the dying sheriff to pass is whoever holds
  // the badge right now — could be the voted-out player, the hunter-shot
  // sheriff, or a revealed-idiot sheriff. `eliminatedPlayerId` only
  // identifies the voted-out player and gets the hunter-shot case wrong.
  currentSheriffUserId?: string | null
  voteHistory?: VoteRoundHistory[]
  actionPending?: boolean
  gameSettings?: GameSettings | null
}>()

const emit = defineEmits<{
  selectPlayer: [userId: string]
  vote: [targetId: string]
  skip: []
  unvote: []
  revealVoting: []
  continueVoting: []
  hunterShoot: [userId: string]
  hunterPass: []
  passBadge: [userId: string]
  destroyBadge: []
  'self-destruct': [targetUserId?: string]
}>()

// ── Screen grouping ───────────────────────────────────────────────────────────
// VOTE_RESULT is merged into the VOTING screen (shown when tallyRevealed).
// A dead shooter (the voted-out player, or a chained shooter) with an open shot
// gets the shoot screen, before the badge handover; everyone else keeps the
// ordinary screens — canShoot is only ever true on the shooter's own state.
const isShooter = computed(() => !!props.votingPhase.canShoot)
const isVotingScreen = computed(
  () =>
    !isShooter.value &&
    (props.votingPhase.subPhase === 'VOTING' ||
      props.votingPhase.subPhase === 'RE_VOTING' ||
      props.votingPhase.subPhase === 'VOTE_RESULT'),
)
// BADGE_RECEIVED is merged into the BADGE_HANDOVER screen
const isBadgeScreen = computed(
  () =>
    props.votingPhase.subPhase === 'BADGE_HANDOVER' ||
    props.votingPhase.subPhase === 'BADGE_RECEIVED',
)

// Tally/result is revealed when tallyRevealed flag is set or we're on VOTE_RESULT
const isRevealed = computed(
  () => props.votingPhase.tallyRevealed || props.votingPhase.subPhase === 'VOTE_RESULT',
)

// Debug: watch votingPhase changes

// watch(
// () => props.votingPhase,
// (newPhase) => {
//   console.log('[VotingPhase] votingPhase changed:', newPhase)
//   console.log('[VotingPhase] isRevealed:', isRevealed.value)
// },
// { deep: true, immediate: true },
// )

// Badge screen: post-action states
//
// We tie this to the backend's authoritative `subPhase`, NOT to player flag
// inspection (the previous approach guessed by looking at who's still flagged
// `isSheriff`, which got the hunter-shot-sheriff case wrong — the eliminated
// player there is the hunter, not the sheriff). The backend transitions
// BADGE_HANDOVER → VOTE_RESULT atomically with the player-flag updates and
// then `isBadgeScreen` flips false, so any UI inside the badge screen runs
// only while we're genuinely waiting for the dying sheriff to act.
const badgeDone = computed(() => {
  if (props.votingPhase.badgeDestroyed) return true
  return props.votingPhase.subPhase !== 'BADGE_HANDOVER'
})

// Check if current player is the dying sheriff (only they can pass the badge).
// Previously this was `votingPhase.eliminatedPlayerId === myUserId` — wrong
// when the hunter is voted out and shoots a different player who happens to be
// the sheriff: the eliminated player is the hunter, but the sheriff (the
// shot victim) is the one who must hand over the badge.
const isEliminatedSheriff = computed(() => {
  return props.currentSheriffUserId === props.myUserId
})

// Get new sheriff info (alive player with isSheriff=true in BADGE_HANDOVER)
const newSheriffInfo = computed(() => {
  if (badgeDone.value && !props.votingPhase.badgeDestroyed) {
    return props.players.find((p) => p.isSheriff && p.isAlive) ?? null
  }
  return null
})

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

// PK re-vote: the tied players (backend-decided); only they can be voted for.
const pkStage = computed(() =>
  props.votingPhase.subPhase === 'RE_VOTING' ? (props.votingPhase.pkCandidates ?? []) : [],
)
const pkSeats = computed(() =>
  props.players
    .filter((p) => pkStage.value.includes(p.userId))
    .map((p) => p.seatIndex)
    .sort((a, b) => a - b),
)

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

// Sync local selection with vote state
watch(
  () => props.votingPhase.myVote || props.votingPhase.myVoteSkipped,
  (hasVoted) => {
    if (hasVoted) {
      // User has voted or skipped - clear local selection
      localSelected.value = undefined
    }
  },
)

const effectiveSelected = computed(() => localSelected.value)

function selectPlayer(userId: string) {
  localSelected.value = userId
  emit('selectPlayer', userId)
}

// ── Timer ─────────────────────────────────────────────────────────────────────
// `now` drives the still-live phase-deadline display (formattedTime) when the
// server publishes a phaseDeadline. There is deliberately no client-side
// auto-continue timer anymore: an invisible setTimeout that emitted
// 'continueVoting' previously fired against HUNTER_SHOOT sub-phases (when a
// voted-out hunter triggered the shoot flow but tallyRevealed stayed true)
// and produced 400s on the backend. The host is now the only trigger for
// VOTING_CONTINUE — matching the same "no invisible frontend timers" rule
// that removed the seer countdown.
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

// ── Sorted tally ──────────────────────────────────────────────────────────────
const sortedTally = computed(() =>
  [...(props.votingPhase.tally ?? [])].sort((a, b) => b.votes - a.votes),
)

// ── Vote History panel ────────────────────────────────────────────────────────
const showHistory = ref(false)
const showRoleCard = ref(false)
const myPlayer = computed(() => props.players.find((p) => p.userId === props.myUserId))
const showLog = ref(false)
const showSettings = ref(false)

watch([showHistory, showRoleCard], ([h, r]) => {
  document.body.style.overflow = h || r ? 'hidden' : ''
})

// ── Role card metadata ─────────────────────────────────────────────────────────
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

// ── Slot variants ─────────────────────────────────────────────────────────────
function votingSlotVariant(player: GamePlayer) {
  if (!player.isAlive) return 'dead' as const
  const hasVoted = props.votingPhase.votedPlayerIds?.includes(player.userId)

  // Selected player being voted for (gold border) - show before or after voting
  if (player.userId === effectiveSelected.value) {
    return 'selected' as const
  }

  // After voting, all voted players show 'ready' (green)
  if (hasVoted) {
    return 'ready' as const
  }

  // Alive players show 'alive' (no special border)
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
  // New sheriff (alive player with isSheriff=true) gets green styling
  if (player.isSheriff && player.isAlive) {
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
  // Prevent selection after player has already voted or skipped
  if (props.votingPhase.myVote || props.votingPhase.myVoteSkipped) return
  if (pkStage.value.length && !pkStage.value.includes(player.userId)) return
  selectPlayer(player.userId)
}

function onHunterTap(player: GamePlayer) {
  // Only the shooter can select a target
  if (!isShooter.value) return
  if (!player.isAlive) return
  if (player.userId === props.votingPhase.eliminatedPlayerId) return
  selectPlayer(player.userId)
}

function onBadgeTap(player: GamePlayer) {
  if (badgeDone.value) return // badge already actioned
  if (!player.isAlive) return
  if (player.userId === props.votingPhase.eliminatedPlayerId) return
  if (player.userId === props.myUserId) return // a sheriff shot by the hunter is still alive
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

.tally-abstain {
  color: var(--muted);
}

.vote-col-abstain {
  opacity: 0.7;
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

/* Role + history row */
.role-history-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 0.25rem 0.875rem 0.375rem;
  min-height: 2.25rem;
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
  min-width: 44px;
  min-height: 44px;
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

/* Right-side stack inside role-history-row: history-btn, log-fab, ActionMenu */
/* Toolbar buttons sit in one row (wrapping on narrow phones) so the seat
   grid and the footer action stay above the fold. */
.right-stack {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
}
</style>
