<template>
  <button
    v-if="!isNight"
    class="lang-toggle"
    data-testid="lang-toggle"
    :data-locale="locale"
    :aria-label="$t('lang.switch')"
    :title="$t('lang.switch')"
    @click="toggle"
  >
    <svg v-if="locale === 'en'" viewBox="0 0 60 40" aria-hidden="true" data-testid="flag-en">
      <rect width="60" height="40" fill="#012169" />
      <path d="M0 0 60 40M60 0 0 40" stroke="#fff" stroke-width="8" />
      <path d="M0 0 60 40M60 0 0 40" stroke="#c8102e" stroke-width="3" />
      <path d="M30 0v40M0 20h60" stroke="#fff" stroke-width="13" />
      <path d="M30 0v40M0 20h60" stroke="#c8102e" stroke-width="8" />
    </svg>
    <svg v-else viewBox="0 0 60 40" aria-hidden="true" data-testid="flag-cn">
      <rect width="60" height="40" fill="#de2910" />
      <polygon
        fill="#ffde00"
        points="10,4 12.35,10.9 19.6,10.9 13.7,15.1 16,22 10,17.8 4,22 6.3,15.1 0.4,10.9 7.65,10.9"
      />
    </svg>
  </button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useGameStore } from '@/stores/gameStore'
import { i18n, setLocale, type Locale } from '@/i18n'

const gameStore = useGameStore()
// No toggle during the night phase: night UI is for eyes-closed play and must
// not offer anything that could pull a player's attention to the screen.
const isNight = computed(() => gameStore.state?.phase === 'NIGHT')
const locale = computed(() => i18n.global.locale.value as Locale)

function toggle() {
  setLocale(locale.value === 'zh' ? 'en' : 'zh')
}
</script>

<style scoped>
.lang-toggle {
  position: fixed;
  top: max(0.5rem, env(safe-area-inset-top));
  right: max(0.5rem, env(safe-area-inset-right));
  z-index: 100;
  width: 32px;
  height: 22px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 4px;
  overflow: hidden;
  background: var(--card);
  cursor: pointer;
  line-height: 0;
}
.lang-toggle svg {
  width: 100%;
  height: 100%;
  display: block;
}
</style>
