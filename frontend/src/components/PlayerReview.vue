<template>
  <div class="rv" data-testid="player-review">
    <h2 class="rv-outcome serif">{{ outcome }}</h2>
    <p class="rv-team">
      {{ $t('pr.myRole') }}<span :class="{ 'rv-wolf': myIsWolf }">{{ myRole }}</span>
    </p>
    <div v-if="!lines.length" class="rv-empty">{{ $t('roleHistory.empty') }}</div>
    <p v-if="showLegend" class="rv-legend">
      <i18n-t keypath="pr.legend" tag="span">
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
              :class="{ 'rv-seat-wolf': isKnownWolf(p) }"
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
          :class="{ 'rv-wolf': s.wolf, 'rv-pill': s.pill, 'rv-muted': s.muted, 'rv-gain': s.gain }"
          >{{ s.text }}</span
        >
      </div>
    </template>
    <template v-if="review.reward">
      <div class="rv-line rv-title">{{ $t('rv.rewardsTitle') }}</div>
      <div class="rv-rewards" data-testid="my-reward">
        <div v-for="(it, i) in review.reward.items" :key="i" class="rv-reward-row">
          <span>{{ itemLabel(it) }}</span>
          <span class="rv-reward-amount">+{{ it.amount }}</span>
        </div>
        <div class="rv-reward-row rv-reward-total">
          <span>{{ $t('pr.total') }}</span>
          <span class="rv-reward-amount">+{{ review.reward.total }}</span>
        </div>
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
  RewardItem,
  RewardReason,
} from '@/types'
import { t } from '@/i18n'
import { isWolfRole, roleName } from '@/utils/roleDefinitions'

const props = defineProps<{ review: PlayerReview }>()

/** A run of text in a line; a seat the viewer knows to be a wolf renders red. */
interface Seg {
  text: string
  wolf?: boolean
  pill?: boolean
  muted?: boolean
  /** credits this line earned, rendered in gold */
  gain?: boolean
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

const seg = (text: string): Seg => ({ text })
const name = (p: PlayerSeat): Seg => ({
  text: t('common.seatNick', { n: p.seatIndex, name: p.nickname }),
  wolf: isKnownWolf(p),
})
const seatBadge = (p: PlayerSeat): Seg => ({
  text: t('common.seat', { n: p.seatIndex }),
  wolf: isKnownWolf(p),
})
const seatAria = (p: PlayerSeat) =>
  t('common.seat', { n: p.seatIndex }) + (isKnownWolf(p) ? ` ${t('rv.wolf')}` : '')
/** The role when it was known, the camp alone when a check gave only that, nothing when neither. */
const tag = (p: PlayerSeat): Seg[] => {
  if (p.role) return [seg(t('rv.roleTag', { role: roleName(p.role) }))]
  if (p.camp) return [seg(p.camp === 'WOLF' ? t('pr.tagWolf') : t('pr.tagGood'))]
  return []
}
const withTag = (p: PlayerSeat): Seg[] => [name(p), ...tag(p)]
const seatNums = (ps: PlayerSeat[]) => ps.map((p) => p.seatIndex).join('·')
const joined = (parts: Seg[][], sep: string): Seg[] =>
  parts.flatMap((part, i) => (i ? [seg(sep), ...part] : part))
const votes = (n: number) => t('rv.votes', { n: Number.isInteger(n) ? n : n.toFixed(1) }, n)
const pill = (text: string): Seg => ({ text, pill: true })
const gain = (n: number): Seg => ({ text: ` +${n}`, gain: true })

const outcome = computed(() => {
  if (!props.review.winner) return t('result.cancelled')
  return props.review.winner === 'WEREWOLF' ? t('result.wolvesWin') : t('result.goodWin')
})
const myRole = computed(() => {
  const r = props.review.me.role
  return t('common.seatNick', {
    n: props.review.me.seatIndex,
    name: r ? roleName(r) : t('pr.unknown'),
  })
})
const myIsWolf = computed(() => isKnownWolf(props.review.me))

function actionSegs(a: MyNightAction): Seg[] {
  switch (a.type) {
    case 'WOLF': {
      const head: Seg = { text: t('pr.wolfHead', { seats: seatNums(a.teammates) }), wolf: true }
      if (!a.target) return [head, seg(t('rv.noKill'))]
      return [head, seg(a.selfKill ? t('rv.selfKill') : t('rv.kill')), ...withTag(a.target)]
    }
    case 'SEER':
      return a.target
        ? [
            seg(t('pr.seerCheck')),
            name(a.target),
            seg(a.isWerewolf ? t('rv.toWolf') : t('rv.toGood')),
          ]
        : [seg(t('pr.seerNone'))]
    case 'WITCH': {
      // The witch is shown the attack first, then what she did about it.
      const head = a.attacked
        ? [seg(t('pr.witchAttacked')), ...withTag(a.attacked), seg(t('pr.witchAttackedTail'))]
        : [seg(t('pr.witchNoAttack'))]
      if (a.antidote) return [...head, seg(t('pr.witchSaved'))]
      if (a.poison) return [...head, seg(t('pr.witchPoison')), ...withTag(a.poison)]
      return [...head, seg(t('pr.witchNone'))]
    }
    case 'GUARD':
      return a.target ? [seg(t('pr.guard')), ...withTag(a.target)] : [seg(t('pr.guardNone'))]
  }
}

function dawnSegs(d: PlayerNight['dawn']): Seg[] {
  const body = d.deaths.length
    ? [...joined(d.deaths.map(withTag), t('common.listSep')), seg(t('pr.dawnOut'))]
    : [seg(t('rv.peaceful'))]
  // Only the wolves and the witch ever get this line: the rest of the table hears a quiet night.
  const saved = d.saved
    ? [
        seg(t('common.parenOpen')),
        ...withTag(d.saved),
        seg(`${t('pr.savedTail')}${t('common.parenClose')}`),
      ]
    : []
  return [seg(t('rv.dawn')), ...body, ...saved]
}

function nightLines(n: PlayerNight): Line[] {
  const rows = [n.action && actionSegs(n.action), dawnSegs(n.dawn)]
  return rows
    .filter((segs): segs is Seg[] => !!segs)
    .map((segs) => ({ kind: 'row' as const, segs }))
}

function voteHead(v: PlayerVote): Seg[] {
  if (v.kind === 'SHERIFF') {
    const head = pill(t('rv.sheriff'))
    if (!v.result) return [head, seg(t('rv.noSheriff'))]
    if (!v.appointed) return [head, name(v.result), seg(t('rv.elected'))]
    const why = v.tally.length ? t('rv.tiedAppoint') : t('rv.noVoteAppoint')
    return [head, seg(why), name(v.result), seg(t('rv.asSheriff'))]
  }
  const head = pill(v.round > 1 ? t('rv.pk') : t('rv.exile'))
  return [
    head,
    ...(v.result ? [...withTag(v.result), seg(t('rv.votedOut'))] : [seg(t('rv.tieNoOut'))]),
  ]
}

/** The credit a single action earned, shown right where it was earned. */
function votePoints(v: PlayerVote): Seg[] {
  return v.points != null ? [gain(v.points)] : []
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
    rows.push({
      target: [seg(t('vote.abstain'))],
      votes: '',
      out: false,
      abstain: true,
      voters: v.abstain,
    })
  const lines: Line[] = [{ kind: 'row', segs: [...voteHead(v), ...votePoints(v)] }]
  if (rows.length) lines.push({ kind: 'tally', rows })
  return lines
}

function eventSegs(e: Exclude<PlayerDayEvent, PlayerVote>): Seg[] {
  switch (e.type) {
    case 'HUNTER_SHOT':
      // The table sees the shot, not whether it came from the 猎人 or the 黑狼王.
      return [seg('🔫 '), ...withTag(e.hunter), seg(t('rv.hunterShot')), ...withTag(e.target)]
    case 'SELF_DESTRUCT':
      return [
        seg('💥 '),
        ...withTag(e.player),
        seg(t('rv.selfDestruct')),
        ...(e.taken ? [seg(t('rv.tookAlong')), ...withTag(e.taken)] : []),
      ]
    case 'IDIOT_REVEAL':
      return [seg('🃏 '), name(e.player), seg(t('rv.idiotReveal'))]
    case 'BADGE_HANDOVER':
      return [
        seg('⭐ '),
        ...withTag(e.from),
        ...(e.to ? [seg(t('rv.badgeTo')), ...withTag(e.to)] : [seg(t('rv.badgeTorn'))]),
        ...(e.points != null ? [gain(e.points)] : []),
      ]
  }
}

function roundLines(r: PlayerRound): Line[] {
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

const lines = computed<Line[]>(() => {
  const ls = props.review.rounds.flatMap(roundLines)
  const died = props.review.diedOnDay
  if (died != null) ls.push({ kind: 'end', segs: [seg(t('pr.ended', { day: died }))] })
  return ls
})
const showLegend = computed(
  () => lines.value.some((l) => l.kind === 'tally') && props.review.seats.some(isKnownWolf),
)

const REASON_LABEL: Record<RewardReason, string> = {
  WIN: 'pr.reason.WIN',
  PARTICIPATION: 'pr.reason.PARTICIPATION',
  WOLF_SURVIVAL: 'pr.reason.WOLF_SURVIVAL',
  SHERIFF: 'pr.reason.SHERIFF',
  WOLF_VOTE: 'pr.reason.WOLF_VOTE',
}

/** Spells out where each number came from, so the score is not just asserted at the player. */
function itemLabel(it: RewardItem): string {
  if (it.reason === 'WOLF_SURVIVAL') {
    const days = t('pr.survived', { n: it.days })
    // The rate is dropped when it no longer multiplies out to the settled amount.
    return it.perDay != null
      ? t('pr.wolfLossRate', { days, rate: it.perDay })
      : t('pr.wolfLossDays', { days })
  }
  const label = t(REASON_LABEL[it.reason])
  return it.count != null && it.each != null && it.count > 1
    ? t('pr.times', { label, count: it.count, each: it.each })
    : label
}
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
.rv-reward-total {
  margin-top: 0.125rem;
  padding-top: 0.3rem;
  border-top: 1px solid var(--border-l);
  font-weight: 600;
}
.rv-gain {
  color: var(--gold);
  font-weight: 600;
}
</style>
