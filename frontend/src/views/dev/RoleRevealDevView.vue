<template>
  <div class="dev-shell">
    <!-- Role picker -->
    <div class="dev-bar">
      <button
        v-for="r in ROLES"
        :key="r"
        :class="{ active: role === r }"
        class="dev-chip"
        @click="role = r"
      >
        {{ r }}
      </button>
    </div>

    <!-- Live preview -->
    <RoleRevealCard
      :role="role"
      :teammates="role === 'WEREWOLF' ? ['Alice', 'Tom'] : []"
      @confirm="onConfirm"
    />

    <!-- Confirm toast -->
    <div v-if="confirmed" class="dev-toast">✓ confirm emitted</div>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import RoleRevealCard from '@/components/RoleRevealCard.vue'
import type { PlayerRole } from '@/types'

const ROLES: PlayerRole[] = ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT']

const role = ref<PlayerRole>('WEREWOLF')
const confirmed = ref(false)

function onConfirm() {
  confirmed.value = true
  window.setTimeout(() => (confirmed.value = false), 1500)
}
</script>

<style scoped>
.dev-shell {
  position: relative;
}

.dev-bar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  padding: 0.5rem 0.75rem;
  background: rgba(26, 20, 12, 0.9);
  backdrop-filter: blur(4px);
}

.dev-chip {
  font-size: 0.625rem;
  letter-spacing: 0.08em;
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: transparent;
  color: rgba(255, 255, 255, 0.55);
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}

.dev-chip.active {
  background: var(--red);
  border-color: var(--red);
  color: #fff;
}

/* Push card content below the fixed bar */
:deep(.reveal-wrap) {
  padding-top: 6rem;
}

.dev-toast {
  position: fixed;
  bottom: 5rem;
  left: 50%;
  transform: translateX(-50%);
  background: var(--green);
  color: #fff;
  font-size: 0.75rem;
  padding: 0.375rem 0.875rem;
  border-radius: 2rem;
  pointer-events: none;
}
</style>
