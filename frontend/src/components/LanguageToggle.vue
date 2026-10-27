<template>
  <button
    v-if="visible"
    class="lang-toggle"
    :class="{ 'lang-toggle-inline': inline }"
    data-testid="lang-toggle"
    :data-locale="locale"
    :aria-label="$t('lang.switch')"
    :title="$t('lang.switch')"
    @click="toggle"
  >
    <svg
      v-if="locale === 'en'"
      viewBox="0 0 60 30"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
      data-testid="flag-en"
    >
      <clipPath id="uk-s"><path d="M0,0 v30 h60 v-30 z" /></clipPath>
      <clipPath id="uk-t"><path d="M30,15 h30 v15 z v15 h-30 z h-30 v-15 z v-15 h30 z" /></clipPath>
      <g clip-path="url(#uk-s)">
        <path d="M0,0 v30 h60 v-30 z" fill="#012169" />
        <path d="M0,0 L60,30 M60,0 L0,30" stroke="#fff" stroke-width="6" />
        <path
          d="M0,0 L60,30 M60,0 L0,30"
          clip-path="url(#uk-t)"
          stroke="#c8102e"
          stroke-width="4"
        />
        <path d="M30,0 v30 M0,15 h60" stroke="#fff" stroke-width="10" />
        <path d="M30,0 v30 M0,15 h60" stroke="#c8102e" stroke-width="6" />
      </g>
    </svg>
    <svg v-else viewBox="0 0 60 40" aria-hidden="true" data-testid="flag-cn">
      <rect width="60" height="40" fill="#de2910" />
      <polygon
        fill="#ffde00"
        points="10.00,4.00 11.35,8.15 15.71,8.15 12.18,10.71 13.53,14.85 10.00,12.29 6.47,14.85 7.82,10.71 4.29,8.15 8.65,8.15"
      />
      <polygon
        fill="#ffde00"
        points="18.29,5.03 19.24,3.93 18.49,2.69 19.83,3.26 20.78,2.16 20.66,3.61 21.99,4.18 20.58,4.50 20.45,5.95 19.70,4.70"
      />
      <polygon
        fill="#ffde00"
        points="22.02,8.28 23.32,7.64 23.12,6.20 24.13,7.25 25.44,6.61 24.76,7.89 25.77,8.93 24.34,8.69 23.66,9.97 23.45,8.53"
      />
      <polygon
        fill="#ffde00"
        points="22.08,13.45 23.53,13.40 23.93,12.00 24.43,13.37 25.88,13.31 24.73,14.21 25.23,15.57 24.03,14.76 22.88,15.66 23.28,14.26"
      />
      <polygon
        fill="#ffde00"
        points="18.44,16.75 19.80,17.26 20.71,16.13 20.64,17.58 22.00,18.09 20.60,18.48 20.53,19.93 19.73,18.71 18.33,19.10 19.24,17.96"
      />
    </svg>
  </button>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useGameStore } from '@/stores/gameStore'
import { i18n, setLocale, type Locale } from '@/i18n'

/** `inline` renders inside a day header instead of floating over the page. */
const props = defineProps<{ inline?: boolean }>()

const gameStore = useGameStore()
const route = useRoute()
// No toggle during the night phase: night UI is for eyes-closed play and must
// not offer anything that could pull a player's attention to the screen.
const isNight = computed(() => gameStore.state?.phase === 'NIGHT')
// The day screens carry their own inline toggle in the header; a floating one
// there would sit on the countdown and the action buttons.
const hasInlineToggle = computed(
  () =>
    route?.name === 'game' &&
    (gameStore.state?.phase === 'DAY_DISCUSSION' || gameStore.state?.phase === 'DAY_VOTING'),
)
const visible = computed(() => !isNight.value && (props.inline || !hasInlineToggle.value))
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
/* Enlarges the tap area without enlarging the flag. */
.lang-toggle::before {
  content: '';
  position: absolute;
  inset: -10px;
}
.lang-toggle-inline {
  position: relative;
  top: auto;
  right: auto;
  z-index: auto;
  flex-shrink: 0;
}
.lang-toggle svg {
  width: 100%;
  height: 100%;
  display: block;
}
</style>
