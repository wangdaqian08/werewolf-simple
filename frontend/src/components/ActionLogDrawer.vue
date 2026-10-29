<template>
  <Teleport to="body">
    <!-- Backdrop -->
    <Transition name="fade">
      <div v-if="open" class="drawer-backdrop" @click="$emit('close')" />
    </Transition>

    <!-- Drawer -->
    <Transition name="slide-up">
      <div v-if="open" class="action-log-drawer">
        <div class="drawer-handle" />

        <div class="drawer-header">
          <span class="drawer-title">{{ $t('common.gameLog') }}</span>
          <button class="drawer-close" data-testid="action-log-close" @click="$emit('close')">
            ✕
          </button>
        </div>

        <div class="drawer-body">
          <div v-if="loading" class="log-loading">{{ $t('common.loading') }}</div>

          <div v-else-if="rounds.length === 0" class="log-empty">{{ $t('roleHistory.empty') }}</div>

          <div v-else class="rounds">
            <div v-for="round in rounds" :key="round.day" class="round-block">
              <div class="round-label">{{ $t('day.pill', { n: round.day }) }}</div>

              <!-- Sheriff election (only emitted on the day of the election) -->
              <div v-if="round.sheriffResult" class="log-section">
                <div class="section-title">{{ $t('log.sheriffElection') }}</div>
                <div v-if="round.sheriffResult.winnerNickname" class="log-row">
                  <span class="seat-badge">{{
                    $t('common.seat', { n: round.sheriffResult.winnerSeatIndex })
                  }}</span>
                  <span class="log-name">{{ round.sheriffResult.winnerNickname }}</span>
                  <span class="log-tag tag-gold">{{ $t('log.sheriff') }}</span>
                </div>
                <div v-else class="log-row muted">{{ $t('log.noSheriff') }}</div>
                <!-- Tally — same shape as vote result so players can analyse alliances -->
                <div v-if="round.sheriffResult.tally.length > 0" class="tally">
                  <div
                    v-for="entry in round.sheriffResult.tally"
                    :key="entry.userId"
                    class="tally-row"
                  >
                    <span class="tally-name">{{
                      $t('common.seatNick', { n: entry.seatIndex, name: entry.nickname })
                    }}</span>
                    <span class="tally-votes">{{ $t('common.votes', { n: entry.votes }) }}</span>
                    <span class="tally-voters">
                      {{
                        $t('common.seatList', {
                          list: entry.voters
                            .map((v) => $t('common.seat', { n: v.seatIndex }))
                            .join($t('common.listSep')),
                        })
                      }}
                    </span>
                  </div>
                </div>
                <div
                  v-if="(round.sheriffResult.abstainCount ?? 0) > 0"
                  class="tally-row tally-abstain"
                >
                  <span class="tally-name muted">{{ $t('vote.abstain') }}</span>
                  <span class="tally-votes">{{
                    $t('common.votes', { n: round.sheriffResult.abstainCount })
                  }}</span>
                  <span class="tally-voters">
                    {{
                      $t('common.seatList', {
                        list: (round.sheriffResult.abstainVoters ?? [])
                          .map((v) => $t('common.seat', { n: v.seatIndex }))
                          .join($t('common.listSep')),
                      })
                    }}
                  </span>
                </div>
              </div>

              <!-- Deaths at start of day -->
              <div v-if="round.deaths.length > 0" class="log-section">
                <div class="section-title">{{ $t('log.nightDeaths') }}</div>
                <div v-for="d in round.deaths" :key="d.userId" class="log-row">
                  <span class="seat-badge">{{ $t('common.seat', { n: d.seatIndex }) }}</span>
                  <span class="log-name">{{ d.nickname }}</span>
                </div>
                <div v-if="round.deaths.length === 0" class="log-row muted">
                  {{ $t('log.peaceful') }}
                </div>
              </div>
              <div v-else class="log-section">
                <div class="section-title">{{ $t('log.nightDeaths') }}</div>
                <div class="log-row muted">{{ $t('log.peaceful') }}</div>
              </div>

              <!-- Idiot reveals -->
              <div v-if="round.idiotReveals.length > 0" class="log-section">
                <div class="section-title">{{ $t('log.idiotReveal') }}</div>
                <div v-for="r in round.idiotReveals" :key="r.userId" class="log-row">
                  <span class="seat-badge">{{ $t('common.seat', { n: r.seatIndex }) }}</span>
                  <span class="log-name">{{ r.nickname }}</span>
                  <span class="log-tag tag-gold">{{ $t('log.idiotSurvives') }}</span>
                </div>
              </div>

              <!-- Vote result -->
              <div v-if="round.voteResult" class="log-section">
                <div class="section-title">{{ $t('log.voteResult') }}</div>
                <div v-if="round.voteResult.eliminatedNickname" class="log-row">
                  <span class="seat-badge">{{
                    $t('common.seat', { n: round.voteResult.eliminatedSeatIndex })
                  }}</span>
                  <span class="log-name">{{ round.voteResult.eliminatedNickname }}</span>
                  <span class="log-tag tag-red">{{ $t('log.out') }}</span>
                </div>
                <div v-else class="log-row muted">{{ $t('log.noOneOut') }}</div>
                <!-- Vote breakdown -->
                <div v-if="round.voteResult.tally.length > 0" class="tally">
                  <div
                    v-for="entry in round.voteResult.tally"
                    :key="entry.userId"
                    class="tally-row"
                  >
                    <span class="tally-name">{{
                      $t('common.seatNick', { n: entry.seatIndex, name: entry.nickname })
                    }}</span>
                    <span class="tally-votes">{{ $t('common.votes', { n: entry.votes }) }}</span>
                    <span class="tally-voters">
                      {{
                        $t('common.seatList', {
                          list: entry.voters
                            .map((v) => $t('common.seat', { n: v.seatIndex }))
                            .join($t('common.listSep')),
                        })
                      }}
                    </span>
                  </div>
                </div>
                <div
                  v-if="(round.voteResult.abstainCount ?? 0) > 0"
                  class="tally-row tally-abstain"
                >
                  <span class="tally-name muted">{{ $t('vote.abstain') }}</span>
                  <span class="tally-votes">{{
                    $t('common.votes', { n: round.voteResult.abstainCount })
                  }}</span>
                  <span class="tally-voters">
                    {{
                      $t('common.seatList', {
                        list: (round.voteResult.abstainVoters ?? [])
                          .map((v) => $t('common.seat', { n: v.seatIndex }))
                          .join($t('common.listSep')),
                      })
                    }}
                  </span>
                </div>
              </div>

              <!-- Shots (hunter / 黑狼王 — the shooter's role is not named; a chain has two) -->
              <div v-if="round.hunterShots.length > 0" class="log-section">
                <div class="section-title">{{ $t('log.shot') }}</div>
                <div
                  v-for="(s, i) in round.hunterShots"
                  :key="i"
                  class="log-row"
                  data-testid="log-shot-row"
                >
                  <span class="seat-badge">{{ $t('common.seat', { n: s.hunterSeatIndex }) }}</span>
                  <span class="log-name">{{ s.hunterNickname }}</span>
                  <span class="muted">{{ $t('log.hit') }}</span>
                  <span class="seat-badge">{{ $t('common.seat', { n: s.targetSeatIndex }) }}</span>
                  <span class="log-name">{{ s.targetNickname }}</span>
                </div>
              </div>

              <!-- Self-destruct -->
              <div v-if="round.selfDestructs.length > 0" class="log-section">
                <div class="section-title">{{ $t('log.selfDestruct') }}</div>
                <div v-for="s in round.selfDestructs" :key="s.userId" class="log-row">
                  <span class="seat-badge">{{ $t('common.seat', { n: s.seatIndex }) }}</span>
                  <span class="log-name">{{ s.nickname }}</span>
                  <span class="log-tag tag-red">{{ $t('actionMenu.selfDestruct') }}</span>
                  <template v-if="s.takenUserId">
                    <span class="muted">{{ $t('log.tookAlong') }}</span>
                    <span class="seat-badge">{{ $t('common.seat', { n: s.takenSeatIndex }) }}</span>
                    <span class="log-name">{{ s.takenNickname }}</span>
                  </template>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { gameService } from '@/services/gameService'
import type {
  ActionLogEntry,
  HunterShotPayload,
  IdiotRevealPayload,
  NightDeathPayload,
  SelfDestructPayload,
  SheriffResultPayload,
  VoteResultPayload,
} from '@/types'

const props = defineProps<{ gameId: number; open: boolean }>()
defineEmits<{ close: [] }>()

interface Round {
  day: number
  sheriffResult: SheriffResultPayload | null
  deaths: NightDeathPayload[]
  voteResult: VoteResultPayload | null
  hunterShots: HunterShotPayload[]
  idiotReveals: IdiotRevealPayload[]
  selfDestructs: SelfDestructPayload[]
}

const loading = ref(false)
const rounds = ref<Round[]>([])

function buildRounds(entries: ActionLogEntry[]): Round[] {
  const map = new Map<number, Round>()

  const getOrCreate = (day: number): Round => {
    if (!map.has(day)) {
      map.set(day, {
        day,
        sheriffResult: null,
        deaths: [],
        voteResult: null,
        hunterShots: [],
        idiotReveals: [],
        selfDestructs: [],
      })
    }
    return map.get(day)!
  }

  for (const entry of entries) {
    const payload = JSON.parse(entry.message)
    const day: number = payload.dayNumber

    switch (entry.eventType) {
      case 'NIGHT_DEATH':
        getOrCreate(day).deaths.push(payload as NightDeathPayload)
        break
      case 'VOTE_RESULT':
        getOrCreate(day).voteResult = payload as VoteResultPayload
        break
      case 'HUNTER_SHOT':
        getOrCreate(day).hunterShots.push(payload as HunterShotPayload)
        break
      case 'IDIOT_REVEAL':
        getOrCreate(day).idiotReveals.push(payload as IdiotRevealPayload)
        break
      case 'SHERIFF_RESULT':
        getOrCreate(day).sheriffResult = payload as SheriffResultPayload
        break
      case 'SELF_DESTRUCT':
        getOrCreate(day).selfDestructs.push(payload as SelfDestructPayload)
        break
    }
  }

  return Array.from(map.values()).sort((a, b) => a.day - b.day)
}

watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen) return
    loading.value = true
    try {
      const entries = await gameService.getActionLog(props.gameId)
      rounds.value = buildRounds(entries)
    } finally {
      loading.value = false
    }
  },
)
</script>

<style scoped>
.drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 200;
}

.action-log-drawer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  max-height: 70vh;
  background: var(--paper, #f5f0e8);
  border-radius: 16px 16px 0 0;
  z-index: 201;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.drawer-handle {
  width: 36px;
  height: 4px;
  background: var(--border, #ccc2b0);
  border-radius: 2px;
  margin: 10px auto 0;
  flex-shrink: 0;
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px 8px;
  border-bottom: 1px solid var(--border-l, #ddd6c6);
  flex-shrink: 0;
}

.drawer-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 16px;
  font-weight: 600;
  color: var(--text, #1a140c);
}

.drawer-close {
  background: none;
  border: none;
  font-size: 16px;
  color: var(--muted, #8a7a65);
  cursor: pointer;
  padding: 4px 8px;
  min-width: 44px;
  min-height: 44px;
}

.drawer-body {
  overflow-y: auto;
  flex: 1;
  padding: 12px 16px 24px;
}

.log-loading,
.log-empty {
  text-align: center;
  color: var(--muted, #8a7a65);
  padding: 24px 0;
  font-size: 14px;
}

.rounds {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.round-block {
  border: 1px solid var(--border-l, #ddd6c6);
  border-radius: 8px;
  overflow: hidden;
}

.round-label {
  background: var(--bg, #ede8df);
  padding: 6px 12px;
  font-size: 13px;
  font-weight: 600;
  color: var(--muted, #8a7a65);
  font-family: 'Noto Serif SC', serif;
}

.log-section {
  padding: 8px 12px;
  border-top: 1px solid var(--border-l, #ddd6c6);
}

.section-title {
  font-size: 12px;
  color: var(--muted, #8a7a65);
  margin-bottom: 6px;
  font-weight: 500;
}

.log-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: var(--text, #1a140c);
  padding: 2px 0;
}

.seat-badge {
  background: var(--bg, #ede8df);
  border: 1px solid var(--border, #ccc2b0);
  border-radius: 4px;
  padding: 1px 5px;
  font-size: 12px;
  color: var(--muted, #8a7a65);
  white-space: nowrap;
}

.log-name {
  font-weight: 500;
}

.log-tag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
}

.tag-red {
  background: #fdecea;
  color: var(--red, #b5251a);
}

.tag-gold {
  background: #fdf3dc;
  color: var(--gold, #a07830);
}

.muted {
  color: var(--muted, #8a7a65);
  font-size: 13px;
}

.tally {
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.tally-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text, #1a140c);
}

.tally-votes {
  color: var(--red, #b5251a);
  font-weight: 600;
  white-space: nowrap;
}

.tally-abstain {
  margin-top: 4px;
  padding-top: 4px;
  border-top: 1px dashed var(--border-l, #ddd6c6);
}

.tally-abstain .tally-votes {
  color: var(--muted, #8a7a65);
}

.tally-voters {
  color: var(--muted, #8a7a65);
  font-size: 12px;
}

/* Transitions */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.slide-up-enter-active,
.slide-up-leave-active {
  transition: transform 0.25s ease;
}
.slide-up-enter-from,
.slide-up-leave-to {
  transform: translateY(100%);
}
</style>
