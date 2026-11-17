<template>
  <div class="mt-wrap" data-testid="gargoyle-turn">
    <!-- ── GARGOYLE_PICK ──────────────────────────────────────────────── -->
    <template v-if="subPhase === 'GARGOYLE_PICK'">
      <div class="mt-hint">{{ $t('night.gargoyle.hint') }}</div>
      <section class="mt-grid">
        <!-- Self, the dead and earlier checks are greyed out; the server rejects them anyway. -->
        <button
          v-for="p in players"
          :key="p.userId"
          type="button"
          :class="['mt-slot', picked === p.userId && 'mt-slot-on']"
          :data-testid="`gargoyle-target-${p.seatIndex}`"
          :disabled="!p.isAlive || p.userId === myUserId || checked.has(p.userId)"
          @click="picked = p.userId"
        >
          {{ $t('common.seatDot', { n: p.seatIndex, name: p.nickname }) }}
        </button>
      </section>
      <footer class="mt-foot">
        <button
          type="button"
          class="btn btn-secondary mt-btn"
          data-testid="gargoyle-skip"
          :class="{ 'is-loading': actionPending }"
          :disabled="actionPending"
          @click="emit('action', 'GARGOYLE_SKIP')"
        >
          {{ $t('night.gargoyle.skip') }}
        </button>
        <button
          type="button"
          class="btn btn-danger mt-btn"
          data-testid="gargoyle-confirm"
          :class="{ 'is-loading': actionPending }"
          :disabled="!picked || actionPending"
          @click="emit('action', 'GARGOYLE_CHECK', picked)"
        >
          {{ $t('night.gargoyle.confirm') }}
        </button>
      </footer>
    </template>

    <!-- ── GARGOYLE_RESULT ────────────────────────────────────────────── -->
    <template v-else>
      <template v-if="result">
        <div
          class="mt-card"
          data-testid="gargoyle-result"
          :data-kind="result.kind"
          :data-role="result.kind === 'CHECK' ? result.role : ''"
          :data-checked-seat="result.kind === 'CHECK' ? result.targetSeatIndex : ''"
        >
          <template v-if="result.kind === 'CHECK'">
            <div class="mt-who">
              {{ $t('common.seatDot', { n: result.targetSeatIndex, name: result.targetNickname }) }}
            </div>
            <div class="mt-verdict">
              {{ $t('night.gargoyle.role', { role: roleName(result.role) }) }}
            </div>
          </template>
          <div v-else class="mt-verdict">{{ $t('night.gargoyle.skipped') }}</div>
        </div>
        <div class="mt-hist" data-testid="gargoyle-history">
          <div class="mt-hist-title">{{ $t('night.gargoyle.history') }}</div>
          <div
            v-for="h in gargoyle?.history"
            :key="`${h.round}-${h.seatIndex}`"
            class="mt-row"
            data-testid="gargoyle-history-entry"
          >
            <span class="mt-dim">{{ $t('night.round', { n: h.round }) }}</span>
            <span class="mt-name">{{
              $t('common.seatDot', { n: h.seatIndex, name: h.nickname })
            }}</span>
            <span>→ {{ roleName(h.role) }}</span>
          </div>
          <div v-if="!gargoyle?.history.length" class="mt-dim">
            {{ $t('night.gargoyle.noHistory') }}
          </div>
        </div>
      </template>
      <!-- Always rendered: without it a turn whose result is missing could never be ended. -->
      <footer class="mt-foot mt-foot-end">
        <button
          type="button"
          class="btn btn-secondary mt-btn"
          data-testid="gargoyle-result-confirm"
          :class="{ 'is-loading': actionPending }"
          :disabled="actionPending"
          @click="emit('action', 'GARGOYLE_CONFIRM')"
        >
          {{ $t('night.gargoyle.done') }}
        </button>
      </footer>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import { roleName } from '@/utils/roleDefinitions'
import type { GamePlayer, NightPhaseState } from '@/types'

const props = defineProps<{
  subPhase: NightPhaseState['subPhase']
  gargoyle?: NightPhaseState['gargoyle']
  players: GamePlayer[]
  myUserId: string
  actionPending?: boolean
}>()
const emit = defineEmits<{
  action: [type: 'GARGOYLE_CHECK' | 'GARGOYLE_SKIP' | 'GARGOYLE_CONFIRM', targetUserId?: string]
}>()

const picked = ref<string>()
const checked = computed(() => new Set(props.gargoyle?.checkedPlayerIds ?? []))
const result = computed(() => props.gargoyle?.result ?? null)
</script>

<style scoped src="../assets/night-turn.css"></style>
<style scoped>
.mt-slot:disabled {
  opacity: 0.35;
  cursor: default;
}
.mt-card {
  background: rgba(160, 120, 48, 0.22);
  border: 1px solid rgba(160, 120, 48, 0.5);
}
.mt-hist {
  margin: 0.625rem 0.875rem 0;
  background: rgba(245, 240, 232, 0.04);
  border: 1px solid rgba(245, 240, 232, 0.08);
  border-radius: 0.5rem;
  padding: 0.5rem 0.75rem;
  font-size: 0.75rem;
  color: rgba(245, 240, 232, 0.6);
}
.mt-hist-title {
  font-size: 0.7625rem;
  letter-spacing: 0.1em;
  color: rgba(245, 240, 232, 0.35);
  margin-bottom: 0.375rem;
}
.mt-row {
  display: flex;
  gap: 0.3rem;
  padding: 0.2rem 0;
}
.mt-name {
  flex: 1;
  color: rgba(245, 240, 232, 0.85);
}
.mt-dim {
  color: rgba(245, 240, 232, 0.38);
}
</style>
