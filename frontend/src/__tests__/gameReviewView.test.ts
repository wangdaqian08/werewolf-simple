/**
 * GameReviewView — /review/:gameId loads the review from the URL alone (deep
 * link or reload work) and explains why it is closed.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import GameReviewView from '@/views/GameReviewView.vue'
import { gameService } from '@/services/gameService'
import type { GameReview } from '@/types'

vi.mock('@/services/gameService', () => ({ gameService: { getReview: vi.fn() } }))

const REVIEW: GameReview = { winner: 'VILLAGER', players: [], rounds: [], rewards: [] }

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/review/:gameId', name: 'review', component: GameReviewView },
      { path: '/result/:gameId', name: 'result', component: { template: '<div>Result</div>' } },
    ],
  })
  await router.push('/review/7')
  const wrapper = mount(GameReviewView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('GameReviewView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads the review for the game in the URL and renders it', async () => {
    vi.mocked(gameService.getReview).mockResolvedValue(REVIEW)
    const { wrapper } = await mountView()
    expect(gameService.getReview).toHaveBeenCalledWith('7')
    expect(wrapper.find('[data-testid="game-review"]').exists()).toBe(true)
  })

  it('explains the review opens after the game when the server answers 403', async () => {
    vi.mocked(gameService.getReview).mockRejectedValue({ response: { status: 403 } })
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-testid="review-error"]').text()).toBe(
      '遊戲結束後，本局玩家才能查看復盤',
    )
  })

  it('an unknown game (404) says the game cannot be found instead of asking to retry', async () => {
    vi.mocked(gameService.getReview).mockRejectedValue({ response: { status: 404 } })
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-testid="review-error"]').text()).toBe('找不到這局遊戲')
  })

  it('generic error: any other failure shows a retry message', async () => {
    vi.mocked(gameService.getReview).mockRejectedValue(new Error('Network Error'))
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-testid="review-error"]').text()).toBe('復盤加載失敗，請稍後再試')
  })

  it('‹ 返回 goes back to the result page of the same game', async () => {
    vi.mocked(gameService.getReview).mockResolvedValue(REVIEW)
    const { wrapper, router } = await mountView()
    await wrapper.find('[data-testid="review-back"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/result/7')
  })
})
