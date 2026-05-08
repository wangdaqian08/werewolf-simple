<template>
  <div :class="['avatar', sizeClass]">
    <img
      v-if="safeUrl && !imgFailed"
      :src="safeUrl"
      :alt="nickname"
      class="avatar-img"
      @error="imgFailed = true"
    />
    <span v-else class="avatar-fallback">{{ fallbackChar }}</span>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue'

/**
 * Renders a user avatar. Three input modes (use whichever is convenient):
 *
 *  1. `<Avatar :avatar="x" :nickname="y" />`  — single-prop convenience.
 *     `avatar` may be either an https URL (rendered as <img>) or an emoji
 *     glyph (rendered as text). Most callers should use this — backend's
 *     RoomPlayerDto.avatar / GamePlayer.avatar / SheriffCandidate.avatar
 *     fields are all "https URL or emoji or null", and the discrimination
 *     happens here in one place.
 *
 *  2. `<Avatar :avatar-url="..." :emoji="..." :nickname="..." />` — explicit
 *     two-prop form, used by PlayerSlot and the lobby identity card where
 *     URL vs emoji is known up front.
 *
 *  3. `<Avatar :nickname="..." />` — no image, no emoji; renders the first
 *     code point of the nickname as a fallback.
 *
 * Fallback chain on every render: img → emoji → first nickname char → '?'.
 * The img falls back to emoji on @error so a broken Google CDN URL
 * gracefully degrades.
 */

const props = withDefaults(
  defineProps<{
    nickname: string
    /** Convenience prop: pass the raw backend value (URL OR emoji). */
    avatar?: string | null
    /** Explicit URL prop. Wins over `avatar` if both are provided. */
    avatarUrl?: string | null
    /** Explicit emoji prop. Used as fallback when no URL is set. */
    emoji?: string
    size?: 'sm' | 'md' | 'lg'
  }>(),
  { size: 'md' },
)

const imgFailed = ref(false)

// `avatar` is a string that may be a URL or an emoji. Discriminate by
// the https:// prefix (the same gate Avatar.vue uses on avatarUrl).
const avatarLooksLikeUrl = computed(() => !!props.avatar && props.avatar.startsWith('https://'))

const effectiveUrl = computed<string | null | undefined>(() => {
  // Explicit avatarUrl wins; otherwise derive from `avatar` if it's a URL.
  if (props.avatarUrl !== undefined && props.avatarUrl !== null) return props.avatarUrl
  return avatarLooksLikeUrl.value ? props.avatar : null
})

const effectiveEmoji = computed<string | undefined>(() => {
  if (props.emoji) return props.emoji
  // `avatar` is a non-URL string → treat as emoji glyph.
  if (props.avatar && !avatarLooksLikeUrl.value) return props.avatar
  return undefined
})

// Reset error state when the URL changes (so a new avatar can replace a
// previously-broken one without needing the component to remount).
watch(effectiveUrl, () => {
  imgFailed.value = false
})

// Allow only `https://` URLs into <img src>. Blocks `data:`,
// `javascript:`, `http:`, etc.
const safeUrl = computed(() => {
  const url = effectiveUrl.value
  return url && url.startsWith('https://') ? url : null
})

const fallbackChar = computed(() => {
  if (effectiveEmoji.value) return effectiveEmoji.value
  if (props.nickname && props.nickname.length > 0) {
    // First code point — handles CJK + emoji nicknames safely.
    return Array.from(props.nickname)[0] ?? '?'
  }
  return '?'
})

const sizeClass = computed(() => `avatar-${props.size}`)
</script>

<style scoped>
.avatar {
  border-radius: 50%;
  background: var(--bg);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
  font-family: 'Noto Sans SC', sans-serif;
  color: var(--text);
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-fallback {
  line-height: 1;
}

.avatar-sm {
  width: 24px;
  height: 24px;
  font-size: 11px;
}

.avatar-md {
  width: 28px;
  height: 28px;
  font-size: 13px;
}

.avatar-lg {
  width: 44px;
  height: 44px;
  font-size: 18px;
}
</style>
