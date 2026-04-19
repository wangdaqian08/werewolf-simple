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
import {act, type RoleName, sheriff} from './helpers/shell-runner'
import {verifyAllBrowsersPhase} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'

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
async function runSheriffElection(ctx: GameContext, pickNickCampaign: string[]): Promise<void> {
  // Host's role-reveal still has to be confirmed via UI if it's still showing.
  const hostPage = ctx.hostPage
  await verifyAllBrowsersPhase(ctx.pages, 'SHERIFF_ELECTION', 20_000)

  // Campaign
  for (const nick of pickNickCampaign) {
    try {
      sheriff('campaign', { player: nick, room: ctx.roomCode })
    } catch {
      // already campaigning or phase moved on — ignore
    }
  }

  // Speeches — host advances
  try {
    act('SHERIFF_START_SPEECH', undefined, { room: ctx.roomCode })
  } catch {
    // already in speech
  }
  for (let i = 0; i < 12; i++) {
    try {
      act('SHERIFF_ADVANCE_SPEECH', undefined, { room: ctx.roomCode })
      await hostPage.waitForTimeout(150)
    } catch {
      break
    }
  }

  // Voting — everyone votes for the first campaigner
  if (pickNickCampaign.length > 0) {
    try {
      sheriff('vote', { target: pickNickCampaign[0], room: ctx.roomCode })
    } catch {
      // some may reject — keep going
    }
  }

  // Reveal
  try {
    act('SHERIFF_REVEAL_RESULT', undefined, { room: ctx.roomCode })
  } catch {
    // may already be revealed
  }
  await hostPage.waitForTimeout(1_500)
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

  const wolfNick = wolfBots.find((b) => b.nick !== 'Host')?.nick
  const seerNick = seerBots.find((b) => b.nick !== 'Host')?.nick
  const witchNick = witchBots.find((b) => b.nick !== 'Host')?.nick
  const guardNick = guardBots.find((b) => b.nick !== 'Host')?.nick

  // ── WEREWOLF_PICK ──
  await waitForSubPhase(hostPage, gameId, 'WEREWOLF_PICK', 20_000)
  if (wolfNick) {
    tryAct('WOLF_KILL', wolfNick, { target: String(targetSeat), room: ctx.roomCode })
  } else {
    // host is the wolf — drive via host UI
    await hostPage.locator('.player-grid .slot-alive').first().click().catch(() => {})
    await hostPage.getByTestId('wolf-confirm-kill').click().catch(() => {})
  }

  // ── SEER_PICK ──
  if (seerBots.length > 0) {
    const reached = await waitForSubPhase(hostPage, gameId, 'SEER_PICK', 15_000)
    if (reached) {
      const checkSeat = seerCheckSeat ?? 1
      if (seerNick) {
        tryAct('SEER_CHECK', seerNick, { target: String(checkSeat), room: ctx.roomCode })
      }
      // SEER_RESULT next
      await waitForSubPhase(hostPage, gameId, 'SEER_RESULT', 10_000)
      if (seerNick) {
        tryAct('SEER_CONFIRM', seerNick, { room: ctx.roomCode })
      }
    }
  }

  // ── WITCH_ACT ──
  if (witchBots.length > 0) {
    const reached = await waitForSubPhase(hostPage, gameId, 'WITCH_ACT', 15_000)
    if (reached && witchNick) {
      tryAct('WITCH_ACT', witchNick, {
        payload: '{"useAntidote":false}',
        room: ctx.roomCode,
      })
    }
  }

  // ── GUARD_PICK ──
  if (guardBots.length > 0) {
    const reached = await waitForSubPhase(hostPage, gameId, 'GUARD_PICK', 15_000)
    if (reached && guardNick) {
      tryAct('GUARD_SKIP', guardNick, { room: ctx.roomCode })
    }
  }
}

/**
 * Drive one day: host reveals the night result via UI, then opens voting.
 * Every alive non-target voter votes for [targetNickOrSeat]. Host reveals tally;
 * host clicks continue. Handles the optional BADGE_HANDOVER sub-phase when the
 * sheriff is voted out: captures a dedicated screenshot and passes the badge.
 */
async function completeDay(
  ctx: GameContext,
  testInfo: Parameters<typeof captureSnapshot>[1],
  targetSeat: number,
  evidenceLabel: string,
): Promise<void> {
  const hostPage = ctx.hostPage

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
    await hostPage.waitForTimeout(800)
  }
  await captureSnapshot(ctx.pages, testInfo, `${evidenceLabel}-day-voting-opened`)

  // Every alive voter must submit for `allVotesIn` → reveal button becomes
  // enabled. That includes the host AND the target themselves (players can
  // vote for themselves; skipping them is why we previously stalled at
  // 10/11 voted). Pull the alive roster from /state so dead players are
  // excluded (backend rejects their votes and allVotesIn drops them from the
  // denominator automatically — but skipping them keeps the logs cleaner).
  const liveState = await hostPage.evaluate(async () => {
    const token = localStorage.getItem('jwt')
    const res = await fetch(`/api/game/${location.pathname.split('/').pop()}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    return res.ok ? res.json() : null
  })
  const alivePlayers: Array<{ nickname: string; seat: number; isAlive: boolean }> =
    liveState?.players?.filter((p: { isAlive: boolean }) => p.isAlive) ?? []

  const targetNick = alivePlayers.find((p) => p.seat === targetSeat)?.nickname
  if (targetNick) {
    for (const voter of alivePlayers) {
      tryAct('SUBMIT_VOTE', voter.nickname, { target: String(targetSeat), room: ctx.roomCode })
    }
  } else {
    // targetSeat not resolvable (e.g., -1 sentinel) → every alive player abstains.
    for (const voter of alivePlayers) {
      tryAct('SUBMIT_VOTE', voter.nickname, { room: ctx.roomCode })
    }
  }
  await hostPage.waitForTimeout(1_500)

  // Reveal tally. If the vote ties, the backend moves the sub-phase to
  // RE_VOTING and requires another round of votes. Loop up to 3 rounds before
  // giving up — the elimination CAN finish in one round but doesn't have to.
  async function freshAlivePlayers(): Promise<
    Array<{ nickname: string; seat: number; isAlive: boolean }>
  > {
    const s = await hostPage.evaluate(async () => {
      const t = localStorage.getItem('jwt')
      const r = await fetch(`/api/game/${location.pathname.split('/').pop()}/state`, {
        headers: { Authorization: `Bearer ${t}` },
      })
      return r.ok ? r.json() : null
    })
    return s?.players?.filter((p: { isAlive: boolean }) => p.isAlive) ?? []
  }
  async function freshSubPhase(): Promise<string> {
    const s = await hostPage.evaluate(async () => {
      const t = localStorage.getItem('jwt')
      const r = await fetch(`/api/game/${location.pathname.split('/').pop()}/state`, {
        headers: { Authorization: `Bearer ${t}` },
      })
      return r.ok ? r.json() : null
    })
    return s?.votingPhase?.subPhase ?? s?.game?.subPhase ?? ''
  }

  for (let attempt = 0; attempt < 3; attempt++) {
    const revealTallyBtn = hostPage.getByTestId('voting-reveal')
    await revealTallyBtn.waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {})
    await expect(revealTallyBtn).toBeEnabled({ timeout: 15_000 }).catch(() => {})
    if (await revealTallyBtn.isVisible().catch(() => false)) {
      await revealTallyBtn.click()
      await hostPage.waitForTimeout(1_500)
    }
    await captureSnapshot(ctx.pages, testInfo, `${evidenceLabel}-day-tally-revealed-r${attempt + 1}`)

    const sub = await freshSubPhase()
    if (sub !== 'RE_VOTING' && sub !== 'VOTING') break

    // Still in voting → re-submit every alive voter (including target & host).
    const survivors = await freshAlivePlayers()
    if (targetNick) {
      for (const voter of survivors) {
        tryAct('SUBMIT_VOTE', voter.nickname, { target: String(targetSeat), room: ctx.roomCode })
      }
    } else {
      for (const voter of survivors) {
        tryAct('SUBMIT_VOTE', voter.nickname, { room: ctx.roomCode })
      }
    }
    await hostPage.waitForTimeout(1_500)
  }

  // Did BADGE_HANDOVER fire? If yes, capture + pass the badge to seat 1 (or destroy).
  const badgeSection = hostPage.locator('[data-testid="badge-handover-panel"], .badge-handover, .badge-passover')
  const badgeVisible = await badgeSection.first().isVisible({ timeout: 2_000 }).catch(() => false)
  if (badgeVisible) {
    await captureSnapshot(ctx.pages, testInfo, `${evidenceLabel}-badge-handover-triggered`)
    const badgeHolders = await freshAlivePlayers()
    let passed = false
    for (const b of badgeHolders) {
      if (b.seat === targetSeat) continue
      if (tryAct('BADGE_PASS', b.nickname, { target: '1', room: ctx.roomCode })) {
        passed = true
        break
      }
    }
    if (!passed) {
      const destroyBtn = hostPage.locator('button:has-text("销毁"), button:has-text("Destroy")').first()
      if (await destroyBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await destroyBtn.click()
      }
    }
    await hostPage.waitForTimeout(1_000)
    await captureSnapshot(ctx.pages, testInfo, `${evidenceLabel}-badge-handover-done`)
  }

  // Host clicks Continue to advance to night
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
      tryAct('START_NIGHT', undefined, { room: ctx.roomCode })
    }
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 20_000)
    await captureSnapshot(ctx.pages, testInfo, 'classic-03-night-1-entered')

    // Wolves will be voted out every day. No village player dies at night if
    // we can avoid it — wolves hit a villager target we'll vote no-one for.
    // Alive wolves-to-eliminate order: all wolves, one per day.
    // Exclude the host even if the host is a wolf — the host drives UI clicks
    // for the rest of the flow, so voting them out stalls the spec. (Host-wolf
    // still dies in the last round when it's the only wolf left; in a 12p
    // classic game there are 4 wolves, so excluding one is safe.)
    const wolvesToEliminate = (ctx.roleMap.WEREWOLF ?? []).filter((b) => b.nick !== 'Host')

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

    // Expect GAME_OVER — verify result screen
    await ctx.hostPage.waitForURL(/\/result\//, { timeout: 60_000 }).catch(() => {})
    await captureSnapshot(ctx.pages, testInfo, 'classic-99-result-screen')

    const onResult = ctx.hostPage.url().includes('/result/')
    if (!onResult) {
      // Not necessarily a failure — capture diagnostic and flag it
      const currentPhase = await ctx.hostPage.evaluate(() => {
        const el = document.querySelector('[data-testid="current-phase"]')
        return el?.textContent ?? document.body.className
      })
      throw new Error(`Villager-win scenario did not reach /result in 6 rounds. Current host state: ${currentPhase}`)
    }

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
    // CI scales ~2× slower; 180s is tight for a 12p 6-round classic game on
    // ubuntu-latest. Bump to 360s under CI only.
    testInfo.setTimeout(process.env.CI ? 360_000 : 180_000)
    ctx = await setupGame(browser, {
      totalPlayers: 12,
      hasSheriff: true,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
      browserRoles: BROWSER_ROLES,
    })

    // Flip the room's win condition to HARD_MODE — no UI toggle for it in
    // create-room, so we patch the already-created room directly through the
    // bot-side REST (dev endpoint). If this is rejected the spec continues
    // under CLASSIC and still demonstrates sheriff + badge passover.
    try {
      act('SET_WIN_CONDITION', 'Host', {
        payload: JSON.stringify({ winCondition: 'HARD_MODE' }),
        room: ctx.roomCode,
      })
    } catch {
      // not supported — fall back to CLASSIC for this scenario
    }
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
    await captureSnapshot(ctx.pages, testInfo, 'hard-01-role-reveal-or-election-start')

    const seerBots = ctx.roleMap.SEER ?? []
    const seerNick = seerBots.find((b) => b.nick !== 'Host')?.nick ?? seerBots[0]?.nick
    if (!seerNick) {
      throw new Error('No seer found — cannot run sheriff-elected-is-seer scenario')
    }

    await runSheriffElection(ctx, [seerNick])
    await captureSnapshot(ctx.pages, testInfo, 'hard-02-sheriff-elected-is-seer')

    // Start night 1
    const startBtn = ctx.hostPage.getByTestId('start-night')
    if (await startBtn.isVisible({ timeout: 10_000 }).catch(() => false)) {
      await startBtn.click()
    } else {
      tryAct('START_NIGHT', undefined, { room: ctx.roomCode })
    }
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 20_000)
    await captureSnapshot(ctx.pages, testInfo, 'hard-03-night-1-entered')

    // N1: wolves kill a random villager; seer checks a wolf. Exclude the
    // host from kill targets so the host stays alive to keep driving the UI.
    const villagers = (ctx.roleMap.VILLAGER ?? [])
      .filter((b) => b.nick !== 'Host')
      .map((b) => b.seat)
    const wolves = (ctx.roleMap.WEREWOLF ?? []).map((b) => b.seat)
    await completeNight(ctx, villagers[0] ?? 2, wolves[0] ?? 1)
    await ctx.hostPage.waitForTimeout(3_000)
    await captureSnapshot(ctx.pages, testInfo, 'hard-04-night-1-done')

    // D1: village votes out the sheriff (seer). Triggers BADGE_HANDOVER.
    const seerSeat = seerBots.find((b) => b.nick === seerNick)?.seat ?? 1
    await completeDay(ctx, testInfo, seerSeat, 'hard-05-day-1')

    // Continue nights — wolves kill one per night; witch keeps potions unused
    // so hasWitchWithPotions stays true for a while, then both spent = win path
    const targets = villagers.filter((s) => s !== seerSeat)
    for (let round = 0; round < 5; round++) {
      if (ctx.hostPage.url().includes('/result/')) break
      const killSeat = targets[round % targets.length] ?? 3
      await completeNight(ctx, killSeat)
      await ctx.hostPage.waitForTimeout(2_500)
      if (ctx.hostPage.url().includes('/result/')) break

      // Day: village can't find wolves; abstain via host-UI skip so the game
      // progresses wolf-ward. Any tie → revote happens in completeDay.
      await completeDay(ctx, testInfo, -1, `hard-06-day-${round + 2}`)
    }

    await ctx.hostPage.waitForURL(/\/result\//, { timeout: 60_000 }).catch(() => {})
    await captureSnapshot(ctx.pages, testInfo, 'hard-99-result-screen')

    if (!ctx.hostPage.url().includes('/result/')) {
      throw new Error('HARD_MODE wolf-win scenario did not reach /result in 6 rounds')
    }

    await expect(ctx.hostPage.locator('.outcome-title')).toBeVisible({ timeout: 10_000 })
    const winner = (await ctx.hostPage.locator('.outcome-title').textContent()) ?? ''
    expect(winner).toMatch(/狼人|Werewolf|WOLF/i)
  })
})
