/**
 * PlayerReview.vue — the viewer's own replay (玩家視角復盤): their night turn, a dawn
 * that names the dead without the cause, and a stop line where their game ended.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PlayerReview from '@/components/PlayerReview.vue'
import type { KnownCamp, PlayerReview as PlayerReviewData, PlayerRole, PlayerSeat } from '@/types'

const seat = (
  seatIndex: number,
  nickname: string,
  role: PlayerRole | null = null,
  camp: KnownCamp | null = null,
): PlayerSeat => ({ userId: `u${seatIndex}`, nickname, seatIndex, role, camp })

/** Seat 4 is the seer; they checked seat 1 and found a wolf, and seat 2 died that night. */
function review(over: Partial<PlayerReviewData> = {}): PlayerReviewData {
  return {
    winner: 'WEREWOLF',
    me: seat(4, 'Dana', 'SEER'),
    seats: [seat(1, 'Alice', null, 'WOLF'), seat(2, 'Bob'), seat(4, 'Dana', 'SEER')],
    rounds: [
      {
        day: 1,
        night: {
          action: { type: 'SEER', target: seat(1, 'Alice', null, 'WOLF'), isWerewolf: true },
          dawn: { deaths: [seat(2, 'Bob')], saved: null },
        },
        events: [],
      },
    ],
    diedOnDay: null,
    reward: null,
    ...over,
  }
}

describe('PlayerReview', () => {
  it('names my own role and shows my own night turn', () => {
    const text = mount(PlayerReview, { props: { review: review() } }).text()
    expect(text).toContain('我的身份：4號 預言家')
    expect(text).toContain('🔮 我查驗 1號 Alice → 狼人')
  })

  it('dawn names the dead seat but never the role or the cause', () => {
    const text = mount(PlayerReview, { props: { review: review() } }).text()
    expect(text).toContain('☀ 天亮 2號 Bob 出局')
    // The god replay would say 被刀出局 and tag the role; the table only hears that they are out.
    expect(text).not.toContain('被刀')
    expect(text).not.toContain('2號 Bob〔')
  })

  it('a villager sleeps through the night, so only dawn is shown', () => {
    const r = review({ me: seat(2, 'Bob', 'VILLAGER') })
    r.rounds[0]!.night!.action = null
    const text = mount(PlayerReview, { props: { review: r } }).text()
    expect(text).not.toContain('🔮')
    expect(text).toContain('☀ 天亮')
  })

  it('a replay cut short by my death says where it stopped', () => {
    const text = mount(PlayerReview, { props: { review: review({ diedOnDay: 2 }) } }).text()
    expect(text).toContain('你於第2天出局，復盤到此結束')
  })

  it('a player who survived to the end gets no stop line', () => {
    const text = mount(PlayerReview, { props: { review: review() } }).text()
    expect(text).not.toContain('復盤到此結束')
  })

  it('points: each line of the total is spelled out, with a 合計', () => {
    const r = review({
      reward: {
        total: 28,
        items: [
          { reason: 'WIN', amount: 20, count: null, each: null, days: null, perDay: null },
          { reason: 'SHERIFF', amount: 5, count: 1, each: 5, days: null, perDay: null },
          { reason: 'WOLF_VOTE', amount: 3, count: 1, each: 3, days: null, perDay: null },
        ],
      },
    })
    const text = mount(PlayerReview, { props: { review: r } }).text()

    expect(text).toContain('勝利方獎勵')
    expect(text).toContain('當選／持有警徽')
    expect(text).toContain('投出狼人')
    expect(text).toContain('合計')
    expect(text).toContain('+28')
  })

  it('points: a line earned more than once shows the rate', () => {
    const r = review({
      reward: {
        total: 11,
        items: [
          { reason: 'PARTICIPATION', amount: 5, count: null, each: null, days: null, perDay: null },
          { reason: 'WOLF_VOTE', amount: 6, count: 2, each: 3, days: null, perDay: null },
        ],
      },
    })
    expect(mount(PlayerReview, { props: { review: r } }).text()).toContain('投出狼人 2 次 × 3 分')
  })

  it('points: a losing wolf score is explained by the days survived and the rate', () => {
    const r = review({
      reward: {
        total: 12,
        items: [
          { reason: 'WOLF_SURVIVAL', amount: 12, count: null, each: null, days: 3, perDay: 4 },
        ],
      },
    })
    expect(mount(PlayerReview, { props: { review: r } }).text()).toContain(
      '狼人失利，存活 3 天 × 4 分/天',
    )
  })

  it('points: only the total shows when the working no longer adds up', () => {
    const r = review({ reward: { total: 99, items: [] } })
    const text = mount(PlayerReview, { props: { review: r } }).text()

    expect(text).toContain('合計')
    expect(text).toContain('+99')
    expect(text).not.toContain('勝利方獎勵')
  })

  it('points: a vote that earned credit shows it on that very line', () => {
    const r = review({
      rounds: [
        {
          day: 1,
          night: null,
          events: [
            {
              type: 'VOTE',
              kind: 'EXILE',
              round: 1,
              tally: [],
              abstain: [],
              result: seat(1, 'Alice', null, 'WOLF'),
              appointed: false,
              points: 3,
            },
          ],
        },
      ],
    })

    expect(mount(PlayerReview, { props: { review: r } }).text()).toContain('被投出 +3')
  })

  it('points: no block at all when the game was never settled', () => {
    expect(mount(PlayerReview, { props: { review: review() } }).text()).not.toContain('本局積分')
  })
})
