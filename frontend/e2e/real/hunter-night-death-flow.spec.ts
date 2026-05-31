/**
 * Hunter night-death shoot — real-backend evidence.
 *
 * When the werewolves kill a HUNTER at night (and the witch does NOT poison
 * them), the day-reveal flow enters DaySubPhase.HUNTER_SHOOT_NIGHT_DEATH: the
 * deaths are reported, the wolf-killed hunter fires one shot through their own
 * browser, then the day lands on RESULT_REVEALED for the host to start the vote.
 *
 * The full decision matrix (poison disables the shot, badge-first ordering when
 * a sheriff also dies, hunter-is-sheriff cascade, win on the shot) is covered by
 * the backend suites (HunterNightDeathShootIntegrationTest + DayRevealAdvancerTest).
 * This spec proves the end-to-end DOM + STOMP wiring for the happy shoot path.
 *
 * CI-vs-local design (see memory feedback_e2e_ci_vs_local):
 *  - every transition is gated on backend /state, never on a sleep;
 *  - random role assignment is handled — the hunter/wolf seats are resolved
 *    after start and the host-as-hunter case falls out of ctx.pages mapping;
 *  - timeouts scale 2× under CI.
 */
import { expect, type Page, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import type { RoleName } from './helpers/shell-runner'
import { driveMinimalNight1ViaDom } from './helpers/night-driver'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { readHostSeat, waitForCondition } from './helpers/state-polling'

const BROWSER_ROLES: RoleName[] = ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'HUNTER', 'VILLAGER']

const scale = (ms: number): number => (process.env.CI ? ms * 2 : ms)

function assertNonNull<T>(value: T | null | undefined, msg: string): asserts value is T {
  expect(value, msg).not.toBeNull()
  expect(value, msg).not.toBeUndefined()
}

interface StatePlayer {
  userId: string
  seatIndex?: number
  isAlive?: boolean
}
interface GameStateLite {
  phase?: string
  dayPhase?: { subPhase?: string } | null
  players?: StatePlayer[]
}

async function fetchState(page: Page, gameId: string): Promise<GameStateLite | null> {
  return page.evaluate(async (id: string) => {
    const token = localStorage.getItem('jwt')
    if (!token) return null
    const res = await fetch(`/api/game/${id}/state`, { headers: { Authorization: `Bearer ${token}` } })
    return res.ok ? await res.json() : null
  }, gameId)
}

/** Poll `state.dayPhase.subPhase` until it matches `target`. (No shared helper
 *  reads the day tree — night/voting ones look at the wrong sub-object.) */
async function waitForDaySubPhase(
  page: Page,
  gameId: string,
  target: string,
  timeoutMs: number,
): Promise<boolean> {
  const deadline = Date.now() + scale(timeoutMs)
  while (Date.now() < deadline) {
    const state = await fetchState(page, gameId)
    if (state?.dayPhase?.subPhase === target) return true
    await page.waitForTimeout(300)
  }
  return false
}

async function seatAlive(page: Page, gameId: string, seat: number): Promise<boolean> {
  const state = await fetchState(page, gameId)
  return !!state?.players?.find((p) => p.seatIndex === seat)?.isAlive
}

/** Resolve a role to its seat, handling the host-holds-the-role case. */
async function resolveSeat(ctx: GameContext, role: RoleName): Promise<number | null> {
  if (ctx.isHostRole(role)) return readHostSeat(ctx.hostPage, ctx.gameId)
  const bot = (ctx.roleMap[role] ?? []).find((b) => b.nick !== 'Host')
  return bot?.seat ?? null
}

test.describe('Hunter night-death shoot (real backend)', () => {
  let ctx: GameContext | undefined

  // eslint-disable-next-line no-empty-pattern
  test.afterEach(async ({}, testInfo) => {
    if (ctx) await attachCompositeOnFailure(ctx.pages, testInfo)
  })
  test.afterAll(async () => {
    if (ctx) await ctx.cleanup()
  })

  test('a wolf-killed hunter fires during the day reveal', async ({ browser }, testInfo) => {
    testInfo.setTimeout(process.env.CI ? 360_000 : 180_000)

    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'HUNTER', 'VILLAGER'],
      browserRoles: BROWSER_ROLES,
    })

    // The hunter must have a real browser to click Shoot. setupGame maps the
    // role to ctx.hostPage when the host rolled HUNTER, so this is never null.
    const hunterPage = ctx.pages.get('HUNTER')
    assertNonNull(hunterPage, 'hunter must have a browser page (HUNTER in browserRoles)')

    const hunterSeat = await resolveSeat(ctx, 'HUNTER')
    const wolfSeat = await resolveSeat(ctx, 'WEREWOLF')
    assertNonNull(hunterSeat, 'hunter seat must resolve')
    assertNonNull(wolfSeat, 'wolf seat must resolve')

    // Drive N1 so the wolves kill the hunter. Guard protects the WOLF's seat
    // (never the hunter, so the kill lands) and the witch passes poison (so the
    // hunter dies by wolf only → eligible to shoot).
    await driveMinimalNight1ViaDom(ctx, { wolfTargetSeat: hunterSeat, guardTargetSeat: wolfSeat })

    // Host reveals the night result → backend applies the kill and the advancer
    // routes to HUNTER_SHOOT_NIGHT_DEATH (no sheriff, hunter wolf-killed, not poisoned).
    const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
    await expect(revealBtn).toBeVisible({ timeout: scale(15_000) })
    await revealBtn.click()

    expect(
      await waitForDaySubPhase(ctx.hostPage, ctx.gameId, 'HUNTER_SHOOT_NIGHT_DEATH', 15_000),
      'reveal must enter HUNTER_SHOOT_NIGHT_DEATH',
    ).toBe(true)

    // Cross-browser delivery: the hunter's own page reflects the sub-phase.
    await expect(hunterPage.locator('.game-wrap')).toHaveAttribute(
      'data-phase-sub',
      'HUNTER_SHOOT_NIGHT_DEATH',
      { timeout: scale(15_000) },
    )
    // The acting hunter sees the shoot prompt + buttons.
    await expect(hunterPage.getByTestId('day-hunter-night-banner')).toBeVisible({
      timeout: scale(10_000),
    })

    // Shoot a living villager (keeps wolves alive → no game-over, lands on RESULT_REVEALED).
    const target = (ctx.roleMap.VILLAGER ?? []).find(
      (v) => v.nick !== 'Host' && v.seat !== hunterSeat,
    )
    assertNonNull(target, 'a non-host villager target must exist')
    expect(await seatAlive(ctx.hostPage, ctx.gameId, target.seat), 'target alive before shot').toBe(
      true,
    )

    const slot = hunterPage.locator(`.player-grid [data-seat="${target.seat}"]`)
    await expect(slot, `target seat ${target.seat} must render on the hunter's grid`).toBeVisible({
      timeout: scale(10_000),
    })
    await slot.click()
    await hunterPage.getByTestId('day-hunter-night-shoot').click()

    // The shot kills the target and the day continues to RESULT_REVEALED.
    await waitForCondition(
      async () => !(await seatAlive(ctx!.hostPage, ctx!.gameId, target.seat)),
      `hunter's target (seat ${target.seat}) must be dead after the shot`,
      10_000,
    )
    expect(
      await waitForDaySubPhase(ctx.hostPage, ctx.gameId, 'RESULT_REVEALED', 10_000),
      'after the shot the day lands on RESULT_REVEALED',
    ).toBe(true)

    await captureSnapshot(ctx.pages, testInfo, 'hunter-night-shoot')
  })
})
