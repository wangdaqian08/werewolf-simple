<template>
  <div class="mt-wrap">
    <!-- ── MEDIUM_PICK ────────────────────────────────────────────────── -->
    <template v-if="subPhase === 'MEDIUM_PICK'">
      <div class="mt-hint">{{ $t('night.medium.hint') }}</div>
      <section class="mt-grid">
        <button
          v-for="p in targets"
          :key="p.userId"
          type="button"
          :class="['mt-slot', picked === p.userId && 'mt-slot-on']"
          :data-testid="`medium-target-${p.seatIndex}`"
          @click="picked = p.userId"
        >
          {{ $t('common.seatDot', { n: p.seatIndex, name: p.nickname }) }}
        </button>
      </section>
      <footer class="mt-foot">
        <button
          type="button"
          class="btn btn-danger mt-btn"
          data-testid="medium-confirm"
          :disabled="!picked"
          @click="emit('action', 'MEDIUM_CHECK', picked)"
        >
          {{ $t('night.medium.confirm') }}
        </button>
      </footer>
    </template>

    <!-- ── MEDIUM_RESULT ──────────────────────────────────────────────── -->
    <template v-else-if="subPhase === 'MEDIUM_RESULT' && mediumResult">
      <div
        class="mt-card"
        data-testid="medium-result"
        :data-role="mediumResult.role"
        :data-checked-seat="mediumResult.checkedSeatIndex"
      >
        <div class="mt-who">
          {{
            $t('common.seatDot', {
              n: mediumResult.checkedSeatIndex,
              name: mediumResult.checkedNickname,
            })
          }}
        </div>
        <div class="mt-verdict">
          {{ $t('night.medium.role', { role: roleName(mediumResult.role) }) }}
        </div>
      </div>
      <div class="mt-hist">
        <div class="mt-hist-title">{{ $t('night.medium.history') }}</div>
        <div v-for="h in mediumResult.history" :key="`${h.round}-${h.nickname}`" class="mt-row">
          <span class="mt-dim">{{ $t('night.round', { n: h.round }) }}</span>
          <span class="mt-name">{{ h.nickname }}</span>
          <span>→ {{ roleName(h.role) }}</span>
        </div>
        <div v-if="!mediumResult.history.length" class="mt-dim">
          {{ $t('night.medium.noHistory') }}
        </div>
      </div>
      <footer class="mt-foot mt-foot-end">
        <button
          type="button"
          class="btn btn-secondary mt-btn"
          data-testid="medium-result-confirm"
          @click="emit('action', 'MEDIUM_CONFIRM')"
        >
          {{ $t('night.medium.done') }}
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
  mediumResult?: NightPhaseState['mediumResult']
  players: GamePlayer[]
  myUserId: string
}>()
const emit = defineEmits<{
  action: [type: 'MEDIUM_CHECK' | 'MEDIUM_CONFIRM', targetUserId?: string]
}>()

const picked = ref<string>()
const targets = computed(() =>
  props.players.filter((p) => p.isAlive && p.userId !== props.myUserId),
)
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
  background: rgba(160, 120, 48, 0.22);
  border: 1px solid rgba(160, 120, 48, 0.5);
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
