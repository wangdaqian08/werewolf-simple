/**
 * PlayerReviewView — /review/:gameId/me loads the viewer's own replay from the URL
 * alone (deep link or reload work) and explains why it is closed.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import PlayerReviewView from '@/views/PlayerReviewView.vue'
import { gameService } from '@/services/gameService'
import type { PlayerReview } from '@/types'

vi.mock('@/services/gameService', () => ({ gameService: { getPlayerReview: vi.fn() } }))

const REVIEW: PlayerReview = {
  winner: 'VILLAGER',
  me: { userId: 'u1', nickname: 'Alice', seatIndex: 1, role: 'VILLAGER', camp: null },
  seats: [],
  rounds: [],
  diedOnDay: null,
  reward: null,
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/review/:gameId/me', name: 'player-review', component: PlayerReviewView },
      { path: '/result/:gameId', name: 'result', component: { template: '<div>Result</div>' } },
    ],
  })
  await router.push('/review/7/me')
  const wrapper = mount(PlayerReviewView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('PlayerReviewView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads the viewer own replay for the game in the URL and renders it', async () => {
    vi.mocked(gameService.getPlayerReview).mockResolvedValue(REVIEW)
    const { wrapper } = await mountView()
    expect(gameService.getPlayerReview).toHaveBeenCalledWith('7')
    expect(wrapper.find('[data-testid="player-review"]').exists()).toBe(true)
  })

  it('explains the replay opens after the game when the server answers 403', async () => {
    vi.mocked(gameService.getPlayerReview).mockRejectedValue({ response: { status: 403 } })
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-testid="player-review-error"]').text()).toBe(
      '遊戲結束後，本局玩家才能檢視覆盤',
    )
  })

  it('an unknown game (404) says the game cannot be found instead of asking to retry', async () => {
    vi.mocked(gameService.getPlayerReview).mockRejectedValue({ response: { status: 404 } })
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-testid="player-review-error"]').text()).toBe('找不到這局遊戲')
  })

  it('generic error: any other failure shows a retry message', async () => {
    vi.mocked(gameService.getPlayerReview).mockRejectedValue(new Error('Network Error'))
    const { wrapper } = await mountView()
    expect(wrapper.find('[data-testid="player-review-error"]').text()).toBe(
      '覆盤載入失敗，請稍後再試',
    )
  })

  it('‹ 返回 goes back to the result page of the same game', async () => {
    vi.mocked(gameService.getPlayerReview).mockResolvedValue(REVIEW)
    const { wrapper, router } = await mountView()
    await wrapper.find('[data-testid="player-review-back"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/result/7')
  })
})
