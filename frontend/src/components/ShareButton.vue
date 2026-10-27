<template>
  <button
    class="share-btn"
    data-testid="share-room-btn"
    type="button"
    :aria-label="$t('share.aria', { code: roomCode })"
    @click="share"
  >
    {{ copied ? $t('share.copied') : $t('share.share') }}
  </button>
</template>

<script lang="ts" setup>
import { t } from '@/i18n'
import { ref } from 'vue'

const props = defineProps<{ roomCode: string }>()
const copied = ref(false)

// The lobby reads ?room= and fills in the code.
async function share() {
  const url = `${window.location.origin}/?room=${props.roomCode}`
  if (navigator.share) {
    try {
      await navigator.share({
        title: t('lobby.title'),
        text: t('share.text', { code: props.roomCode }),
        url,
      })
      return
    } catch (e) {
      if ((e as DOMException).name === 'AbortError') return // the player closed the sheet
    }
  }
  // No share sheet (WeChat in-app browser, desktop): copy the link instead.
  try {
    await navigator.clipboard.writeText(url)
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } catch {
    /* clipboard blocked: the room code is still on screen */
  }
}
</script>

<style scoped>
.share-btn {
  flex-shrink: 0;
  min-height: 44px;
  padding: 0 1rem;
  border-radius: 0.5rem;
  border: 1px solid var(--gold);
  background: var(--gold);
  color: #fff;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}
</style>
