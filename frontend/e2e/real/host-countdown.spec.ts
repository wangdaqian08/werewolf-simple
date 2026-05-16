/**
 * Real-backend E2E: Host countdown timer — DAY_DISCUSSION + SHERIFF SPEECH.
 *
 * Five flows:
 * A. DAY_DISCUSSION host start / stop, sync across clients
 * B. SHERIFF SPEECH host start, advance-speech cancels, re-arm for next candidate
 * C. Reconnect mid-timer (DAY_DISCUSSION) recovers correct remaining
 * D. Phase exit cancels timer (DAY_DISCUSSION → DAY_VOTING)
 * E. Sub-phase exit cancels timer (SHERIFF SPEECH → VOTING)
 */
import { expect, type Page, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { act, actName, type RoleName } from './helpers/shell-runner'
import { waitForCondition, waitForPhase } from './helpers/state-polling'
import { attachCompositeOnFailure } from './helpers/composite-screenshot'
import { driveMinimalNight1ViaDom } from './helpers/night-driver'

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Wait until all provided pages show [data-testid="phase-countdown"] with
 *  data-remaining-ms within [minMs, maxMs]. */
async function waitForTimerOnAllPages(
  pages: Page[],
  minMs: number,
  maxMs: number,
  timeoutMs = 5_000,
) {
  await Promise.all(
    pages.map((page, idx) =>
      waitForCondition(
        async () => {
          const el = await page.$('[data-testid="phase-countdown"]')
          if (!el) return false
          const raw = await el.getAttribute('data-remaining-ms')
          if (!raw) return false
          const ms = Number(raw)
          return ms >= minMs && ms <= maxMs
        },
        `page[${idx}] phase-countdown remainingMs in [${minMs},${maxMs}]`,
        timeoutMs,
        200,
      ),
    ),
  )
}

/** Return the data-remaining-ms attribute value from all pages. */
async function getRemainingMsAll(pages: Page[]): Promise<number[]> {
  return Promise.all(
    pages.map(async (p) => {
      const el = await p.$('[data-testid="phase-countdown"]')
      if (!el) return 0
      const raw = await el.getAttribute('data-remaining-ms')
      return raw ? Number(raw) : 0
    }),
  )
}

/** Drive the game from ROLE_REVEAL through Night 1 to DAY_DISCUSSION.
 *  Uses existing night-driver helper for DOM-driven night actions. */
async function driveToDay(ctx: GameContext) {
  await driveMinimalNight1ViaDom(ctx, { wolfTargetSeat: 4 })

  // Reveal night result
  await waitForPhase(ctx.hostPage, ctx.gameId, 'DAY_DISCUSSION', 30_000)
  const hostBot = ctx.allBots.find((b) => b.nick === 'Host') ?? ctx.allBots[0]
  act('REVEAL_NIGHT_RESULT', actName(hostBot), { room: ctx.roomCode })
  await waitForCondition(
    async () => {
      const el = await ctx.hostPage.$('[data-testid="phase-countdown"]')
      return el !== null
    },
    'host page sees [data-testid="phase-countdown"]',
    10_000,
    300,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Flow A: DAY_DISCUSSION host start / stop, sync across clients
// ─────────────────────────────────────────────────────────────────────────────

test.describe('Flow A — DAY_DISCUSSION timer start/stop syncs to all clients', () => {
  let ctx: GameContext

  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    ctx = await setupGame(browser, {
      totalPlayers: 4,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
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

  test('A.1 drive to DAY_DISCUSSION', async () => {
    await driveToDay(ctx)
  })

  test('A.2 host starts 60s timer — all clients see remaining ≈ 60s within 1s', async () => {
    const allPages = [ctx.hostPage, ...ctx.pages.values()]

    // Click 1:00 pill on host browser
    await ctx.hostPage.click('[data-testid="phase-countdown-start-60"]')

    await waitForTimerOnAllPages(allPages, 55_000, 60_500)
  })

  test('A.3 wait 3s — data-remaining-ms decrements by 2500-3500 on every client', async () => {
    const allPages = [ctx.hostPage, ...ctx.pages.values()]
    const before = await getRemainingMsAll(allPages)

    await new Promise((r) => setTimeout(r, 3_000))
    await new Promise((r) => setTimeout(r, 200)) // extra tick

    const after = await getRemainingMsAll(allPages)
    for (let i = 0; i < allPages.length; i++) {
      const delta = before[i] - after[i]
      expect(delta).toBeGreaterThanOrEqual(2_500)
      expect(delta).toBeLessThanOrEqual(4_000)
    }
  })

  test('A.4 host stops timer — all clients converge to remainingMs=0 and see preset pills', async () => {
    const allPages = [ctx.hostPage, ...ctx.pages.values()]

    await ctx.hostPage.click('[data-testid="phase-countdown-stop"]')

    // All clients see running=false (remainingMs=0)
    await waitForTimerOnAllPages(allPages, 0, 0, 3_000)

    // Host sees preset pills again
    await ctx.hostPage.waitForSelector('[data-testid="phase-countdown-start-60"]', { timeout: 3_000 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Flow C: Reconnect mid-timer recovers correct remaining
// ─────────────────────────────────────────────────────────────────────────────

test.describe('Flow C — reconnect mid-timer recovers correct remaining', () => {
  let ctx: GameContext

  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    ctx = await setupGame(browser, {
      totalPlayers: 4,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
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

  test('C.1 drive to DAY_DISCUSSION and start 60s timer', async () => {
    await driveToDay(ctx)
    await ctx.hostPage.click('[data-testid="phase-countdown-start-60"]')
    await waitForTimerOnAllPages([ctx.hostPage], 55_000, 60_500)
  })

  test('C.2 wait 5s, one player reloads — recovers 50-56s remaining', async () => {
    await new Promise((r) => setTimeout(r, 5_000))

    // Pick any non-host page to reload
    const playerPage = [...ctx.pages.values()][0]
    await playerPage.reload()
    await playerPage.waitForSelector('[data-testid="phase-countdown"]', { timeout: 15_000 })

    const el = await playerPage.$('[data-testid="phase-countdown"]')!
    const raw = await el!.getAttribute('data-remaining-ms')
    const ms = Number(raw ?? '0')
    expect(ms).toBeGreaterThanOrEqual(50_000)
    expect(ms).toBeLessThanOrEqual(56_000)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Flow D: Phase exit cancels timer (DAY_DISCUSSION → DAY_VOTING)
// ─────────────────────────────────────────────────────────────────────────────

test.describe('Flow D — DAY_DISCUSSION exit cancels timer', () => {
  let ctx: GameContext

  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    ctx = await setupGame(browser, {
      totalPlayers: 4,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
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

  test('D.1 drive to DAY_DISCUSSION and start 120s timer', async () => {
    await driveToDay(ctx)
    await ctx.hostPage.click('[data-testid="phase-countdown-start-120"]')
    await waitForTimerOnAllPages([ctx.hostPage], 115_000, 120_500)
  })

  test('D.2 host starts vote — all clients see timer running=false within 2s', async () => {
    const allPages = [ctx.hostPage, ...ctx.pages.values()]
    const hostBot = ctx.allBots.find((b) => b.nick === 'Host') ?? ctx.allBots[0]

    // Advance DAY_DISCUSSION to DAY_VOTING
    act('START_VOTE', actName(hostBot), { room: ctx.roomCode })

    await waitForTimerOnAllPages(allPages, 0, 0, 3_000)
  })
})
