<template>
  <div class="rv" data-testid="player-review">
    <h2 class="rv-outcome serif">{{ outcome }}</h2>
    <p class="rv-team">
      我的身份：<span :class="{ 'rv-wolf': myIsWolf }">{{ myRole }}</span>
    </p>
    <div v-if="!lines.length" class="rv-empty">暂无记录</div>
    <p v-if="showLegend" class="rv-legend">
      投票中 <span class="rv-seat rv-seat-wolf" aria-hidden="true">1</span> 红色座位为你已知的狼人
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
              :class="{ 'rv-seat-wolf': isKnownWolf(p) }"
              :aria-label="`${p.seatIndex}号${isKnownWolf(p) ? ' 狼人' : ''}`"
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
  MyNightAction,
  PlayerDayEvent,
  PlayerNight,
  PlayerReview,
  PlayerRound,
  PlayerSeat,
  PlayerVote,
} from '@/types'
import { isWolfRole, roleDefinition } from '@/utils/roleDefinitions'

const props = defineProps<{ review: PlayerReview }>()

/** A run of text in a line; a seat the viewer knows to be a wolf renders red. */
interface Seg {
  text: string
  wolf?: boolean
  pill?: boolean
  muted?: boolean
}
interface TallyRow {
  target: Seg[]
  votes: string
  out: boolean
  abstain: boolean
  voters: PlayerSeat[]
}
type Line = { kind: 'title' | 'row' | 'end'; segs: Seg[] } | { kind: 'tally'; rows: TallyRow[] }

/** A known role settles the camp; otherwise only a check can, and often nothing does. */
function isKnownWolf(p: PlayerSeat): boolean {
  return p.role ? isWolfRole(p.role) : p.camp === 'WOLF'
}

const t = (text: string): Seg => ({ text })
const name = (p: PlayerSeat): Seg => ({
  text: `${p.seatIndex}号 ${p.nickname}`,
  wolf: isKnownWolf(p),
})
const seatBadge = (p: PlayerSeat): Seg => ({ text: `${p.seatIndex}号`, wolf: isKnownWolf(p) })
/** The role when it was known, the camp alone when a check gave only that, nothing when neither. */
const tag = (p: PlayerSeat): Seg[] => {
  if (p.role) return [t(`〔${roleDefinition(p.role)?.nameZh ?? p.role}〕`)]
  if (p.camp) return [t(p.camp === 'WOLF' ? '〔狼人〕' : '〔好人〕')]
  return []
}
const withTag = (p: PlayerSeat): Seg[] => [name(p), ...tag(p)]
const seatNums = (ps: PlayerSeat[]) => `${ps.map((p) => p.seatIndex).join('·')}号`
const joined = (parts: Seg[][], sep: string): Seg[] =>
  parts.flatMap((part, i) => (i ? [t(sep), ...part] : part))
const votes = (n: number) => `${Number.isInteger(n) ? n : n.toFixed(1)}票`
const pill = (text: string): Seg => ({ text, pill: true })

const outcome = computed(() => {
  if (!props.review.winner) return '比赛取消'
  return props.review.winner === 'WEREWOLF' ? '狼人胜' : '好人胜'
})
const myRole = computed(() => {
  const r = props.review.me.role
  return `${props.review.me.seatIndex}号 ${r ? (roleDefinition(r)?.nameZh ?? r) : '未知'}`
})
const myIsWolf = computed(() => isKnownWolf(props.review.me))

function actionSegs(a: MyNightAction): Seg[] {
  switch (a.type) {
    case 'WOLF': {
      const head: Seg = { text: `🐺 狼队（${seatNums(a.teammates)}） `, wolf: true }
      if (!a.target) return [head, t('空刀')]
      return [head, t(a.selfKill ? '自刀 ' : '刀 '), ...withTag(a.target)]
    }
    case 'SEER':
      return a.target
        ? [t('🔮 我查验 '), name(a.target), t(a.isWerewolf ? ' → 狼人' : ' → 好人')]
        : [t('🔮 我未查验')]
    case 'WITCH': {
      // The witch is shown the attack first, then what she did about it.
      const head = a.attacked
        ? [t('🧪 今晚 '), ...withTag(a.attacked), t(' 被刀')]
        : [t('🧪 今晚无人被刀')]
      if (a.antidote) return [...head, t('，我用解药救下')]
      if (a.poison) return [...head, t('，我毒 '), ...withTag(a.poison)]
      return [...head, t('，我未用药')]
    }
    case 'GUARD':
      return a.target ? [t('🛡 我守 '), ...withTag(a.target)] : [t('🛡 我空守')]
  }
}

function dawnSegs(d: PlayerNight['dawn']): Seg[] {
  const body = d.deaths.length
    ? [...joined(d.deaths.map(withTag), '、'), t(' 出局')]
    : [t('平安夜')]
  // Only the wolves and the witch ever get this line: the rest of the table hears a quiet night.
  const saved = d.saved ? [t('（'), ...withTag(d.saved), t(' 被救下）')] : []
  return [t('☀ 天亮 '), ...body, ...saved]
}

function nightLines(n: PlayerNight): Line[] {
  const rows = [n.action && actionSegs(n.action), dawnSegs(n.dawn)]
  return rows
    .filter((segs): segs is Seg[] => !!segs)
    .map((segs) => ({ kind: 'row' as const, segs }))
}

function voteHead(v: PlayerVote): Seg[] {
  if (v.kind === 'SHERIFF') {
    const head = pill('警长')
    if (!v.result) return [head, t('无警长')]
    if (!v.appointed) return [head, name(v.result), t(' 当选')]
    const why = v.tally.length ? '平票，房主指定 ' : '无人投票，房主指定 '
    return [head, t(why), name(v.result), t(' 当警长')]
  }
  const head = pill(v.round > 1 ? 'PK' : '放逐')
  return [head, ...(v.result ? [...withTag(v.result), t(' 被投出')] : [t('平票，无人出局')])]
}

function voteLines(v: PlayerVote): Line[] {
  const rows: TallyRow[] = v.tally.map((e) => ({
    target: [seatBadge(e.target), ...tag(e.target)],
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

function eventSegs(e: Exclude<PlayerDayEvent, PlayerVote>): Seg[] {
  switch (e.type) {
    case 'HUNTER_SHOT':
      // The table sees the shot, not whether it came from the 猎人 or the 黑狼王.
      return [t('🔫 '), ...withTag(e.hunter), t(' 开枪带走 '), ...withTag(e.target)]
    case 'SELF_DESTRUCT':
      return [
        t('💥 '),
        ...withTag(e.player),
        t(' 自爆'),
        ...(e.taken ? [t('，带走 '), ...withTag(e.taken)] : []),
      ]
    case 'IDIOT_REVEAL':
      return [t('🃏 '), name(e.player), t(' 白痴翻牌，免于出局')]
    case 'BADGE_HANDOVER':
      return [
        t('⭐ '),
        ...withTag(e.from),
        ...(e.to ? [t(' 移交警徽给 '), ...withTag(e.to)] : [t(' 撕毁警徽')]),
      ]
  }
}

function roundLines(r: PlayerRound): Line[] {
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

const lines = computed<Line[]>(() => {
  const ls = props.review.rounds.flatMap(roundLines)
  const died = props.review.diedOnDay
  if (died != null) ls.push({ kind: 'end', segs: [t(`你于第${died}天出局，复盘到此结束`)] })
  return ls
})
const showLegend = computed(
  () => lines.value.some((l) => l.kind === 'tally') && props.review.seats.some(isKnownWolf),
)
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
.rv-end {
  margin-top: 0.875rem;
  padding-top: 0.625rem;
  border-top: 1px solid var(--border-l);
  color: var(--muted);
  text-align: center;
  font-size: 0.8125rem;
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
