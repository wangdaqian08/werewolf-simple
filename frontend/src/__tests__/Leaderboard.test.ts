/**
 * Lobby leaderboard: the top three by 戰績分, plus the viewer's own row when
 * they are not among them. Ranks come from the backend; this only displays.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import Leaderboard from '@/components/Leaderboard.vue'
import type { Leaderboard as Board, LeaderboardEntry } from '@/types'

const h = vi.hoisted(() => ({ getLeaderboard: vi.fn() }))
vi.mock('@/services/userService', () => ({ userService: { getLeaderboard: h.getLeaderboard } }))

const e = (rank: number, nickname: string, score: number, me = false): LeaderboardEntry => ({
  rank,
  nickname,
  score,
  me,
})
const TOP = [e(1, 'Alice', 320), e(2, 'Bob', 210), e(3, 'Carol', 150)]

async function mountBoard(board: Board) {
  h.getLeaderboard.mockResolvedValue(board)
  const w = mount(Leaderboard)
  await flushPromises()
  return w
}
const rows = (w: Awaited<ReturnType<typeof mountBoard>>) =>
  w.findAll('[data-testid="leaderboard-row"]')

describe('Leaderboard', () => {
  beforeEach(() => {
    h.getLeaderboard.mockReset()
  })

  it('lists the top three in rank order with name and score', async () => {
    const w = await mountBoard({ top: TOP, me: null })
    expect(rows(w).map((r) => r.attributes('data-rank'))).toEqual(['1', '2', '3'])
    expect(rows(w)[0]!.text()).toContain('Alice')
    expect(rows(w)[0]!.text()).toContain('320')
    expect(rows(w)[2]!.text()).toContain('Carol')
    expect(rows(w)[2]!.text()).toContain('150')
  })

  it('a viewer in the top three: three rows, theirs marked', async () => {
    const top = [e(1, 'Alice', 320), e(2, 'Me', 210, true), e(3, 'Carol', 150)]
    const w = await mountBoard({ top, me: top[1]! })
    expect(rows(w)).toHaveLength(3)
    expect(rows(w).map((r) => r.attributes('data-me'))).toEqual(['false', 'true', 'false'])
    expect(w.find('[data-testid="leaderboard-unranked"]').exists()).toBe(false)
  })

  it('a viewer below the top three gets a fourth row with their own rank', async () => {
    const w = await mountBoard({ top: TOP, me: e(17, 'Me', 40, true) })
    expect(rows(w)).toHaveLength(4)
    const mine = rows(w)[3]!
    expect(mine.attributes('data-rank')).toBe('17')
    expect(mine.attributes('data-me')).toBe('true')
    expect(mine.text()).toContain('17')
    expect(mine.text()).toContain('Me')
    expect(mine.text()).toContain('40')
  })

  it('a viewer with no score sees how to get on the board', async () => {
    const w = await mountBoard({ top: TOP, me: null })
    expect(rows(w)).toHaveLength(3)
    expect(w.find('[data-testid="leaderboard-unranked"]').text()).toBe('暫無排名，完成一局即可上榜')
  })

  it('nobody has played yet: only the hint', async () => {
    const w = await mountBoard({ top: [], me: null })
    expect(rows(w)).toHaveLength(0)
    expect(w.find('[data-testid="leaderboard-unranked"]').exists()).toBe(true)
  })

  it('renders nothing when the board cannot be loaded', async () => {
    h.getLeaderboard.mockRejectedValue(new Error('down'))
    const w = mount(Leaderboard)
    await flushPromises()
    expect(w.find('[data-testid="leaderboard"]').exists()).toBe(false)
  })
})
