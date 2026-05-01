import { expect, type Page } from '@playwright/test'
import type { GameContext } from './multi-browser'
import { waitForCondition, waitForNightSubPhase } from './state-polling'

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
  const checkSeat = opts.seerCheckSeat ?? 1
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

  // ── Wait for Day 1 morning state ─────────────────────────────────────
  // Backend transitions NIGHT → DAY_DISCUSSION/RESULT_HIDDEN once the
  // night phase completes. Caller will then click `day-reveal-result`.
  await waitForCondition(
    async () => {
      const state = await fetchPhaseAndSubPhase(hostPage, gameId)
      return state?.phase === 'DAY_DISCUSSION' && state?.subPhase === 'RESULT_HIDDEN'
    },
    'game to reach DAY_DISCUSSION/RESULT_HIDDEN after Night 1 completes',
    30_000,
  )
}

/**
 * Click `day-reveal-result` on the host browser and (when hasSheriff &&
 * dayNumber==1) wait for the auto-trigger into SHERIFF_ELECTION/SIGNUP.
 *
 * For non-sheriff games or Day 2+, callers should use the regular reveal
 * flow inline; this helper is specifically for the Variant B Day 1 trigger.
 */
export async function revealNightResultAndOpenSheriffElection(ctx: GameContext): Promise<void> {
  const hostPage = ctx.hostPage
  const gameId = ctx.gameId

  const revealBtn = hostPage.getByTestId('day-reveal-result')
  await expect(revealBtn).toBeVisible({ timeout: 15_000 })
  await expect(revealBtn).toBeEnabled({ timeout: 10_000 })
  await revealBtn.click()

  // Backend transitions DAY_DISCUSSION/RESULT_REVEALED → SHERIFF_ELECTION
  // immediately on Day 1 with hasSheriff. Wait for the SIGNUP sub-phase to
  // appear in the SheriffElection state.
  await waitForCondition(
    async () => {
      const state = await fetchGameState(hostPage, gameId)
      return (
        state?.phase === 'SHERIFF_ELECTION' && state?.sheriffElection?.subPhase === 'SIGNUP'
      )
    },
    'game to reach SHERIFF_ELECTION/SIGNUP after revealNightResult on Day 1',
    15_000,
  )
}

/**
 * Wait for the configurable post-sheriff auto-advance to land the game in
 * DAY_DISCUSSION/RESULT_REVEALED. Backed by
 * `werewolf.timing.sheriff-result-auto-advance-ms` — application-e2e.yml
 * sets it to 2_000ms; default production value is 60_000ms.
 */
export async function waitForDayDiscussionAfterSheriff(
  ctx: GameContext,
  timeoutMs = 20_000,
): Promise<void> {
  await waitForCondition(
    async () => {
      const state = await fetchGameState(ctx.hostPage, ctx.gameId)
      return state?.phase === 'DAY_DISCUSSION' && state?.subPhase === 'RESULT_REVEALED'
    },
    'auto-advance from SHERIFF_ELECTION/RESULT to DAY_DISCUSSION/RESULT_REVEALED',
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
