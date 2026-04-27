/**
 * 12-player full-game flow evidence — host drives every UI decision via
 * real clicks; bots sign in and act through REST (via scripts/act.sh), the
 * same way a second device would. No shortcuts via test-only endpoints.
 *
 * Two scenarios:
 *   1. CLASSIC (easy) — villagers win by voting out every wolf.
 *   2. HARD_MODE — wolves win; day-1 voting sends the elected sheriff
 *      (seer) to elimination so the badge-handover sub-phase fires and is
 *      captured in a screenshot.
 *
 * Each test step attaches a composite screenshot (host + per-role pages) to
 * the Playwright report so the evidence is viewable at:
 *   docs/e2e-evidence/<run>/index.html  (after npx playwright show-report)
 */
import {expect, type Page, test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act, actName, type RoleName, sheriff} from './helpers/shell-runner'
import {verifyAllBrowsersPhase} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'
import {
  readAlivePlayerIds,
  readHostSeat,
  readHostUserId,
  readUnvotedAlivePlayerIds,
  waitForVotingSubPhase,
} from './helpers/state-polling'

/** A special-role player resolved to either a bot OR the host. */
interface RolePlayer {
  seat: number
  nick: string
  isHost: boolean
  userId: string
}

/**
 * `expect(value).not.toBeNull()` doesn't narrow via TypeScript flow analysis,
 * so callers still see `T | null` after the assertion. This helper uses a TS
 * assertion signature so subsequent reads of `value` are non-null without
 * resorting to a non-null bang at every call site.
 */
function assertNonNull<T>(value: T | null | undefined, msg: string): asserts value is T {
  expect(value, msg).not.toBeNull()
  expect(value, msg).not.toBeUndefined()
}

/**
 * Resolve `role` to its bot OR to the host (whoever holds it). Returns null
 * only if neither holds the role (impossible if the role is in the kit).
 */
async function resolveRolePlayer(
  ctx: GameContext,
  role: RoleName,
): Promise<RolePlayer | null> {
  if (ctx.isHostRole(role)) {
    const hostSeat = await readHostSeat(ctx.hostPage, ctx.gameId)
    const hostUserId = await readHostUserId(ctx.hostPage)
    if (hostSeat == null || hostUserId == null) return null
    return { seat: hostSeat, nick: 'Host', isHost: true, userId: hostUserId }
  }
  const bot = (ctx.roleMap[role] ?? []).find((b) => b.nick !== 'Host')
  if (!bot) return null
  return { seat: bot.seat, nick: bot.nick, isHost: false, userId: bot.userId }
}

const BROWSER_ROLES: RoleName[] = ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER']

// ── Shared helpers ───────────────────────────────────────────────────────────

function tryAct(...args: Parameters<typeof act>): boolean {
  try {
    const out = act(...args)
    const rejected = out.includes('rejected') || out.includes('fail')
    if (rejected) {
      // Silent rejections are the #1 cause of coroutine stalls. Surface them
      // in CI logs so the next failing run points straight at the culprit.
      // eslint-disable-next-line no-console
      console.warn(`[tryAct rejected] args=${JSON.stringify(args)} output=\n${out}`)
    }
    return !rejected
  } catch (e) {
    // eslint-disable-next-line no-console
    console.warn(`[tryAct threw] args=${JSON.stringify(args)} err=${(e as Error).message}`)
    return false
  }
}

/**
 * Poll the backend via the host browser (it already has the JWT in
 * localStorage) until the game's night sub-phase matches `target`, then
 * return. Without this, bot actions fire faster than the role-loop coroutine
 * advances, land in the wrong sub-phase, and are rejected — stalling the
 * whole night.
 */
async function waitForSubPhase(
  hostPage: Page,
  gameId: string,
  target: string,
  timeoutMs = 30_000,
): Promise<boolean> {
  // Same CI scaling rationale as assertions.ts: slow GH runners need more slack.
  const effective = process.env.CI ? timeoutMs * 2 : timeoutMs
  const deadline = Date.now() + effective
  while (Date.now() < deadline) {
    const state = await hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      if (!token) return null
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return null
      return res.json()
    }, gameId)
    const sp = state?.nightPhase?.subPhase
    const phase = state?.phase
    if (sp === target) return true
    // Short-circuit if game already moved past NIGHT (e.g., already in DAY)
    if (phase && phase !== 'NIGHT' && target !== phase) return false
    await hostPage.waitForTimeout(300)
  }
  return false
}

/**
 * Push the sheriff election through on the host — candidates campaign, speeches,
 * votes, reveal. Returns the role the bots voted for (so callers know who holds
 * the badge once the badge-award sub-phase completes).
 */
/**
 * Poll the backend for a sheriff sub-phase transition. Unlike waitForSubPhase,
 * which reads `nightPhase.subPhase`, sheriff state lives at
 * `state.sheriffElection.subPhase` (confirmed: scripts/act.sh:357,
 * SheriffService.kt transitions SIGNUP→SPEECH→VOTING→RESULT/TIED).
 */
async function waitForSheriffSubPhase(
  hostPage: Page,
  gameId: string,
  target: string,
  timeoutMs = 15_000,
): Promise<boolean> {
  const effective = process.env.CI ? timeoutMs * 2 : timeoutMs
  const deadline = Date.now() + effective
  while (Date.now() < deadline) {
    const sub = await hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      if (!token) return null
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return null
      const state = await res.json()
      return state?.sheriffElection?.subPhase ?? null
    }, gameId)
    if (sub === target) return true
    await hostPage.waitForTimeout(200)
  }
  return false
}

/**
 * Host drives all sheriff speeches via a single UI button click per speaker.
 * The `sheriff-advance-speech` button is rendered ONLY when
 * `election.subPhase === 'SPEECH'` (SheriffElection.vue:115,128,182); it is
 * not in the DOM once SheriffService.kt auto-transitions SPEECH→VOTING after
 * the last speaker. Loop exit condition therefore reads both the backend
 * sub-phase (/state) AND the button's DOM presence — either signals done.
 *
 * Replaces the prior `for (12) { act('SHERIFF_ADVANCE_SPEECH') }` loop, which
 * (a) iterated more times than needed and (b) through `act.sh`'s default
 * PLAYER_SEL="all" fanned each call out to 11 non-host bots, producing 1,500+
 * "Only host can advance speeches" rejections per CI run (see
 * docs/ci-tests-issues.md for the pre-fix rejection breakdown).
 */
async function advanceAllSheriffSpeeches(hostPage: Page, gameId: string): Promise<void> {
  // Safety cap — 12p games have at most 12 speakers. Real exit condition is
  // the sub-phase leaving SPEECH (backend auto-advances after last speaker).
  const maxIterations = 20
  for (let i = 0; i < maxIterations; i++) {
    const sub = await hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      if (!token) return null
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return null
      const state = await res.json()
      return state?.sheriffElection?.subPhase ?? null
    }, gameId)
    if (sub !== 'SPEECH') return

    const advanceBtn = hostPage.getByTestId('sheriff-advance-speech')
    // Wait (don't just probe) for the button to be visible. During speaker
    // transitions Vue re-renders the SheriffElection component and the
    // button is briefly detached; `isVisible()` returns false for that tick
    // and would incorrectly bail. `waitFor({state: 'visible'})` waits up to
    // the timeout for it to settle.
    const appeared = await advanceBtn
      .waitFor({ state: 'visible', timeout: 5_000 })
      .then(() => true)
      .catch(() => false)
    if (!appeared) {
      // eslint-disable-next-line no-console
      console.warn(
        `[sheriff] advance button did not reappear after 5s (iter ${i}, subPhase=${sub}) — will re-poll`,
      )
      // Don't early-return; if sub is still SPEECH on the next iteration,
      // try once more. Only exit the whole helper if iterations run out.
      continue
    }

    await advanceBtn.click()
    // Small settle so the next state poll observes the post-click state.
    await hostPage.waitForTimeout(400)
  }
  // eslint-disable-next-line no-console
  console.warn('[sheriff] advanceAllSpeeches hit maxIterations — check backend speaker count')
}

async function readSheriffSubPhase(hostPage: Page, gameId: string): Promise<string | null> {
  return hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return null
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return null
    const state = await res.json()
    return state?.sheriffElection?.subPhase ?? null
  }, gameId)
}

async function runSheriffElection(ctx: GameContext, pickNickCampaign: string[]): Promise<void> {
  const hostPage = ctx.hostPage
  const gameId = ctx.gameId
  await verifyAllBrowsersPhase(ctx.pages, 'SHERIFF_ELECTION', 20_000)

  // eslint-disable-next-line no-console
  console.warn(
    `[sheriff] entered runSheriffElection — candidates=${JSON.stringify(pickNickCampaign)} ` +
      `initial subPhase=${await readSheriffSubPhase(hostPage, gameId)}`,
  )

  // Campaign: legitimate per-candidate fan-out; stays as script calls.
  for (const nick of pickNickCampaign) {
    try {
      sheriff('campaign', { player: nick, room: ctx.roomCode })
    } catch (e) {
      // eslint-disable-next-line no-console
      console.warn(`[sheriff] campaign ${nick} threw: ${(e as Error).message}`)
    }
  }
  // eslint-disable-next-line no-console
  console.warn(`[sheriff] after campaign scripts — subPhase=${await readSheriffSubPhase(hostPage, gameId)}`)

  // Host UI click: SIGNUP → SPEECH via `sheriff-start-campaign`. The button
  // is disabled while `runningCandidates.length === 0` (SheriffElection.vue:64)
  // so poll briefly for enabled state to let the campaign scripts' STOMP
  // events land on the host page.
  const startBtn = hostPage.getByTestId('sheriff-start-campaign')
  const startVisible = await startBtn
    .waitFor({ state: 'visible', timeout: 10_000 })
    .then(() => true)
    .catch(() => false)
  const startEnabled = await expect(startBtn)
    .toBeEnabled({ timeout: 10_000 })
    .then(() => true)
    .catch(() => false)
  // eslint-disable-next-line no-console
  console.warn(`[sheriff] sheriff-start-campaign: visible=${startVisible} enabled=${startEnabled}`)
  if (startVisible && startEnabled) {
    await startBtn.click()
    // eslint-disable-next-line no-console
    console.warn('[sheriff] clicked sheriff-start-campaign')
  }

  // Wait for backend to register the SIGNUP→SPEECH transition.
  const reachedSpeech = await waitForSheriffSubPhase(hostPage, gameId, 'SPEECH', 15_000)
  // eslint-disable-next-line no-console
  console.warn(`[sheriff] reachedSpeech=${reachedSpeech} current=${await readSheriffSubPhase(hostPage, gameId)}`)
  await advanceAllSheriffSpeeches(hostPage, gameId)
  // eslint-disable-next-line no-console
  console.warn(`[sheriff] advanceAllSpeeches done — subPhase=${await readSheriffSubPhase(hostPage, gameId)}`)

  const reachedVoting = await waitForSheriffSubPhase(hostPage, gameId, 'VOTING', 15_000)
  // eslint-disable-next-line no-console
  console.warn(`[sheriff] reachedVoting=${reachedVoting}`)

  // Voting: bots vote for the first campaigner via sheriff.sh (legitimate
  // per-voter fan-out). Host doesn't vote through the script (the script
  // iterates bots only, not the host's browser-owned session), so the
  // frontend's `election.allVoted` stays false (SheriffElection.vue:279)
  // and sheriff-reveal-result remains disabled. Host must also register
  // a vote/abstain for allVoted to flip true.
  //
  // Note: the backend's revealResult() actually only requires subPhase=VOTING
  // (SheriffService.kt:247-252). The old test bypassed this via act('SHERIFF_
  // REVEAL_RESULT') hitting the REST API directly with the host token. The
  // UI-click path respects the stricter frontend allVoted gate, which also
  // more accurately models real-user behavior.
  if (pickNickCampaign.length > 0) {
    try {
      sheriff('vote', { target: pickNickCampaign[0], room: ctx.roomCode })
    } catch (e) {
      // eslint-disable-next-line no-console
      console.warn(`[sheriff] vote threw: ${(e as Error).message}`)
    }
    // Backend rejects self-votes (SheriffService.kt:385-386 "Cannot vote for
    // yourself"). When a candidate is the target of the fan-out sheriff.sh
    // vote, they can't vote for themselves — so they must abstain instead
    // or `allVoted` never reaches true. Run abstain for every candidate in
    // the election so the denominator is satisfied regardless of which bots
    // campaigned.
    for (const candidateNick of pickNickCampaign) {
      try {
        sheriff('abstain', { player: candidateNick, room: ctx.roomCode })
      } catch (e) {
        // eslint-disable-next-line no-console
        console.warn(`[sheriff] candidate-abstain ${candidateNick} threw: ${(e as Error).message}`)
      }
    }
  }

  // Host abstains via UI so allVoted becomes true. (Host is never a
  // candidate in this test — pickNickCampaign filters out the host.)
  const abstainBtn = hostPage.getByTestId('sheriff-abstain')
  const abstainAppeared = await abstainBtn
    .waitFor({ state: 'visible', timeout: 10_000 })
    .then(() => true)
    .catch(() => false)
  // eslint-disable-next-line no-console
  console.warn(`[sheriff] sheriff-abstain: visible=${abstainAppeared}`)
  if (abstainAppeared) {
    await abstainBtn.click()
    // eslint-disable-next-line no-console
    console.warn('[sheriff] clicked sheriff-abstain')
  }

  // Diagnostic: read voteProgress so we see the actual vote count vs the
  // eligible-voter denominator. If submitted < total we know bots' script
  // votes didn't all land (retry / candidate filtering bug), not a bug in
  // the host-abstain click.
  const progressAfter = await hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return null
    const state = await res.json()
    return {
      voteProgress: state?.sheriffElection?.voteProgress,
      allVoted: state?.sheriffElection?.allVoted,
      candidates: state?.sheriffElection?.candidates,
    }
  }, gameId)
  // eslint-disable-next-line no-console
  console.warn(`[sheriff] voteProgress=${JSON.stringify(progressAfter)}`)

  // Host UI click: VOTING → RESULT/TIED via `sheriff-reveal-result`.
  const revealBtn = hostPage.getByTestId('sheriff-reveal-result')
  const revealVisible = await revealBtn
    .waitFor({ state: 'visible', timeout: 10_000 })
    .then(() => true)
    .catch(() => false)
  const revealEnabled = await expect(revealBtn)
    .toBeEnabled({ timeout: 15_000 })
    .then(() => true)
    .catch(() => false)
  // eslint-disable-next-line no-console
  console.warn(
    `[sheriff] sheriff-reveal-result: visible=${revealVisible} enabled=${revealEnabled} ` +
      `subPhase=${await readSheriffSubPhase(hostPage, gameId)}`,
  )
  if (revealVisible && revealEnabled) {
    await revealBtn.click()
    // eslint-disable-next-line no-console
    console.warn('[sheriff] clicked sheriff-reveal-result')
  }
  await hostPage.waitForTimeout(1_500)
  // eslint-disable-next-line no-console
  console.warn(`[sheriff] leaving runSheriffElection — subPhase=${await readSheriffSubPhase(hostPage, gameId)}`)
}

/**
 * Drive one night through role actions. targetSeat = the seat wolves kill.
 * seerCheckSeat = optional target for seer's check. Returns silently; rejected
 * actions are ignored (roles may be dead / skipped).
 */
async function completeNight(ctx: GameContext, targetSeat: number, seerCheckSeat?: number): Promise<void> {
  const wolfBots = ctx.roleMap.WEREWOLF ?? []
  const seerBots = ctx.roleMap.SEER ?? []
  const witchBots = ctx.roleMap.WITCH ?? []
  const guardBots = ctx.roleMap.GUARD ?? []
  const hostPage = ctx.hostPage
  const gameId = ctx.gameId

  // Fetch live roster so role actors reflect prior-day eliminations. roleMap
  // is populated once at game start and never updates when players die —
  // without this filter, picking `wolfBots[0]` on night 2+ would hand us a
  // dead wolf (e.g. the one the village voted out on D1), the action rejects
  // silently, and the role-loop coroutine stalls forever waiting for an
  // action that was never dispatched. Same risk for seer/witch/guard.
  //
  // readAlivePlayerIds also underpins the target-alive precondition: if the
  // caller's `targetSeat` refers to a player already killed on a prior
  // night, WOLF_KILL rejects with "Target not alive" — we fall back to any
  // alive non-wolf seat to keep the night progressing.
  const aliveIds = await readAlivePlayerIds(hostPage, gameId)
  const isAlive = (uid: string): boolean => aliveIds.size === 0 || aliveIds.has(uid)

  const wolfBot = wolfBots.find((b) => isAlive(b.userId))
  const seerBot = seerBots.find((b) => isAlive(b.userId))
  const witchBot = witchBots.find((b) => isAlive(b.userId))
  const guardBot = guardBots.find((b) => isAlive(b.userId))

  // Verify WOLF_KILL target is alive; if not, re-target any alive non-wolf
  // seat. Avoids the "villagerSeats rotation hands wolves an already dead
  // seat on a later round" stall documented in the 2026-04-24 walkthrough.
  // Resolve via the live game state (not ctx.allBots) — the host is in
  // state.players but not in ctx.allBots, so a host-seated kill target
  // (e.g. host=GUARD on N1, host=WITCH on N2) would otherwise fall through
  // to the first non-host alive bot.
  const wolfSeats = new Set(wolfBots.map((b) => b.seat))
  const seatToUserId = await hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return {} as Record<string, string>
    const res = await fetch(`/api/game/${id}/state`, { headers: { Authorization: `Bearer ${token}` } })
    if (!res.ok) return {} as Record<string, string>
    const state = await res.json()
    return Object.fromEntries(
      ((state?.players ?? []) as Array<{ seatIndex: number; userId: string; isAlive?: boolean }>)
        .filter((p) => p.isAlive !== false)
        .map((p) => [String(p.seatIndex), p.userId]),
    )
  }, gameId)
  const targetUserId = seatToUserId[String(targetSeat)] ?? null
  const targetIsAlive = targetUserId != null && isAlive(targetUserId)
  const resolvedTargetSeat = targetIsAlive
    ? targetSeat
    : (Object.entries(seatToUserId).find(
        ([s, uid]) => !wolfSeats.has(Number(s)) && isAlive(uid),
      )?.[0] ?? targetSeat)
  const resolvedTargetSeatNum = typeof resolvedTargetSeat === 'string' ? Number(resolvedTargetSeat) : resolvedTargetSeat

  // ── WEREWOLF_PICK ──
  // Only fire the kill if the backend actually reached the WEREWOLF_PICK
  // sub-phase. waitForSubPhase returns false when the game already left
  // NIGHT (e.g. someone won at post-night-resolve before this call ran)
  // or the gate timed out — in either case there's nothing for the role
  // actor to do, and firing anyway produces a "Not in WEREWOLF_PICK
  // sub-phase" rejection in the CI log.
  const reachedWolfPick = await waitForSubPhase(hostPage, gameId, 'WEREWOLF_PICK', 20_000)
  if (!reachedWolfPick) return
  if (wolfBot) {
    tryAct('WOLF_KILL', actName(wolfBot), { target: String(resolvedTargetSeatNum), room: ctx.roomCode })
  } else {
    // host is the sole alive wolf — drive via host UI
    await hostPage.locator('.player-grid .slot-alive').first().click().catch(() => {})
    await hostPage.getByTestId('wolf-confirm-kill').click().catch(() => {})
  }

  // ── SEER_PICK ──
  if (seerBots.length > 0) {
    const reached = await waitForSubPhase(hostPage, gameId, 'SEER_PICK', 15_000)
    if (reached && seerBot) {
      // If the caller's seerCheckSeat is dead (or not provided), probe any
      // alive non-seer seat. The seer's own identity is handled below via
      // the self-check prohibition (game-rules memory).
      const candidateSeat = seerCheckSeat ?? 1
      const candidateBot = ctx.allBots.find((b) => b.seat === candidateSeat && isAlive(b.userId))
      const checkSeat = candidateBot && candidateBot.userId !== seerBot.userId
        ? candidateSeat
        : ctx.allBots.find((b) => b.userId !== seerBot.userId && b.nick !== 'Host' && isAlive(b.userId))?.seat ?? candidateSeat
      tryAct('SEER_CHECK', actName(seerBot), { target: String(checkSeat), room: ctx.roomCode })
      // SEER_RESULT next
      await waitForSubPhase(hostPage, gameId, 'SEER_RESULT', 10_000)
      tryAct('SEER_CONFIRM', actName(seerBot), { room: ctx.roomCode })
    }
  }

  // ── WITCH_ACT ──
  if (witchBots.length > 0) {
    const reached = await waitForSubPhase(hostPage, gameId, 'WITCH_ACT', 15_000)
    if (reached && witchBot) {
      tryAct('WITCH_ACT', actName(witchBot), {
        payload: '{"useAntidote":false}',
        room: ctx.roomCode,
      })
    }
  }

  // ── GUARD_PICK ──
  if (guardBots.length > 0) {
    const reached = await waitForSubPhase(hostPage, gameId, 'GUARD_PICK', 15_000)
    if (reached && guardBot) {
      tryAct('GUARD_SKIP', actName(guardBot), { room: ctx.roomCode })
    }
  }
}

/**
 * Drive one day: host reveals the night result via UI, then opens voting.
 * Every alive non-target voter votes for [targetNickOrSeat]. Host reveals tally;
 * host clicks continue. Handles the optional BADGE_HANDOVER sub-phase when the
 * sheriff is voted out: captures a dedicated screenshot and passes the badge.
 *
 * When the sheriff is expected to be voted out, pass `sheriffPage` — the
 * browser page logged in as the player wearing the badge. Only that page sees
 * the pass-badge / destroy-badge buttons (`isEliminatedSheriff` is true on
 * that page only — VotingPhase.vue:601). Without the page, badge handover
 * never resolves and the game stays parked at DAY_VOTING/BADGE_HANDOVER until
 * the test times out (root cause of the pre-fix HARD_MODE 6-round timeout —
 * verified locally 2026-04-27 in /tmp/werewolf-e2e-backend.log: every
 * subsequent WOLF_KILL was REJECTED with "No active night phase").
 */
async function completeDay(
  ctx: GameContext,
  testInfo: Parameters<typeof captureSnapshot>[1],
  targetSeat: number,
  evidenceLabel: string,
  sheriffPage?: Page,
  badgeRecipientSeat?: number,
): Promise<void> {
  const hostPage = ctx.hostPage
  const gameId = ctx.gameId

  // Wait for night to resolve → day-reveal-result becomes visible. Night role
  // loop takes ~10-15s under test timings (wolf+seer+seer_result+witch+guard,
  // each with open_eyes / await-action / close_eyes / cooldown / gap).
  const revealBtn = hostPage.getByTestId('day-reveal-result')
  await revealBtn.waitFor({ state: 'visible', timeout: 30_000 }).catch(() => {})
  if (await revealBtn.isVisible().catch(() => false)) {
    await revealBtn.click()
    await hostPage.waitForTimeout(800)
  }
  await captureSnapshot(ctx.pages, testInfo, `${evidenceLabel}-day-result-revealed`)

  // Host starts vote
  const startVoteBtn = hostPage.getByTestId('day-start-vote')
  if (await startVoteBtn.isVisible({ timeout: 8_000 }).catch(() => false)) {
    await startVoteBtn.click()
  }
  await captureSnapshot(ctx.pages, testInfo, `${evidenceLabel}-day-voting-opened`)

  // Resolve target (if any) from the current alive roster. Unresolved target
  // → everyone abstains. Use the live game state's `players` list (not
  // `ctx.allBots`) — the host is in `players` but not in `allBots`, so a
  // host-seated target (e.g. the seer-as-sheriff when the host rolled SEER)
  // would otherwise resolve to undefined and the fan-out would silently
  // abstain instead of voting.
  const aliveIds = await readAlivePlayerIds(hostPage, gameId)
  const targetUserId = targetSeat >= 0
    ? await hostPage.evaluate(
        async ({ id, seat }) => {
          const token = localStorage.getItem('jwt')
          if (!token) return null as string | null
          const res = await fetch(`/api/game/${id}/state`, {
            headers: { Authorization: `Bearer ${token}` },
          })
          if (!res.ok) return null as string | null
          const state = await res.json()
          const match = ((state?.players ?? []) as Array<{ seatIndex: number; userId: string }>)
            .find((p) => p.seatIndex === seat)
          return match?.userId ?? null
        },
        { id: gameId, seat: targetSeat },
      )
    : null
  const targetBot = targetUserId && aliveIds.has(targetUserId) ? { seat: targetSeat, userId: targetUserId } : undefined
  // eslint-disable-next-line no-console
  console.warn(
    `[completeDay] targetSeat=${targetSeat} → targetUserId=${targetUserId ?? 'null'} alive=${targetUserId ? aliveIds.has(targetUserId) : false}`,
  )

  // Vote cycle — up to 3 rounds (initial + 2 revotes).
  //
  // For each round:
  //   1. Gate on the backend sub-phase before firing any SUBMIT_VOTE. Previous
  //      implementation fanned out to every alive voter with 3× retries per
  //      call, which stalled the spec — a 12-player game revoting 3 times
  //      could burn ~100s on rejected votes alone. The gate skips that.
  //   2. Fan out only to non-host, alive, UNVOTED players via
  //      readUnvotedAlivePlayerIds. The helper now returns empty outside
  //      VOTING/RE_VOTING, so if the backend isn't in a voting sub-phase the
  //      fan-out is skipped entirely.
  //   3. Host votes via act('Host', ...) — setupGame saves a hostToken in the
  //      shell state file, so act.sh can use it the same way it uses bot
  //      tokens.
  //   4. Reveal tally via the host browser button. Break out once the backend
  //      leaves VOTING/RE_VOTING (i.e. landed on VOTE_RESULT / BADGE_HANDOVER
  //      / HUNTER_SHOOT / post-elimination GAME_OVER).
  for (let attempt = 0; attempt < 3; attempt++) {
    const expected = attempt === 0 ? 'VOTING' : 'RE_VOTING'
    const reached = await waitForVotingSubPhase(hostPage, gameId, expected, 15_000)
    if (!reached) break

    const unvoted = await readUnvotedAlivePlayerIds(hostPage, gameId)
    const hostId = await readHostUserId(hostPage)
    const voteOpts: { target?: string; room: string } = targetBot
      ? { target: String(targetSeat), room: ctx.roomCode }
      : { room: ctx.roomCode }

    if (hostId && unvoted.has(hostId)) {
      tryAct('SUBMIT_VOTE', 'Host', voteOpts)
    }
    for (const bot of ctx.allBots) {
      if (bot.nick === 'Host' || bot.userId === hostId) continue
      if (!unvoted.has(bot.userId)) continue
      tryAct('SUBMIT_VOTE', bot.nick, voteOpts)
    }
    await hostPage.waitForTimeout(1_500)

    const revealTallyBtn = hostPage.getByTestId('voting-reveal')
    await revealTallyBtn.waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {})
    await expect(revealTallyBtn).toBeEnabled({ timeout: 15_000 }).catch(() => {})
    if (await revealTallyBtn.isVisible().catch(() => false)) {
      await revealTallyBtn.click()
      await hostPage.waitForTimeout(1_500)
    } else {
      tryAct('VOTING_REVEAL_TALLY', 'Host', { room: ctx.roomCode })
      await hostPage.waitForTimeout(1_500)
    }
    await captureSnapshot(ctx.pages, testInfo, `${evidenceLabel}-day-tally-revealed-r${attempt + 1}`)

    // If the backend has left voting (VOTE_RESULT / BADGE_HANDOVER /
    // HUNTER_SHOOT) or the top-level phase changed (e.g. GAME_OVER), stop.
    const leftVoting = await waitForVotingSubPhase(hostPage, gameId, 'VOTE_RESULT', 5_000)
    if (leftVoting) break
  }

  // Did BADGE_HANDOVER fire? Read the backend sub-phase directly — DOM-only
  // detection misses fast transitions on slow runners.
  const subPhaseAfterReveal = await hostPage.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return null
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return null
    return (await res.json())?.votingPhase?.subPhase ?? null
  }, gameId)

  if (subPhaseAfterReveal === 'BADGE_HANDOVER') {
    await captureSnapshot(ctx.pages, testInfo, `${evidenceLabel}-badge-handover-triggered`)
    // Resolve the eliminated sheriff's page. Caller may have supplied it
    // explicitly (HARD_MODE knows the seer is sheriff up-front) but in
    // CLASSIC the elected sheriff depends on who campaigns + who wins (e.g.
    // when host=SEER, the wolf becomes sole candidate and wins; voting that
    // wolf out then triggers BADGE_HANDOVER on the wolf bot's own page).
    // Auto-resolve in priority order:
    //   1. eliminated == host → use hostPage.
    //   2. eliminated is the bot tracked in ctx.bots[role] → use that role's page.
    //   3. eliminated is a different bot of a tracked role → match by userId
    //      across roleMap, but only if we have a page logged in as THAT bot.
    //   4. None match → log + skip (game stalls — caller must open a page).
    const hostUserId = await readHostUserId(hostPage)
    let resolvedSheriffPage = sheriffPage
    if (!resolvedSheriffPage && targetUserId) {
      if (targetUserId === hostUserId) {
        resolvedSheriffPage = hostPage
      } else {
        for (const [role, bot] of ctx.bots) {
          if (bot.userId === targetUserId) {
            resolvedSheriffPage = ctx.pages.get(role)
            break
          }
        }
      }
      // eslint-disable-next-line no-console
      console.warn(
        `[completeDay] auto-resolved sheriffPage for eliminated userId=${targetUserId} → ` +
          `${resolvedSheriffPage ? 'found' : 'NOT FOUND in ctx.pages or hostPage'}`,
      )
    }
    // Default the badge recipient to host's seat if the caller didn't
    // specify one. This avoids cascading BADGE_HANDOVERs in CLASSIC: when
    // bot-A sheriff is voted out and we click "first alive slot" on bot-A's
    // page, the badge can land on another wolf bot we don't have a page
    // for; voting THAT bot out later then fires BADGE_HANDOVER and the
    // auto-resolve has no page to drive. Parking the badge on the host
    // (whose page we always have) makes the next handover — when host
    // themselves gets voted out — driveable on hostPage. When host IS the
    // eliminated sheriff, fall back to first-alive (host's seat is dead).
    let resolvedRecipientSeat = badgeRecipientSeat
    if (resolvedRecipientSeat === undefined && targetUserId !== hostUserId) {
      const hostSeat = await readHostSeat(hostPage, gameId)
      if (hostSeat != null) {
        resolvedRecipientSeat = hostSeat
        // eslint-disable-next-line no-console
        console.warn(`[completeDay] defaulting badge recipient to host seat=${hostSeat}`)
      }
    }
    if (!resolvedSheriffPage) {
      // eslint-disable-next-line no-console
      console.warn(
        `[completeDay] BADGE_HANDOVER fired for game=${gameId} but no sheriffPage available — ` +
          `caller must pass the eliminated sheriff's browser page (or open one in browserRoles).`,
      )
    } else {
      // Pick the badge recipient. If the caller supplied `badgeRecipientSeat`,
      // click that exact seat — important when the same eliminated-sheriff
      // flow can fire on a later day with a different person voted out
      // (e.g. HARD_MODE D2 elimination), where parking the badge on a wolf
      // keeps it sticky for the remainder of the test. Otherwise pick the
      // first alive slot. Click is on the page where `isEliminatedSheriff`
      // is true (resolvedSheriffPage) — only that page renders the buttons.
      const slot = resolvedRecipientSeat !== undefined
        ? resolvedSheriffPage.locator(`.player-grid [data-seat="${resolvedRecipientSeat}"].slot-alive`)
        : resolvedSheriffPage.locator('.player-grid .slot-alive').first()
      await slot.waitFor({ state: 'visible', timeout: 10_000 })
      await slot.click()
      await resolvedSheriffPage.waitForTimeout(300)

      const passBtn = resolvedSheriffPage.getByTestId('badge-pass')
      const passEnabled = await passBtn
        .waitFor({ state: 'visible', timeout: 5_000 })
        .then(() => true)
        .catch(() => false)
      if (passEnabled) {
        await passBtn.click()
      } else {
        // Fall back to destroy if for some reason no slot was selectable.
        const destroyBtn = resolvedSheriffPage.getByTestId('badge-destroy')
        if (await destroyBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
          await destroyBtn.click()
        }
      }
      // Wait for the sub-phase to leave BADGE_HANDOVER (backend transitions
      // to VOTE_RESULT once the badge is passed/destroyed).
      const leftBadge = await waitForVotingSubPhase(hostPage, gameId, 'VOTE_RESULT', 10_000)
      // eslint-disable-next-line no-console
      console.warn(`[completeDay] left BADGE_HANDOVER → VOTE_RESULT: ${leftBadge}`)
    }
    await hostPage.waitForTimeout(1_000)
    await captureSnapshot(ctx.pages, testInfo, `${evidenceLabel}-badge-handover-done`)
  }

  // Host clicks Continue to advance to night. After the badge handover
  // resolves the host page sees `voting-continue`; if the elimination ended
  // the game (HARD_MODE wolf-win at this very vote) the page redirected to
  // /result and the button never renders — that's fine, just skip it.
  if (hostPage.url().includes('/result/')) return
  const continueBtn = hostPage.getByTestId('voting-continue')
  if (await continueBtn.isVisible({ timeout: 6_000 }).catch(() => false)) {
    await continueBtn.click()
    await hostPage.waitForTimeout(1_200)
  }
}

// ───────────────────────────────────────────────────────────────────────────────
// Scenario 1 — 12 players, CLASSIC, sheriff election, VILLAGERS win
// ───────────────────────────────────────────────────────────────────────────────

test.describe('12p sheriff — CLASSIC villager win', () => {
  test.setTimeout(600_000) // 10 min max

  let ctx: GameContext

  test.beforeAll(async ({ browser }, testInfo) => {
    // CI scales ~2× slower; 180s is tight for a 12p 6-round classic game on
    // ubuntu-latest. Bump to 360s under CI only.
    testInfo.setTimeout(process.env.CI ? 360_000 : 180_000)
    ctx = await setupGame(browser, {
      totalPlayers: 12,
      hasSheriff: true,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
      browserRoles: BROWSER_ROLES,
    })
  })

  test.afterAll(async () => {
    await ctx?.cleanup()
  })

  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status === 'failed' && ctx?.pages) {
      await attachCompositeOnFailure(ctx.pages, testInfo)
    }
  })

  // UN-SKIP attempt 2026-04-27: workers:1 + the rewritten 6-design-points
  // diagnostics (browser sentinels, backend log scan, invariants, action
  // observability, sub-phase gating) should make this both bearable on the
  // shared backend AND debuggable when it fails.
  test('phase: role-reveal + sheriff election + village votes out wolves', async ({}, testInfo) => {
    await captureSnapshot(ctx.pages, testInfo, 'classic-01-role-reveal-or-election-start')

    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    // Prefer non-host candidates so the badge lives on a bot we can script later
    const candidates = [seerBots[0], wolfBots[0]]
      .filter((b): b is NonNullable<typeof b> => !!b && b.nick !== 'Host')
      .map((b) => b.nick)

    await runSheriffElection(ctx, candidates)
    await captureSnapshot(ctx.pages, testInfo, 'classic-02-sheriff-elected')

    // Start night
    const startBtn = ctx.hostPage.getByTestId('start-night')
    if (await startBtn.isVisible({ timeout: 10_000 }).catch(() => false)) {
      await startBtn.click()
    } else {
      // sheriff flow sometimes auto-transitions; try scripted fallback
      tryAct('START_NIGHT', 'Host', { room: ctx.roomCode })
    }
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 20_000)
    await captureSnapshot(ctx.pages, testInfo, 'classic-03-night-1-entered')

    // Wolves will be voted out every day. No village player dies at night if
    // we can avoid it — wolves hit a villager target we'll vote no-one for.
    // Alive wolves-to-eliminate order: all wolves including host (if host
    // rolled WEREWOLF). The earlier "exclude host" filter was wrong: with
    // host filtered, a host-wolf survives the entire spec, the loop exits
    // with 1 wolf still alive, and `/result/` is never reached. The host
    // can be voted out and still drive the post-elimination UI (host-only
    // buttons check hostUserId, not alive status). Include host with their
    // real seat from the API — the shell state file's seat=0 is stale.
    const hostSeat = await readHostSeat(ctx.hostPage, ctx.gameId)
    const wolvesToEliminate = (ctx.roleMap.WEREWOLF ?? []).map((b) => ({
      nick: b.nick,
      userId: b.userId,
      // Host's seat in roleMap comes from the shell state file's seat=0
      // (initialized in setupGame and never updated post seat-claim).
      // Substitute the live API seat. Other bots' seats from the state
      // file are correct.
      seat: b.nick === 'Host' && hostSeat != null ? hostSeat : b.seat,
    }))

    const villagerBots = ctx.roleMap.VILLAGER ?? []
    // Do NOT target the host even if the host's role is VILLAGER — the spec
    // needs the host alive to keep driving UI clicks. Also exclude the
    // elected sheriff if they're on the village team so the badge stays put
    // for as long as possible (simplifies reasoning in evidence screenshots).
    const villagerSeats = villagerBots.filter((b) => b.nick !== 'Host').map((b) => b.seat)

    let round = 0
    const maxRounds = 6
    while (round < maxRounds && wolvesToEliminate.length > 0) {
      // Night: wolves kill a villager; seer checks a wolf (for evidence)
      const killSeat = villagerSeats[round % villagerSeats.length] ?? 1
      const checkSeat = wolvesToEliminate[0]?.seat ?? 1
      await completeNight(ctx, killSeat, checkSeat)
      await ctx.hostPage.waitForTimeout(3_000)
      if (ctx.hostPage.url().includes('/result/')) break
      await captureSnapshot(ctx.pages, testInfo, `classic-04-night-${round + 1}-actions`)

      // Day: vote out the front wolf
      const targetWolfSeat = wolvesToEliminate[0].seat
      await completeDay(ctx, testInfo, targetWolfSeat, `classic-04-day-${round + 1}`)
      await ctx.hostPage.waitForTimeout(2_500)
      if (ctx.hostPage.url().includes('/result/')) break

      wolvesToEliminate.shift()
      round++
    }

    // Expect GAME_OVER — assert via authoritative API state, not URL.
    // STOMP-driven router.push to /result lags backend commit by 200-500ms
    // (longer on CI), so URL-only detection is racy.
    const finalPhase = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    expect(
      finalPhase,
      `villager-win plan must reach GAME_OVER within ${maxRounds} rounds — actual phase=${finalPhase}`,
    ).toBe('GAME_OVER')

    // Wait for the result-screen redirect so the outcome title renders.
    await ctx.hostPage.waitForURL(/\/result\//, { timeout: 60_000 })
    await captureSnapshot(ctx.pages, testInfo, 'classic-99-result-screen')

    await expect(ctx.hostPage.locator('.outcome-title')).toBeVisible({ timeout: 10_000 })
    const winner = (await ctx.hostPage.locator('.outcome-title').textContent()) ?? ''
    expect(winner).toMatch(/村民|好人|Villager|GOOD/i)
  })
})

// ───────────────────────────────────────────────────────────────────────────────
// Scenario 2 — 12 players, HARD_MODE, sheriff election, WOLVES win
// Day 1: village votes out the elected sheriff → BADGE_HANDOVER fires.
// Subsequent nights: wolves kill; wolves-win rule triggers when counterplay
// (witch potions + guard + hunter-if-any) is exhausted.
// ───────────────────────────────────────────────────────────────────────────────

test.describe('12p sheriff — HARD_MODE wolf win with badge passover', () => {
  test.setTimeout(600_000)

  let ctx: GameContext

  test.beforeAll(async ({ browser }, testInfo) => {
    // CI scales ~2× slower; 180s is tight for a 12p sheriff-elect + 2-night
    // game on ubuntu-latest. Bump to 360s under CI only.
    testInfo.setTimeout(process.env.CI ? 360_000 : 180_000)
    ctx = await setupGame(browser, {
      totalPlayers: 12,
      hasSheriff: true,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
      browserRoles: BROWSER_ROLES,
      winCondition: 'HARD_MODE',
    })
  })

  test.afterAll(async () => {
    await ctx?.cleanup()
  })

  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status === 'failed' && ctx?.pages) {
      await attachCompositeOnFailure(ctx.pages, testInfo)
    }
  })

  test('phase: role-reveal + sheriff-elect (seer) + D1 vote out sheriff → badge passover → wolves win', async ({}, testInfo) => {
    // eslint-disable-next-line no-console
    console.warn(`[hard-mode test] starting with hostRole=${ctx.hostRole}`)

    await captureSnapshot(ctx.pages, testInfo, 'hard-01-role-reveal-or-election-start')

    // For each special role: resolve to bot OR host (host takes the role on
    // ~25% of rolls in this 12p kit). Read the host's actual seat from the
    // live game state — the shell state file's seat=0 for the host is stale
    // (multi-browser.ts:227 writes it once before seat-claim and never
    // updates it).
    const seer = await resolveRolePlayer(ctx, 'SEER')
    const guard = await resolveRolePlayer(ctx, 'GUARD')
    const witch = await resolveRolePlayer(ctx, 'WITCH')
    assertNonNull(seer, `kit must have a SEER (bot or host=${ctx.hostRole})`)
    assertNonNull(guard, `kit must have a GUARD (bot or host=${ctx.hostRole})`)
    assertNonNull(witch, `kit must have a WITCH (bot or host=${ctx.hostRole})`)
    // eslint-disable-next-line no-console
    console.warn(
      `[hard-mode test] resolved roles — seer=${seer.nick}(seat=${seer.seat},host=${seer.isHost}) ` +
        `guard=${guard.nick}(seat=${guard.seat},host=${guard.isHost}) ` +
        `witch=${witch.nick}(seat=${witch.seat},host=${witch.isHost})`,
    )

    const wolfSeats = new Set((ctx.roleMap.WEREWOLF ?? []).map((b) => b.seat))
    const villagerSeats = (ctx.roleMap.VILLAGER ?? [])
      .filter((b) => b.nick !== 'Host')
      .map((b) => b.seat)
      .filter((s) => !wolfSeats.has(s))
    expect(
      villagerSeats.length,
      `D2 vote-out plan needs at least one non-host non-wolf villager seat`,
    ).toBeGreaterThanOrEqual(1)

    // Sheriff election — only the seer campaigns. After speeches + votes
    // the seer holds the badge.
    await runSheriffElection(ctx, [seer.nick])
    await captureSnapshot(ctx.pages, testInfo, 'hard-02-sheriff-elected-is-seer')

    // Start night 1
    const startBtn = ctx.hostPage.getByTestId('start-night')
    if (await startBtn.isVisible({ timeout: 10_000 }).catch(() => false)) {
      await startBtn.click()
    } else {
      tryAct('START_NIGHT', 'Host', { room: ctx.roomCode })
    }
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 20_000)
    await captureSnapshot(ctx.pages, testInfo, 'hard-03-night-1-entered')

    // N1: wolves kill the GUARD. Witch's `useAntidote: false` (set inside
    // completeNight) leaves the kill standing. Seer checks a wolf so the
    // SEER_PICK sub-phase advances. After: 11 alive (4W/1S/1Wi/0G/5V),
    // counterplay still has the witch, post-night logical win is skipped.
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    await completeNight(ctx, guard.seat, wolfBots[0]?.seat ?? guard.seat)
    await ctx.hostPage.waitForTimeout(2_500)
    await captureSnapshot(ctx.pages, testInfo, 'hard-04-night-1-done')

    // D1: village votes out the seer (sheriff). Backend transitions to
    // BADGE_HANDOVER — only the seer's browser page sees the pass-badge
    // button (isEliminatedSheriff is true there only). When the host is
    // the seer, ctx.pages.get('SEER') === ctx.hostPage by setupGame's
    // mapping (multi-browser.ts:347), so the host's own page renders the
    // badge UI. Park the badge on a non-host wolf so the sheriff never
    // moves again (wolves don't get voted, don't kill themselves).
    const seerPage = ctx.pages.get('SEER')
    assertNonNull(
      seerPage,
      'badge-handover needs the SEER browser page — only the eliminated sheriff sees the pass-badge UI',
    )
    const badgeWolfBot = wolfBots.find((b) => b.nick !== 'Host')
    assertNonNull(
      badgeWolfBot,
      'a non-host wolf is needed as the badge recipient so the badge stays put for the rest of the test',
    )
    await completeDay(
      ctx,
      testInfo,
      seer.seat,
      'hard-05-day-1',
      seerPage,
      badgeWolfBot.seat,
    )
    await ctx.hostPage.waitForTimeout(2_000)

    // Plan needs 2 nights: D1 vote-out fires BADGE_HANDOVER + post-vote win
    // check, but counterplay (witch + alive humans) keeps it from ending.
    // Use API state, not URL — STOMP /result/ redirect lags backend commit.
    const phaseAfterD1 = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    expect(
      phaseAfterD1,
      'wolf-win plan needs 2 nights — D1 vote-out should NOT end the game',
    ).not.toBe('GAME_OVER')

    // N2: wolves kill the WITCH. Witch sees herself as the wolves' target
    // during WITCH_ACT but the `useAntidote: false` payload (in
    // completeNight) forces the witch to decline self-heal — she dies. After:
    // 9 alive (4W/0S/0Wi/0G/5V), no remaining counterplay tokens.
    await completeNight(ctx, witch.seat)
    await ctx.hostPage.waitForTimeout(2_500)
    await captureSnapshot(ctx.pages, testInfo, 'hard-06-night-2-done')

    // Edge: if N2 already wrapped the game (wolves got parity via cascading
    // death), capture and assert wolf-win here. Use API state — STOMP
    // /result/ redirect lags backend GAME_OVER commit.
    const phaseAfterN2 = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    if (phaseAfterN2 === 'GAME_OVER') {
      await ctx.hostPage.waitForURL(/\/result\//, { timeout: 30_000 })
      await captureSnapshot(ctx.pages, testInfo, 'hard-99-result-screen')
      await expect(ctx.hostPage.locator('.outcome-title')).toBeVisible({ timeout: 10_000 })
      const winnerEarly = (await ctx.hostPage.locator('.outcome-title').textContent()) ?? ''
      expect(winnerEarly).toMatch(/狼人|Werewolf|WOLF/i)
      return
    }

    // D2: village votes out any non-host non-wolf villager. The vote
    // produces an elimination, which fires the POST_VOTE win check — and
    // with hasGuard=false, hasWitch=false, hasHunter=N/A (HUNTER not in role
    // kit), counterplay.any=false. Wolves at parity (4W vs 4 humans
    // remaining: host + 3 villagers) → HARD_MODE wolf-win logical branch.
    await completeDay(ctx, testInfo, villagerSeats[0], 'hard-07-day-2')
    await ctx.hostPage.waitForTimeout(2_000)

    // Authoritative GAME_OVER assertion via API, not URL.
    const phaseAfterD2 = await ctx.hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.phase : null
    }, ctx.gameId)
    expect(
      phaseAfterD2,
      `HARD_MODE plan must reach GAME_OVER after N1(guard)+D1(seer)+N2(witch)+D2(villager) — actual=${phaseAfterD2}`,
    ).toBe('GAME_OVER')

    await ctx.hostPage.waitForURL(/\/result\//, { timeout: 60_000 })
    await captureSnapshot(ctx.pages, testInfo, 'hard-99-result-screen')

    await expect(ctx.hostPage.locator('.outcome-title')).toBeVisible({ timeout: 10_000 })
    const winner = (await ctx.hostPage.locator('.outcome-title').textContent()) ?? ''
    expect(winner).toMatch(/狼人|Werewolf|WOLF/i)
  })
})
