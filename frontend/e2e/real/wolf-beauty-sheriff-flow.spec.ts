/**
 * 狼美人 (WOLF_BEAUTY) with the sheriff election — real-backend evidence for
 * docs/superpowers/specs/2026-11-18-wolf-beauty-demon-hunter-design.md (W6, W12, X2, X4,
 * Q10, Q13, decision G).
 *
 * Every game: 9 seats, sheriff ON, 空刀 allowed — 2 wolves + 狼美人, seer, witch, hunter,
 * knight and 2 villagers. In each game, in this order:
 *  1. deal the roles (start the game); the host becomes a villager (swapped in ROLE_REVEAL),
 *  2. name every seat from the real dealt roles,
 *  3. N1 (empty knife; the 狼美人 only confirms — no charm on night 1, O1), then the day-1
 *     警長競選: two candidates sign up, everyone else passes, the host runs the speeches
 *     through its UI, the voters split 4 : 2 for the chosen player, the host abstains,
 *  4. the scenario:
 *   A. the charmed player is the sheriff (X2, W12, G): the seer wins the badge; D1 is quiet;
 *      N2 the 狼美人 charms the seer; D2 the table votes the 狼美人 out → the seer 殉情 and,
 *      dead, hands the badge over on the BADGE_HANDOVER screen, which shows the 殉情 banner.
 *   B. the 狼美人 is the sheriff and is shot (X4, Q10): the 狼美人 wins the badge; N2 it
 *      charms the villager; D2 the hunter is voted out and shoots it → the villager dies at
 *      the shot, the 狼美人 stays alive through its badge handover, cannot give the badge to
 *      the dead villager, and dies only once the badge has passed.
 *
 * The D2 votes are unanimous, so the sheriff's 1.5 weight cannot change the exile.
 *
 * CI-vs-local (memory feedback_e2e_ci_vs_local): every action is gated on the backend
 * (sub-)phase, never on a sleep; timeouts scale 2× under CI. The election is driven per
 * player (never the all-bots fan-out, whose candidate votes are rejected), so the backend log
 * must show no REJECTED action except the one Game B fires on purpose and asserts.
 * Leak scan (no game over): no page may receive another player's role, and only the
 * 狼美人's page may receive `wolfBeauty`.
 */
import { expect, type Page, test, type TestInfo } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { actName, type BotInfo, getRoles, type RoleName } from './helpers/shell-runner'
import {
  advanceAllSheriffSpeeches,
  assertNonNull,
  completeDay,
  tryAct,
  waitForSheriffSubPhase,
  waitForSubPhase,
} from './helpers/flow-drivers'
import {
  fetchGameState,
  readHostUserId,
  readUnvotedAlivePlayerIds,
  waitForCondition,
  waitForDayRevealed,
  waitForPhase,
  waitForVotingSubPhase,
} from './helpers/state-polling'
import { hostStartsNight, waitForDayDiscussionAfterSheriff } from './helpers/night-driver'
import { readBackendLogLineCount, readBackendLogSince } from './helpers/backend-log'
import { assertNoBrowserErrors } from './helpers/error-sentinel'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { cleanupRoomData, swapRoles } from './helpers/test-support'
import { findRoleLeaks, type PayloadLog, recordPayloads } from './helpers/role-leak'

// WITCH must stay in the list (setupGame role-toggle trap, see memory).
const ROLES: RoleName[] = [
  'WEREWOLF',
  'WOLF_BEAUTY',
  'VILLAGER',
  'SEER',
  'WITCH',
  'HUNTER',
  'KNIGHT',
]

const scale = (ms: number): number => (process.env.CI ? ms * 2 : ms)

interface Table {
  game: GameContext
  bPage: Page
  hostId: string
  beauty: BotInfo
  seer: BotInfo
  witch: BotInfo
  hunter: BotInfo
  knight: BotInfo
  wolves: [BotInfo, BotInfo]
  /** The villager who is not the host. */
  villager: BotInfo
  /** What each open page receives, and the role it plays after the host swap. */
  payloads: Map<Page, PayloadLog>
  roleOf: Map<Page, RoleName>
  logStart: number
}

let table: Table | undefined

const nonHost = (game: GameContext, role: RoleName): BotInfo[] =>
  (game.roleMap[role] ?? []).filter((b) => b.nick !== 'Host')

function only(game: GameContext, role: RoleName): BotInfo {
  const holder = nonHost(game, role)[0]
  assertNonNull(holder, `a ${role} bot`)
  return holder
}

/**
 * Steps 1 + 2: deal the board, make the host a villager (the swap happens in ROLE_REVEAL,
 * before the night) and name every seat. `extraPages` opens browsers beyond the 狼美人's.
 */
async function openTable(
  browser: Parameters<typeof setupGame>[0],
  extraPages: RoleName[] = [],
): Promise<Table> {
  const payloads = new Map<Page, PayloadLog>()
  const game = await setupGame(browser, {
    totalPlayers: 9,
    hasSheriff: true,
    noKillAllowed: true,
    roles: ROLES,
    // The VILLAGER page becomes the 狼美人's when the host rolled it (swap below).
    browserRoles: ['WOLF_BEAUTY', 'VILLAGER', ...extraPages],
    onPage: (_label, p) => payloads.set(p, recordPayloads(p)),
  })
  // Hand the context to afterEach before anything here can fail.
  table = { game } as Table
  const logStart = readBackendLogLineCount()
  game.markBackendLogPosition()
  const hostId = await readHostUserId(game.hostPage)
  assertNonNull(hostId, 'host userId')

  const dealtHostRole = game.hostRole
  if (dealtHostRole && dealtHostRole !== 'VILLAGER') {
    const pagedVillager = game.bots.get('VILLAGER')
    const villagerPage = game.pages.get('VILLAGER')
    assertNonNull(pagedVillager, 'a villager bot with a page')
    assertNonNull(villagerPage, 'the villager browser page')
    // setupGame put a paged role on the host's page: hand it to the villager whose page is
    // open. Any other host role goes to the villager without one, so every page keeps its role.
    const movesPage = dealtHostRole === 'WOLF_BEAUTY' || extraPages.includes(dealtHostRole)
    const partner = movesPage
      ? pagedVillager
      : nonHost(game, 'VILLAGER').find((v) => v.userId !== pagedVillager.userId)
    assertNonNull(partner, 'a dealt villager to swap with')
    await swapRoles(game.hostPage, Number(game.gameId), hostId, partner.userId)
    if (movesPage) {
      // Relabel so lookups and failure composites name the pages right.
      game.pages.set(dealtHostRole, villagerPage)
      game.pages.set('VILLAGER', game.hostPage)
      game.bots.delete('VILLAGER')
      game.bots.set(dealtHostRole, pagedVillager)
    }
    game.roleMap = getRoles(game.roomCode)
    game.hostRole = 'VILLAGER'
    game.isHostRole = (role) => role === 'VILLAGER'
    // Scan only what each page receives in its final role.
    for (const log of payloads.values()) log.bodies.length = 0
    // GameView's confirmed flag is local: a reload brings the card back with the NEW role.
    const reconfirm = async (page: Page, role: RoleName) => {
      await page.reload()
      const reveal = page.getByTestId('reveal-role-btn')
      await reveal.waitFor({ state: 'visible', timeout: scale(15_000) })
      await reveal.click()
      await expect(page.getByTestId('role-reveal-card')).toHaveAttribute('data-role', role)
      await page.getByTestId('confirm-role-btn').click()
    }
    if (movesPage) await reconfirm(villagerPage, dealtHostRole)
    await reconfirm(game.hostPage, 'VILLAGER')
    await expect(game.hostPage.getByTestId('start-night')).toBeVisible({ timeout: scale(15_000) })
  }
  const bPage = game.pages.get('WOLF_BEAUTY')
  assertNonNull(bPage, 'the 狼美人 browser page')
  const roleOf = new Map<Page, RoleName>()
  for (const [label, page] of game.pages) {
    if (label !== 'HOST') roleOf.set(page, label as RoleName)
  }
  roleOf.set(game.hostPage, 'VILLAGER')

  const [wolfA, wolfB] = nonHost(game, 'WEREWOLF')
  assertNonNull(wolfA, 'pack wolf A')
  assertNonNull(wolfB, 'pack wolf B')
  const hostSeat = (game.roleMap.VILLAGER ?? []).find((b) => b.userId === hostId)?.seat
  assertNonNull(hostSeat, 'host seat')
  table = {
    game,
    bPage,
    hostId,
    beauty: only(game, 'WOLF_BEAUTY'),
    seer: only(game, 'SEER'),
    witch: only(game, 'WITCH'),
    hunter: only(game, 'HUNTER'),
    knight: only(game, 'KNIGHT'),
    wolves: [wolfA, wolfB],
    villager: only(game, 'VILLAGER'),
    payloads,
    roleOf,
    logStart,
  }
  const t = table
  console.warn(
    `[wolf-beauty-sheriff] game=${game.gameId} host=${hostSeat} (dealt ${dealtHostRole}) ` +
      `beauty=${t.beauty.seat} wolves=${wolfA.seat},${wolfB.seat} seer=${t.seer.seat} ` +
      `witch=${t.witch.seat} hunter=${t.hunter.seat} knight=${t.knight.seat} villager=${t.villager.seat}`,
  )
  return t
}

async function reach(t: Table, subPhase: string): Promise<void> {
  expect(
    await waitForSubPhase(t.game.hostPage, t.game.gameId, subPhase, 25_000),
    `night sub-phase ${subPhase} reached`,
  ).toBe(true)
}

function bot(
  t: Table,
  action: string,
  actor: BotInfo,
  opts: { target?: number; payload?: string } = {},
): void {
  const ok = tryAct(action, actName(actor), {
    target: opts.target === undefined ? undefined : String(opts.target),
    payload: opts.payload,
    room: t.game.roomCode,
  })
  expect(ok, `${action} by ${actor.nick} accepted`).toBe(true)
}

async function isAlive(t: Table, seat: number): Promise<boolean | undefined> {
  const state = await fetchGameState(t.game.hostPage, t.game.gameId)
  return state?.players?.find((p) => p.seatIndex === seat)?.isAlive
}

async function sheriffId(t: Table): Promise<string | null | undefined> {
  const state = await fetchGameState(t.game.hostPage, t.game.gameId)
  return (state as { sheriffUserId?: string | null } | null)?.sheriffUserId
}

/** The 狼美人's turn through its own browser: night 1 只確認, later nights a charm. */
async function beautyTurn(t: Table, charm?: BotInfo): Promise<void> {
  await reach(t, 'WOLF_BEAUTY_PICK')
  await expect(t.bPage.getByTestId('wolf-beauty-turn')).toBeVisible({ timeout: scale(10_000) })
  if (!charm) {
    await expect(t.bPage.getByTestId('wolf-beauty-confirm'), 'no charm on night 1').toHaveCount(0)
    await t.bPage.getByTestId('wolf-beauty-n1-confirm').click()
    return
  }
  await t.bPage.getByTestId(`wolf-beauty-target-${charm.seat}`).click()
  await t.bPage.getByTestId('wolf-beauty-confirm').click()
}

/** An empty knife, the 狼美人's turn, the witch passes and the seer checks a wolf. */
async function night(t: Table, endPhase: string, charm?: BotInfo): Promise<void> {
  await reach(t, 'WEREWOLF_PICK')
  bot(t, 'WOLF_SKIP', t.wolves[0])
  await beautyTurn(t, charm)
  await reach(t, 'WITCH_ACT')
  bot(t, 'WITCH_ACT', t.witch, { payload: '{"useAntidote":false}' })
  await reach(t, 'SEER_PICK')
  bot(t, 'SEER_CHECK', t.seer, { target: t.wolves[0].seat })
  await reach(t, 'SEER_RESULT')
  bot(t, 'SEER_CONFIRM', t.seer)
  expect(await waitForPhase(t.game.hostPage, t.game.gameId, endPhase, 30_000)).toBe(true)
}

/**
 * Step 3: N1, then the day-1 election steered so `winner` takes the badge: `winner` and
 * `rival` sign up, everyone else passes (the host through its UI); the host runs the
 * speeches; `rivalVoters` vote the rival, every other bot the winner, the host abstains.
 * Lands on D1 RESULT_HIDDEN.
 */
async function nightOneAndElection(
  t: Table,
  testInfo: TestInfo,
  winner: BotInfo,
  rival: BotInfo,
  rivalVoters: BotInfo[],
): Promise<void> {
  const { game } = t
  const host = game.hostPage
  await hostStartsNight(game)
  await night(t, 'SHERIFF_ELECTION')
  expect(await waitForSheriffSubPhase(host, game.gameId, 'SIGNUP', 15_000)).toBe(true)

  const candidates = new Set([winner.userId, rival.userId])
  const voters = game.allBots.filter((b) => !candidates.has(b.userId))
  bot(t, 'SHERIFF_CAMPAIGN', winner)
  bot(t, 'SHERIFF_CAMPAIGN', rival)
  for (const b of voters) bot(t, 'SHERIFF_PASS', b)
  await host.getByTestId('sheriff-pass').click()
  expect(
    await waitForSheriffSubPhase(host, game.gameId, 'SPEECH', 15_000),
    'every player decided → speeches',
  ).toBe(true)
  await advanceAllSheriffSpeeches(host, game.gameId)
  expect(await waitForSheriffSubPhase(host, game.gameId, 'VOTING', 15_000)).toBe(true)

  const forRival = new Set(rivalVoters.map((b) => b.userId))
  for (const b of voters) {
    bot(t, 'SHERIFF_VOTE', b, { target: forRival.has(b.userId) ? rival.seat : winner.seat })
  }
  await host.getByTestId('sheriff-abstain').click()
  const reveal = host.getByTestId('sheriff-reveal-result')
  await expect(reveal, 'everyone voted').toBeEnabled({ timeout: scale(15_000) })
  await reveal.click()
  expect(await waitForSheriffSubPhase(host, game.gameId, 'RESULT', 15_000)).toBe(true)
  const result = (
    (await fetchGameState(host, game.gameId)) as {
      sheriffElection?: { result?: { tally?: { candidateId: string; votes: number }[] } }
    } | null
  )?.sheriffElection?.result
  const votesFor = (who: BotInfo) => result?.tally?.find((c) => c.candidateId === who.userId)?.votes
  expect(votesFor(winner), `${winner.nick}'s votes`).toBe(voters.length - rivalVoters.length)
  expect(votesFor(rival), `${rival.nick}'s votes`).toBe(rivalVoters.length)
  expect(await sheriffId(t), `${winner.nick} holds the badge`).toBe(winner.userId)
  await captureSnapshot(game.pages, testInfo, 'd1-sheriff-elected')
  await waitForDayDiscussionAfterSheriff(game)
}

async function revealDay(t: Table): Promise<void> {
  const btn = t.game.hostPage.getByTestId('day-reveal-result')
  await expect(btn).toBeVisible({ timeout: scale(15_000) })
  await btn.click()
  expect(await waitForDayRevealed(t.game.hostPage, t.game.gameId)).toBe('DAY_DISCUSSION')
}

/** After the reveal: open the vote, every living player votes `target`, reveal the tally. */
async function voteOut(t: Table, target: BotInfo): Promise<void> {
  const host = t.game.hostPage
  await host.getByTestId('day-start-vote').click()
  expect(await waitForVotingSubPhase(host, t.game.gameId, 'VOTING', 15_000)).toBe(true)
  const unvoted = await readUnvotedAlivePlayerIds(host, t.game.gameId)
  for (const voter of [...t.game.allBots, { nick: 'Host', userId: t.hostId }]) {
    if (!unvoted.has(voter.userId)) continue
    expect(
      tryAct('SUBMIT_VOTE', voter.nick, { target: String(target.seat), room: t.game.roomCode }),
      `${voter.nick} votes seat ${target.seat}`,
    ).toBe(true)
  }
  await waitForCondition(
    async () => (await readUnvotedAlivePlayerIds(host, t.game.gameId)).size === 0,
    'all alive players voted',
    scale(15_000),
  )
  await host.getByTestId('voting-reveal').click()
}

const slot = (page: Page, seat: number) => page.locator(`.player-grid [data-seat="${seat}"]`)

/**
 * On the dying sheriff's BADGE_HANDOVER screen: tapping a dead seat selects nothing, then
 * the badge goes to `heir` through the UI and the vote result shows the heir's badge.
 */
async function handOverBadge(t: Table, sheriffPage: Page, dead: BotInfo, heir: BotInfo) {
  const pass = sheriffPage.getByTestId('badge-pass')
  await expect(pass).toBeDisabled({ timeout: scale(10_000) })
  // The dead seat is not tappable; a dispatched click reaches the handler, which refuses it.
  await slot(sheriffPage, dead.seat).dispatchEvent('click')
  await expect(pass, `the badge cannot go to dead seat ${dead.seat}`).toBeDisabled()
  await slot(sheriffPage, heir.seat).click()
  await expect(pass).toBeEnabled()
  await pass.click()
  expect(await waitForVotingSubPhase(t.game.hostPage, t.game.gameId, 'VOTE_RESULT', 15_000)).toBe(
    true,
  )
  expect(await sheriffId(t), `${heir.nick} holds the badge`).toBe(heir.userId)
  await expect(slot(t.game.hostPage, heir.seat).getByTestId('sheriff-badge')).toBeVisible({
    timeout: scale(10_000),
  })
  await expect(t.game.hostPage.locator('.player-grid').getByTestId('sheriff-badge')).toHaveCount(1)
}

function charmBannerText(t: Table, victim: BotInfo): string {
  return (
    `${t.beauty.seat}號 · ${t.beauty.nick}（狼美人）出局，` +
    `${victim.seat}號 · ${victim.nick}殉情出局`
  )
}

/**
 * No action rejected for this game except `expectedRejections` (each must occur exactly
 * once); no role leak; no browser or backend errors.
 */
async function assertCleanRun(t: Table, testInfo: TestInfo, expectedRejections: RegExp[] = []) {
  const gameTag = new RegExp(`game=${t.game.gameId}(?=[\\s:,])`)
  const rejected = readBackendLogSince(t.logStart).filter(
    (l) => l.includes('-> REJECTED') && gameTag.test(l),
  )
  for (const want of expectedRejections) {
    expect(
      rejected.filter((l) => want.test(l)),
      `the deliberate rejection ${want}`,
    ).toHaveLength(1)
  }
  expect(
    rejected.filter((l) => !expectedRejections.some((want) => want.test(l))),
    'no other action was rejected',
  ).toEqual([])

  for (const [page, log] of t.payloads) {
    const role = t.roleOf.get(page)
    assertNonNull(role, 'the role of an open page')
    const me = await page.evaluate(() => localStorage.getItem('userId'))
    assertNonNull(me, `${role} page userId`)
    expect(log.bodies.length, `${role} page recorded payloads`).toBeGreaterThan(0)
    expect(findRoleLeaks(log, me, role), `${role} page got another player's role`).toEqual([])
    const carriers = log.bodies.filter((b) => b.body.includes('"wolfBeauty":{'))
    if (role === 'WOLF_BEAUTY') {
      expect(carriers.length, 'the 狼美人 received its state').toBeGreaterThan(0)
    } else {
      expect(
        carriers.map((b) => b.src),
        `${role} page must never receive the 狼美人 state`,
      ).toEqual([])
    }
  }

  await assertNoBrowserErrors(t.game.errors, testInfo)
  await t.game.assertNoBackendErrors(testInfo)
}

test.describe('Wolf Beauty with the sheriff election (real backend)', () => {
  // eslint-disable-next-line no-empty-pattern
  test.afterEach(async ({}, testInfo) => {
    const game = table?.game
    table = undefined
    if (!game) return
    if (testInfo.status === 'failed') await attachCompositeOnFailure(game.pages, testInfo)
    await cleanupRoomData(game.hostPage, game.roomCode)
    await game.cleanup()
  })

  test('a charmed sheriff 殉情 when the 狼美人 is voted out and still hands over the badge', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 480_000 : 240_000)
    const t = await openTable(browser, ['SEER'])
    const { game, bPage, beauty, seer, witch } = t
    const host = game.hostPage
    const seerPage = game.pages.get('SEER')
    assertNonNull(seerPage, 'the seer browser page')

    // The seer beats the knight 4 : 2 (the 狼美人 and one wolf back the knight).
    await nightOneAndElection(t, testInfo, seer, t.knight, [beauty, t.wolves[1]])
    expect(await completeDay(game, testInfo, { targetSeat: null, label: 'd1' })).toBe('NIGHT')

    // N2: the 狼美人 charms the sheriff.
    await night(t, 'DAY_DISCUSSION', seer)
    await revealDay(t)
    expect(await isAlive(t, seer.seat), 'the charmed sheriff survived N2').toBe(true)

    // ── X2 + G: the 狼美人 is voted out → the sheriff 殉情 → BADGE_HANDOVER ──
    await voteOut(t, beauty)
    expect(
      await waitForVotingSubPhase(host, game.gameId, 'BADGE_HANDOVER', 15_000),
      'the 殉情 sheriff opens the badge handover',
    ).toBe(true)
    expect(await isAlive(t, beauty.seat), 'the 狼美人 was voted out').toBe(false)
    expect(await isAlive(t, seer.seat), 'the charmed sheriff 殉情').toBe(false)
    expect(await sheriffId(t), 'the dead seer still holds the badge').toBe(seer.userId)
    for (const page of [host, seerPage, bPage]) {
      await expect(page.getByTestId('badge-handover-screen')).toBeVisible({
        timeout: scale(10_000),
      })
      await expect(page.getByTestId('charm-death-banner')).toContainText(charmBannerText(t, seer))
    }
    // Only the dead seer hands over.
    await expect(seerPage.getByTestId('badge-pass')).toBeVisible()
    await expect(seerPage.getByTestId('badge-destroy')).toBeVisible()
    for (const page of [host, bPage]) await expect(page.getByTestId('badge-pass')).toHaveCount(0)
    await captureSnapshot(game.pages, testInfo, 'd2-charmed-sheriff-badge-handover')

    await handOverBadge(t, seerPage, beauty, witch)
    await captureSnapshot(game.pages, testInfo, 'd2-badge-passed')
    expect((await fetchGameState(host, game.gameId))?.phase, 'no winner yet').toBe('DAY_VOTING')

    await assertCleanRun(t, testInfo)
  })

  test('a 狼美人 sheriff shot by the hunter: the charmed player dies at the shot, then the badge passes', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 480_000 : 240_000)
    const t = await openTable(browser)
    const { game, bPage, beauty, hunter, villager, wolves } = t
    const host = game.hostPage

    // The 狼美人 beats the seer 4 : 2 (the knight and the villager back the seer).
    await nightOneAndElection(t, testInfo, beauty, t.seer, [t.knight, villager])
    expect(await completeDay(game, testInfo, { targetSeat: null, label: 'd1' })).toBe('NIGHT')

    // N2: the 狼美人 charms the villager.
    await night(t, 'DAY_DISCUSSION', villager)
    await revealDay(t)

    // ── X4 + Q10: the hunter is voted out and shoots the 狼美人 sheriff ──
    await voteOut(t, hunter)
    expect(await waitForVotingSubPhase(host, game.gameId, 'VOTE_RESULT', 15_000)).toBe(true)
    bot(t, 'HUNTER_SHOOT', hunter, { target: beauty.seat })
    expect(
      await waitForVotingSubPhase(host, game.gameId, 'BADGE_HANDOVER', 15_000),
      'the shot 狼美人 sheriff hands over the badge',
    ).toBe(true)
    expect(await isAlive(t, villager.seat), 'the charmed villager died at the shot').toBe(false)
    expect(await isAlive(t, beauty.seat), 'the 狼美人 lives until the badge passes').toBe(true)
    expect(await sheriffId(t)).toBe(beauty.userId)
    for (const page of [host, bPage]) {
      await expect(page.getByTestId('badge-handover-screen')).toBeVisible({
        timeout: scale(10_000),
      })
      await expect(page.getByTestId('charm-death-banner')).toContainText(
        charmBannerText(t, villager),
      )
    }
    await expect(bPage.getByTestId('badge-pass')).toBeVisible()
    await expect(host.getByTestId('badge-pass')).toHaveCount(0)
    await captureSnapshot(game.pages, testInfo, 'd2-shot-beauty-sheriff-badge-handover')

    // The backend refuses the badge to the dead villager too (deliberate, checked below).
    expect(
      tryAct('BADGE_PASS', actName(beauty), { target: String(villager.seat), room: game.roomCode }),
      'the badge cannot go to the 殉情 villager',
    ).toBe(false)
    expect(await waitForVotingSubPhase(host, game.gameId, 'BADGE_HANDOVER', 5_000)).toBe(true)
    expect(await isAlive(t, beauty.seat)).toBe(true)

    await handOverBadge(t, bPage, villager, wolves[0])
    expect(await isAlive(t, beauty.seat), 'the 狼美人 dies once the badge has passed').toBe(false)
    await captureSnapshot(game.pages, testInfo, 'd2-badge-passed')
    expect((await fetchGameState(host, game.gameId))?.phase, 'no winner yet').toBe('DAY_VOTING')

    await assertCleanRun(t, testInfo, [
      new RegExp(`type=BADGE_PASS .*-> REJECTED reason="Target not found or dead"`),
    ])
  })
})
