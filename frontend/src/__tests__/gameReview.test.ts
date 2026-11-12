/**
 * GameReview.vue — the God's-eye replay (上帝視角復盤): one line per role each
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
  ReviewReward,
  ReviewRolePick,
  ReviewTask,
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
const mech = P(10, 'Mia', 'MECHANICAL_WOLF')
const medium = P(11, 'Meg', 'MEDIUM')
const gravekeeper = P(12, 'Gia', 'GRAVEKEEPER')
const players = [w1, w2, king, seer, witch, guard, hunter, idiot, vil]

const night = (over: Partial<ReviewNight> = {}): ReviewNight => ({
  wolf: { actors: [w1, w2, king], action: 'KILL', target: vil },
  seer: { actor: seer, dead: false, target: null, isWerewolf: false },
  mech: null,
  medium: null,
  gravekeeper: null,
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

function render(
  rounds: ReviewRound[],
  winner: GameReviewData['winner'] = 'WEREWOLF',
  roster: ReviewPlayer[] = players,
  rewards: ReviewReward[] = [],
  rolePicks: ReviewRolePick[] = [],
  tasks: ReviewTask[] = [],
) {
  return mount(GameReview, {
    props: { review: { winner, players: roster, rounds, rewards, rolePicks, tasks } },
  })
}
const lines = (wrapper: ReturnType<typeof render>) =>
  wrapper.findAll('.rv-line').map((l) => l.text())
/** Vote-table rows as "target votes ← seats", a wolf seat marked 🐺; out = the voted-out row. */
const tally = (wrapper: ReturnType<typeof render>) =>
  wrapper.findAll('.rv-tally-row').map((r) => {
    const seats = r
      .findAll('.rv-seat')
      .map((s) => s.text() + (s.classes('rv-seat-wolf') ? '🐺' : ''))
      .join(',')
    const out = r.find('.rv-votes').classes('rv-out') ? ' out' : ''
    return `${r.find('.rv-target').text()} ${r.find('.rv-votes').text()} ← ${seats}${out}`
  })

const turn = (
  over: Partial<NonNullable<ReviewNight['mech']>>,
): NonNullable<ReviewNight['mech']> => ({
  actor: mech,
  dead: false,
  learned: null,
  learnedRole: null,
  check: null,
  poison: null,
  protect: null,
  checkRole: null,
  checkIsWerewolf: null,
  ...over,
})

describe('GameReview', () => {
  it('role picks: one row per pick with camp and whether it took effect', () => {
    const w = render(
      [],
      'VILLAGER',
      players,
      [],
      [
        { player: w1, choice: 'WOLF', tookEffect: true },
        { player: vil, choice: 'GOD', tookEffect: false },
      ],
    )

    expect(w.find('[data-testid="review-role-picks"]').exists()).toBe(true)
    expect(w.find('[data-testid="review-role-pick-1"]').text()).toContain('生效')
    expect(w.find('[data-testid="review-role-pick-1"]').text()).toContain('狼人')
    expect(w.find('[data-testid="review-role-pick-9"]').text()).toContain('未抽中')
    expect(w.find('[data-testid="review-role-pick-9"]').text()).toContain('神')
  })

  it('role picks: no section when nobody picked', () => {
    expect(render([]).find('[data-testid="review-role-picks"]').exists()).toBe(false)
  })

  it('points: every player who earned is listed with their amount', () => {
    const w = render([], 'VILLAGER', players, [
      { player: w1, amount: 12 },
      { player: seer, amount: 20 },
    ])

    const block = w.find('[data-testid="review-rewards"]')
    expect(w.findAll('.rv-reward-row')).toHaveLength(2)
    expect(block.text()).toContain('+12')
    expect(block.text()).toContain('+20')
  })

  it('points: no block at all when the game was never settled', () => {
    expect(render([]).find('[data-testid="review-rewards"]').exists()).toBe(false)
  })

  it('header: the outcome and the wolf team', () => {
    const wrapper = render([])
    expect(wrapper.find('.rv-outcome').text()).toBe('狼人勝')
    expect(wrapper.find('.rv-team').text()).toBe(
      '狼人陣營：1號 Wolf1 · 2號 Wolf2 · 3號 King（白狼王）',
    )
    expect(render([], 'VILLAGER').find('.rv-outcome').text()).toBe('好人勝')
  })

  it('黑狼王: tagged in the wolf team, and a chain shows each shooter with its role', () => {
    const bk = P(3, 'Black', 'BLACK_WOLF_KING')
    const roster = [w1, w2, bk, seer, hunter, vil]
    const wrapper = render(
      [
        {
          day: 2,
          night: null,
          events: [
            { type: 'HUNTER_SHOT', hunter: bk, target: hunter },
            { type: 'HUNTER_SHOT', hunter, target: vil },
          ],
        },
      ],
      'WEREWOLF',
      roster,
    )
    expect(wrapper.find('.rv-team').text()).toBe(
      '狼人陣營：1號 Wolf1 · 2號 Wolf2 · 3號 Black（黑狼王）',
    )
    expect(lines(wrapper)).toEqual([
      '第2天',
      '🔫 3號 Black〔黑狼王〕 開槍帶走 7號 Hal〔獵人〕',
      '🔫 7號 Hal〔獵人〕 開槍帶走 9號 Val〔村民〕',
    ])
  })

  it('empty review: a cancelled game with nothing played shows 比賽取消 and 暫無記錄', () => {
    const wrapper = render([], null)
    expect(wrapper.find('.rv-outcome').text()).toBe('比賽取消')
    expect(wrapper.find('.rv-empty').text()).toBe('暫無記錄')
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
      '🐺 狼人（1·2·3號） 刀 9號 Val〔村民〕',
      '🔮 預言家 4號 驗 1號 Wolf1 → 狼人',
      '🧪 女巫 5號 未用藥',
      '🛡 守衛 6號 守 9號 Val',
      '☀ 天亮 平安夜（9號 Val 被守衛守住）',
    ])
  })

  it('game review lists the mech learn/check/poison/protect and medium checks per night', () => {
    const wrapper = render([
      {
        day: 1,
        night: night({
          mech: turn({ learned: medium, learnedRole: 'MEDIUM' }),
          medium: { actor: medium, dead: false, target: vil, role: 'VILLAGER' },
        }),
        events: [],
      },
      {
        day: 2,
        night: night({
          mech: turn({ check: hunter, checkRole: 'HUNTER', poison: vil, protect: seer }),
          medium: { actor: medium, dead: true, target: null, role: null },
        }),
        events: [],
      },
    ])
    const text = lines(wrapper)
    expect(text).toContain('⚙ 機械狼 10號 學 11號 Meg〔通靈師〕')
    expect(text).toContain('👻 通靈師 11號 驗 9號 Val〔村民〕')
    // learned the medium's role check, so the check reads out the role
    expect(text).toContain('⚙ 機械狼 10號 驗 7號 Hal〔獵人〕，毒 9號 Val〔村民〕，守 4號 Sia')
    expect(text).toContain('👻 通靈師 11號 已出局')
  })

  it('game review lists the gravekeeper turn: exiled camp, no exile, dead', () => {
    const turn = (over: Partial<NonNullable<ReviewNight['gravekeeper']>>) => ({
      actor: gravekeeper,
      dead: false,
      exiled: null,
      result: null,
      ...over,
    })
    const wrapper = render([
      { day: 1, night: night({ gravekeeper: turn({ result: 'NONE' }) }), events: [] },
      { day: 2, night: night({ gravekeeper: turn({ exiled: king, result: 'WOLF' }) }), events: [] },
      { day: 3, night: night({ gravekeeper: turn({ exiled: vil, result: 'GOOD' }) }), events: [] },
      { day: 4, night: night({ gravekeeper: turn({ dead: true }) }), events: [] },
    ])
    expect(lines(wrapper).filter((l) => l.includes('守墓人'))).toEqual([
      '⚰ 守墓人 12號 無人被放逐',
      // the camp it was told, never the exiled wolf's exact role
      '⚰ 守墓人 12號 得知 3號 King → 狼人',
      '⚰ 守墓人 12號 得知 9號 Val → 好人',
      '⚰ 守墓人 12號 已出局',
    ])
    // After the medium's line, before the witch's.
    const first = lines(render([{ day: 1, night: night({ gravekeeper: turn({}) }), events: [] }]))
    expect(first.findIndex((l) => l.includes('守墓人'))).toBe(
      first.findIndex((l) => l.includes('女巫')) - 1,
    )
  })

  it('no gravekeeper line when the role is not in the game', () => {
    const wrapper = render([{ day: 1, night: night(), events: [] }])
    expect(lines(wrapper).join('')).not.toContain('守墓人')
  })

  it('a learned alignment check shows wolf or good, never a role name', () => {
    const wrapper = render([
      { day: 1, night: night({ mech: turn({ learned: seer, learnedRole: 'SEER' }) }), events: [] },
      { day: 2, night: night({ mech: turn({ check: w1, checkIsWerewolf: true }) }), events: [] },
      { day: 3, night: night({ mech: turn({ check: vil, checkIsWerewolf: false }) }), events: [] },
      { day: 4, night: night({ mech: turn({}) }), events: [] },
    ])
    const text = lines(wrapper).filter((l) => l.includes('機械狼'))
    expect(text).toEqual([
      '⚙ 機械狼 10號 學 4號 Sia〔預言家〕',
      '⚙ 機械狼 10號 驗 1號 Wolf1 → 狼人',
      '⚙ 機械狼 10號 驗 9號 Val → 好人',
      '⚙ 機械狼 10號 無行動',
    ])
  })

  it("renders the result the server stored, not the target's real role", () => {
    const wrapper = render([
      {
        day: 1,
        night: night({
          // the medium checked a mechanical wolf that learned SEER: told 預言家, not 機械狼
          mech: {
            actor: mech,
            dead: false,
            learned: null,
            learnedRole: null,
            check: hunter,
            checkRole: 'SEER',
            checkIsWerewolf: null,
            poison: null,
            protect: null,
          },
          medium: { actor: medium, dead: false, target: mech, role: 'SEER' },
        }),
        events: [],
      },
    ])
    const text = lines(wrapper)
    expect(text).toContain('👻 通靈師 11號 驗 10號 Mia〔預言家〕')
    expect(text).toContain('⚙ 機械狼 10號 驗 7號 Hal〔預言家〕')
    expect(text.join('')).not.toContain('〔機械狼〕')
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
      '🐺 狼人（1·2·3號） 空刀',
      '🔮 預言家 4號 未查驗',
      '🧪 女巫 5號 毒 7號 Hal〔獵人〕',
      '🛡 守衛 6號 空守',
      '☀ 天亮 7號 Hal〔獵人〕 被毒出局',
      '第2夜',
      '🐺 狼人（1·2·3號） 自刀 2號 Wolf2〔狼人〕',
      '🔮 預言家 4號 已出局',
      '🧪 女巫 5號 救 2號 Wolf2',
      '🛡 守衛 6號 守 2號 Wolf2',
      '☀ 天亮 2號 Wolf2〔狼人〕 同守同救出局',
    ])
  })

  it('roles that are not in the game get no line', () => {
    const wrapper = render([
      { day: 1, night: night({ seer: null, witch: null, guard: null }), events: [] },
    ])
    expect(lines(wrapper)).toEqual([
      '第1夜',
      '🐺 狼人（1·2·3號） 刀 9號 Val〔村民〕',
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
    expect(lines(wrapper)).toEqual(['第1天', '放逐9號 Val〔村民〕 被投出（狼人衝票）'])
    expect(tally(wrapper)).toEqual([
      '9號〔村民〕 3票 ← 1🐺,2🐺,4 out',
      '1號〔狼人〕 1票 ← 6',
      '棄權  ← 7',
    ])
    expect(wrapper.find('.rv-pill').text()).toBe('放逐')
    expect(wrapper.find('.rv-tally .rv-seat-wolf').attributes('aria-label')).toBe('1號 狼人')
    expect(wrapper.find('.rv-legend').exists()).toBe(true)
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

    expect(lines(tie)).toEqual(['第1天', '警長平票，房主指定 9號 Val 當警長'])
    expect(tally(tie)).toEqual(['4號〔預言家〕 2票 ← 1🐺,5', '9號〔村民〕 2票 ← 2🐺,6 out'])
    expect(lines(noVotes)).toEqual(['第1天', '警長無人投票，房主指定 9號 Val 當警長'])
    expect(noVotes.find('.rv-legend').exists()).toBe(false)
  })

  it('PK re-vote, 倒鉤, tie, sheriff vote, and the 1.5 sheriff weight', () => {
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
      '警長4號 Sia 當選',
      '放逐平票，無人出局（狼人倒鉤）',
      'PK9號 Val〔村民〕 被投出',
      '警長無警長',
    ])
    expect(tally(wrapper)).toEqual([
      '4號〔預言家〕 2票 ← 5,6 out',
      '9號〔村民〕 1票 ← 1🐺',
      '2號〔狼人〕 1票 ← 3🐺',
      '9號〔村民〕 2.5票 ← 1🐺,4 out',
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
      '🔫 7號 Hal〔獵人〕 開槍帶走 1號 Wolf1〔狼人〕',
      '💥 3號 King〔白狼王〕 自爆，帶走 4號 Sia〔預言家〕',
      '💥 2號 Wolf2〔狼人〕 自爆',
      '🃏 8號 Ida 白癡翻牌，免於出局',
    ])
  })

  it('KNIGHT_DUEL day event renders, wolf and good outcomes', () => {
    const knight = P(12, 'Kay', 'KNIGHT')
    const wrapper = render([
      {
        day: 2,
        night: null,
        events: [
          { type: 'KNIGHT_DUEL', knight, target: w1, targetIsWolf: true },
          { type: 'KNIGHT_DUEL', knight, target: vil, targetIsWolf: false },
        ],
      },
    ])
    expect(lines(wrapper)).toEqual([
      '第2天',
      '⚔️ 12號 Kay〔騎士〕 翻牌決鬥 1號 Wolf1〔狼人〕，是狼人，出局',
      '⚔️ 12號 Kay〔騎士〕 翻牌決鬥 9號 Val〔村民〕，是好人，騎士出局',
    ])
  })

  it('a badge handover names the old and new sheriff; a destroyed badge has none', () => {
    const wrapper = render([
      {
        day: 2,
        night: null,
        events: [
          { type: 'BADGE_HANDOVER', from: seer, to: witch },
          { type: 'BADGE_HANDOVER', from: witch, to: null },
        ],
      },
    ])
    expect(lines(wrapper)).toEqual([
      '第2天',
      '⭐ 4號 Sia〔預言家〕 移交警徽給 5號 Wen〔女巫〕',
      '⭐ 5號 Wen〔女巫〕 撕毀警徽',
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
      '🐺 狼人（1·2·3號）',
      '1號 Wolf1',
    ])
  })

  it('lists every seat task and whether it was done', () => {
    const tasks: ReviewTask[] = [
      { player: players[1]!, task: 'SEER_FIND_TWO_WOLVES', score: 10, done: true },
      { player: players[0]!, task: 'WOLF_WIN_ALIVE', score: 20, done: false },
    ]
    const w = render([], 'VILLAGER', players, [], [], tasks)
    const rows = w.findAll('[data-testid="review-task"]')
    expect(rows.map((r) => r.attributes('data-seat'))).toEqual([
      String(players[1]!.seatIndex),
      String(players[0]!.seatIndex),
    ])
    expect(rows[0]!.get('[data-testid="task-status"]').text()).toBe('已完成')
    expect(rows[1]!.get('[data-testid="task-status"]').text()).toBe('未完成')
  })

  it('no task section when no seat had a task', () => {
    expect(render([]).find('[data-testid="review-tasks"]').exists()).toBe(false)
  })

  it('a done per-night task shows what it paid', () => {
    const tasks: ReviewTask[] = [
      {
        player: players[1]!,
        task: 'SEER_WOLF_EVERY_NIGHT',
        score: 10,
        done: true,
        perNight: true,
        earned: 30,
      },
    ]
    const w = render([], 'VILLAGER', players, [], [], tasks)
    expect(w.get('[data-testid="review-task"] [data-testid="task-score"]').text()).toBe(
      '+30 戰績分',
    )
  })
})
