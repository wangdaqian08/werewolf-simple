/**
 * PlayerReview.vue — the viewer's own replay (玩家视角复盘): their night turn, a dawn
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
    ...over,
  }
}

describe('PlayerReview', () => {
  it('names my own role and shows my own night turn', () => {
    const text = mount(PlayerReview, { props: { review: review() } }).text()
    expect(text).toContain('我的身份：4号 预言家')
    expect(text).toContain('🔮 我查验 1号 Alice → 狼人')
  })

  it('dawn names the dead seat but never the role or the cause', () => {
    const text = mount(PlayerReview, { props: { review: review() } }).text()
    expect(text).toContain('☀ 天亮 2号 Bob 出局')
    // The god replay would say 被刀出局 and tag the role; the table only hears that they are out.
    expect(text).not.toContain('被刀')
    expect(text).not.toContain('2号 Bob〔')
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
    expect(text).toContain('你于第2天出局，复盘到此结束')
  })

  it('a player who survived to the end gets no stop line', () => {
    const text = mount(PlayerReview, { props: { review: review() } }).text()
    expect(text).not.toContain('复盘到此结束')
  })
})
