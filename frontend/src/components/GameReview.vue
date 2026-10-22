<template>
  <div class="rv" data-testid="game-review">
    <h2 class="rv-outcome serif">{{ outcome }}</h2>
    <p v-if="wolfTeam" class="rv-team">
      狼人阵营：<span class="rv-wolf">{{ wolfTeam }}</span>
    </p>
    <div v-if="!lines.length" class="rv-empty">暂无记录</div>
    <p v-if="hasTally" class="rv-legend">
      投票中 <span class="rv-seat rv-seat-wolf" aria-hidden="true">1</span> 红色座位为狼人
    </p>
    <template v-for="(line, i) in lines" :key="i">
      <div v-if="line.kind === 'tally'" class="rv-tally">
        <div v-for="(row, r) in line.rows" :key="r" class="rv-tally-row">
          <span class="rv-target" :class="{ 'rv-muted': row.abstain }">
            <span v-for="(s, j) in row.target" :key="j" :class="{ 'rv-wolf': s.wolf }">{{
              s.text
            }}</span>
          </span>
          <span class="rv-votes" :class="{ 'rv-out': row.out }">{{ row.votes }}</span>
          <span class="rv-arrow">←</span>
          <span class="rv-voters" role="list">
            <span
              v-for="p in row.voters"
              :key="p.userId"
              role="listitem"
              class="rv-seat"
              :class="{ 'rv-seat-wolf': isWolfRole(p.role) }"
              :aria-label="`${p.seatIndex}号${isWolfRole(p.role) ? ' 狼人' : ''}`"
              >{{ p.seatIndex }}</span
            >
          </span>
        </div>
      </div>
      <div v-else class="rv-line" :class="`rv-${line.kind}`">
        <span
          v-for="(s, j) in line.segs"
          :key="j"
          :class="{ 'rv-wolf': s.wolf, 'rv-pill': s.pill, 'rv-muted': s.muted }"
          >{{ s.text }}</span
        >
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

/** A run of text in a line; wolf seats render red, a pill is the vote's label. */
interface Seg {
  text: string
  wolf?: boolean
  pill?: boolean
  muted?: boolean
}
/** One vote-table row: "9号〔村民〕 3票 ← ①②④", the voters as seat badges. */
interface TallyRow {
  target: Seg[]
  votes: string
  out: boolean
  abstain: boolean
  voters: ReviewPlayer[]
}
type Line = { kind: 'title' | 'row'; segs: Seg[] } | { kind: 'tally'; rows: TallyRow[] }

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
const pill = (text: string): Seg => ({ text, pill: true })

const outcome = computed(() => {
  if (!props.review.winner) return '比赛取消'
  return props.review.winner === 'WEREWOLF' ? '狼人胜' : '好人胜'
})

const wolfTeam = computed(() =>
  props.review.players
    .filter((p) => isWolfRole(p.role))
    // Tag the special wolves (白狼王 / 黑狼王).
    .map(
      (p) =>
        `${p.seatIndex}号 ${p.nickname}${p.role !== 'WEREWOLF' ? `（${roleDefinition(p.role)?.nameZh}）` : ''}`,
    )
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
    const head = pill('警长')
    if (!v.result) return [head, t('无警长')]
    if (!v.appointed) return [head, name(v.result), t(' 当选')]
    const why = v.tally.length ? '平票，房主指定 ' : '无人投票，房主指定 '
    return [head, t(why), name(v.result), t(' 当警长')]
  }
  const head = pill(v.round > 1 ? 'PK' : '放逐')
  const play = v.wolfPlay ? [{ text: `（狼人${WOLF_PLAY[v.wolfPlay]}）`, muted: true }] : []
  return [
    head,
    ...(v.result ? [...withRole(v.result), t(' 被投出')] : [t('平票，无人出局')]),
    ...play,
  ]
}

/** A vote table, one row per target, abstainers last; red seat badges are the wolves. */
function voteLines(v: ReviewVote): Line[] {
  const rows: TallyRow[] = v.tally.map((e) => ({
    target: [seat(e.target), roleTag(e.target)],
    votes: votes(e.votes),
    out: e.target.userId === v.result?.userId,
    abstain: false,
    voters: e.voters,
  }))
  if (v.abstain.length)
    rows.push({ target: [t('弃权')], votes: '', out: false, abstain: true, voters: v.abstain })
  const lines: Line[] = [{ kind: 'row', segs: voteHead(v) }]
  if (rows.length) lines.push({ kind: 'tally', rows })
  return lines
}

function eventSegs(e: Exclude<ReviewDayEvent, ReviewVote>): Seg[] {
  switch (e.type) {
    case 'HUNTER_SHOT':
      // The shooter is a HUNTER or a BLACK_WOLF_KING; the review is post-game, so name it.
      return [t('🔫 '), ...withRole(e.hunter), t(' 开枪带走 '), ...withRole(e.target)]
    case 'SELF_DESTRUCT':
      return [
        t('💥 '),
        ...withRole(e.player),
        t(' 自爆'),
        ...(e.taken ? [t('，带走 '), ...withRole(e.taken)] : []),
      ]
    case 'IDIOT_REVEAL':
      return [t('🃏 '), name(e.player), t(' 白痴翻牌，免于出局')]
    case 'BADGE_HANDOVER':
      return [
        t('⭐ '),
        ...withRole(e.from),
        ...(e.to ? [t(' 移交警徽给 '), ...withRole(e.to)] : [t(' 撕毁警徽')]),
      ]
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
const hasTally = computed(() => lines.value.some((l) => l.kind === 'tally'))
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
.rv-legend {
  font-size: 0.75rem;
  color: var(--muted);
  margin: -0.5rem 0 0.5rem;
  text-align: center;
}
.rv-pill {
  display: inline-block;
  margin-right: 0.375rem;
  padding: 0 0.375rem;
  border-radius: 3px;
  background: var(--text);
  color: var(--paper);
  font-size: 0.6875rem;
  font-weight: 700;
  line-height: 1.125rem;
  vertical-align: 1px;
}
.rv-muted {
  color: var(--muted);
}
/* Vote table: target | votes | ← | voter seats, columns aligned across rows. */
.rv-tally {
  display: grid;
  grid-template-columns: auto auto auto minmax(0, 1fr);
  column-gap: 0.5rem;
  row-gap: 0.25rem;
  align-items: center;
  padding: 0.125rem 0 0.375rem 1.25rem;
  font-size: 0.8125rem;
}
.rv-tally-row {
  display: contents;
}
.rv-votes {
  font-weight: 700;
  white-space: nowrap;
}
.rv-out {
  color: var(--red);
}
.rv-arrow {
  color: var(--muted);
}
.rv-voters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
}
.rv-seat {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 1.25rem;
  height: 1.25rem;
  padding: 0 0.25rem;
  border: 1px solid var(--border);
  border-radius: 0.625rem;
  background: var(--card);
  font-size: 0.6875rem;
  font-weight: 600;
}
.rv-seat-wolf {
  border-color: var(--red);
  background: var(--red);
  color: #fff;
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
