<template>
  <div class="rv" data-testid="game-review">
    <h2 class="rv-outcome serif">{{ outcome }}</h2>
    <p v-if="wolfTeam" class="rv-team">
      狼人阵营：<span class="rv-wolf">{{ wolfTeam }}</span>
    </p>
    <div v-if="!lines.length" class="rv-empty">暂无记录</div>
    <template v-for="(line, i) in lines" :key="i">
      <div v-if="line.kind === 'tally'" class="rv-tally">
        <div v-for="(row, r) in line.rows" :key="r" class="rv-line rv-tally-row">
          <span v-for="(cell, c) in row" :key="c" class="rv-cell">
            <span v-for="(s, j) in cell" :key="j" :class="{ 'rv-wolf': s.wolf }">{{ s.text }}</span>
          </span>
        </div>
      </div>
      <div v-else class="rv-line" :class="`rv-${line.kind}`">
        <span v-for="(s, j) in line.segs" :key="j" :class="{ 'rv-wolf': s.wolf }">{{
          s.text
        }}</span>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type {
  GameReview,
  ReviewDayEvent,
  ReviewNight,
  ReviewPlayer,
  ReviewRound,
  ReviewVote,
} from '@/types'
import { isWolfRole, roleDefinition } from '@/utils/roleDefinitions'

const props = defineProps<{ review: GameReview }>()

/** A run of text in a line; wolf seats render red. */
interface Seg {
  text: string
  wolf?: boolean
}
/** A 'tally' is a vote table: each row is the cells [voters, →, target, votes]. */
type Line = { kind: 'title' | 'row' | 'sub'; segs: Seg[] } | { kind: 'tally'; rows: Seg[][][] }

const CAUSE = { WOLF: '被刀出局', POISON: '被毒出局', BOTH_SAVES: '同守同救出局' } as const
const SAVED_BY = { GUARD: '被守卫守住', ANTIDOTE: '被女巫救下', PERK: '首夜免死生效' } as const
const WOLF_PLAY = { PUSH: '冲票', BUS: '倒钩' } as const

const t = (text: string): Seg => ({ text })
const seat = (p: ReviewPlayer): Seg => ({ text: `${p.seatIndex}号`, wolf: isWolfRole(p.role) })
const name = (p: ReviewPlayer): Seg => ({
  text: `${p.seatIndex}号 ${p.nickname}`,
  wolf: isWolfRole(p.role),
})
const roleTag = (p: ReviewPlayer): Seg => t(`〔${roleDefinition(p.role)?.nameZh ?? p.role}〕`)
const withRole = (p: ReviewPlayer): Seg[] => [name(p), roleTag(p)]
const seats = (ps: ReviewPlayer[]) => `${ps.map((p) => p.seatIndex).join('·')}号`
const joined = (parts: Seg[][], sep: string): Seg[] =>
  parts.flatMap((part, i) => (i ? [t(sep), ...part] : part))
const votes = (n: number) => `${Number.isInteger(n) ? n : n.toFixed(1)}票`
const seatList = (ps: ReviewPlayer[], mark = seat): Seg[] =>
  joined(
    ps.map((p) => [mark(p)]),
    '、',
  )
/** A voter's seat; wolves also carry 🐺 so the vote table shows who the wolves backed. */
const voterSeat = (p: ReviewPlayer): Seg =>
  isWolfRole(p.role) ? { text: `${p.seatIndex}号🐺`, wolf: true } : seat(p)

const outcome = computed(() => {
  if (!props.review.winner) return '比赛取消'
  return props.review.winner === 'WEREWOLF' ? '狼人胜' : '好人胜'
})

const wolfTeam = computed(() =>
  props.review.players
    .filter((p) => isWolfRole(p.role))
    .map((p) => `${p.seatIndex}号 ${p.nickname}${p.role === 'WHITE_WOLF_KING' ? '（白狼王）' : ''}`)
    .join(' · '),
)

function wolfSegs(w: ReviewNight['wolf']): Seg[] {
  const head: Seg = { text: `🐺 狼人（${seats(w.actors)}） `, wolf: true }
  if (!w.target || w.action === 'SKIP') return [head, t('空刀')]
  return [head, t(w.action === 'SELF_KILL' ? '自刀 ' : '刀 '), ...withRole(w.target)]
}

function seerSegs(s: NonNullable<ReviewNight['seer']>): Seg[] {
  const head = t(`🔮 预言家 ${s.actor.seatIndex}号 `)
  if (s.dead) return [head, t('已出局')]
  if (!s.target) return [head, t('未查验')]
  return [head, t('验 '), name(s.target), t(s.isWerewolf ? ' → 狼人' : ' → 好人')]
}

function witchSegs(w: NonNullable<ReviewNight['witch']>): Seg[] {
  const head = t(`🧪 女巫 ${w.actor.seatIndex}号 `)
  if (w.dead) return [head, t('已出局')]
  // One potion per night: WitchHandler rejects antidote + poison together.
  if (w.antidote) return [head, t('救 '), name(w.antidote)]
  if (w.poison) return [head, t('毒 '), ...withRole(w.poison)]
  return [head, t('未用药')]
}

function guardSegs(g: NonNullable<ReviewNight['guard']>): Seg[] {
  const head = t(`🛡 守卫 ${g.actor.seatIndex}号 `)
  if (g.dead) return [head, t('已出局')]
  return g.target ? [head, t('守 '), name(g.target)] : [head, t('空守')]
}

function dawnSegs(d: ReviewNight['dawn']): Seg[] {
  const segs = d.deaths.length
    ? joined(
        d.deaths.map((x) => [...withRole(x.player), t(` ${CAUSE[x.cause]}`)]),
        '，',
      )
    : [t('平安夜')]
  if (d.saved) segs.push(t('（'), name(d.saved.player), t(` ${SAVED_BY[d.saved.by]}）`))
  return [t('☀ 天亮 '), ...segs]
}

function nightLines(n: ReviewNight): Line[] {
  const rows = [
    wolfSegs(n.wolf),
    n.seer && seerSegs(n.seer),
    n.witch && witchSegs(n.witch),
    n.guard && guardSegs(n.guard),
    dawnSegs(n.dawn),
  ]
  return rows
    .filter((segs): segs is Seg[] => !!segs)
    .map((segs) => ({ kind: 'row' as const, segs }))
}

function voteHead(v: ReviewVote): Seg[] {
  if (v.kind === 'SHERIFF') {
    const head = t('⭐ 警长投票：')
    if (!v.result) return [head, t('无警长')]
    if (!v.appointed) return [head, name(v.result), t(' 当选')]
    const why = v.tally.length ? '平票，房主指定 ' : '无人投票，房主指定 '
    return [head, t(why), name(v.result), t(' 当警长')]
  }
  const head = t(v.round > 1 ? '🗳 PK 投票：' : '🗳 放逐投票：')
  return [head, ...(v.result ? [...withRole(v.result), t(' 被投出')] : [t('平票，无人出局')])]
}

/** A vote table, one row per target: "2号🐺、6号 → 9号 小红〔村民〕 2票", abstainers last. */
function voteLines(v: ReviewVote): Line[] {
  const arrow = t(' → ')
  const rows: Seg[][][] = v.tally.map((e) => [
    seatList(e.voters, voterSeat),
    [arrow],
    withRole(e.target),
    [t(` ${votes(e.votes)}`)],
  ])
  if (v.abstain.length) rows.push([seatList(v.abstain, voterSeat), [arrow], [t('弃权')], []])
  const lines: Line[] = [{ kind: 'row', segs: voteHead(v) }]
  if (rows.length) lines.push({ kind: 'tally', rows })
  const wolfGroups = v.tally
    .map((e) => ({ target: e.target, wolves: e.voters.filter((p) => isWolfRole(p.role)) }))
    .filter((g) => g.wolves.length)
  if (wolfGroups.length) {
    const groups = wolfGroups.map((g) => [
      ...seatList(g.wolves),
      arrow,
      seat(g.target),
      roleTag(g.target),
    ])
    const tag = v.wolfPlay ? [t(`（${WOLF_PLAY[v.wolfPlay]}）`)] : []
    lines.push({ kind: 'sub', segs: [t('🐺 狼人投票：'), ...joined(groups, '；'), ...tag] })
  }
  return lines
}

function eventSegs(e: Exclude<ReviewDayEvent, ReviewVote>): Seg[] {
  switch (e.type) {
    case 'HUNTER_SHOT':
      return [t('🔫 猎人 '), name(e.hunter), t(' 开枪带走 '), ...withRole(e.target)]
    case 'SELF_DESTRUCT':
      return [
        t('💥 '),
        ...withRole(e.player),
        t(' 自爆'),
        ...(e.taken ? [t('，带走 '), ...withRole(e.taken)] : []),
      ]
    case 'IDIOT_REVEAL':
      return [t('🃏 '), name(e.player), t(' 白痴翻牌，免于出局')]
  }
}

function roundLines(r: ReviewRound): Line[] {
  const lines: Line[] = []
  if (r.night) lines.push({ kind: 'title', segs: [t(`第${r.day}夜`)] }, ...nightLines(r.night))
  if (r.events.length) {
    lines.push({ kind: 'title', segs: [t(`第${r.day}天`)] })
    for (const e of r.events) {
      if (e.type === 'VOTE') lines.push(...voteLines(e))
      else lines.push({ kind: 'row', segs: eventSegs(e) })
    }
  }
  return lines
}

const lines = computed(() => props.review.rounds.flatMap(roundLines))
</script>

<style scoped>
.rv {
  text-align: left;
}
.rv-outcome {
  font-family: 'Noto Serif SC', serif;
  font-size: 1.5rem;
  margin: 0 0 0.25rem;
  text-align: center;
}
.rv-team {
  font-size: 0.8125rem;
  color: var(--muted);
  margin: 0 0 0.75rem;
  text-align: center;
}
.rv-line {
  font-size: 0.875rem;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.rv-title {
  margin-top: 0.875rem;
  padding-bottom: 0.25rem;
  border-bottom: 1px solid var(--border-l);
  font-family: 'Noto Serif SC', serif;
  font-weight: 700;
}
.rv-row {
  padding: 0.2rem 0;
}
.rv-sub {
  padding-left: 1.25rem;
  font-size: 0.8125rem;
  color: var(--muted);
}
/* Vote table: voters | → | target | votes, columns aligned across rows. */
.rv-tally {
  display: grid;
  grid-template-columns: fit-content(45%) auto minmax(0, 1fr) auto;
  column-gap: 0.375rem;
  row-gap: 0.125rem;
  align-items: baseline;
  padding: 0.125rem 0 0.25rem 1.25rem;
}
.rv-tally-row {
  display: contents;
  font-size: 0.8125rem;
}
.rv-cell:nth-child(2) {
  color: var(--muted);
}
.rv-cell:nth-child(4) {
  color: var(--muted);
  text-align: right;
  white-space: nowrap;
}
.rv-wolf {
  color: var(--red);
  font-weight: 600;
}
.rv-empty {
  color: var(--muted);
  text-align: center;
  padding: 1rem 0;
}
</style>
