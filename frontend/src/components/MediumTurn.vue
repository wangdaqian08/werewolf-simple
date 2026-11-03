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
          :class="{ 'is-loading': actionPending }"
          :disabled="!picked || actionPending"
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
          :class="{ 'is-loading': actionPending }"
          :disabled="actionPending"
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
  actionPending?: boolean
}>()
const emit = defineEmits<{
  action: [type: 'MEDIUM_CHECK' | 'MEDIUM_CONFIRM', targetUserId?: string]
}>()

const picked = ref<string>()
const targets = computed(() =>
  props.players.filter((p) => p.isAlive && p.userId !== props.myUserId),
)
</script>

<style scoped src="../assets/night-turn.css"></style>
<style scoped>
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
