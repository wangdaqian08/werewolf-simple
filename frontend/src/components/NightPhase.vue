<template>
  <div class="nw">
    <!-- ── Header ──────────────────────────────────────────────────────── -->
    <header class="nh">
      <div class="nh-moon">🌙</div>
      <div class="nh-title">
        {{ subPhase === 'SEER_RESULT' ? '查验结果' : '夜晚降临' }}
        <span class="nh-en">{{ subPhase === 'SEER_RESULT' ? 'Seer Result' : 'Night Falls' }}</span>
      </div>
      <div class="nh-round">
        第 {{ nightPhase.dayNumber }} 夜 · Round {{ nightPhase.dayNumber }}
      </div>
    </header>

    <!-- ── Role badge (hidden for WAITING) ────────────────────────────── -->
    <div v-if="isMyTurn && meta" class="rb">
      <span class="rb-emoji">{{ meta.emoji }}</span>
      <div class="rb-body">
        <div class="rb-names">
          {{ meta.nameZh }}
          <span :class="`rb-tag rb-tag-${meta.team}`">{{ meta.nameEn }}</span>
        </div>
        <div class="rb-sub">{{ badgeSub }}</div>
      </div>
    </div>

    <!-- ── Dead player banner ─────────────────────────────────────────── -->
    <div v-if="me && !me.isAlive" class="banner-area">
      <div class="banner banner-info">
        <span class="banner-icon"> 👤 </span>
        <div>
          <div class="srh-title">你已经出局</div>
          <div class="banner-sub">You are eliminated</div>
        </div>
      </div>
    </div>

    <!-- ── WEREWOLF_PICK ──────────────────────────────────────────────── -->
    <template v-if="subPhase === 'WEREWOLF_PICK' && myRole === 'WEREWOLF' && me?.isAlive">
      <div v-if="nightPhase.teammates?.length" class="team-row">
        <span class="tr-label">队友：</span>
        <span v-for="(t, i) in nightPhase.teammates" :key="t" class="tr-name">
          {{ t }}<template v-if="i < nightPhase.teammates!.length - 1"> · </template>
        </span>
      </div>
      <div class="pick-hint">选择今晚的袭击目标：</div>
      <section class="player-grid">
        <PlayerSlot
          v-for="p in players"
          :key="p.userId"
          mode="room"
          :seat="p.seatIndex"
          :nickname="p.nickname"
          :avatar="p.avatar ?? '👤'"
          :variant="wolfVariantFn(p)"
          :aria-disabled="!isWolfTargetFn(p) ? 'true' : undefined"
          @click="isWolfTargetFn(p) && selectPlayer(p.userId)"
        >
          <template v-if="p.isSheriff" #badge>
            <div class="sheriff-badge">⭐</div>
          </template>
          <template v-if="!p.isAlive" #overlay>
            <div class="slot-overlay np-dead-x">✕</div>
          </template>
        </PlayerSlot>
      </section>
      <footer class="nf">
        <button
          class="btn btn-danger nf-btn"
          data-testid="wolf-confirm-kill"
          :disabled="!effectivePhase.selectedTargetId || actionPending"
          @click="emit('confirm', localSelected)"
        >
          确认袭击 Confirm
        </button>
      </footer>
    </template>

    <!-- ── SEER_PICK ──────────────────────────────────────────────────── -->
    <template v-else-if="subPhase === 'SEER_PICK' && myRole === 'SEER' && me?.isAlive">
      <div class="pick-hint">选择查验目标 · Select a player to check:</div>
      <section class="player-grid">
        <PlayerSlot
          v-for="p in players"
          :key="p.userId"
          mode="room"
          :seat="p.seatIndex"
          :nickname="p.nickname"
          :avatar="p.avatar ?? '👤'"
          :variant="seerVariantFn(p)"
          :aria-disabled="!isSeerTargetFn(p) ? 'true' : undefined"
          @click="isSeerTargetFn(p) && selectPlayer(p.userId)"
        >
          <template v-if="p.isSheriff" #badge>
            <div class="sheriff-badge">⭐</div>
          </template>
          <template v-if="!p.isAlive" #overlay>
            <div class="slot-overlay np-dead-x">✕</div>
          </template>
        </PlayerSlot>
      </section>
      <footer class="nf">
        <button
          class="btn btn-danger nf-btn"
          data-testid="seer-check"
          :disabled="!effectivePhase.selectedTargetId || actionPending"
          @click="emit('confirm', localSelected)"
        >
          查验 · Check
        </button>
      </footer>
    </template>

    <!-- ── SEER_RESULT ────────────────────────────────────────────────── -->
    <template
      v-else-if="
        subPhase === 'SEER_RESULT' && myRole === 'SEER' && nightPhase.seerResult && me?.isAlive
      "
    >
      <div class="sr-wrap">
        <div :class="['sr-card', nightPhase.seerResult.isWerewolf ? 'sr-wolf' : 'sr-village']">
          <div class="sr-player">
            {{ nightPhase.seerResult.checkedSeatIndex }}号 ·
            {{ nightPhase.seerResult.checkedNickname }}
          </div>
          <div class="sr-verdict">
            {{
              nightPhase.seerResult.isWerewolf ? '🐺 是狼人！· Werewolf' : '✅ 平民阵营 · Good Camp'
            }}
          </div>
        </div>
        <div class="sr-hist">
          <div class="srh-title">历史查验记录</div>
          <template v-if="nightPhase.seerResult.history.length">
            <div
              v-for="h in nightPhase.seerResult.history"
              :key="`${h.round}-${h.nickname}`"
              class="srh-row"
            >
              <span class="srh-round">Round {{ h.round }}</span>
              <span class="srh-sep">·</span>
              <span class="srh-name">{{ h.nickname }}</span>
              <span class="srh-arrow">→</span>
              <span :class="h.isWerewolf ? 'srh-wolf' : 'srh-ok'">
                {{ h.isWerewolf ? '狼人 ✗' : '平民 ✓' }}
              </span>
            </div>
          </template>
          <div v-else class="srh-empty">暂无历史记录</div>
        </div>
        <footer class="nf" style="margin-top: auto">
          <button
            class="btn btn-secondary nf-btn"
            data-testid="seer-done"
            :disabled="actionPending"
            @click="emit('confirm')"
          >
            查验完毕 · Done
          </button>
        </footer>
      </div>
    </template>

    <!-- ── WITCH_ACT ───────────────────────────────────────────────────── -->
    <template v-else-if="subPhase === 'WITCH_ACT' && myRole === 'WITCH' && me?.isAlive">
      <!-- Antidote — always visible when witch has it; grayed out after decision -->
      <div
        v-if="nightPhase.hasAntidote"
        :class="['w-section', nightPhase.antidoteDecided && 'ws-decided']"
      >
        <div class="ws-hdr">
          <span class="ws-pill ws-pill-green">解药 · ANTIDOTE ×1</span>
        </div>
        <p class="ws-desc">
          今晚
          <span class="ws-killed">
            {{ nightPhase.attackedSeatIndex }}号·{{ nightPhase.attackedNickname }}
          </span>
          被狼人袭击，是否使用解药？
        </p>
        <div class="ws-row">
          <button
            class="btn btn-primary ws-btn"
            data-testid="witch-antidote"
            :disabled="!!nightPhase.antidoteDecided || actionPending"
            @click="emit('witchAntidote')"
          >
            使用解药
          </button>
          <button
            class="btn btn-secondary ws-btn"
            data-testid="switch-pass-antidote"
            :disabled="!!nightPhase.antidoteDecided || actionPending"
            @click="emit('witchPassAntidote')"
          >
            放弃
          </button>
        </div>
      </div>

      <!-- Poison — always visible when witch has it; grayed out after decision -->
      <div
        v-if="nightPhase.hasPoison"
        :class="['w-section', nightPhase.poisonDecided && 'ws-decided']"
      >
        <div class="ws-hdr">
          <span class="ws-pill ws-pill-red">毒药 · POISON ×1</span>
        </div>
        <p class="ws-desc">是否对某位玩家使用毒药？</p>
        <template v-if="poisonMode && !nightPhase.poisonDecided">
          <section class="player-grid player-grid-sm">
            <PlayerSlot
              v-for="p in players"
              :key="p.userId"
              mode="room"
              :seat="p.seatIndex"
              :nickname="p.nickname"
              :avatar="p.avatar ?? '👤'"
              :variant="poisonVariantFn(p)"
              :aria-disabled="!isPoisonTargetFn(p) ? 'true' : undefined"
              @click="isPoisonTargetFn(p) && selectPlayer(p.userId)"
            >
              <template v-if="p.isSheriff" #badge>
                <div class="sheriff-badge">⭐</div>
              </template>
              <template v-if="!p.isAlive" #overlay>
                <div class="slot-overlay np-dead-x">✕</div>
              </template>
            </PlayerSlot>
          </section>
          <div class="ws-row">
            <button
              class="btn btn-primary ws-btn"
              data-testid="witch-poison-confirm"
              :disabled="!effectivePhase.selectedTargetId || actionPending"
              @click="emit('witchPoison', effectivePhase.selectedTargetId!)"
            >
              确认毒杀 Confirm
            </button>
            <button
              class="btn btn-secondary ws-btn"
              data-testid="poison-mode-cancel"
              @click="poisonMode = false"
            >
              取消
            </button>
          </div>
        </template>
        <div v-else class="ws-row">
          <button
            class="btn btn-danger ws-btn"
            data-testid="use-poison"
            :disabled="!!nightPhase.poisonDecided || actionPending"
            @click="poisonMode = true"
          >
            使用毒药
          </button>
          <button
            class="btn btn-secondary ws-btn"
            data-testid="switch-pass-poison"
            :disabled="!!nightPhase.poisonDecided || actionPending"
            @click="emit('witchPassPoison')"
          >
            不用
          </button>
        </div>
      </div>

      <!-- No items available - show done button -->
      <div v-if="!nightPhase.hasAntidote && !nightPhase.hasPoison" class="w-section">
        <div class="ws-hdr">
          <span class="ws-pill">女巫 · WITCH</span>
        </div>
        <p class="ws-desc">你没有可用的道具。</p>
        <div class="ws-row">
          <button
            class="btn btn-primary ws-btn"
            data-testid="witch-skip"
            :disabled="actionPending"
            @click="emit('witchSkip')"
          >
            完成操作 · Done
          </button>
        </div>
      </div>
    </template>

    <!-- ── GUARD_PICK ──────────────────────────────────────────────────── -->
    <template v-else-if="subPhase === 'GUARD_PICK' && myRole === 'GUARD' && me?.isAlive">
      <div class="pick-hint">
        选择守护目标 · Protect a player:
        <span v-if="nightPhase.previousGuardTargetId" class="guard-note">
          上轮已保护: {{ prevGuardPlayer?.seatIndex }}号·{{ prevGuardPlayer?.nickname }}
          （不可选择）
        </span>
      </div>
      <section class="player-grid">
        <PlayerSlot
          v-for="p in players"
          :key="p.userId"
          mode="room"
          :seat="p.seatIndex"
          :nickname="p.nickname"
          :avatar="p.avatar ?? '👤'"
          :variant="guardVariantFn(p)"
          :data-prev-guard="p.userId === nightPhase.previousGuardTargetId ? 'true' : undefined"
          :aria-disabled="!isGuardTargetFn(p) ? 'true' : undefined"
          @click="isGuardTargetFn(p) && selectPlayer(p.userId)"
        >
          <template v-if="p.isSheriff" #badge>
            <div class="sheriff-badge">⭐</div>
          </template>
          <template v-if="!p.isAlive" #overlay>
            <div class="slot-overlay np-dead-x">✕</div>
          </template>
        </PlayerSlot>
      </section>
      <footer class="nf">
        <!-- Guard confirm is RED per design -->
        <button
          class="btn btn-danger nf-btn"
          data-testid="guard-confirm-protect"
          :disabled="!effectivePhase.selectedTargetId || actionPending"
          @click="emit('confirm', localSelected)"
        >
          确认保护 Confirm
        </button>
      </footer>
    </template>

    <!-- ── Sleep screen — non-actor during any active phase ──────────── -->
    <template v-else-if="subPhase !== 'WAITING'">
      <div class="sleep-screen">
        <div class="ss-emoji">🌙</div>
        <div class="ss-title" data-testid="sleep-screen-title">
          {{ me && !me.isAlive ? '夜晚降临' : '请闭眼' }}
        </div>
        <div class="ss-en">
          {{ me && !me.isAlive ? 'Night is coming...' : 'Night is in progress...' }}
        </div>
        <div class="ss-sub">
          {{
            me && !me.isAlive
              ? '所有人请闭眼 / Everyone please close your eyes'
              : '等待其他玩家行动 / Waiting for others'
          }}
        </div>
      </div>
    </template>

    <!-- ── WAITING ────────────────────────────────────────────────────── -->
    <template v-else-if="subPhase === 'WAITING'">
      <div class="sleep-screen">
        <div class="ss-emoji">🌙</div>
        <div class="ss-title" data-testid="sleep-screen-title">夜晚即将开始</div>
        <div class="ss-en">Night is beginning...</div>
        <div class="ss-sub">所有人请闭眼 / Everyone please close your eyes</div>
      </div>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { computed, onUnmounted, ref, watch } from 'vue'

import type { GamePlayer, NightPhaseState, PlayerRole } from '@/types'
import PlayerSlot from '@/components/PlayerSlot.vue'
import {
  guardVariant,
  isGuardTarget,
  isPoisonTarget,
  isSeerTarget,
  isWolfTarget,
  poisonVariant,
  seerVariant,
  wolfVariant,
} from '@/utils/nightPhaseHelpers'

const props = defineProps<{
  nightPhase: NightPhaseState
  players: GamePlayer[]
  myUserId: string
  myRole?: PlayerRole
  actionPending?: boolean
}>()

const emit = defineEmits<{
  selectPlayer: [userId: string]
  confirm: [targetId?: string]
  witchAntidote: []
  witchPassAntidote: []
  witchPoison: [targetId: string]
  witchPassPoison: []
  witchSkip: []
}>()

const subPhase = computed(() => props.nightPhase.subPhase)
const poisonMode = ref(false)

// Current player (me)
const me = computed(() => props.players.find((p) => p.userId === props.myUserId))

// True only when the current sub-phase is this player's active turn
const isMyTurn = computed(() => {
  const sp = subPhase.value
  const role = props.myRole
  // Dead players cannot take turns
  if (!me.value?.isAlive) return false
  if (!role) return false
  if (sp === 'WEREWOLF_PICK') return role === 'WEREWOLF'
  if (sp === 'SEER_PICK' || sp === 'SEER_RESULT') return role === 'SEER'
  if (sp === 'WITCH_ACT') return role === 'WITCH'
  if (sp === 'GUARD_PICK') return role === 'GUARD'
  return false
})

// Selection is local UI state — server is notified but not authoritative for display
const localSelected = ref<string | undefined>(props.nightPhase.selectedTargetId)

// Reset when a new action phase begins
watch(subPhase, () => {
  localSelected.value = undefined
})

const effectivePhase = computed(() => ({
  ...props.nightPhase,
  selectedTargetId: localSelected.value,
}))

function selectPlayer(userId: string) {
  localSelected.value = userId
  emit('selectPlayer', userId)
}

// ── Seer auto-advance timer ───────────────────────────────────────────────────
const seerCountdown = ref(30)
let seerTimerId: ReturnType<typeof setInterval> | null = null

watch(
  () => props.nightPhase.subPhase,
  (phase) => {
    if (seerTimerId) {
      clearInterval(seerTimerId)
      seerTimerId = null
    }
    if (phase === 'SEER_RESULT') {
      seerCountdown.value = 30
      seerTimerId = setInterval(() => {
        seerCountdown.value--
        if (seerCountdown.value <= 0) {
          clearInterval(seerTimerId!)
          seerTimerId = null
          emit('confirm') // auto-advance to WAITING
        }
      }, 1000)
    } else {
      seerCountdown.value = 30
    }
  },
  { immediate: true },
)

onUnmounted(() => {
  if (seerTimerId) clearInterval(seerTimerId)
})

// ── Role metadata ─────────────────────────────────────────────────────────────

interface RoleMeta {
  nameZh: string
  nameEn: string
  emoji: string
  team: 'wolf' | 'village' | 'special'
}

const ROLE_META: Record<PlayerRole, RoleMeta> = {
  WEREWOLF: { nameZh: '狼人', nameEn: 'WEREWOLF', emoji: '🐺', team: 'wolf' },
  VILLAGER: { nameZh: '村民', nameEn: 'VILLAGER', emoji: '🌾', team: 'village' },
  SEER: { nameZh: '预言家', nameEn: 'SEER', emoji: '🔭', team: 'special' },
  WITCH: { nameZh: '女巫', nameEn: 'WITCH', emoji: '🔮', team: 'special' },
  HUNTER: { nameZh: '猎人', nameEn: 'HUNTER', emoji: '🏹', team: 'special' },
  GUARD: { nameZh: '守卫', nameEn: 'GUARD', emoji: '🛡️', team: 'special' },
  IDIOT: { nameZh: '白痴', nameEn: 'IDIOT', emoji: '🃏', team: 'special' },
}

const meta = computed(() => (props.myRole ? ROLE_META[props.myRole] : null))

const badgeSub = computed(() => {
  const role = props.myRole
  switch (subPhase.value) {
    case 'WEREWOLF_PICK':
      return role === 'WEREWOLF' ? '与队友商议攻击目标' : '请闭眼 / Eyes closed'
    case 'SEER_PICK':
      return role === 'SEER' ? '查验一名玩家的身份' : '请闭眼 / Eyes closed'
    case 'SEER_RESULT':
      return role === 'SEER' ? '查验完毕' : '请闭眼 / Eyes closed'
    case 'WITCH_ACT':
      return role === 'WITCH' ? '你的行动时间 / Your turn' : '请闭眼 / Eyes closed'
    case 'GUARD_PICK':
      return role === 'GUARD' ? '选择守护的玩家' : '请闭眼 / Eyes closed'
    default:
      return ''
  }
})

// ── Previous guard target ────────────────────────────────────────────────────

const prevGuardPlayer = computed(() =>
  props.players.find((p) => p.userId === props.nightPhase.previousGuardTargetId),
)

// ── Slot variant / target wrappers (bind props) ──────────────────────────────

const wolfVariantFn = (p: GamePlayer) => wolfVariant(p, effectivePhase.value, props.myUserId)
const isWolfTargetFn = (p: GamePlayer) => isWolfTarget(p, props.myUserId)
const seerVariantFn = (p: GamePlayer) => seerVariant(p, effectivePhase.value, props.myUserId)
const isSeerTargetFn = (p: GamePlayer) => isSeerTarget(p, props.myUserId)
const guardVariantFn = (p: GamePlayer) => guardVariant(p, effectivePhase.value)
const isGuardTargetFn = (p: GamePlayer) => isGuardTarget(p, props.nightPhase)
const poisonVariantFn = (p: GamePlayer) => poisonVariant(p, effectivePhase.value, props.myUserId)
const isPoisonTargetFn = (p: GamePlayer) => isPoisonTarget(p, props.myUserId)
</script>

<style scoped>
/* ── Wrapper ─────────────────────────────────────────────────────────────── */
.nw {
  display: flex;
  flex-direction: column;
  min-height: 100dvh;
  background: var(--ink);
  color: var(--paper);
}

/* ── Header ──────────────────────────────────────────────────────────────── */
.nh {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 3rem 1.25rem 0.625rem;
  gap: 0.2rem;
}

.nh-moon {
  font-size: 1.75rem;
  line-height: 1;
  margin-bottom: 0.2rem;
}

.nh-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.375rem;
  font-weight: 700;
  color: var(--paper);
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
}

.nh-en {
  font-size: 0.6875rem;
  font-family: 'Noto Sans SC', sans-serif;
  font-weight: 400;
  color: rgba(245, 240, 232, 0.5);
  letter-spacing: 0.05em;
}

.nh-round {
  font-size: 0.75rem;
  color: rgba(245, 240, 232, 0.4);
  letter-spacing: 0.04em;
}

/* ── Role badge ──────────────────────────────────────────────────────────── */
.rb {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin: 0.375rem 0.875rem 0;
  background: rgba(245, 240, 232, 0.06);
  border: 1px solid rgba(245, 240, 232, 0.1);
  border-radius: 0.625rem;
  padding: 0.625rem 0.875rem;
}

.rb-emoji {
  font-size: 1.625rem;
  flex-shrink: 0;
}

.rb-body {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.rb-names {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--paper);
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.rb-tag {
  font-size: 0.5625rem;
  letter-spacing: 0.15em;
  font-weight: 700;
  padding: 0.15rem 0.4rem;
  border-radius: 0.25rem;
}

/* Wolf: bright red */
.rb-tag-wolf {
  background: rgba(181, 37, 26, 0.4);
  color: #ff8a85;
  border: 1px solid rgba(181, 37, 26, 0.7);
}

/* Village: green */
.rb-tag-village {
  background: rgba(45, 106, 63, 0.35);
  color: #7dda96;
  border: 1px solid rgba(45, 106, 63, 0.6);
}

/* Special (Seer/Witch/Guard/Hunter): gold */
.rb-tag-special {
  background: rgba(160, 120, 48, 0.4);
  color: #e8c070;
  border: 1px solid rgba(160, 120, 48, 0.7);
}

.rb-sub {
  font-size: 0.6875rem;
  color: rgba(245, 240, 232, 0.48);
}

/* ── Team row (werewolf teammates) ───────────────────────────────────────── */
.team-row {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.375rem 0.875rem 0;
  font-size: 0.75rem;
}

.tr-label {
  color: rgba(245, 240, 232, 0.45);
  flex-shrink: 0;
}

.tr-name {
  color: #7eb8f7; /* teammate blue */
  font-weight: 600;
}

/* ── Pick hint ───────────────────────────────────────────────────────────── */
.pick-hint {
  font-size: 0.75rem;
  color: rgba(245, 240, 232, 0.48);
  padding: 0.375rem 0.875rem 0.25rem;
  line-height: 1.5;
}

/* ── Night player grid ───────────────────────────────────────────────────── */

.player-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  column-gap: 0.5rem;
  row-gap: 0.375rem;
  align-content: start;
  padding: 0.375rem 0.875rem;
  flex: 1;
}

.player-grid-sm {
  flex: unset;
  margin-bottom: 0.5rem;
}

/* ── PlayerSlot night-mode overrides (via :deep) ─────────────────────────── */

/* Base room cell on dark background */
:deep(.slot-room) {
  color: var(--paper);
}

:deep(.slot-alive) {
  background: rgba(245, 240, 232, 0.06);
  border: 1px solid rgba(245, 240, 232, 0.12);
  cursor: pointer;
}

:deep(.slot-alive:hover) {
  border-color: rgba(181, 37, 26, 0.55);
  background: rgba(181, 37, 26, 0.12);
}

:deep(.slot-selected) {
  background: rgba(181, 37, 26, 0.18);
  border: 2px solid var(--red);
  box-shadow: 0 0 0 2px rgba(181, 37, 26, 0.18);
}

/* Teammate: blue highlight, still clickable */
:deep(.slot-teammate) {
  background: rgba(59, 130, 246, 0.18);
  border: 1px solid rgba(96, 165, 250, 0.6);
  cursor: pointer;
}

:deep(.slot-teammate:hover) {
  border-color: rgba(181, 37, 26, 0.55);
  background: rgba(181, 37, 26, 0.12);
}

:deep(.slot-dead) {
  background: rgba(245, 240, 232, 0.025);
  border: 1px solid rgba(245, 240, 232, 0.07);
  opacity: 0.28;
  cursor: default;
}

/* Self / non-targetable alive cell */
:deep(.slot-waiting) {
  background: rgba(245, 240, 232, 0.025);
  border: 1px solid rgba(245, 240, 232, 0.07);
  opacity: 0.5;
  cursor: default;
}

/* Prevent interaction on aria-disabled elements */
:deep([aria-disabled='true']) {
  pointer-events: none;
}

/* Avatar circle on dark background */
:deep(.av) {
  background: rgba(245, 240, 232, 0.08);
}

/* Seat index + name on dark background */
:deep(.slot-index) {
  color: rgba(245, 240, 232, 0.4);
  opacity: 1;
}

:deep(.av-name) {
  color: rgba(245, 240, 232, 0.75);
}

/* Dead overlay (slot #overlay) */
.np-dead-x {
  font-size: 1rem;
  color: rgba(245, 240, 232, 0.35);
  background: rgba(42, 31, 20, 0.45);
}

/* ── Footer ──────────────────────────────────────────────────────────────── */
.nf {
  padding: 0.625rem 0.875rem 2.5rem;
  border-top: 1px solid rgba(245, 240, 232, 0.07);
  background: rgba(255, 255, 255, 0.025);
}

/* ── Seer result ─────────────────────────────────────────────────────────── */
.sr-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.sr-card {
  margin: 0.625rem 0.875rem 0;
  border-radius: 0.625rem;
  padding: 1.125rem 1rem;
  text-align: center;
}

.sr-wolf {
  background: rgba(181, 37, 26, 0.22);
  border: 1px solid rgba(181, 37, 26, 0.5);
}

.sr-village {
  background: rgba(45, 106, 63, 0.22);
  border: 1px solid rgba(45, 106, 63, 0.5);
}

.sr-player {
  font-size: 1rem;
  font-weight: 600;
  color: var(--paper);
  margin-bottom: 0.375rem;
}

.sr-verdict {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.125rem;
  font-weight: 700;
  color: var(--paper);
}

.sr-hist {
  margin: 0.625rem 0.875rem 0;
  background: rgba(245, 240, 232, 0.04);
  border: 1px solid rgba(245, 240, 232, 0.08);
  border-radius: 0.5rem;
  padding: 0.5rem 0.75rem;
}

.srh-title {
  font-size: 0.7625rem;
  letter-spacing: 0.1em;
  color: rgba(245, 240, 232, 0.35);
  text-transform: uppercase;
  margin-bottom: 0.375rem;
}

.srh-row {
  display: flex;
  align-items: center;
  gap: 0.3rem;
  font-size: 0.75rem;
  padding: 0.2rem 0;
  border-bottom: 1px solid rgba(245, 240, 232, 0.06);
  color: rgba(245, 240, 232, 0.6);
}

.srh-row:last-child {
  border-bottom: none;
}

.srh-round {
  color: rgba(245, 240, 232, 0.38);
  font-size: 0.6875rem;
  white-space: nowrap;
}

.srh-sep,
.srh-arrow {
  color: rgba(245, 240, 232, 0.2);
}

.srh-name {
  flex: 1;
  color: rgba(245, 240, 232, 0.85);
}

.srh-wolf {
  color: #ff8a85;
  font-weight: 600;
}

.srh-ok {
  color: #7dda96;
  font-weight: 600;
}

.srh-empty {
  font-size: 0.75rem;
  color: rgba(245, 240, 232, 0.3);
  text-align: center;
  padding: 0.25rem 0;
}

/* ── Witch ───────────────────────────────────────────────────────────────── */
.w-section {
  margin: 0.625rem 0.875rem 0;
  background: rgba(245, 240, 232, 0.04);
  border: 1px solid rgba(245, 240, 232, 0.09);
  border-radius: 0.625rem;
  padding: 0.75rem 0.875rem;
  transition: opacity 0.2s;
}

.ws-decided {
  opacity: 0.4;
  pointer-events: none;
}

.ws-hdr {
  margin-bottom: 0.4rem;
}

.ws-pill {
  font-size: 0.5625rem;
  letter-spacing: 0.12em;
  font-weight: 700;
  padding: 0.175rem 0.45rem;
  border-radius: 0.25rem;
}

.ws-pill-green {
  background: rgba(45, 106, 63, 0.35);
  color: #7dda96;
  border: 1px solid rgba(45, 106, 63, 0.5);
}

.ws-pill-red {
  background: rgba(181, 37, 26, 0.35);
  color: #ff8a85;
  border: 1px solid rgba(181, 37, 26, 0.5);
}

.ws-desc {
  font-size: 0.8125rem;
  color: rgba(245, 240, 232, 0.75);
  line-height: 1.5;
  margin: 0 0 0.625rem;
}

/* Attacked player name highlighted in red */
.ws-killed {
  color: #ff8a85;
  font-weight: 600;
}

.ws-row {
  display: flex;
  gap: 0.5rem;
}

.ws-btn {
  flex: 1;
}

/* ── Guard note (inline with pick-hint) ─────────────────────────────────── */
.guard-note {
  display: block;
  font-size: 0.6875rem;
  color: rgba(245, 240, 232, 0.38);
  margin-top: 0.2rem;
}

/* ── Waiting / sleep screen ──────────────────────────────────────────────── */
.sleep-screen {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: 0.625rem;
  padding: 0 1.5rem;
  text-align: center;
  transform: translateY(-4.5rem);
}

.ss-emoji {
  font-size: 4rem;
  line-height: 1;
  margin-bottom: 0.375rem;
}

.ss-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.5rem;
  font-weight: 700;
  color: var(--paper);
}

.ss-en {
  font-size: 0.9375rem;
  color: rgba(245, 240, 232, 0.65);
}

.ss-sub {
  font-size: 0.75rem;
  color: rgba(245, 240, 232, 0.38);
  margin-top: 0.375rem;
}

.ss-countdown {
  font-family: 'Noto Serif SC', serif;
  font-size: 3.5rem;
  font-weight: 700;
  color: var(--paper);
  line-height: 1;
  margin: 0.25rem 0;
  opacity: 0.9;
}

/* ── Button overrides for night mode ─────────────────────────────────────── */
/* Size/shape use global .btn; only color overrides needed here */
.btn:disabled {
  opacity: 0.4;
  cursor: default;
}

.btn-danger {
  background: var(--red);
  color: #fff;
}

.btn-success {
  background: var(--green);
  color: #fff;
}

.btn-secondary {
  background: rgba(245, 240, 232, 0.1);
  color: rgba(245, 240, 232, 0.72);
  border: 1px solid rgba(245, 240, 232, 0.14);
}

/* ── Sheriff badge ─────────────────────────────────────────────────────────── */
.sheriff-badge {
  position: absolute;
  top: 4px;
  right: 6px;
  font-size: 0.75rem;
}
</style>
