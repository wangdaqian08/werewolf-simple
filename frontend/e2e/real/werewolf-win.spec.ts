/**
 * Real-backend E2E: Werewolf win — play until wolves win and verify the result screen
 * shows all players' roles revealed.
 *
 * Strategy:
 *   - 9-player game, no sheriff (simpler flow)
 *   - Each round: wolves kill a villager, village abstains vote (no elimination)
 *   - After enough rounds, wolves outnumber village → GAME_OVER
 *   - Verify result screen shows winner + all roles
 */
import {expect, test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act, type RoleName} from './helpers/shell-runner'
import {verifyAllBrowsersPhase} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'

let ctx: GameContext

test.describe('Werewolf win — result screen shows all roles', () => {
  test.setTimeout(180_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
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

  /** Try a script action, return false if it fails (dead player, wrong phase, etc.) */
  function tryAct(...args: Parameters<typeof act>): boolean {
    try {
      const output = act(...args)
      return !output.includes('rejected') && !output.includes('fail')
    } catch {
      return false
    }
  }

  /**
   * Complete a full night round via scripts + browser fallback.
   * Uses browser-page UI waits (not fixed timeouts) to detect sub-phase transitions.
   */
  async function completeNight(targetSeat: number) {
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    const witchBots = ctx.roleMap.WITCH ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const seerPage = ctx.pages.get('SEER')
    const witchPage = ctx.pages.get('WITCH')
    const guardPage = ctx.pages.get('GUARD')

    // ── Wolf kill ──
    const wolfPage = ctx.pages.get('WEREWOLF')
    if (wolfPage) {
      await wolfPage
        .locator('.player-grid')
        .first()
        .waitFor({ state: 'visible', timeout: 10_000 })
        .catch(() => {})
    }
    let wolfDone = false
    for (const wb of wolfBots.filter((b) => b.nick !== 'Host')) {
      if (tryAct('WOLF_KILL', wb.nick, { target: String(targetSeat), room: ctx.roomCode })) {
        wolfDone = true
        break
      }
    }
    // Browser fallback: use wolf browser page (works whether host is wolf or not)
    if (!wolfDone && wolfPage) {
      const slot = wolfPage.locator('.player-grid .slot-alive').first()
      if (await slot.isVisible({ timeout: 5_000 }).catch(() => false)) {
        await slot.click()
        await wolfPage.waitForTimeout(500)
        await wolfPage.getByRole('button', { name: /确认袭击|Confirm/i }).click()
      }
    }

    // Wait for seer sub-phase (or timeout if dead/skipped)
    if (seerPage) {
      await seerPage
        .getByText(/选择查验目标|Select a player to check/i)
        .first()
        .waitFor({ state: 'visible', timeout: 8_000 })
        .catch(() => {})
    }

    // ── Seer ── (may be dead in later rounds)
    let seerDone = false
    for (const sb of seerBots.filter((b) => b.nick !== 'Host')) {
      const allSeats = [targetSeat, 1, 2, 3, 4, 5, 6, 7, 8, 9]
      for (const seat of allSeats) {
        if (tryAct('SEER_CHECK', sb.nick, { target: String(seat), room: ctx.roomCode })) {
          seerDone = true
          // Wait for seer result before confirming
          if (seerPage) {
            await seerPage
              .locator('.sr-wrap')
              .first()
              .waitFor({ state: 'visible', timeout: 8_000 })
              .catch(() => {})
          }
          tryAct('SEER_CONFIRM', sb.nick, { room: ctx.roomCode })
          break
        }
      }
      if (seerDone) break
    }
    if (!seerDone && seerPage) {
      if (
        await seerPage
          .getByText(/选择查验目标|Select a player to check/i)
          .first()
          .isVisible()
          .catch(() => false)
      ) {
        await seerPage.locator('.player-grid .slot-alive').first().click()
        await seerPage.getByRole('button', { name: /查验|Check/i }).click()
        await expect(seerPage.locator('.sr-wrap').first()).toBeVisible({ timeout: 10_000 })
        await seerPage.getByRole('button', { name: /查验完毕|Done/i }).click()
      }
    }

    // Wait for witch sub-phase (or timeout if dead/skipped)
    if (witchPage) {
      await witchPage
        .locator('.w-section')
        .first()
        .waitFor({ state: 'visible', timeout: 8_000 })
        .catch(() => {})
    }

    // ── Witch ── (may be dead in later rounds)
    let witchDone = false
    for (const wb of witchBots.filter((b) => b.nick !== 'Host')) {
      witchDone = tryAct('WITCH_ACT', wb.nick, {
        payload: '{"useAntidote":false}',
        room: ctx.roomCode,
      })
      if (witchDone) break
    }
    if (!witchDone && witchPage) {
      if (await witchPage.locator('.w-section').first().isVisible().catch(() => false)) {
        const passBtn = witchPage.getByRole('button', { name: /放弃/ })
        if (await passBtn.isVisible().catch(() => false)) await passBtn.click()
        await witchPage.waitForTimeout(500)
        const skipBtn = witchPage.getByRole('button', { name: /不用/ })
        if (await skipBtn.isVisible().catch(() => false)) await skipBtn.click()
        const doneBtn = witchPage.getByRole('button', { name: /完成操作|Done/i })
        if (await doneBtn.isVisible().catch(() => false)) await doneBtn.click()
      }
    }

    // Wait for guard sub-phase (or timeout if dead/skipped)
    if (guardPage) {
      await guardPage
        .getByText(/选择守护目标|Protect a player/i)
        .first()
        .waitFor({ state: 'visible', timeout: 8_000 })
        .catch(() => {})
    }

    // ── Guard ── (may be dead in later rounds)
    let guardDone = false
    for (const gb of guardBots.filter((b) => b.nick !== 'Host')) {
      guardDone = tryAct('GUARD_SKIP', gb.nick, { room: ctx.roomCode })
      break
    }
    if (!guardDone && guardPage) {
      if (
        await guardPage
          .getByText(/选择守护目标|Protect a player/i)
          .first()
          .isVisible()
          .catch(() => false)
      ) {
        // Pick LAST alive player to avoid protecting the wolf's target (which is first)
        await guardPage.locator('.player-grid .slot-alive').last().click()
        await guardPage.getByRole('button', { name: /确认保护|Confirm/i }).click()
      }
    }
  }

  /** Complete a day phase: host reveals result, starts vote, everyone abstains. Handles revotes. */
  async function completeDay() {
    const hostPage = ctx.hostPage

    // Host reveals night result
    const revealBtn = hostPage.getByRole('button', { name: /显示结果|Result/i })
    if (await revealBtn.isVisible({ timeout: 10_000 }).catch(() => false)) {
      await revealBtn.click()
      await hostPage.waitForTimeout(1_000)
    }

    // Host starts vote
    const startVoteBtn = hostPage.getByRole('button', { name: /开始投票|Start Vote/i })
    if (await startVoteBtn.isVisible({ timeout: 10_000 }).catch(() => false)) {
      await startVoteBtn.click()
      await hostPage.waitForTimeout(1_000)
    }

    // Vote cycle — repeat if revote triggered (tie)
    for (let attempt = 0; attempt < 3; attempt++) {
      const abstainBtn = hostPage.locator('.skip-btn').first()
      if (await abstainBtn.isVisible({ timeout: 10_000 }).catch(() => false)) {
        await abstainBtn.click()
        await hostPage.waitForTimeout(500)
      }

      tryAct('SUBMIT_VOTE', undefined, { room: ctx.roomCode })
      await hostPage.waitForTimeout(2_000)

      tryAct('VOTING_REVEAL_TALLY', 'HOST', { room: ctx.roomCode })
      await hostPage.waitForTimeout(2_000)

      tryAct('VOTING_CONTINUE', 'HOST', { room: ctx.roomCode })
      await hostPage.waitForTimeout(3_000)

      // Check if still in voting (revote) — if skip-btn reappears, another round needed
      const stillVoting = await hostPage.locator('.skip-btn').first().isVisible().catch(() => false)
      if (!stillVoting) break
    }
  }

  test('1. Start night — all browsers transition', async ({}, testInfo) => {
    const hostPage = ctx.hostPage
    const startNightBtn = hostPage.getByRole('button', { name: /开始夜晚|Start Night/i })
    if (!(await startNightBtn.isVisible().catch(() => false))) {
      await startNightBtn.waitFor({ state: 'visible', timeout: 10_000 })
    }
    await startNightBtn.click()
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)
    await captureSnapshot(ctx.pages, testInfo, '01-night-start')
  })

  test('2. Play rounds until werewolf wins, verify result screen', async ({}, testInfo) => {
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const seerBots = ctx.roleMap.SEER ?? []
    const guardBots = ctx.roleMap.GUARD ?? []
    const witchBots = ctx.roleMap.WITCH ?? []

    // Targets: kill non-wolf, non-host players each round (host must stay alive for game management)
    const targets = [...villagerBots, ...seerBots, ...guardBots, ...witchBots]
      .filter((b) => b.nick !== 'Host')
      .map((b) => b.seat)

    const maxRounds = 6

    for (let round = 0; round < maxRounds; round++) {
      if (ctx.hostPage.url().includes('/result/')) break

      // Night
      const targetSeat = targets[round % targets.length]
      await completeNight(targetSeat)

      // Wait for DAY or GAME_OVER
      await ctx.hostPage.waitForTimeout(5_000)
      if (ctx.hostPage.url().includes('/result/')) break

      // Day
      await completeDay()
      await ctx.hostPage.waitForTimeout(3_000)
      if (ctx.hostPage.url().includes('/result/')) break
    }

    // Game should be over — verify result screen
    await ctx.hostPage.waitForURL(/\/result\//, { timeout: 30_000 })
    await captureSnapshot(ctx.pages, testInfo, '02-game-over')

    const hostPage = ctx.hostPage
    await expect(hostPage.locator('.result-wrap')).toBeVisible({ timeout: 10_000 })

    // Winner displayed
    await expect(hostPage.locator('.outcome-title')).toBeVisible({ timeout: 5_000 })

    // "ROLES REVEALED" section
    await expect(hostPage.getByText(/身份揭露|ROLES REVEALED/i)).toBeVisible({ timeout: 5_000 })

    // All 9 players' roles shown
    const rolePills = hostPage.locator('.role-pill')
    const pillCount = await rolePills.count()
    expect(pillCount).toBe(9)

    // Wolf roles visible
    const wolfPills = hostPage.locator('.role-pill').filter({ hasText: /狼人/ })
    expect(await wolfPills.count()).toBeGreaterThanOrEqual(1)

    // Village roles visible
    const villagePills = hostPage.locator('.role-pill').filter({ hasText: /村民|预言家|女巫|守卫/ })
    expect(await villagePills.count()).toBeGreaterThanOrEqual(1)

    await captureSnapshot(ctx.pages, testInfo, '02-result-roles-revealed')
  })
})
