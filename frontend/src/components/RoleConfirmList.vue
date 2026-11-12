<template>
  <ul class="confirm-list" data-testid="role-confirm-list">
    <li
      v-for="p in rows"
      :key="p.userId"
      class="confirm-row"
      :data-testid="`role-confirm-row-${p.seatIndex}`"
      :data-confirmed="p.confirmed"
    >
      <span class="confirm-name"
        >{{ $t('common.seat', { n: p.seatIndex }) }} {{ truncateNickname(p.nickname, 12) }}</span
      >
      <span :class="p.confirmed ? 'confirm-done' : 'confirm-wait'">{{
        $t(p.confirmed ? 'game.roleConfirmed' : 'game.roleNotConfirmed')
      }}</span>
    </li>
  </ul>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import type { GamePlayer } from '@/types'
import { truncateNickname } from '@/utils/truncate'

const props = defineProps<{ players: GamePlayer[]; confirmedUserIds: string[] }>()

const rows = computed(() =>
  [...props.players]
    .sort((a, b) => a.seatIndex - b.seatIndex)
    .map((p) => ({ ...p, confirmed: props.confirmedUserIds.includes(p.userId) })),
)
</script>

<style scoped>
.confirm-list {
  list-style: none;
  margin: 0.5rem 0 0;
  padding: 0;
  width: 100%;
  max-width: 22rem;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.25rem 0.75rem;
  font-size: 0.8125rem;
}
.confirm-row {
  display: flex;
  justify-content: space-between;
  gap: 0.375rem;
  padding: 0.25rem 0.5rem;
  border-bottom: 1px solid var(--border-l);
  min-width: 0;
}
.confirm-name {
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.confirm-done,
.confirm-wait {
  font-size: 0.75rem;
  flex-shrink: 0;
}
.confirm-done {
  color: var(--green);
}
/* Same plain bold red as the room's 「未準備」 seat tag */
.confirm-wait {
  color: var(--red);
  font-weight: 700;
}
</style>
