<template>
  <section :id="id" :data-testid="`demo-section-${id}`" class="demo-section">
    <header class="demo-section-head">
      <span class="demo-section-num">{{ num }}</span>
      <div>
        <h2 class="demo-section-title">{{ title }}</h2>
        <p v-if="subtitle" class="demo-section-subtitle">{{ subtitle }}</p>
      </div>
    </header>
    <div class="demo-section-body">
      <slot />
    </div>
  </section>
</template>

<script lang="ts" setup>
defineProps<{
  id: string
  num: string
  title: string
  subtitle?: string
}>()
</script>

<style scoped>
.demo-section {
  background: var(--card);
  border: 1px solid var(--border-l);
  border-radius: 0.75rem;
  padding: 1rem;
  scroll-margin-top: 4rem;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.demo-section-head {
  display: flex;
  gap: 0.625rem;
  align-items: flex-start;
  margin-bottom: 0.75rem;
  padding-bottom: 0.625rem;
  border-bottom: 1px dashed var(--border-l);
}

.demo-section-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 50%;
  background: var(--red);
  color: #fff;
  font-family: 'Noto Serif SC', serif;
  font-weight: 700;
  font-size: 0.8125rem;
  flex-shrink: 0;
}

.demo-section-title {
  font-family: 'Noto Serif SC', serif;
  font-size: 1rem;
  font-weight: 600;
  margin: 0;
  color: var(--text);
}

.demo-section-subtitle {
  margin: 0.125rem 0 0;
  font-size: 0.75rem;
  color: var(--muted);
  line-height: 1.5;
}

.demo-section-body {
  position: relative;
}
</style>
