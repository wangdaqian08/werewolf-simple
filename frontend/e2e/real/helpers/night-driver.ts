import { expect, type Page } from '@playwright/test'
import type { GameContext } from './multi-browser'
import { waitForCondition, waitForNightSubPhase, waitForPhase } from './state-polling'

/**
 * Drive a Night 1 to completion via DOM clicks on each special-role browser.
 *
 * Wolf, seer, witch, and guard each take their action through their own
 * browser context — never through act.sh. This keeps the test honest about
 * what real users do (per the e2e-six-design-principles memory: "Special-role
 * browsers + the host browser fire each gameplay action via Playwright clicks
 * on real testids, not via act.sh"). It catches button-render bugs, click-
 * handler wiring, target-grid filters, and reactive watchers — the very
 * regressions that prompted Variant B in the first place.
 *
 * Preconditions:
 *  - ctx.game in ROLE_REVEAL with all roles confirmed.
 *  - ctx.pages has entries for WEREWOLF, SEER, WITCH, GUARD.
 *
 * Postcondition: backend state is DAY_DISCUSSION/RESULT_HIDDEN, ready for
 * the host to click `day-reveal-result`.
 */
export async function driveMinimalNight1ViaDom(
  ctx: GameContext,
  opts: { wolfTargetSeat: number; seerCheckSeat?: number; guardTargetSeat?: number },
): Promise<void> {
  const hostPage = ctx.hostPage
  const gameId = ctx.gameId

  // ── Host starts night ────────────────────────────────────────────────
  const startBtn = hostPage.getByTestId('start-night')
  await expect(startBtn).toBeVisible({ timeout: 15_000 })
  await expect(startBtn).toBeEnabled({ timeout: 10_000 })
  await startBtn.click()

  // After clicking start-night, the backend transitions ROLE_REVEAL → NIGHT.
  // waitForNightSubPhase has an early-exit guard that returns false if
  // game.phase is anything other than NIGHT — gate on phase=NIGHT first
  // to avoid racing that guard while the transition is still in-flight.
  // (Flake observed in PR #89 CI run 73954244714: the helper was called
  // mid-transition while phase still showed ROLE_REVEAL.)
  if (!(await waitForPhase(hostPage, gameId, 'NIGHT', 15_000))) {
    throw new Error('driveMinimalNight1ViaDom: NIGHT phase not reached after start-night click')
  }

  // ── Wolf kill ────────────────────────────────────────────────────────
  const reachedWolfPick = await waitForNightSubPhase(hostPage, gameId, 'WEREWOLF_PICK', 25_000)
  if (!reachedWolfPick) {
    throw new Error('driveMinimalNight1ViaDom: WEREWOLF_PICK sub-phase not reached')
  }
  const wolfPage = pageOrThrow(ctx, 'WEREWOLF')
  const wolfSlot = wolfPage.locator(`.player-grid [data-seat="${opts.wolfTargetSeat}"]`)
  await expect(wolfSlot, `wolf target seat ${opts.wolfTargetSeat} must render`).toBeVisible({
    timeout: 10_000,
  })
  await wolfSlot.click()
  await wolfPage.getByTestId('wolf-confirm-kill').click()

  // ── Seer check + acknowledge result ──────────────────────────────────
  const reachedSeerPick = await waitForNightSubPhase(hostPage, gameId, 'SEER_PICK', 15_000)
  if (!reachedSeerPick) {
    throw new Error('driveMinimalNight1ViaDom: SEER_PICK sub-phase not reached')
  }
  const seerPage = pageOrThrow(ctx, 'SEER')
  // Default seerCheckSeat must NOT be the seer's own seat — seer-self-check
  // is disallowed (per project_game_rules_clarifications memory), so the
  // slot renders with aria-disabled="true" and .player-grid intercepts the
  // click. Without this guard the test hangs on retry-click for 180s and
  // fails with the misleading "Target page, context or browser has been
  // closed" error (the page is fine; Playwright tears it down at timeout).
  // Bot1 always lands at seat 1, and the SEER role rolls onto bot1 ~1/N
  // of the time → without this guard the test is randomly flaky.
  const seerSeat = ctx.roleMap.SEER?.[0]?.seat
  const checkSeat = opts.seerCheckSeat ?? (seerSeat !== 1 ? 1 : 2)
  const seerSlot = seerPage.locator(`.player-grid [data-seat="${checkSeat}"]`)
  await expect(seerSlot, `seer check seat ${checkSeat} must render`).toBeVisible({ timeout: 10_000 })
  await seerSlot.click()
  await seerPage.getByTestId('seer-check').click()

  await waitForNightSubPhase(hostPage, gameId, 'SEER_RESULT', 10_000)
  await expect(seerPage.getByTestId('seer-result-card')).toBeVisible({ timeout: 10_000 })
  await seerPage.getByTestId('seer-done').click()

  // ── Witch passes on antidote + poison ────────────────────────────────
  const reachedWitchAct = await waitForNightSubPhase(hostPage, gameId, 'WITCH_ACT', 15_000)
  if (!reachedWitchAct) {
    throw new Error('driveMinimalNight1ViaDom: WITCH_ACT sub-phase not reached')
  }
  const witchPage = pageOrThrow(ctx, 'WITCH')
  // The witch UI shows: (a) antidote section if hasAntidote, (b) poison
  // section if hasPoison, (c) skip button only when neither item is
  // available. For a fresh Day 1 witch both items are available — pass on
  // each in turn. Use isVisible-with-short-timeout rather than asserting
  // visibility: a particular sub-section only renders if the condition is
  // met (e.g. switch-pass-poison is gated on a wolf having killed someone).
  const passAntidote = witchPage.getByTestId('switch-pass-antidote')
  if (await passAntidote.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await passAntidote.click()
  }
  const passPoison = witchPage.getByTestId('switch-pass-poison')
  if (await passPoison.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await passPoison.click()
  }
  // If the witch happens to have no items, the skip button replaces the
  // sections above and is the only path forward.
  const witchSkip = witchPage.getByTestId('witch-skip')
  if (await witchSkip.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await witchSkip.click()
  }

  // ── Guard protects (UI has no skip — must pick a target) ─────────────
  const reachedGuardPick = await waitForNightSubPhase(hostPage, gameId, 'GUARD_PICK', 15_000)
  if (!reachedGuardPick) {
    throw new Error('driveMinimalNight1ViaDom: GUARD_PICK sub-phase not reached')
  }
  const guardPage = pageOrThrow(ctx, 'GUARD')
  const protectSeat = opts.guardTargetSeat ?? 1
  const guardSlot = guardPage.locator(`.player-grid [data-seat="${protectSeat}"]`)
  await expect(guardSlot, `guard protect seat ${protectSeat} must render`).toBeVisible({
    timeout: 10_000,
  })
  await guardSlot.click()
  await guardPage.getByTestId('guard-confirm-protect').click()

  // ── Wait for end-of-night transition ─────────────────────────────────
  // Variant B (correct ordering): the backend automatically opens
  // SHERIFF_ELECTION at end-of-night when Day 1 + hasSheriff + no sheriff
  // yet. Otherwise it transitions to DAY_DISCUSSION/RESULT_HIDDEN. Either
  // way, we just wait for whichever phase the game settles into.
  await waitForSheriffOrDayDiscussion(ctx)
}

/**
 * Wait for the end-of-night transition to land in either:
 *  - SHERIFF_ELECTION/SIGNUP   (Day 1 + hasSheriff: kills are still deferred,
 *                               N1 victims are alive in DB and can 上警)
 *  - DAY_DISCUSSION/RESULT_HIDDEN  (anything else: kills deferred until host
 *                                   reveals)
 */
export async function waitForSheriffOrDayDiscussion(
  ctx: GameContext,
  timeoutMs = 30_000,
): Promise<void> {
  await waitForCondition(
    async () => {
      const state = await fetchGameState(ctx.hostPage, ctx.gameId)
      if (state?.phase === 'SHERIFF_ELECTION' && state?.sheriffElection?.subPhase === 'SIGNUP')
        return true
      if (state?.phase === 'DAY_DISCUSSION' && state?.subPhase === 'RESULT_HIDDEN') return true
      return false
    },
    'end-of-night transition to SHERIFF_ELECTION/SIGNUP (Day 1 + hasSheriff) or DAY_DISCUSSION/RESULT_HIDDEN',
    timeoutMs,
  )
}

/**
 * Drive the SHERIFF_ELECTION/RESULT screen forward to DAY_DISCUSSION/RESULT_HIDDEN.
 *
 * Variant B (no auto-timer): the host clicks 显示结果 (testid
 * `sheriff-end-result`) which fires SHERIFF_END_RESULT and transitions the
 * game. Kills are still deferred at this point — the host must click
 * REVEAL_NIGHT_RESULT next to apply them and flip RESULT_HIDDEN →
 * RESULT_REVEALED. Use [waitForResultRevealed] after the host reveal click.
 */
export async function waitForDayDiscussionAfterSheriff(
  ctx: GameContext,
  timeoutMs = 20_000,
): Promise<void> {
  // Wait for RESULT sub-phase to be reachable (the backend may still be
  // settling after the SHERIFF_REVEAL_RESULT click).
  const endBtn = ctx.hostPage.getByTestId('sheriff-end-result')
  await endBtn.waitFor({ state: 'visible', timeout: timeoutMs })
  await endBtn.click()

  await waitForCondition(
    async () => {
      const state = await fetchGameState(ctx.hostPage, ctx.gameId)
      return state?.phase === 'DAY_DISCUSSION' && state?.subPhase === 'RESULT_HIDDEN'
    },
    'SHERIFF_END_RESULT click landed on DAY_DISCUSSION/RESULT_HIDDEN',
    timeoutMs,
  )
}

// ── internal helpers ───────────────────────────────────────────────────

function pageOrThrow(ctx: GameContext, role: string): Page {
  const page = ctx.pages.get(role)
  if (!page) {
    throw new Error(
      `ctx.pages missing browser for role=${role}. Pass browserRoles including '${role}' to setupGame.`,
    )
  }
  return page
}

interface PhaseSnapshot {
  phase?: string
  subPhase?: string | null
  sheriffElection?: { subPhase?: string | null } | null
}

async function fetchGameState(page: Page, gameId: string): Promise<PhaseSnapshot | null> {
  return page.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return null
    const res = await fetch(`/api/game/${id}/state`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    return res.ok ? await res.json() : null
  }, gameId)
}

async function fetchPhaseAndSubPhase(
  page: Page,
  gameId: string,
): Promise<{ phase?: string; subPhase?: string | null } | null> {
  const state = await fetchGameState(page, gameId)
  if (!state) return null
  return { phase: state.phase, subPhase: state.subPhase ?? null }
}
