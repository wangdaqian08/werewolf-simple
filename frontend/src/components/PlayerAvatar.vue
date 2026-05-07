<template>
  <img :src="resolvedSrc" :alt="alt ?? ''" class="player-avatar" @error="onError" />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { DEFAULT_AVATAR } from '@/assets/iconManifest'

const props = defineProps<{
  src?: string | null
  alt?: string
}>()

const errored = ref(false)

watch(
  () => props.src,
  () => {
    errored.value = false
  },
)

function onError() {
  errored.value = true
}

const resolvedSrc = computed(() => {
  if (errored.value) return DEFAULT_AVATAR
  const trimmed = props.src?.trim()
  if (!trimmed) return DEFAULT_AVATAR
  return trimmed
})
</script>

<style scoped>
.player-avatar {
  display: inline-block;
  object-fit: cover;
}
</style>
