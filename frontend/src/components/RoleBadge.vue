<template>
  <div :class="['role-badge', roleClass]">{{ role ?? '?' }}</div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'

const props = defineProps<{
  role?: string | null
}>()

const roleClass = computed(() => {
  switch (props.role?.toLowerCase()) {
    case 'werewolf':
      return 'role-werewolf'
    case 'villager':
      return 'role-villager'
    case 'seer':
      return 'role-seer'
    case 'witch':
      return 'role-witch'
    case 'hunter':
      return 'role-hunter'
    case 'guard':
      return 'role-guard'
    case 'idiot':
      return 'role-idiot'
    default:
      return 'role-unknown'
  }
})
</script>

<style scoped>
.role-badge {
  font-size: 0.65rem;
  font-weight: 700;
  padding: 0.125rem 0.375rem;
  border-radius: 0.25rem;
  display: inline-block;
}

.role-werewolf {
  background: var(--red);
  color: #fff;
}

.role-villager {
  background: var(--muted);
  color: #fff;
}

.role-seer {
  background: var(--gold);
  color: #fff;
}

.role-witch {
  background: var(--green);
  color: #fff;
}

.role-hunter {
  background: #6b4c2a;
  color: #fff;
}

.role-guard {
  background: #3a5f8a;
  color: #fff;
}

.role-idiot {
  background: #888;
  color: #fff;
}

.role-unknown {
  background: var(--border);
  color: var(--text);
}
</style>
