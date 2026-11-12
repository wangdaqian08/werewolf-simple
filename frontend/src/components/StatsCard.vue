<template>
  <section class="stats" data-testid="stats-card">
    <h2 class="st">{{ $t('account.stats.title') }}</h2>
    <div class="score" data-testid="stats-score">
      <span class="score-name">{{ $t('account.stats.score') }}</span>
      <b data-testid="stats-score-value">{{ stats.score }}</b>
      <span class="score-hint">{{ $t('account.stats.scoreHint') }}</span>
    </div>
    <div class="big">
      <div data-testid="stats-games">
        <b>{{ stats.games }}</b
        ><span>{{ $t('account.stats.games') }}</span>
      </div>
      <div data-testid="stats-wins">
        <b class="win">{{ stats.wins }}</b
        ><span>{{ $t('account.stats.win') }}</span>
      </div>
      <div data-testid="stats-losses">
        <b class="lose">{{ stats.losses }}</b
        ><span>{{ $t('account.stats.loss') }}</span>
      </div>
      <div data-testid="stats-winrate">
        <b>{{ stats.winRate }}%</b><span>{{ $t('account.stats.winRate') }}</span>
      </div>
    </div>
    <p v-if="stats.percentile !== null" class="beat" data-testid="stats-percentile">
      {{ $t('account.stats.beaten', { pct: stats.percentile }) }}
    </p>
    <p v-else class="beat" data-testid="stats-unlock">
      {{ $t('account.stats.unlock', { n: stats.gamesToRank }) }}
    </p>
    <div class="camps">
      <div data-testid="stats-wolf">
        🐺 {{ $t('account.stats.wolf') }}
        <div class="rec">
          <span class="win">{{ stats.wolf.wins }}{{ $t('account.stats.win') }}</span> ·
          <span class="lose">{{ stats.wolf.losses }}{{ $t('account.stats.loss') }}</span>
        </div>
      </div>
      <div data-testid="stats-good">
        🌾 {{ $t('account.stats.good') }}
        <div class="rec">
          <span class="win">{{ stats.good.wins }}{{ $t('account.stats.win') }}</span> ·
          <span class="lose">{{ stats.good.losses }}{{ $t('account.stats.loss') }}</span>
        </div>
      </div>
    </div>
    <p class="lbl">{{ $t('account.stats.recent') }}</p>
    <div class="strip" data-testid="stats-recent">
      <div
        v-for="(r, i) in stats.recent"
        :key="i"
        class="c"
        :class="r === 'W' ? 'cw' : 'cl'"
        data-testid="stats-recent-cell"
        :data-result="r"
      >
        {{ r === 'W' ? $t('account.stats.win') : $t('account.stats.loss') }}
      </div>
    </div>
  </section>
</template>

<script lang="ts" setup>
import type { UserStats } from '@/types'

defineProps<{ stats: UserStats }>()
</script>

<style scoped>
.stats {
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.75rem;
  padding: 0.875rem;
  margin-bottom: 1rem;
}
.st {
  font-family: 'Noto Serif SC', serif;
  font-size: 1rem;
  color: var(--text);
  margin: 0 0 0.5rem;
}
.score {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0 0.5rem;
  padding-bottom: 0.5rem;
  margin-bottom: 0.5rem;
  border-bottom: 1px solid var(--border-l);
}
.score-name {
  font-size: 0.8125rem;
  color: var(--muted);
}
.score b {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.75rem;
  color: var(--red);
  font-variant-numeric: tabular-nums;
}
.score-hint {
  flex-basis: 100%;
  font-size: 0.6875rem;
  color: var(--muted);
}
.big {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  text-align: center;
  margin-bottom: 0.5rem;
}
.big b {
  display: block;
  font-family: 'Noto Serif SC', serif;
  font-size: 1.375rem;
}
.big span {
  font-size: 0.75rem;
  color: var(--muted);
}
.win {
  color: var(--green);
}
.lose {
  color: var(--red);
}
.beat {
  background: rgb(160 120 48 / 10%);
  color: var(--gold);
  font-size: 0.75rem;
  border-radius: 0.375rem;
  padding: 0.375rem 0.5rem;
  text-align: center;
  margin: 0 0 0.5rem;
}
.camps {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.375rem;
  font-size: 0.75rem;
  margin-bottom: 0.625rem;
}
.camps > div {
  background: var(--paper);
  border-radius: 0.375rem;
  padding: 0.375rem 0.5rem;
}
.rec {
  white-space: nowrap;
  margin-top: 0.125rem;
}
.lbl {
  font-size: 0.75rem;
  color: var(--muted);
  margin: 0 0 0.3rem;
}
.strip {
  display: grid;
  grid-template-columns: repeat(10, 1fr);
  gap: 3px;
}
.c {
  height: 1.375rem;
  border-radius: 0.25rem;
  font-size: 0.75rem;
  font-weight: 700;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.cw {
  background: var(--green);
}
.cl {
  background: var(--red);
}
</style>
