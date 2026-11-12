<template>
  <div
    class="task-line"
    data-testid="task-line"
    :data-task="task.code"
    :data-done="task.done === null ? 'pending' : String(task.done)"
  >
    <span class="task-label">{{ $t('task.title') }}</span>
    <span class="task-name" data-testid="task-name">{{ $t(`task.name.${task.code}`) }}</span>
    <span class="task-score" data-testid="task-score">{{
      $t('task.score', { n: task.score })
    }}</span>
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
import type { MyTask } from '@/types'

defineProps<{ task: MyTask }>()
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
