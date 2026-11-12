<template>
  <div class="mt-wrap">
    <template v-if="gravekeeperResult">
      <div
        class="mt-card"
        data-testid="gravekeeper-result"
        :data-verdict="verdict"
        :data-exiled-seat="gravekeeperResult.exiledSeatIndex"
      >
        <div
          v-if="gravekeeperResult.exiledSeatIndex != null"
          class="mt-who"
          data-testid="gravekeeper-exiled"
        >
          {{
            $t('common.seatDot', {
              n: gravekeeperResult.exiledSeatIndex,
              name: gravekeeperResult.exiledNickname,
            })
          }}
        </div>
        <div class="mt-verdict" data-testid="gravekeeper-verdict">
          {{ verdict && $t(VERDICT_KEY[verdict]) }}
        </div>
      </div>
      <div class="mt-hist" data-testid="gravekeeper-history">
        <div class="mt-hist-title">{{ $t('night.gravekeeper.history') }}</div>
        <div
          v-for="h in gravekeeperResult.history"
          :key="`${h.round}-${h.nickname}`"
          class="mt-row"
          data-testid="gravekeeper-history-entry"
        >
          <span class="mt-dim">{{ $t('night.round', { n: h.round }) }}</span>
          <span class="mt-name">{{ h.nickname }}</span>
          <span>→ {{ $t(VERDICT_KEY[h.isWerewolf ? 'WOLF' : 'GOOD']) }}</span>
        </div>
        <div v-if="!gravekeeperResult.history.length" class="mt-dim">
          {{ $t('night.gravekeeper.noHistory') }}
        </div>
      </div>
    </template>
    <!-- Always rendered: without it a turn whose result is missing could never be ended. -->
    <footer class="mt-foot mt-foot-end">
      <button
        type="button"
        class="btn btn-secondary mt-btn"
        data-testid="gravekeeper-result-confirm"
        :class="{ 'is-loading': actionPending }"
        :disabled="actionPending"
        @click="emit('action', 'GRAVEKEEPER_CONFIRM')"
      >
        {{ $t('night.gravekeeper.done') }}
      </button>
    </footer>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import type { NightPhaseState } from '@/types'

const props = defineProps<{
  gravekeeperResult?: NightPhaseState['gravekeeperResult']
  dayNumber: number
  actionPending?: boolean
}>()
const emit = defineEmits<{
  action: [type: 'GRAVEKEEPER_CONFIRM']
}>()

const VERDICT_KEY = {
  GOOD: 'night.gravekeeper.good',
  WOLF: 'night.gravekeeper.wolf',
  NO_EXILE: 'night.gravekeeper.noExile',
  FIRST_NIGHT: 'night.gravekeeper.firstNight',
} as const

// The server stores night 1 and "nobody was voted out" both as NONE; only the wording differs.
const verdict = computed(() => {
  const result = props.gravekeeperResult?.result
  if (result === 'NONE') return props.dayNumber === 1 ? 'FIRST_NIGHT' : 'NO_EXILE'
  return result
})
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
