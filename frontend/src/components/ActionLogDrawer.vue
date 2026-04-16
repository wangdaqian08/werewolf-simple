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
          <span class="drawer-title">游戏记录</span>
          <button class="drawer-close" @click="$emit('close')">✕</button>
        </div>

        <div class="drawer-body">
          <div v-if="loading" class="log-loading">加载中…</div>

          <div v-else-if="rounds.length === 0" class="log-empty">暂无记录</div>

          <div v-else class="rounds">
            <div v-for="round in rounds" :key="round.day" class="round-block">
              <div class="round-label">第 {{ round.day }} 天</div>

              <!-- Deaths at start of day -->
              <div v-if="round.deaths.length > 0" class="log-section">
                <div class="section-title">☽ 昨夜出局</div>
                <div v-for="d in round.deaths" :key="d.userId" class="log-row">
                  <span class="seat-badge">{{ d.seatIndex }}号</span>
                  <span class="log-name">{{ d.nickname }}</span>
                </div>
                <div v-if="round.deaths.length === 0" class="log-row muted">平安夜</div>
              </div>
              <div v-else class="log-section">
                <div class="section-title">☽ 昨夜出局</div>
                <div class="log-row muted">平安夜</div>
              </div>

              <!-- Idiot reveals -->
              <div v-if="round.idiotReveals.length > 0" class="log-section">
                <div class="section-title">🃏 白痴身份揭示</div>
                <div v-for="r in round.idiotReveals" :key="r.userId" class="log-row">
                  <span class="seat-badge">{{ r.seatIndex }}号</span>
                  <span class="log-name">{{ r.nickname }}</span>
                  <span class="log-tag tag-gold">白痴幸存</span>
                </div>
              </div>

              <!-- Vote result -->
              <div v-if="round.voteResult" class="log-section">
                <div class="section-title">☀ 投票结果</div>
                <div v-if="round.voteResult.eliminatedNickname" class="log-row">
                  <span class="seat-badge">{{ round.voteResult.eliminatedSeatIndex }}号</span>
                  <span class="log-name">{{ round.voteResult.eliminatedNickname }}</span>
                  <span class="log-tag tag-red">出局</span>
                </div>
                <div v-else class="log-row muted">无人出局</div>
                <!-- Vote breakdown -->
                <div v-if="round.voteResult.tally.length > 0" class="tally">
                  <div
                    v-for="entry in round.voteResult.tally"
                    :key="entry.userId"
                    class="tally-row"
                  >
                    <span class="tally-name">{{ entry.seatIndex }}号 {{ entry.nickname }}</span>
                    <span class="tally-votes">{{ entry.votes }} 票</span>
                    <span class="tally-voters">
                      （{{ entry.voters.map((v) => v.seatIndex + '号').join('、') }}）
                    </span>
                  </div>
                </div>
              </div>

              <!-- Hunter shot -->
              <div v-if="round.hunterShot" class="log-section">
                <div class="section-title">🔫 猎人开枪</div>
                <div class="log-row">
                  <span class="seat-badge">{{ round.hunterShot.hunterSeatIndex }}号</span>
                  <span class="log-name">{{ round.hunterShot.hunterNickname }}</span>
                  <span class="muted">→ 击中</span>
                  <span class="seat-badge">{{ round.hunterShot.targetSeatIndex }}号</span>
                  <span class="log-name">{{ round.hunterShot.targetNickname }}</span>
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
  VoteResultPayload,
} from '@/types'

const props = defineProps<{ gameId: number; open: boolean }>()
defineEmits<{ close: [] }>()

interface Round {
  day: number
  deaths: NightDeathPayload[]
  voteResult: VoteResultPayload | null
  hunterShot: HunterShotPayload | null
  idiotReveals: IdiotRevealPayload[]
}

const loading = ref(false)
const rounds = ref<Round[]>([])

function buildRounds(entries: ActionLogEntry[]): Round[] {
  const map = new Map<number, Round>()

  const getOrCreate = (day: number): Round => {
    if (!map.has(day)) {
      map.set(day, { day, deaths: [], voteResult: null, hunterShot: null, idiotReveals: [] })
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
        getOrCreate(day).hunterShot = payload as HunterShotPayload
        break
      case 'IDIOT_REVEAL':
        getOrCreate(day).idiotReveals.push(payload as IdiotRevealPayload)
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
