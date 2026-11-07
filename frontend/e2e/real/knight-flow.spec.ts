/**
 * Real-backend E2E: Knight (騎士) duel.
 *
 *  1. During the sheriff campaign (SIGNUP, SPEECH) the knight's Action menu has
 *     no 決鬥 item — the duel window opens only after the night result is revealed.
 *  2. After the reveal the knight duels a wolf: the wolf dies, every browser shows
 *     the duel banner, the host only gets 進入夜晚 (daySkipVoting), and the
 *     遊戲記錄 drawer has the duel entry.
 *  3. In a second game the knight duels a good player: the knight dies and the
 *     host can still start the vote.
 *
 *  Same conventions as self-destruct-flow.spec.ts: testid locators, condition
 *  polling, assertions on observable DOM + backend state.
 */
import { expect, type Page, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { type BotInfo, type RoleName, sheriff } from './helpers/shell-runner'
import { verifyAllBrowsersPhase } from './helpers/assertions'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { driveMinimalNight1ViaDom, waitForDayDiscussionAfterSheriff } from './helpers/night-driver'
import { advanceAllSheriffSpeeches, waitForSheriffSubPhase } from './helpers/flow-drivers'
import {
  fetchGameState,
  readAlivePlayerIds,
  readHostUserId,
  waitForDaySkipVoting,
  waitForDaySubPhase,
  waitForPhase,
  waitForUiSubPhase,
} from './helpers/state-polling'

// 9 players = 3 wolves / SEER + WITCH + KNIGHT / 3 villagers. No HUNTER, so no
// on-death shot can change the death list (memory e2e-night-drivers-avoid-killing-hunter).
const ROLES = ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'KNIGHT'] as RoleName[]
const BROWSER_ROLES = ['WEREWOLF', 'SEER', 'WITCH', 'KNIGHT', 'VILLAGER'] as RoleName[]

function nonHost(ctx: GameContext, role: RoleName): BotInfo[] {
  return (ctx.roleMap[role] ?? []).filter((b) => b.nick !== 'Host')
}

function knightPage(ctx: GameContext): Page {
  const page = ctx.pages.get('KNIGHT')
  expect(page, 'knight browser must exist').toBeTruthy()
  return page!
}

/** Night 1 with the witch passing, so `victim` dies; lands on SHERIFF_ELECTION or DAY_DISCUSSION. */
async function night1(ctx: GameContext, victim: BotInfo): Promise<void> {
  await driveMinimalNight1ViaDom(ctx, { wolfTargetSeat: victim.seat })
}

/** Host reveals the night result → DAY_DISCUSSION/RESULT_REVEALED. */
async function revealNightResult(ctx: GameContext): Promise<void> {
  const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
  await expect(revealBtn).toBeVisible({ timeout: 10_000 })
  await revealBtn.click()
  expect(
    await waitForDaySubPhase(ctx.hostPage, ctx.gameId, 'RESULT_REVEALED', 15_000),
    'host reveal landed at DAY_DISCUSSION/RESULT_REVEALED',
  ).toBe(true)
}

/** The knight opens 決鬥, picks `target` and confirms. */
async function duel(page: Page, target: BotInfo): Promise<void> {
  await page.getByTestId('action-menu-btn').click()
  await page.getByTestId('action-menu-knight-duel').click()
  const targetBtn = page.getByTestId(`action-menu-duel-${target.userId}`)
  await expect(targetBtn).toBeVisible({ timeout: 5_000 })
  await targetBtn.click()
  await page.getByTestId('action-menu-duel-confirm').click()
}

/** Open the Action menu, assert there is no 決鬥 item, close it again. */
async function expectNoDuelItem(page: Page): Promise<void> {
  await page.getByTestId('action-menu-btn').click()
  await expect(page.locator('.action-menu-sheet')).toBeVisible({ timeout: 5_000 })
  await expect(page.getByTestId('action-menu-knight-duel')).toHaveCount(0)
  await page.locator('.action-menu-backdrop').click({ position: { x: 5, y: 5 } })
  await expect(page.locator('.action-menu-sheet')).toBeHidden({ timeout: 5_000 })
}

test.describe('Knight duel during the sheriff campaign and against a wolf', () => {
  // One game per describe: a later test needs the earlier ones, so a retry must
  // replay the group from its first test, not resume mid-game after beforeAll.
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(process.env.CI ? 360_000 : 180_000)

  let ctx: GameContext
  // victim = N1 wolf kill; campaigner = the only sheriff candidate (wins the badge).
  // Both are non-host villagers, so the sheriff is never the duel's loser here.
  let victim: BotInfo
  let campaigner: BotInfo

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: true,
      roles: ROLES,
      browserRoles: BROWSER_ROLES,
    })
    const villagers = nonHost(ctx, 'VILLAGER')
    expect(villagers.length, 'need two non-host villagers').toBeGreaterThanOrEqual(2)
    ;[victim, campaigner] = villagers as [BotInfo, BotInfo]
  })

  test.afterAll(async () => {
    await ctx?.cleanup()
  })

  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status === 'failed' && ctx?.pages) {
      await attachCompositeOnFailure(ctx.pages, testInfo)
    }
  })

  test('knight has no 決鬥 during the sheriff campaign', async () => {
    const kp = knightPage(ctx)
    await night1(ctx, victim)
    expect(
      await waitForPhase(ctx.hostPage, ctx.gameId, 'SHERIFF_ELECTION', 15_000),
      'Day 1 + sheriff on → SHERIFF_ELECTION',
    ).toBe(true)

    await waitForUiSubPhase(kp, 'SIGNUP')
    await expectNoDuelItem(kp)

    // SIGNUP → SPEECH fires once every alive player has decided: one candidate, everyone else passes.
    sheriff('campaign', { player: campaigner.nick, room: ctx.roomCode })
    const hostUserId = await readHostUserId(ctx.hostPage)
    const everyone = Object.values(ctx.roleMap).flatMap((b) => b ?? [])
    for (const userId of await readAlivePlayerIds(ctx.hostPage, ctx.gameId)) {
      if (userId === campaigner.userId) continue
      const nick = userId === hostUserId ? 'HOST' : everyone.find((b) => b.userId === userId)?.nick
      if (nick) sheriff('pass', { player: nick, room: ctx.roomCode })
    }
    expect(await waitForSheriffSubPhase(ctx.hostPage, ctx.gameId, 'SPEECH', 15_000)).toBe(true)

    await waitForUiSubPhase(kp, 'SPEECH')
    await expectNoDuelItem(kp)
  })

  test('knight duels a wolf after the reveal', async ({}, testInfo) => {
    // Finish the election: bots vote for the candidate, the candidate and the host abstain.
    await advanceAllSheriffSpeeches(ctx.hostPage, ctx.gameId)
    expect(await waitForSheriffSubPhase(ctx.hostPage, ctx.gameId, 'VOTING', 15_000)).toBe(true)
    sheriff('vote', { target: campaigner.nick, room: ctx.roomCode })
    sheriff('abstain', { player: campaigner.nick, room: ctx.roomCode })
    const abstainBtn = ctx.hostPage.getByTestId('sheriff-abstain')
    await expect(abstainBtn).toBeVisible({ timeout: 10_000 })
    await abstainBtn.click()
    const sheriffReveal = ctx.hostPage.getByTestId('sheriff-reveal-result')
    await expect(sheriffReveal).toBeEnabled({ timeout: 15_000 })
    await sheriffReveal.click()
    await waitForDayDiscussionAfterSheriff(ctx)
    await revealNightResult(ctx)

    // Before the duel the host's footer is the vote-starter.
    await expect(ctx.hostPage.getByTestId('day-start-vote')).toBeVisible({ timeout: 10_000 })

    const wolf = nonHost(ctx, 'WEREWOLF')[0]
    expect(wolf, 'need a non-host wolf to duel').toBeDefined()
    const knight = ctx.roleMap.KNIGHT?.[0]
    expect(knight, 'need a knight').toBeDefined()
    await duel(knightPage(ctx), wolf!)

    expect(
      await waitForDaySkipVoting(ctx.hostPage, ctx.gameId, 10_000),
      'backend daySkipVoting=true after the knight duels a wolf',
    ).toBe(true)
    const alive = await readAlivePlayerIds(ctx.hostPage, ctx.gameId)
    expect(alive.has(wolf!.userId), 'the dueled wolf is dead').toBe(false)
    expect(alive.has(knight!.userId), 'the knight survives a wolf duel').toBe(true)

    // Every browser shows the duel banner.
    for (const page of new Set(ctx.pages.values())) {
      await expect(page.getByTestId('day-banner-knight-duel')).toBeVisible({ timeout: 10_000 })
    }
    await expect(
      ctx.hostPage.getByTestId(`day-knight-duel-knight-seat-${knight!.seat}`),
    ).toBeVisible()
    await expect(
      ctx.hostPage.getByTestId(`day-knight-duel-target-seat-${wolf!.seat}`),
    ).toBeVisible()

    // The host only gets 進入夜晚 — no vote today.
    await expect(ctx.hostPage.getByTestId('day-enter-night')).toBeVisible({ timeout: 10_000 })
    await expect(ctx.hostPage.getByTestId('day-start-vote')).toHaveCount(0)

    // 遊戲記錄 has the duel entry.
    await ctx.hostPage.getByTestId('log-fab').click()
    await expect(ctx.hostPage.getByTestId('log-knight-duel')).toBeVisible({ timeout: 5_000 })
    await captureSnapshot(ctx.pages, testInfo, 'knight-duel-wolf')
    await ctx.hostPage.getByTestId('action-log-close').click()
    await expect(ctx.hostPage.getByTestId('log-knight-duel')).toBeHidden({ timeout: 5_000 })
  })
})

test.describe('Knight duel against a good player', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(process.env.CI ? 360_000 : 180_000)

  let ctx: GameContext

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120_000)
    // No sheriff: this game only needs a revealed day (the campaign is covered above).
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ROLES,
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

  test('knight duels a good player', async () => {
    const villagers = nonHost(ctx, 'VILLAGER')
    expect(villagers.length, 'need two non-host villagers').toBeGreaterThanOrEqual(2)
    const [victim, target] = villagers as [BotInfo, BotInfo]
    const knight = ctx.roleMap.KNIGHT?.[0]
    expect(knight, 'need a knight').toBeDefined()

    await night1(ctx, victim)
    await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)
    await revealNightResult(ctx)

    await duel(knightPage(ctx), target)

    // The good target survives; the knight dies; the day keeps its vote.
    await expect(ctx.hostPage.getByTestId('day-banner-knight-duel')).toBeVisible({
      timeout: 10_000,
    })
    const alive = await readAlivePlayerIds(ctx.hostPage, ctx.gameId)
    expect(alive.has(knight!.userId), 'the knight dies after dueling a good player').toBe(false)
    expect(alive.has(target.userId), 'the good target survives').toBe(true)
    const state = await fetchGameState(ctx.hostPage, ctx.gameId)
    expect(state?.daySkipVoting, 'a good-player duel keeps the vote').toBe(false)
    expect(state?.dayPhase?.subPhase).toBe('RESULT_REVEALED')

    const startVote = ctx.hostPage.getByTestId('day-start-vote')
    await expect(startVote).toBeVisible({ timeout: 10_000 })
    await expect(ctx.hostPage.getByTestId('day-enter-night')).toHaveCount(0)
    await startVote.click()
    expect(
      await waitForPhase(ctx.hostPage, ctx.gameId, 'DAY_VOTING', 15_000),
      'host started the vote after the good-player duel',
    ).toBe(true)
  })
})
