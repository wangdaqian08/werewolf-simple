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
          :class="{ 'is-loading': actionPending }"
          :disabled="actionPending"
          @click="emit('action', 'MECH_SKIP')"
        >
          {{ skill === 'NONE' ? $t('night.mech.noAction') : $t('night.mech.skip') }}
        </button>
        <button
          v-if="skill !== 'NONE'"
          type="button"
          class="btn btn-danger mt-btn"
          :data-testid="`${prefix}-confirm`"
          :class="{ 'is-loading': actionPending }"
          :disabled="!picked || actionPending"
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
        <div v-if="verdict" class="mt-verdict" data-testid="mech-verdict">{{ verdict }}</div>
      </div>
      <footer class="mt-foot mt-foot-end">
        <button
          type="button"
          class="btn btn-secondary mt-btn"
          data-testid="mech-result-confirm"
          :class="{ 'is-loading': actionPending }"
          :disabled="actionPending"
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
  actionPending?: boolean
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
      if (r.isWerewolf === undefined) return '' // no verdict rather than a guessed "good"
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

<style scoped src="../assets/night-turn.css"></style>
<style scoped>
.mt-card {
  background: rgba(45, 106, 63, 0.22);
  border: 1px solid rgba(45, 106, 63, 0.5);
}
.mt-card-wolf {
  background: rgba(181, 37, 26, 0.22);
  border-color: rgba(181, 37, 26, 0.5);
}
</style>
