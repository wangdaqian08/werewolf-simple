<template>
  <div class="mt-wrap" data-testid="wolf-beauty-turn">
    <!-- ── Night 1: no charm; 確認 ends the turn ─────────────────────────── -->
    <template v-if="!wolfBeauty?.canCharm">
      <div class="mt-card">
        <div class="mt-verdict">{{ $t('night.wolfBeauty.firstNight') }}</div>
      </div>
      <footer class="mt-foot mt-foot-end">
        <button
          type="button"
          class="btn btn-secondary mt-btn"
          data-testid="wolf-beauty-n1-confirm"
          :class="{ 'is-loading': actionPending }"
          :disabled="actionPending"
          @click="emit('action', 'WOLF_BEAUTY_SKIP')"
        >
          {{ $t('night.wolfBeauty.ok') }}
        </button>
      </footer>
    </template>

    <!-- ── Night 2+: charm one player, or skip ──────────────────────────── -->
    <template v-else>
      <div class="mt-hint">{{ $t('night.wolfBeauty.hint') }}</div>
      <section class="mt-grid">
        <!-- Greys out exactly whom the server would reject: self, the dead, its pack, last night's target. -->
        <button
          v-for="p in players"
          :key="p.userId"
          type="button"
          :class="['mt-slot', picked === p.userId && 'mt-slot-on']"
          :data-testid="`wolf-beauty-target-${p.seatIndex}`"
          :disabled="!p.isAlive || p.userId === myUserId || !!reason(p.userId)"
          @click="picked = p.userId"
        >
          {{ $t('common.seatDot', { n: p.seatIndex, name: p.nickname }) }}
          <span v-if="reason(p.userId)" class="mt-reason">{{ reason(p.userId) }}</span>
        </button>
      </section>
      <footer class="mt-foot">
        <button
          type="button"
          class="btn btn-secondary mt-btn"
          data-testid="wolf-beauty-skip"
          :class="{ 'is-loading': actionPending }"
          :disabled="actionPending"
          @click="emit('action', 'WOLF_BEAUTY_SKIP')"
        >
          {{ $t('night.wolfBeauty.skip') }}
        </button>
        <button
          type="button"
          class="btn btn-danger mt-btn"
          data-testid="wolf-beauty-confirm"
          :class="{ 'is-loading': actionPending }"
          :disabled="!picked || actionPending"
          @click="emit('action', 'WOLF_BEAUTY_CHARM', picked)"
        >
          {{ $t('night.wolfBeauty.confirm') }}
        </button>
      </footer>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import { t } from '@/i18n'
import type { GamePlayer, NightPhaseState } from '@/types'

const props = defineProps<{
  wolfBeauty?: NightPhaseState['wolfBeauty']
  players: GamePlayer[]
  myUserId: string
  actionPending?: boolean
}>()
const emit = defineEmits<{
  action: [type: 'WOLF_BEAUTY_CHARM' | 'WOLF_BEAUTY_SKIP', targetUserId?: string]
}>()

const picked = ref<string>()
function reason(userId: string): string {
  if (props.wolfBeauty?.packUserIds.includes(userId)) return t('night.wolfBeauty.reasonPack')
  if (props.wolfBeauty?.lastNightTargetUserId === userId)
    return t('night.wolfBeauty.reasonLastNight')
  return ''
}
</script>

<style scoped src="../assets/night-turn.css"></style>
<style scoped>
.mt-slot:disabled {
  opacity: 0.35;
  cursor: default;
}
.mt-card {
  background: rgba(181, 37, 26, 0.18);
  border: 1px solid rgba(181, 37, 26, 0.45);
}
.mt-reason {
  display: block;
  font-size: 0.75rem;
  color: rgba(245, 240, 232, 0.6);
}
</style>
