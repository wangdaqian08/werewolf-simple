<template>
  <div
    class="task-line"
    data-testid="task-line"
    :data-task="task.code"
    :data-done="task.done === null ? 'pending' : String(task.done)"
  >
    <span class="task-label">{{ $t('task.title') }}</span>
    <span class="task-name" data-testid="task-name">{{ $t(`task.name.${task.code}`) }}</span>
    <span class="task-score" data-testid="task-score">{{ scoreText }}</span>
    <span
      v-if="task.done !== null"
      class="task-status"
      :class="task.done ? 'task-done' : 'task-missed'"
      data-testid="task-status"
      >{{ $t(task.done ? 'task.done' : 'task.notDone') }}</span
    >
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { t } from '@/i18n'
import type { MyTask } from '@/types'

const props = defineProps<{ task: MyTask }>()

// Done: what it paid. Otherwise the rate — per night for a per-night task.
const scoreText = computed(() => {
  const { done, earned, perNight, score } = props.task
  if (done && earned != null) return t('task.score', { n: earned })
  return t(perNight ? 'task.scorePerNight' : 'task.score', { n: score })
})
</script>

<style scoped>
.task-line {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.25rem 0.5rem;
  font-size: 0.8125rem;
  line-height: 1.5;
}
.task-label {
  color: var(--muted);
  font-size: 0.75rem;
}
.task-name {
  font-weight: 600;
}
.task-score {
  color: var(--gold);
  font-variant-numeric: tabular-nums;
}
.task-status {
  font-size: 0.75rem;
  font-weight: 700;
}
.task-done {
  color: var(--green);
}
.task-missed {
  color: var(--muted);
}
</style>
