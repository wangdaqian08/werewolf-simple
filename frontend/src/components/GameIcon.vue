<template>
  <img :src="resolvedSrc" :alt="alt ?? name" class="game-icon" @error="onError" />
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { FALLBACK_ICON, ICON_MANIFEST } from '@/assets/iconManifest'

const props = defineProps<{
  name: string
  alt?: string
}>()

const errored = ref(false)

watch(
  () => props.name,
  () => {
    errored.value = false
  },
)

function onError() {
  errored.value = true
}

const resolvedSrc = computed(() => {
  if (errored.value) return FALLBACK_ICON
  return ICON_MANIFEST[props.name] ?? FALLBACK_ICON
})
</script>

<style scoped>
.game-icon {
  display: inline-block;
  width: 1em;
  height: 1em;
  object-fit: contain;
  vertical-align: middle;
}
</style>
