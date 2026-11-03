<template>
  <div class="mt-wrap">
    <!-- ── MECH_WOLF_PICK ─────────────────────────────────────────────── -->
    <template v-if="subPhase === 'MECH_WOLF_PICK'">
      <template v-if="skill !== 'NONE'">
        <div class="mt-hint">{{ $t(`night.mech.${HINT_KEY[skill]}`) }}</div>
        <section class="mt-grid">
          <button
            v-for="p in targets"
            :key="p.userId"
            type="button"
            :class="['mt-slot', picked === p.userId && 'mt-slot-on']"
            :data-testid="`${prefix}-target-${p.seatIndex}`"
            @click="picked = p.userId"
          >
            {{ $t('common.seatDot', { n: p.seatIndex, name: p.nickname }) }}
          </button>
        </section>
      </template>
      <div v-else class="mt-hint">{{ $t('night.mech.noAction') }}</div>
      <footer class="mt-foot">
        <button
          type="button"
          class="btn btn-secondary mt-btn"
          data-testid="mech-skip"
          @click="emit('action', 'MECH_SKIP')"
        >
          {{ skill === 'NONE' ? $t('night.mech.noAction') : $t('night.mech.skip') }}
        </button>
        <button
          v-if="skill !== 'NONE'"
          type="button"
          class="btn btn-danger mt-btn"
          :data-testid="`${prefix}-confirm`"
          :disabled="!picked"
          @click="emit('action', ACTION[skill], picked)"
        >
          {{ $t(`night.mech.${CONFIRM_KEY[skill]}`) }}
        </button>
      </footer>
    </template>

    <!-- ── MECH_WOLF_RESULT ───────────────────────────────────────────── -->
    <template v-else-if="subPhase === 'MECH_WOLF_RESULT' && result">
      <div
        :class="['mt-card', result.isWerewolf === true && 'mt-card-wolf']"
        data-testid="mech-result"
        :data-kind="result.kind"
        :data-role="result.role ?? ''"
      >
        <div v-if="result.targetSeatIndex !== undefined" class="mt-who">
          {{ $t('common.seatDot', { n: result.targetSeatIndex, name: result.targetNickname }) }}
        </div>
        <div class="mt-verdict">{{ verdict }}</div>
      </div>
      <footer class="mt-foot mt-foot-end">
        <button
          type="button"
          class="btn btn-secondary mt-btn"
          data-testid="mech-result-confirm"
          @click="emit('action', 'MECH_CONFIRM')"
        >
          {{ $t('night.mech.done') }}
        </button>
      </footer>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import { t } from '@/i18n'
import { roleName } from '@/utils/roleDefinitions'
import type { GamePlayer, MechNightSkill, NightPhaseState } from '@/types'

type MechAction =
  | 'MECH_LEARN'
  | 'MECH_CHECK'
  | 'MECH_POISON'
  | 'MECH_PROTECT'
  | 'MECH_SKIP'
  | 'MECH_CONFIRM'

const props = defineProps<{
  subPhase: NightPhaseState['subPhase']
  mechWolf?: NightPhaseState['mechWolf']
  players: GamePlayer[]
  myUserId: string
}>()
const emit = defineEmits<{ action: [type: MechAction, targetUserId?: string] }>()

const ACTION: Record<Exclude<MechNightSkill, 'NONE'>, MechAction> = {
  LEARN: 'MECH_LEARN',
  CHECK_ROLE: 'MECH_CHECK',
  CHECK_ALIGNMENT: 'MECH_CHECK',
  POISON: 'MECH_POISON',
  PROTECT: 'MECH_PROTECT',
}
const HINT_KEY = {
  LEARN: 'learnHint',
  CHECK_ROLE: 'checkRoleHint',
  CHECK_ALIGNMENT: 'checkAlignmentHint',
  POISON: 'poisonHint',
  PROTECT: 'protectHint',
} as const
const CONFIRM_KEY = {
  LEARN: 'confirmLearn',
  CHECK_ROLE: 'confirmCheck',
  CHECK_ALIGNMENT: 'confirmCheck',
  POISON: 'confirmPoison',
  PROTECT: 'confirmProtect',
} as const

const skill = computed<MechNightSkill>(() => props.mechWolf?.skillTonight ?? 'NONE')
const result = computed(() => props.mechWolf?.result ?? null)
const prefix = computed(() => (skill.value === 'LEARN' ? 'mech-learn' : 'mech-skill'))
const picked = ref<string>()

// Only PROTECT may name the mechanical wolf itself.
const targets = computed(() =>
  props.players.filter(
    (p) => p.isAlive && (skill.value === 'PROTECT' || p.userId !== props.myUserId),
  ),
)

const verdict = computed(() => {
  const r = result.value
  if (!r) return ''
  switch (r.kind) {
    case 'LEARN':
      return t('night.mech.learned', { role: roleName(r.role) })
    case 'CHECK':
      if (r.role) return t('night.mech.role', { role: roleName(r.role) })
      return r.isWerewolf ? t('night.isWolf') : t('night.isGood')
    case 'POISON':
      return t('night.mech.poisoned')
    case 'PROTECT':
      return t('night.mech.protectedPlayer')
    default:
      return t('night.mech.noAction')
  }
})
</script>

<style scoped>
.mt-wrap {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.mt-hint {
  font-size: 0.75rem;
  color: rgba(245, 240, 232, 0.48);
  padding: 0.375rem 0.875rem 0.25rem;
  line-height: 1.5;
}
.mt-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(85px, 47%), 1fr));
  gap: 0.5rem;
  align-content: start;
  padding: 0.375rem 0.875rem;
  flex: 1;
}
.mt-slot {
  padding: 0.75rem 0.25rem;
  border-radius: 0.5rem;
  background: rgba(42, 31, 20, 0.45);
  border: 1px solid rgba(245, 240, 232, 0.14);
  color: var(--paper);
  font-size: 0.875rem;
}
.mt-slot-on {
  border-color: var(--red);
  background: rgba(181, 37, 26, 0.22);
}
.mt-card {
  margin: 0.625rem 0.875rem 0;
  border-radius: 0.625rem;
  padding: 1.125rem 1rem;
  text-align: center;
  background: rgba(45, 106, 63, 0.22);
  border: 1px solid rgba(45, 106, 63, 0.5);
}
.mt-card-wolf {
  background: rgba(181, 37, 26, 0.22);
  border-color: rgba(181, 37, 26, 0.5);
}
.mt-who {
  font-size: 1rem;
  font-weight: 600;
  color: var(--paper);
  margin-bottom: 0.375rem;
}
.mt-verdict {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.125rem;
  font-weight: 700;
  color: var(--paper);
}
.mt-foot {
  display: flex;
  gap: 0.5rem;
  padding: 0.625rem 0.875rem var(--fab-clearance);
  border-top: 1px solid rgba(245, 240, 232, 0.07);
  background: rgba(255, 255, 255, 0.025);
}
.mt-foot-end {
  margin-top: auto;
}
.mt-btn {
  flex: 1;
}
.btn:disabled {
  opacity: 0.4;
  cursor: default;
}
.btn-danger {
  background: var(--red);
  color: #fff;
}
.btn-secondary {
  background: rgba(245, 240, 232, 0.1);
  color: rgba(245, 240, 232, 0.72);
  border: 1px solid rgba(245, 240, 232, 0.14);
}
</style>
