<template>
  <section v-if="board" class="board" data-testid="leaderboard">
    <h2 class="board-title">{{ $t('lobby.board.title') }}</h2>
    <ol class="board-rows">
      <li
        v-for="r in rows"
        :key="r.rank"
        class="board-row"
        :class="{ mine: r.me }"
        data-testid="leaderboard-row"
        :data-rank="r.rank"
        :data-me="r.me"
        :data-score="r.score"
      >
        <span class="rank">{{ r.rank }}</span>
        <span class="name">{{ r.nickname }}</span>
        <span class="score">{{ r.score }}</span>
      </li>
    </ol>
    <p v-if="!board.me" class="unranked" data-testid="leaderboard-unranked">
      {{ $t('lobby.board.unranked') }}
    </p>
  </section>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref } from 'vue'
import { userService } from '@/services/userService'
import type { Leaderboard } from '@/types'

const board = ref<Leaderboard | null>(null)

// The top three, then the viewer's own row when they are ranked below them.
const rows = computed(() => {
  const b = board.value
  if (!b) return []
  return b.me && !b.top.some((r) => r.me) ? [...b.top, b.me] : b.top
})

onMounted(async () => {
  try {
    board.value = await userService.getLeaderboard()
  } catch {
    // The lobby works without it; show nothing.
  }
})
</script>

<style scoped>
.board {
  margin-top: 1.25rem;
  padding-top: 1rem;
  border-top: 1px solid var(--border-l);
}
.board-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 0.9375rem;
  color: var(--text);
  margin: 0 0 0.5rem;
}
.board-rows {
  list-style: none;
  margin: 0;
  padding: 0;
}
.board-row {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  padding: 0.375rem 0.25rem;
  font-size: 0.875rem;
  color: var(--text);
  border-bottom: 1px solid var(--border-l);
}
.rank {
  width: 1.75rem;
  flex-shrink: 0;
  font-family: 'Noto Serif SC', serif;
  color: var(--gold);
  font-variant-numeric: tabular-nums;
}
.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.score {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
/* The viewer's own row: one size up, bold vermillion */
.board-row.mine,
.board-row.mine .rank {
  color: var(--red);
  font-weight: 700;
  font-size: 1rem;
}
.unranked {
  margin: 0.5rem 0 0;
  font-size: 0.75rem;
  color: var(--muted);
}
</style>
