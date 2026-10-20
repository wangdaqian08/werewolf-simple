/**
 * GameReview.vue — the God's-eye replay (上帝视角复盘): one line per role each
 * night, then the day's votes and events, every player's true role shown.
 */
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import GameReview from '@/components/GameReview.vue'
import type {
  GameReview as GameReviewData,
  PlayerRole,
  ReviewNight,
  ReviewPlayer,
  ReviewRound,
  ReviewVote,
} from '@/types'

const P = (seatIndex: number, nickname: string, role: PlayerRole): ReviewPlayer => ({
  userId: `u${seatIndex}`,
  nickname,
  seatIndex,
  role,
})
const w1 = P(1, 'Wolf1', 'WEREWOLF')
const w2 = P(2, 'Wolf2', 'WEREWOLF')
const king = P(3, 'King', 'WHITE_WOLF_KING')
const seer = P(4, 'Sia', 'SEER')
const witch = P(5, 'Wen', 'WITCH')
const guard = P(6, 'Gus', 'GUARD')
const hunter = P(7, 'Hal', 'HUNTER')
const idiot = P(8, 'Ida', 'IDIOT')
const vil = P(9, 'Val', 'VILLAGER')
const players = [w1, w2, king, seer, witch, guard, hunter, idiot, vil]

const night = (over: Partial<ReviewNight> = {}): ReviewNight => ({
  wolf: { actors: [w1, w2, king], action: 'KILL', target: vil },
  seer: { actor: seer, dead: false, target: null, isWerewolf: false },
  witch: { actor: witch, dead: false, antidote: null, poison: null },
  guard: { actor: guard, dead: false, target: null },
  dawn: { deaths: [], saved: null },
  ...over,
})
const vote = (over: Partial<ReviewVote> = {}): ReviewVote => ({
  type: 'VOTE',
  kind: 'EXILE',
  round: 1,
  tally: [],
  abstain: [],
  result: null,
  appointed: false,
  wolfPlay: null,
  ...over,
})

function render(rounds: ReviewRound[], winner: GameReviewData['winner'] = 'WEREWOLF') {
  return mount(GameReview, { props: { review: { winner, players, rounds } } })
}
const lines = (wrapper: ReturnType<typeof render>) =>
  wrapper.findAll('.rv-line').map((l) => l.text())

describe('GameReview', () => {
  it('header: the outcome and the wolf team', () => {
    const wrapper = render([])
    expect(wrapper.find('.rv-outcome').text()).toBe('狼人胜')
    expect(wrapper.find('.rv-team').text()).toBe(
      '狼人阵营：1号 Wolf1 · 2号 Wolf2 · 3号 King（白狼王）',
    )
    expect(render([], 'VILLAGER').find('.rv-outcome').text()).toBe('好人胜')
  })

  it('empty review: a cancelled game with nothing played shows 比赛取消 and 暂无记录', () => {
    const wrapper = render([], null)
    expect(wrapper.find('.rv-outcome').text()).toBe('比赛取消')
    expect(wrapper.find('.rv-empty').text()).toBe('暂无记录')
    expect(lines(wrapper)).toEqual([])
  })

  it('a night reads as one line per role in night order, then the dawn outcome', () => {
    const wrapper = render([
      {
        day: 1,
        night: night({
          seer: { actor: seer, dead: false, target: w1, isWerewolf: true },
          guard: { actor: guard, dead: false, target: vil },
          dawn: { deaths: [], saved: { player: vil, by: 'GUARD' } },
        }),
        events: [],
      },
    ])
    expect(lines(wrapper)).toEqual([
      '第1夜',
      '🐺 狼人（1·2·3号） 刀 9号 Val〔村民〕',
      '🔮 预言家 4号 验 1号 Wolf1 → 狼人',
      '🧪 女巫 5号 未用药',
      '🛡 守卫 6号 守 9号 Val',
      '☀ 天亮 平安夜（9号 Val 被守卫守住）',
    ])
  })

  it('空刀, 自刀, dead and idle roles, and each death with its cause', () => {
    const wrapper = render([
      {
        day: 1,
        night: night({
          wolf: { actors: [w1, w2, king], action: 'SKIP', target: null },
          witch: { actor: witch, dead: false, antidote: null, poison: hunter },
          dawn: { deaths: [{ player: hunter, cause: 'POISON' }], saved: null },
        }),
        events: [],
      },
      {
        day: 2,
        night: night({
          wolf: { actors: [w1, w2, king], action: 'SELF_KILL', target: w2 },
          seer: { actor: seer, dead: true, target: null, isWerewolf: false },
          witch: { actor: witch, dead: false, antidote: w2, poison: null },
          guard: { actor: guard, dead: false, target: w2 },
          dawn: { deaths: [{ player: w2, cause: 'BOTH_SAVES' }], saved: null },
        }),
        events: [],
      },
    ])
    expect(lines(wrapper)).toEqual([
      '第1夜',
      '🐺 狼人（1·2·3号） 空刀',
      '🔮 预言家 4号 未查验',
      '🧪 女巫 5号 毒 7号 Hal〔猎人〕',
      '🛡 守卫 6号 空守',
      '☀ 天亮 7号 Hal〔猎人〕 被毒出局',
      '第2夜',
      '🐺 狼人（1·2·3号） 自刀 2号 Wolf2〔狼人〕',
      '🔮 预言家 4号 已出局',
      '🧪 女巫 5号 救 2号 Wolf2',
      '🛡 守卫 6号 守 2号 Wolf2',
      '☀ 天亮 2号 Wolf2〔狼人〕 同守同救出局',
    ])
  })

  it('roles that are not in the game get no line', () => {
    const wrapper = render([
      { day: 1, night: night({ seer: null, witch: null, guard: null }), events: [] },
    ])
    expect(lines(wrapper)).toEqual([
      '第1夜',
      '🐺 狼人（1·2·3号） 刀 9号 Val〔村民〕',
      '☀ 天亮 平安夜',
    ])
  })

  it("an exile vote lists each target's voters, the abstainers and where the wolves' votes went", () => {
    const wrapper = render([
      {
        day: 1,
        night: null,
        events: [
          vote({
            tally: [
              { target: vil, votes: 3, voters: [w1, w2, seer] },
              { target: w1, votes: 1, voters: [guard] },
            ],
            abstain: [hunter],
            result: vil,
            wolfPlay: 'PUSH',
          }),
        ],
      },
    ])
    expect(lines(wrapper)).toEqual([
      '第1天',
      '🗳 放逐投票：9号 Val〔村民〕 被投出',
      '1号🐺、2号🐺、4号 → 9号 Val〔村民〕 3票',
      '6号 → 1号 Wolf1〔狼人〕 1票',
      '7号 → 弃权',
      '🐺 狼人投票：1号、2号 → 9号〔村民〕（冲票）',
    ])
  })

  it('a sheriff the host picked says so: after a tie, or when nobody got a vote', () => {
    const tie = render([
      {
        day: 1,
        night: null,
        events: [
          vote({
            kind: 'SHERIFF',
            tally: [
              { target: seer, votes: 2, voters: [w1, witch] },
              { target: vil, votes: 2, voters: [w2, guard] },
            ],
            result: vil,
            appointed: true,
          }),
        ],
      },
    ])
    const noVotes = render([
      { day: 1, night: null, events: [vote({ kind: 'SHERIFF', result: vil, appointed: true })] },
    ])

    expect(lines(tie)).toEqual([
      '第1天',
      '⭐ 警长投票：平票，房主指定 9号 Val 当警长',
      '1号🐺、5号 → 4号 Sia〔预言家〕 2票',
      '2号🐺、6号 → 9号 Val〔村民〕 2票',
      '🐺 狼人投票：1号 → 4号〔预言家〕；2号 → 9号〔村民〕',
    ])
    expect(lines(noVotes)).toEqual(['第1天', '⭐ 警长投票：无人投票，房主指定 9号 Val 当警长'])
  })

  it('PK re-vote, 倒钩, tie, sheriff vote, and the 1.5 sheriff weight', () => {
    const wrapper = render([
      {
        day: 2,
        night: null,
        events: [
          vote({
            kind: 'SHERIFF',
            tally: [{ target: seer, votes: 2, voters: [witch, guard] }],
            result: seer,
          }),
          vote({
            tally: [
              { target: vil, votes: 1, voters: [w1] },
              { target: w2, votes: 1, voters: [king] },
            ],
            wolfPlay: 'BUS',
          }),
          vote({ round: 2, tally: [{ target: vil, votes: 2.5, voters: [w1, seer] }], result: vil }),
          vote({ kind: 'SHERIFF', result: null }),
        ],
      },
    ])
    expect(lines(wrapper)).toEqual([
      '第2天',
      '⭐ 警长投票：4号 Sia 当选',
      '5号、6号 → 4号 Sia〔预言家〕 2票',
      '🗳 放逐投票：平票，无人出局',
      '1号🐺 → 9号 Val〔村民〕 1票',
      '3号🐺 → 2号 Wolf2〔狼人〕 1票',
      '🐺 狼人投票：1号 → 9号〔村民〕；3号 → 2号〔狼人〕（倒钩）',
      '🗳 PK 投票：9号 Val〔村民〕 被投出',
      '1号🐺、4号 → 9号 Val〔村民〕 2.5票',
      '🐺 狼人投票：1号 → 9号〔村民〕',
      '⭐ 警长投票：无警长',
    ])
  })

  it('hunter shot, self-destruct with and without a taken player, and the idiot reveal', () => {
    const wrapper = render([
      {
        day: 2,
        night: null,
        events: [
          { type: 'HUNTER_SHOT', hunter, target: w1 },
          { type: 'SELF_DESTRUCT', player: king, taken: seer },
          { type: 'SELF_DESTRUCT', player: w2, taken: null },
          { type: 'IDIOT_REVEAL', player: idiot },
        ],
      },
    ])
    expect(lines(wrapper)).toEqual([
      '第2天',
      '🔫 猎人 7号 Hal 开枪带走 1号 Wolf1〔狼人〕',
      '💥 3号 King〔白狼王〕 自爆，带走 4号 Sia〔预言家〕',
      '💥 2号 Wolf2〔狼人〕 自爆',
      '🃏 8号 Ida 白痴翻牌，免于出局',
    ])
  })

  it('wolf seats are marked so they render red', () => {
    const wrapper = render([
      {
        day: 1,
        night: night({ seer: { actor: seer, dead: false, target: w1, isWerewolf: true } }),
        events: [],
      },
    ])
    expect(wrapper.findAll('.rv-line .rv-wolf').map((s) => s.text())).toEqual([
      '🐺 狼人（1·2·3号）',
      '1号 Wolf1',
    ])
  })
})
