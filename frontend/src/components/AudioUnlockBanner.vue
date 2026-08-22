<template>
  <!--
    Rendered as a real <button> for the 88px tap target and a11y, but it needs
    no click handler: audioService's document-level capture listener runs on
    click/touchstart/pointerdown before any component handler, primes the pool,
    resumes the parked queue and fires the deferred BGM start. The banner then
    unmounts itself when the subscription reports unblocked. Its whole job is
    to tell the player that a tap is what's missing.
  -->
  <button v-if="blocked" type="button" class="aub-root" data-testid="audio-unlock-banner">
    <span class="aub-icon" aria-hidden="true">🔇</span>
    <span class="aub-text">点击任意处开启声音 / Tap anywhere to enable sound</span>
  </button>
</template>

<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { audioService } from '@/services/audioService'

/*
 * "The night was silent and nobody knew why."
 *
 * Browsers refuse un-gestured playback, so on a document with no interaction
 * yet — most commonly a mid-game reload, which iOS Safari does on its own to
 * backgrounded tabs — narration parks and BGM defers. Neither is visible. Worse,
 * a parked backlog is REPLACED by the next sequence rather than stacked, so the
 * cues are not merely delayed, they are discarded: reproduced on a real iPhone
 * (game 94), where a reloaded host lost all 8 cues of night 1 plus BGM and heard
 * only the daybreak pair after finally tapping.
 *
 * A host narrating a night has no reason to tap anything, which is exactly why
 * the silence persisted for a whole night and then "fixed itself" the next day.
 */
const blocked = ref(audioService.isAudioBlocked())

const unsubscribeBlocked = audioService.onAudioBlockedChange((b) => {
  blocked.value = b
})

onUnmounted(() => {
  unsubscribeBlocked()
})
</script>

<style scoped>
/* Style C "Ink & Paper" — project tokens, readable over both the parchment
   day background and the --ink night background. */
.aub-root {
  position: fixed;
  left: 50%;
  transform: translateX(-50%);
  top: calc(env(safe-area-inset-top, 0px) + 8px);
  z-index: 60;

  display: flex;
  align-items: center;
  gap: 8px;

  /* Touch target: >=88px wide, comfortable height per the UI guidelines. */
  min-width: 88px;
  min-height: 44px;
  max-width: calc(100vw - 24px);
  padding: 10px 16px;

  background: var(--red);
  color: #fff;
  border: 1px solid var(--red);
  border-radius: 999px;
  box-shadow: 0 2px 10px rgb(0 0 0 / 25%);

  font-size: 14px;
  line-height: 1.2;
  text-align: left;
  cursor: pointer;

  animation: aub-pulse 2s ease-in-out infinite;
}

.aub-icon {
  font-size: 18px;
  flex: 0 0 auto;
}

.aub-text {
  flex: 1 1 auto;
}

@keyframes aub-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.72;
  }
}

@media (prefers-reduced-motion: reduce) {
  .aub-root {
    animation: none;
  }
}
</style>
