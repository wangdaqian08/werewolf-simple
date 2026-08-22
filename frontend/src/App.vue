<template>
  <RouterView />
  <VersionBadge />
  <!--
    Global on purpose: this is driven by audio-service state, not by route.
    isAudioBlocked() only goes true once a cue has actually parked or a BGM
    start has actually deferred, which cannot happen outside a game — so it
    never shows spuriously in the lobby, and it survives the room -> game
    transition without remounting.
  -->
  <AudioUnlockBanner />
</template>

<script lang="ts" setup>
import { RouterView } from 'vue-router'
import VersionBadge from '@/components/VersionBadge.vue'
import AudioUnlockBanner from '@/components/AudioUnlockBanner.vue'
</script>
