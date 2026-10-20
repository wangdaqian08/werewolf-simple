<template>
  <div class="review-wrap">
    <div class="review-card">
      <header class="review-header">
        <button class="review-back" data-testid="review-back" @click="back">‹ 返回</button>
        <span class="review-heading serif">上帝视角复盘</span>
      </header>
      <p v-if="error" class="review-msg" data-testid="review-error">{{ error }}</p>
      <p v-else-if="!review" class="review-msg">加载中…</p>
      <GameReview v-else :review="review" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import GameReview from '@/components/GameReview.vue'
import { gameService } from '@/services/gameService'
import type { GameReview as GameReviewData } from '@/types'

const route = useRoute()
const router = useRouter()
const gameId = String(route.params.gameId)
const review = ref<GameReviewData | null>(null)
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    review.value = await gameService.getReview(gameId)
  } catch (err: unknown) {
    const status = (err as { response?: { status?: number } }).response?.status
    error.value =
      status === 404
        ? '找不到这局游戏'
        : status === 403
          ? '游戏结束后，本局玩家才能查看复盘'
          : '复盘加载失败，请稍后再试'
  }
})

function back() {
  router.push({ name: 'result', params: { gameId } })
}
</script>

<style scoped>
.review-wrap {
  display: flex;
  justify-content: center;
  min-height: 100dvh;
  background: var(--bg);
  padding: 1rem;
}
.review-card {
  background: var(--paper);
  border: 1px solid var(--border);
  border-radius: 1rem;
  padding: 1rem 1.25rem 1.5rem;
  width: 100%;
  max-width: 380px;
  align-self: flex-start;
}
.review-header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}
.review-back {
  background: none;
  border: none;
  color: var(--muted);
  font-size: 0.9375rem;
  padding: 0.25rem 0;
  cursor: pointer;
}
.review-heading {
  font-family: 'Noto Serif SC', serif;
  font-weight: 700;
  letter-spacing: 0.1em;
}
.review-msg {
  color: var(--muted);
  text-align: center;
  padding: 2rem 0;
}
</style>
