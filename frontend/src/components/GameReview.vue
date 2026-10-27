<template>
  <div class="rv" data-testid="game-review">
    <h2 class="rv-outcome serif">{{ outcome }}</h2>
    <p v-if="wolfTeam" class="rv-team">
      {{ $t('rv.wolfTeam') }}<span class="rv-wolf">{{ wolfTeam }}</span>
    </p>
    <div v-if="!lines.length" class="rv-empty">{{ $t('roleHistory.empty') }}</div>
    <p v-if="hasTally" class="rv-legend">
      <i18n-t keypath="rv.legend" tag="span">
        <template #seat><span class="rv-seat rv-seat-wolf" aria-hidden="true">1</span></template>
      </i18n-t>
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
              :aria-label="seatAria(p)"
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
    <template v-if="review.rewards.length">
      <div class="rv-line rv-title">{{ $t('rv.rewardsTitle') }}</div>
      <div class="rv-rewards" data-testid="review-rewards">
        <div v-for="r in review.rewards" :key="r.player.userId" class="rv-reward-row">
          <span :class="{ 'rv-wolf': isWolfRole(r.player.role) }">
            {{ $t('common.seatNick', { n: r.player.seatIndex, name: r.player.nickname }) }}
          </span>
          <span class="rv-reward-amount">+{{ r.amount }}</span>
        </div>
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
import { t } from '@/i18n'
import { isWolfRole, roleName } from '@/utils/roleDefinitions'

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

const CAUSE = {
  WOLF: 'rv.cause.WOLF',
  POISON: 'rv.cause.POISON',
  BOTH_SAVES: 'rv.cause.BOTH_SAVES',
} as const
const SAVED_BY = {
  GUARD: 'rv.saved.GUARD',
  ANTIDOTE: 'rv.saved.ANTIDOTE',
  PERK: 'rv.saved.PERK',
} as const
const WOLF_PLAY = { PUSH: 'rv.play.PUSH', BUS: 'rv.play.BUS' } as const

const seg = (text: string): Seg => ({ text })
const seat = (p: ReviewPlayer): Seg => ({
  text: t('common.seat', { n: p.seatIndex }),
  wolf: isWolfRole(p.role),
})
const name = (p: ReviewPlayer): Seg => ({
  text: t('common.seatNick', { n: p.seatIndex, name: p.nickname }),
  wolf: isWolfRole(p.role),
})
const roleTag = (p: ReviewPlayer): Seg => seg(t('rv.roleTag', { role: roleName(p.role) }))
const withRole = (p: ReviewPlayer): Seg[] => [name(p), roleTag(p)]
const seatNums = (ps: ReviewPlayer[]) => ps.map((p) => p.seatIndex).join('·')
const seatAria = (p: ReviewPlayer) =>
  t('common.seat', { n: p.seatIndex }) + (isWolfRole(p.role) ? ` ${t('rv.wolf')}` : '')
const joined = (parts: Seg[][], sep: string): Seg[] =>
  parts.flatMap((part, i) => (i ? [seg(sep), ...part] : part))
const votes = (n: number) => t('rv.votes', { n: Number.isInteger(n) ? n : n.toFixed(1) }, n)
const pill = (text: string): Seg => ({ text, pill: true })

const outcome = computed(() => {
  if (!props.review.winner) return t('result.cancelled')
  return props.review.winner === 'WEREWOLF' ? t('result.wolvesWin') : t('result.goodWin')
})

const wolfTeam = computed(() =>
  props.review.players
    .filter((p) => isWolfRole(p.role))
    // Tag the special wolves (白狼王 / 黑狼王).
    .map(
      (p) =>
        t('common.seatNick', { n: p.seatIndex, name: p.nickname }) +
        (p.role !== 'WEREWOLF' ? t('common.paren', { x: roleName(p.role) }) : ''),
    )
    .join(' · '),
)

function wolfSegs(w: ReviewNight['wolf']): Seg[] {
  const head: Seg = { text: t('rv.wolfHead', { seats: seatNums(w.actors) }), wolf: true }
  if (!w.target || w.action === 'SKIP') return [head, seg(t('rv.noKill'))]
  return [
    head,
    seg(w.action === 'SELF_KILL' ? t('rv.selfKill') : t('rv.kill')),
    ...withRole(w.target),
  ]
}

function seerSegs(s: NonNullable<ReviewNight['seer']>): Seg[] {
  const head = seg(t('rv.seerHead', { seat: t('common.seat', { n: s.actor.seatIndex }) }))
  if (s.dead) return [head, seg(t('rv.dead'))]
  if (!s.target) return [head, seg(t('rv.noCheck'))]
  return [
    head,
    seg(t('rv.check')),
    name(s.target),
    seg(s.isWerewolf ? t('rv.toWolf') : t('rv.toGood')),
  ]
}

function witchSegs(w: NonNullable<ReviewNight['witch']>): Seg[] {
  const head = seg(t('rv.witchHead', { seat: t('common.seat', { n: w.actor.seatIndex }) }))
  if (w.dead) return [head, seg(t('rv.dead'))]
  // One potion per night: WitchHandler rejects antidote + poison together.
  if (w.antidote) return [head, seg(t('rv.save')), name(w.antidote)]
  if (w.poison) return [head, seg(t('rv.poison')), ...withRole(w.poison)]
  return [head, seg(t('rv.noPotion'))]
}

function guardSegs(g: NonNullable<ReviewNight['guard']>): Seg[] {
  const head = seg(t('rv.guardHead', { seat: t('common.seat', { n: g.actor.seatIndex }) }))
  if (g.dead) return [head, seg(t('rv.dead'))]
  return g.target ? [head, seg(t('rv.guard')), name(g.target)] : [head, seg(t('rv.noGuard'))]
}

function dawnSegs(d: ReviewNight['dawn']): Seg[] {
  const segs = d.deaths.length
    ? joined(
        d.deaths.map((x) => [...withRole(x.player), seg(` ${t(CAUSE[x.cause])}`)]),
        t('common.comma'),
      )
    : [seg(t('rv.peaceful'))]
  if (d.saved)
    segs.push(
      seg(t('common.parenOpen')),
      name(d.saved.player),
      seg(` ${t(SAVED_BY[d.saved.by])}${t('common.parenClose')}`),
    )
  return [seg(t('rv.dawn')), ...segs]
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
    const head = pill(t('rv.sheriff'))
    if (!v.result) return [head, seg(t('rv.noSheriff'))]
    if (!v.appointed) return [head, name(v.result), seg(t('rv.elected'))]
    const why = v.tally.length ? t('rv.tiedAppoint') : t('rv.noVoteAppoint')
    return [head, seg(why), name(v.result), seg(t('rv.asSheriff'))]
  }
  const head = pill(v.round > 1 ? t('rv.pk') : t('rv.exile'))
  const play = v.wolfPlay
    ? [{ text: t('rv.wolfPlay', { play: t(WOLF_PLAY[v.wolfPlay]) }), muted: true }]
    : []
  return [
    head,
    ...(v.result ? [...withRole(v.result), seg(t('rv.votedOut'))] : [seg(t('rv.tieNoOut'))]),
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
    rows.push({
      target: [seg(t('vote.abstain'))],
      votes: '',
      out: false,
      abstain: true,
      voters: v.abstain,
    })
  const lines: Line[] = [{ kind: 'row', segs: voteHead(v) }]
  if (rows.length) lines.push({ kind: 'tally', rows })
  return lines
}

function eventSegs(e: Exclude<ReviewDayEvent, ReviewVote>): Seg[] {
  switch (e.type) {
    case 'HUNTER_SHOT':
      // The shooter is a HUNTER or a BLACK_WOLF_KING; the review is post-game, so name it.
      return [seg('🔫 '), ...withRole(e.hunter), seg(t('rv.hunterShot')), ...withRole(e.target)]
    case 'SELF_DESTRUCT':
      return [
        seg('💥 '),
        ...withRole(e.player),
        seg(t('rv.selfDestruct')),
        ...(e.taken ? [seg(t('rv.tookAlong')), ...withRole(e.taken)] : []),
      ]
    case 'IDIOT_REVEAL':
      return [seg('🃏 '), name(e.player), seg(t('rv.idiotReveal'))]
    case 'BADGE_HANDOVER':
      return [
        seg('⭐ '),
        ...withRole(e.from),
        ...(e.to ? [seg(t('rv.badgeTo')), ...withRole(e.to)] : [seg(t('rv.badgeTorn'))]),
      ]
  }
}

function roundLines(r: ReviewRound): Line[] {
  const lines: Line[] = []
  if (r.night)
    lines.push(
      { kind: 'title', segs: [seg(t('rv.nightN', { day: r.day }))] },
      ...nightLines(r.night),
    )
  if (r.events.length) {
    lines.push({ kind: 'title', segs: [seg(t('rv.dayN', { day: r.day }))] })
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

.rv-rewards {
  padding: 0.25rem 0 0 1.25rem;
  font-size: 0.8125rem;
}
.rv-reward-row {
  display: flex;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.15rem 0;
}
.rv-reward-amount {
  color: var(--gold);
  font-weight: 600;
  white-space: nowrap;
}
</style>
