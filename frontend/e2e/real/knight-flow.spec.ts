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
 *  4. In a third game the host is made the knight (test-only role swap): the host's
 *     menu has 宣布玩家出局 but no 決鬥 during the campaign, both after the reveal,
 *     and the host's duel against a wolf ends the day's vote.
 *
 *  Same conventions as self-destruct-flow.spec.ts: testid locators, condition
 *  polling, assertions on observable DOM + backend state.
 */
import { expect, type Page, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { type BotInfo, getRoles, type RoleName, sheriff } from './helpers/shell-runner'
import { verifyAllBrowsersPhase } from './helpers/assertions'
import { swapRoles } from './helpers/test-support'
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
  await duelFromOpenMenu(page, target)
}

/** With the Action menu open: 決鬥 → `target` → confirm. */
async function duelFromOpenMenu(page: Page, target: BotInfo): Promise<void> {
  await page.getByTestId('action-menu-knight-duel').click()
  const targetBtn = page.getByTestId(`action-menu-duel-${target.userId}`)
  await expect(targetBtn).toBeVisible({ timeout: 5_000 })
  await targetBtn.click()
  await page.getByTestId('action-menu-duel-confirm').click()
}

/**
 * Open the Action menu, assert there is no 決鬥 item, close it again. The menu has
 * rendered once its content shows: the empty state for a guest, 宣布玩家出局 for the host.
 */
async function expectNoDuelItem(page: Page, isHost: boolean): Promise<void> {
  await page.getByTestId('action-menu-btn').click()
  const content = page.getByTestId(isHost ? 'action-menu-declare-out' : 'action-menu-empty')
  await expect(content).toBeVisible({ timeout: 5_000 })
  await expect(page.getByTestId('action-menu-knight-duel')).toHaveCount(0)
  await page.getByTestId('action-menu-backdrop').click({ position: { x: 5, y: 5 } })
  await expect(content).toBeHidden({ timeout: 5_000 })
}

/** SIGNUP → SPEECH: `campaigner` runs, every other alive player (the host included) passes. */
async function campaignToSpeech(ctx: GameContext, campaigner: BotInfo): Promise<void> {
  sheriff('campaign', { player: campaigner.nick, room: ctx.roomCode })
  const hostUserId = await readHostUserId(ctx.hostPage)
  const everyone = Object.values(ctx.roleMap).flatMap((b) => b ?? [])
  for (const userId of await readAlivePlayerIds(ctx.hostPage, ctx.gameId)) {
    if (userId === campaigner.userId) continue
    const nick = userId === hostUserId ? 'HOST' : everyone.find((b) => b.userId === userId)?.nick
    if (nick) sheriff('pass', { player: nick, room: ctx.roomCode })
  }
  expect(await waitForSheriffSubPhase(ctx.hostPage, ctx.gameId, 'SPEECH', 15_000)).toBe(true)
}

/** SPEECH → badge to `campaigner` → night result revealed (DAY_DISCUSSION/RESULT_REVEALED). */
async function electAndReveal(ctx: GameContext, campaigner: BotInfo): Promise<void> {
  // Bots vote for the candidate, the candidate and the host abstain.
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
}

/**
 * Hand the host the KNIGHT (still ROLE_REVEAL — setupGame leaves the game there
 * until the host starts the night; confirmation is role-independent). The host
 * re-reveals and re-confirms its card on reload, which also proves the card shows
 * the knight; the former knight's page reloads onto its new role. ctx is rewritten
 * so the night drivers find the right pages.
 */
async function makeHostTheKnight(ctx: GameContext): Promise<void> {
  const hostUserId = await readHostUserId(ctx.hostPage)
  expect(hostUserId, 'host userId').toBeTruthy()
  const oldHostRole = ctx.hostRole
  expect(oldHostRole, 'host role must be known').toBeTruthy()
  if (oldHostRole !== 'KNIGHT') {
    const knightBot = ctx.roleMap.KNIGHT?.[0]
    const knightBotPage = ctx.pages.get('KNIGHT')
    expect(knightBot, 'need the dealt knight').toBeDefined()
    expect(knightBotPage, 'knight browser must exist').toBeTruthy()
    await swapRoles(ctx.hostPage, Number(ctx.gameId), hostUserId!, knightBot!.userId)

    // Every role is in BROWSER_ROLES, so the host's old role had the host page.
    ctx.pages.set('KNIGHT', ctx.hostPage)
    ctx.pages.set(oldHostRole!, knightBotPage!)
    ctx.bots.delete('KNIGHT')
    ctx.bots.set(oldHostRole!, knightBot!)
    ctx.roleMap = getRoles(ctx.roomCode)
    ctx.hostRole = 'KNIGHT'
    ctx.isHostRole = (role) => role === 'KNIGHT'

    await knightBotPage!.reload()
    // The reload brings the role card back; it must show the page's NEW role.
    const revealBtn = knightBotPage!.getByTestId('reveal-role-btn')
    await revealBtn.waitFor({ state: 'visible', timeout: 15_000 })
    await revealBtn.click()
    await expect(knightBotPage!.getByTestId('role-reveal-card')).toHaveAttribute(
      'data-role',
      oldHostRole!,
    )
    // Re-confirm (idempotent on the backend) so the page is back on the waiting screen.
    await knightBotPage!.getByTestId('confirm-role-btn').click()
  }

  const state = await fetchGameState(ctx.hostPage, ctx.gameId)
  expect(state?.phase).toBe('ROLE_REVEAL')
  expect(state?.myRole, 'backend: the host holds KNIGHT').toBe('KNIGHT')
  expect(
    ctx.roleMap.KNIGHT?.map((b) => b.userId),
    'roles.sh: the host is the only knight',
  ).toEqual([hostUserId])

  // GameView's confirmed flag is local, so a reload brings the role card back.
  await ctx.hostPage.reload()
  await ctx.hostPage.getByTestId('reveal-role-btn').click()
  await expect(ctx.hostPage.getByTestId('role-reveal-card')).toHaveAttribute('data-role', 'KNIGHT')
  await ctx.hostPage.getByTestId('confirm-role-btn').click()
  await expect(ctx.hostPage.getByTestId('start-night')).toBeVisible({ timeout: 15_000 })
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
    await expectNoDuelItem(kp, kp === ctx.hostPage)

    // SIGNUP → SPEECH fires once every alive player has decided.
    await campaignToSpeech(ctx, campaigner)

    await waitForUiSubPhase(kp, 'SPEECH')
    await expectNoDuelItem(kp, kp === ctx.hostPage)
  })

  test('knight duels a wolf after the reveal', async ({}, testInfo) => {
    await electAndReveal(ctx, campaigner)

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

test.describe('host is the knight', () => {
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(process.env.CI ? 360_000 : 180_000)

  let ctx: GameContext
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
  })

  test.afterAll(async () => {
    await ctx?.cleanup()
  })

  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status === 'failed' && ctx?.pages) {
      await attachCompositeOnFailure(ctx.pages, testInfo)
    }
  })

  test('host knight: 宣布玩家出局 but no 決鬥 during the campaign', async () => {
    await makeHostTheKnight(ctx)
    // Picked after the swap: the host's old role may have been a villager.
    const villagers = nonHost(ctx, 'VILLAGER')
    expect(villagers.length, 'need two non-host villagers').toBeGreaterThanOrEqual(2)
    ;[victim, campaigner] = villagers as [BotInfo, BotInfo]

    await night1(ctx, victim)
    expect(
      await waitForPhase(ctx.hostPage, ctx.gameId, 'SHERIFF_ELECTION', 15_000),
      'Day 1 + sheriff on → SHERIFF_ELECTION',
    ).toBe(true)

    await waitForUiSubPhase(ctx.hostPage, 'SIGNUP')
    await expectNoDuelItem(ctx.hostPage, true)
    await campaignToSpeech(ctx, campaigner)
    await waitForUiSubPhase(ctx.hostPage, 'SPEECH')
    await expectNoDuelItem(ctx.hostPage, true)
  })

  test('host knight duels a wolf after the reveal', async ({}, testInfo) => {
    await electAndReveal(ctx, campaigner)
    const hp = ctx.hostPage
    const hostUserId = await readHostUserId(hp)
    const wolf = nonHost(ctx, 'WEREWOLF')[0]
    expect(wolf, 'need a non-host wolf to duel').toBeDefined()

    await hp.getByTestId('action-menu-btn').click()
    await expect(hp.getByTestId('action-menu-declare-out')).toBeVisible({ timeout: 5_000 })
    await expect(hp.getByTestId('action-menu-knight-duel')).toBeVisible()
    await duelFromOpenMenu(hp, wolf!)

    expect(
      await waitForDaySkipVoting(hp, ctx.gameId, 10_000),
      'backend daySkipVoting=true after the host knight duels a wolf',
    ).toBe(true)
    const alive = await readAlivePlayerIds(hp, ctx.gameId)
    expect(alive.has(wolf!.userId), 'the dueled wolf is dead').toBe(false)
    expect(alive.has(hostUserId!), 'the host knight survives a wolf duel').toBe(true)

    const seerPage = ctx.pages.get('SEER')
    expect(seerPage, 'seer browser must exist').toBeTruthy()
    for (const page of [hp, seerPage!]) {
      await expect(page.getByTestId('day-banner-knight-duel')).toBeVisible({ timeout: 10_000 })
    }
    await expect(hp.getByTestId('day-enter-night')).toBeVisible({ timeout: 10_000 })
    await expect(hp.getByTestId('day-start-vote')).toHaveCount(0)
    await captureSnapshot(ctx.pages, testInfo, 'host-knight-duel-wolf')
  })
})
