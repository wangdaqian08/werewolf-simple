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

const props = withDefaults(
  defineProps<{
    nickname: string
    avatarUrl?: string | null
    emoji?: string
    size?: 'sm' | 'md' | 'lg'
  }>(),
  { size: 'md' },
)

const imgFailed = ref(false)

// Reset error state when the URL changes (so a new avatar can replace a
// previously-broken one without needing the component to remount).
watch(
  () => props.avatarUrl,
  () => {
    imgFailed.value = false
  },
)

// Allow only `https://` URLs into <img src>. Blocks `data:`,
// `javascript:`, `http:`, etc.
const safeUrl = computed(() => {
  const url = props.avatarUrl
  return url && url.startsWith('https://') ? url : null
})

const fallbackChar = computed(() => {
  if (props.emoji) return props.emoji
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
