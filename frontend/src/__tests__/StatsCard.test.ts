import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StatsCard from '@/components/StatsCard.vue'
import { MOCK_USER_STATS } from '@/mocks/data'
import type { UserStats } from '@/types'

const mountCard = (stats: UserStats) => mount(StatsCard, { props: { stats } })
const text = (w: ReturnType<typeof mountCard>, id: string) => w.find(`[data-testid="${id}"]`).text()

describe('StatsCard', () => {
  it('renders the headline numbers and camp splits', () => {
    const w = mountCard(MOCK_USER_STATS)
    expect(text(w, 'stats-games')).toContain('37')
    expect(text(w, 'stats-wins')).toContain('22')
    expect(text(w, 'stats-losses')).toContain('15')
    expect(text(w, 'stats-winrate')).toContain('59%')
    expect(text(w, 'stats-wolf')).toContain('9')
    expect(text(w, 'stats-wolf')).toContain('5')
    expect(text(w, 'stats-good')).toContain('13')
    expect(text(w, 'stats-good')).toContain('10')
  })

  it('shows the score under its own name, apart from coins', () => {
    const w = mountCard({ ...MOCK_USER_STATS, score: 1280 })
    expect(text(w, 'stats-score')).toContain('1280')
    expect(text(w, 'stats-score')).toContain('戰績分')
    expect(text(w, 'stats-score')).not.toContain('◈')
  })

  it('shows a score of 0 before the first rewarded game', () => {
    expect(text(mountCard({ ...MOCK_USER_STATS, score: 0 }), 'stats-score')).toContain('0')
  })

  it('renders 20 recent cells in order', () => {
    const cells = mountCard(MOCK_USER_STATS).findAll('[data-testid="stats-recent-cell"]')
    expect(cells.map((c) => c.attributes('data-result')).join('')).toBe('WWLWLLWWWLWLWWLLWLWW')
  })

  it('shows the beaten percentage', () => {
    const w = mountCard(MOCK_USER_STATS)
    expect(text(w, 'stats-percentile')).toBe('打敗了 72% 的玩家')
    expect(w.find('[data-testid="stats-unlock"]').exists()).toBe(false)
  })

  it('shows the unlock hint when unranked', () => {
    const w = mountCard({ ...MOCK_USER_STATS, percentile: null, gamesToRank: 2 })
    expect(text(w, 'stats-unlock')).toBe('再玩 2 局解鎖排名')
    expect(w.find('[data-testid="stats-percentile"]').exists()).toBe(false)
  })

  it('renders zeros and no cells for a player with no games', () => {
    const w = mountCard({
      games: 0,
      wins: 0,
      losses: 0,
      winRate: 0,
      wolf: { wins: 0, losses: 0 },
      good: { wins: 0, losses: 0 },
      recent: [],
      percentile: null,
      gamesToRank: 5,
      score: 0,
    })
    expect(text(w, 'stats-games')).toContain('0')
    expect(text(w, 'stats-winrate')).toContain('0%')
    expect(w.findAll('[data-testid="stats-recent-cell"]')).toHaveLength(0)
    expect(text(w, 'stats-unlock')).toBe('再玩 5 局解鎖排名')
  })
})
