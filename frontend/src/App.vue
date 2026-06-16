<template>
  <RouterView />
  <VersionBadge />
</template>

<script lang="ts" setup>
import { RouterView } from 'vue-router'
import VersionBadge from '@/components/VersionBadge.vue'
</script>
