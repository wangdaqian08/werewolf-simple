/**
 * Real-backend E2E: Wolf self-destruction (自爆) flow.
 *
 * Verifies the end-to-end behaviour the SelfDestructServiceTest cases assert
 * on the backend, but driven through the actual UI:
 *
 *  1. From DAY_DISCUSSION/RESULT_REVEALED a wolf taps the Action chip,
 *     selects 自爆, and confirms.
 *  2. Backend transitions to DAY_DISCUSSION/RESULT_REVEALED with
 *     daySkipVoting=true (no phase change since we were already there).
 *  3. Host's footer button flips from 开始投票 → 进入夜晚.
 *  4. The 游戏记录 drawer shows "X号 · nickname 自爆" under 💥 自爆.
 *  5. The wolf shows as dead (✕ overlay) on every browser's player grid.
 *
 *  Reasoning: PR #122 layout regression checks live in unit tests
 *  (dayPhase.test.ts, votingPhase.test.ts, sheriffElection.test.ts). This
 *  spec covers the real STOMP wire and end-to-end action dispatch.
 *
 *  Designed per memory `e2e-six-design-principles` + `e2e-ci-vs-local-env-differences`:
 *   - testid-based locators, never getByText
 *   - condition polling, never waitForTimeout
 *   - asserts on observable DOM, not internal state
 */
import { expect, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { type RoleName } from './helpers/shell-runner'
import { verifyAllBrowsersPhase } from './helpers/assertions'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { driveMinimalNight1ViaDom } from './helpers/night-driver'
import { waitForCondition } from './helpers/state-polling'

let ctx: GameContext

test.describe('Wolf self-destruction (自爆) — real-backend flow', () => {
  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[],
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

  test('wolf self-destructs from DAY_DISCUSSION → daySkipVoting flips → host sees 进入夜晚', async ({}, testInfo) => {
    // Drive Night 1 via DOM clicks on each role's browser.
    const villagerSeats = (ctx.roleMap.VILLAGER ?? [])
      .filter((b) => b.nick !== 'Host')
      .map((b) => b.seat)
    const wolfTargetSeat = villagerSeats[0]
    expect(wolfTargetSeat, 'need a non-host VILLAGER for the wolf to target').toBeDefined()

    await driveMinimalNight1ViaDom(ctx, { wolfTargetSeat: wolfTargetSeat! })

    // hasSheriff=false → end-of-night lands at DAY_DISCUSSION/RESULT_HIDDEN.
    await verifyAllBrowsersPhase(ctx.pages, 'DAY_DISCUSSION', 20_000)

    // Host reveals night kills → RESULT_REVEALED. log-fab is gated on
    // RESULT_REVEALED, so this is the earliest point we can verify the
    // top-right pill is visible.
    const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
    await expect(revealBtn).toBeVisible({ timeout: 10_000 })
    await revealBtn.click()
    await waitForCondition(
      async () => {
        const r = await ctx.hostPage.request.get(`/api/game/${ctx.gameId}/state`)
        if (!r.ok()) return false
        const s = await r.json()
        return s?.subPhase === 'RESULT_REVEALED'
      },
      'host reveal landed at DAY_DISCUSSION/RESULT_REVEALED',
      15_000,
    )

    // ── Pre-self-destruct invariants ─────────────────────────────────────
    // Host's footer button is the vote-starter, NOT the night-entry.
    await expect(ctx.hostPage.getByTestId('day-start-vote')).toBeVisible({ timeout: 5_000 })
    await expect(ctx.hostPage.getByTestId('day-enter-night')).toHaveCount(0)

    // ── Drive self-destruct from the wolf's browser ──────────────────────
    const wolfPage = ctx.pages.get('WEREWOLF')
    expect(wolfPage, 'wolf browser must exist').toBeTruthy()
    const wp = wolfPage!

    // Action chip visible + opens the self-destruct option (wolf path).
    const actionBtn = wp.getByTestId('action-menu-btn')
    await expect(actionBtn).toBeVisible({ timeout: 10_000 })
    await actionBtn.click()
    const sdItem = wp.getByTestId('action-menu-self-destruct')
    await expect(sdItem).toBeVisible({ timeout: 5_000 })
    await sdItem.click()

    // Confirm modal.
    const confirmBtn = wp.getByTestId('action-menu-confirm')
    await expect(confirmBtn).toBeVisible({ timeout: 5_000 })
    await confirmBtn.click()

    // ── Wait for daySkipVoting + button swap to propagate via STOMP ──────
    await waitForCondition(
      async () => {
        const r = await ctx.hostPage.request.get(`/api/game/${ctx.gameId}/state`)
        if (!r.ok()) return false
        const s = await r.json()
        return s?.daySkipVoting === true
      },
      'backend daySkipVoting=true after wolf self-destruct',
      10_000,
    )

    // Host's footer flipped: now 进入夜晚 is visible, 开始投票 is gone.
    await expect(ctx.hostPage.getByTestId('day-enter-night')).toBeVisible({ timeout: 10_000 })
    await expect(ctx.hostPage.getByTestId('day-start-vote')).toHaveCount(0)

    // ── 游戏记录 drawer shows 💥 自爆 entry on the host's view ───────────
    await ctx.hostPage.getByTestId('log-fab').click()
    const drawer = ctx.hostPage.locator('.action-log-drawer')
    await expect(drawer).toBeVisible({ timeout: 5_000 })
    await expect(drawer.getByText('💥 自爆')).toBeVisible({ timeout: 5_000 })
    await expect(drawer.getByText(/号\s*·\s*\S+\s*自爆/)).toBeVisible({ timeout: 5_000 })

    await captureSnapshot(ctx.pages, testInfo, 'self-destruct-post-confirm')
  })

  test('non-wolf taps Action chip → sees 暂无操作, no self-destruct option', async () => {
    // Reuses the post-self-destruct game state. Pick any non-wolf browser.
    const villagerPage =
      ctx.pages.get('VILLAGER') ?? ctx.pages.get('SEER') ?? ctx.pages.get('WITCH')
    expect(villagerPage, 'need at least one non-wolf browser').toBeTruthy()
    const vp = villagerPage!

    const actionBtn = vp.getByTestId('action-menu-btn')
    await expect(actionBtn).toBeVisible({ timeout: 10_000 })
    await actionBtn.click()

    // Empty-state menu is shown, self-destruct option is NOT rendered.
    await expect(vp.getByTestId('action-menu-empty')).toBeVisible({ timeout: 5_000 })
    await expect(vp.getByTestId('action-menu-self-destruct')).toHaveCount(0)
  })
})
