<template>
  <div class="version-badge" data-testid="version-badge">{{ version }}</div>
</template>

<script setup lang="ts">
const version = __APP_VERSION__
</script>

<style scoped>
.version-badge {
  position: fixed;
  left: max(0.5rem, env(safe-area-inset-left));
  bottom: max(0.5rem, env(safe-area-inset-bottom));
  z-index: 100;
  font-family: 'Noto Serif SC', serif;
  font-size: 11px;
  line-height: 1;
  color: var(--muted);
  pointer-events: none;
  user-select: none;
  font-variant-numeric: tabular-nums;
}

:global(.night-mode) .version-badge {
  color: rgba(245, 240, 232, 0.45);
}
</style>
