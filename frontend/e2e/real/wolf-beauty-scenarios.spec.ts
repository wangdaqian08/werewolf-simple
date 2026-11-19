/**
 * 狼美人 (WOLF_BEAUTY) — real-backend scenarios beyond wolf-beauty-flow.spec.ts, for
 * docs/superpowers/specs/2026-11-18-wolf-beauty-demon-hunter-design.md
 * (coverage cases C3, C4, C5, C7, C11, C13).
 *
 * Every game: 9 seats, no sheriff, 空刀 allowed — 2 wolves + 狼美人, seer, witch, hunter,
 * knight and 2 villagers. The host is always a villager (swapped in during ROLE_REVEAL), so
 * every bot actor is known up front. The 狼美人 charms through its own browser on night 2
 * (night 1 only offers 確認); the other roles act via act.sh, each gated on the backend
 * sub-phase. Each test is its own game:
 *
 *  1. knife ban + night poison (C3, C5): on N1 the pack's picker greys the 狼美人 and a tap on it
 *     selects nothing; the pack knifes the other villager through the UI. N2: the 狼美人 charms
 *     the seer and the witch poisons the 狼美人 → both are in the dawn list with no cause, no
 *     殉情 banner, no 殉情 entry in the 游戏记录 and no CHARM_DEATH in /events.
 *  2. no 自爆 + hunter shot (C4, C7): after the D2 reveal the 狼美人's ⚡ menu is empty while a
 *     pack wolf's offers 自爆; the table votes the hunter out, it shoots the 狼美人 → the charmed
 *     knight 殉情 and every page shows the banner.
 *  3. knight duel (C11): the knight duels the 狼美人 → it dies, the charmed seer lives, no banner.
 *  4. last-wolf win (C13, W15): the host declares out the pack wolves, the witch, the hunter
 *     and the knight in ROLE_REVEAL (判定出局 causes no 殉情); the 狼美人 charms the seer, the last
 *     god, and is voted out on D2 → the seer 殉情 first and the wolves win.
 *
 * CI-vs-local (memory feedback_e2e_ci_vs_local): every action is gated on the backend
 * sub-phase, never on a sleep; timeouts scale 2× under CI; the backend log must show no
 * REJECTED action for the game, so a UI that sent a knife on the 狼美人 would fail here too.
 */
import { expect, type Page, test, type TestInfo } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { actName, type BotInfo, getRoles, type RoleName } from './helpers/shell-runner'
import { assertNonNull, completeDay, tryAct, waitForSubPhase } from './helpers/flow-drivers'
import {
  fetchGameState,
  readHostUserId,
  readUnvotedAlivePlayerIds,
  waitForCondition,
  waitForDayRevealed,
  waitForPhase,
  waitForUiSubPhase,
  waitForVotingSubPhase,
} from './helpers/state-polling'
import { hostStartsNight } from './helpers/night-driver'
import { readBackendLogLineCount, readBackendLogSince } from './helpers/backend-log'
import { assertNoBrowserErrors } from './helpers/error-sentinel'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { cleanupRoomData, swapRoles } from './helpers/test-support'

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
  hostSeat: number
  beauty: BotInfo
  seer: BotInfo
  witch: BotInfo
  hunter: BotInfo
  knight: BotInfo
  wolves: [BotInfo, BotInfo]
  /** The villager who is not the host. */
  villager: BotInfo
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
 * Deal the board, make the host a villager (the swap happens in ROLE_REVEAL, before the
 * night), and name every seat. `extraPages` opens browsers beyond the 狼美人's.
 */
async function openTable(
  browser: Parameters<typeof setupGame>[0],
  extraPages: RoleName[] = [],
): Promise<Table> {
  const game = await setupGame(browser, {
    totalPlayers: 9,
    hasSheriff: false,
    noKillAllowed: true,
    roles: ROLES,
    // The VILLAGER page becomes the 狼美人's when the host rolled it (swap below).
    browserRoles: ['WOLF_BEAUTY', 'VILLAGER', ...extraPages],
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

  const [wolfA, wolfB] = nonHost(game, 'WEREWOLF')
  assertNonNull(wolfA, 'pack wolf A')
  assertNonNull(wolfB, 'pack wolf B')
  const hostSeat = (game.roleMap.VILLAGER ?? []).find((b) => b.userId === hostId)?.seat
  assertNonNull(hostSeat, 'host seat')
  table = {
    game,
    bPage,
    hostId,
    hostSeat,
    beauty: only(game, 'WOLF_BEAUTY'),
    seer: only(game, 'SEER'),
    witch: only(game, 'WITCH'),
    hunter: only(game, 'HUNTER'),
    knight: only(game, 'KNIGHT'),
    wolves: [wolfA, wolfB],
    villager: only(game, 'VILLAGER'),
    logStart,
  }
  const t = table
  console.warn(
    `[wolf-beauty-scenarios] game=${game.gameId} host=${hostSeat} (dealt ${dealtHostRole}) ` +
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

async function waitDead(t: Table, who: BotInfo, why: string): Promise<void> {
  await waitForCondition(
    async () => (await isAlive(t, who.seat)) === false,
    `${why} (seat ${who.seat} dead)`,
    scale(15_000),
  )
}

/** The 狼美人's turn through its own browser: night 1 只確認, later nights a charm. */
async function beautyTurn(t: Table, charm?: BotInfo): Promise<void> {
  await reach(t, 'WOLF_BEAUTY_PICK')
  await expect(t.bPage.getByTestId('wolf-beauty-turn')).toBeVisible({ timeout: scale(10_000) })
  if (!charm) {
    await t.bPage.getByTestId('wolf-beauty-n1-confirm').click()
    return
  }
  await t.bPage.getByTestId(`wolf-beauty-target-${charm.seat}`).click()
  await t.bPage.getByTestId('wolf-beauty-confirm').click()
}

/** Witch (if alive) then seer; ends on the day. */
async function restOfNight(t: Table, seerChecks: BotInfo, poison?: BotInfo): Promise<void> {
  if (await isAlive(t, t.witch.seat)) {
    await reach(t, 'WITCH_ACT')
    const payload = poison
      ? `{"useAntidote":false,"poisonTargetUserId":"${poison.userId}"}`
      : '{"useAntidote":false}'
    bot(t, 'WITCH_ACT', t.witch, { payload })
  }
  await reach(t, 'SEER_PICK')
  bot(t, 'SEER_CHECK', t.seer, { target: seerChecks.seat })
  await reach(t, 'SEER_RESULT')
  bot(t, 'SEER_CONFIRM', t.seer)
  expect(await waitForPhase(t.game.hostPage, t.game.gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)
}

/** N1 with an empty knife and a quiet D1 (everyone abstains twice), landing on night 2. */
async function quietFirstRound(t: Table, testInfo: TestInfo): Promise<void> {
  await hostStartsNight(t.game)
  await reach(t, 'WEREWOLF_PICK')
  bot(t, 'WOLF_SKIP', t.wolves[0])
  await beautyTurn(t)
  await restOfNight(t, t.wolves[0])
  expect(await completeDay(t.game, testInfo, { targetSeat: null, label: 'd1' })).toBe('NIGHT')
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
  await waitForCondition(
    async () => {
      const s = await fetchGameState(host, t.game.gameId)
      return s?.phase !== 'DAY_VOTING' || s?.votingPhase?.subPhase !== 'VOTING'
    },
    'vote tally revealed',
    scale(15_000),
  )
}

async function publicEventTypes(t: Table): Promise<string[]> {
  return t.game.hostPage.evaluate(async (id: string) => {
    const res = await fetch(`/api/game/${id}/events`, {
      headers: { Authorization: `Bearer ${localStorage.getItem('jwt')}` },
    })
    if (!res.ok) throw new Error(`/events HTTP ${res.status}`)
    return ((await res.json()) as { eventType: string }[]).map((e) => e.eventType)
  }, t.game.gameId)
}

async function openLog(page: Page): Promise<void> {
  await page.getByTestId('log-fab').click()
}

async function closeLog(page: Page): Promise<void> {
  await page.getByTestId('action-log-close').click()
}

/** No action rejected for this game; no browser or backend errors. */
async function assertCleanRun(t: Table, testInfo: TestInfo): Promise<void> {
  const gameTag = new RegExp(`game=${t.game.gameId}(?=[\\s:,])`)
  expect(
    readBackendLogSince(t.logStart).filter((l) => l.includes('-> REJECTED') && gameTag.test(l)),
    'no action was rejected',
  ).toEqual([])
  await assertNoBrowserErrors(t.game.errors, testInfo)
  await t.game.assertNoBackendErrors(testInfo)
}

function charmBannerText(t: Table, victim: BotInfo): string {
  return (
    `${t.beauty.seat}號 · ${t.beauty.nick}（狼美人）出局，` +
    `${victim.seat}號 · ${victim.nick}殉情出局`
  )
}

test.describe('Wolf Beauty scenarios (real backend)', () => {
  // eslint-disable-next-line no-empty-pattern
  test.afterEach(async ({}, testInfo) => {
    const game = table?.game
    table = undefined
    if (!game) return
    if (testInfo.status === 'failed') await attachCompositeOnFailure(game.pages, testInfo)
    await cleanupRoomData(game.hostPage, game.roomCode)
    await game.cleanup()
  })

  test('the pack cannot knife the 狼美人; a poisoned 狼美人 takes its charm in the dawn list', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 480_000 : 240_000)
    const t = await openTable(browser, ['WEREWOLF'])
    const { game, beauty, seer, villager } = t
    const wolfPage = game.pages.get('WEREWOLF')
    const wolf = game.bots.get('WEREWOLF')
    assertNonNull(wolfPage, 'a pack wolf browser page')
    assertNonNull(wolf, 'the paged pack wolf')

    // ── C3 (W8): N1 — the 狼美人 is greyed in the pack's picker and cannot be selected ──
    await hostStartsNight(game)
    await reach(t, 'WEREWOLF_PICK')
    const confirmKill = wolfPage.getByTestId('wolf-confirm-kill')
    await expect(confirmKill).toBeVisible({ timeout: scale(10_000) })
    const beautySlot = wolfPage.locator(`.player-grid [data-seat="${beauty.seat}"]`)
    const villagerSlot = wolfPage.locator(`.player-grid [data-seat="${villager.seat}"]`)
    await expect(beautySlot, 'the 狼美人 is greyed for the pack').toHaveAttribute(
      'aria-disabled',
      'true',
    )
    await expect(villagerSlot, 'a villager is a knife target').not.toHaveAttribute(
      'aria-disabled',
      'true',
    )
    // pointer-events:none blocks a real tap; a dispatched click still reaches the handler,
    // which must refuse it.
    await beautySlot.dispatchEvent('click')
    await expect(confirmKill, 'tapping the 狼美人 selects nothing').toBeDisabled()
    await captureSnapshot(game.pages, testInfo, 'n1-pack-picker')
    // The pack knifes the other villager through its own UI.
    await villagerSlot.click()
    await expect(confirmKill).toBeEnabled()
    await confirmKill.click()
    await beautyTurn(t)
    await restOfNight(t, wolf)
    expect(await completeDay(game, testInfo, { targetSeat: null, label: 'd1' })).toBe('NIGHT')
    expect(await isAlive(t, villager.seat), 'the villager was knifed on N1').toBe(false)
    expect(await isAlive(t, beauty.seat), 'the 狼美人 survived N1').toBe(true)

    // ── C5 (X1, W11, Q2): N2 — the 狼美人 charms the seer, the witch poisons the 狼美人 ──
    await reach(t, 'WEREWOLF_PICK')
    bot(t, 'WOLF_SKIP', t.wolves[0])
    await beautyTurn(t, seer)
    await restOfNight(t, t.hunter, beauty)
    await revealDay(t)
    expect(await isAlive(t, beauty.seat), 'the poisoned 狼美人 died').toBe(false)
    expect(await isAlive(t, seer.seat), 'the charmed seer 殉情 at dawn').toBe(false)

    // The dawn list names both seats, with no cause and no 狼美人.
    for (const page of [game.hostPage, wolfPage]) {
      const dawn = page.getByTestId('day-banner-kill')
      await expect(dawn.getByTestId(`day-killed-seat-${beauty.seat}`)).toBeVisible({
        timeout: scale(10_000),
      })
      await expect(dawn.getByTestId(`day-killed-seat-${seer.seat}`)).toBeVisible()
      await expect(dawn).not.toContainText('殉情')
      await expect(dawn).not.toContainText('狼美人')
      await expect(page.getByTestId('charm-death-banner'), 'no 殉情 banner at dawn').toHaveCount(0)
    }
    await captureSnapshot(game.pages, testInfo, 'd2-dawn-list')

    // 游戏记录: both deaths, no 殉情 entry.
    await openLog(game.hostPage)
    await expect(game.hostPage.getByTestId(`log-night-death-${beauty.seat}`)).toBeVisible({
      timeout: scale(10_000),
    })
    await expect(game.hostPage.getByTestId(`log-night-death-${seer.seat}`)).toBeVisible()
    await expect(game.hostPage.getByTestId('log-charm-death')).toHaveCount(0)
    await captureSnapshot(game.pages, testInfo, 'd2-game-log')
    await closeLog(game.hostPage)

    // The public game log carries no CHARM_DEATH while the game runs.
    expect((await fetchGameState(game.hostPage, game.gameId))?.phase).toBe('DAY_DISCUSSION')
    const types = await publicEventTypes(t)
    expect(types, 'the night deaths are public').toContain('NIGHT_DEATH')
    expect(types, 'a night 殉情 is not in the public log').not.toContain('CHARM_DEATH')

    await assertCleanRun(t, testInfo)
  })

  test('the 狼美人 has no 自爆; a voted-out hunter shoots it and the charmed knight 殉情', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 480_000 : 240_000)
    const t = await openTable(browser, ['WEREWOLF'])
    const { game, bPage, beauty, knight, hunter } = t
    const wolfPage = game.pages.get('WEREWOLF')
    assertNonNull(wolfPage, 'a pack wolf browser page')

    await quietFirstRound(t, testInfo)
    // N2: the 狼美人 charms the knight.
    await reach(t, 'WEREWOLF_PICK')
    bot(t, 'WOLF_SKIP', t.wolves[0])
    await beautyTurn(t, knight)
    await restOfNight(t, hunter)
    await revealDay(t)

    // ── C4 (W8): in the self-destruct window only the pack wolf is offered 自爆 ──
    // Each page flips to RESULT_REVEALED only after its own PhaseChanged refetch, and DayPhase is
    // keyed on the sub-phase: a menu opened before that is remounted away (CI run 44434085333).
    for (const page of [bPage, wolfPage]) await waitForUiSubPhase(page, 'RESULT_REVEALED')
    await bPage.getByTestId('action-menu-btn').click({ timeout: scale(5_000) })
    await expect(bPage.getByTestId('action-menu-empty')).toBeVisible({ timeout: scale(5_000) })
    await expect(
      bPage.getByTestId('action-menu-self-destruct'),
      'no 自爆 for the 狼美人',
    ).toHaveCount(0)
    await wolfPage.getByTestId('action-menu-btn').click({ timeout: scale(5_000) })
    await expect(wolfPage.getByTestId('action-menu-self-destruct')).toBeVisible({
      timeout: scale(5_000),
    })
    await captureSnapshot(game.pages, testInfo, 'd2-action-menus')
    for (const page of [bPage, wolfPage]) {
      await page
        .getByTestId('action-menu-backdrop')
        .click({ position: { x: 5, y: 5 }, timeout: scale(5_000) })
      await expect(page.getByTestId('action-menu-backdrop')).toBeHidden({ timeout: scale(5_000) })
    }

    // ── C7 (X3): the hunter is voted out and shoots the 狼美人 → the knight 殉情 ──
    await voteOut(t, hunter)
    expect(await waitForVotingSubPhase(game.hostPage, game.gameId, 'VOTE_RESULT', 15_000)).toBe(
      true,
    )
    bot(t, 'HUNTER_SHOOT', hunter, { target: beauty.seat })
    await waitDead(t, beauty, 'the shot 狼美人')
    await waitDead(t, knight, 'the charmed knight 殉情')
    for (const page of [game.hostPage, bPage, wolfPage]) {
      await expect(page.getByTestId('charm-death-banner')).toContainText(
        charmBannerText(t, knight),
        {
          timeout: scale(10_000),
        },
      )
    }
    await captureSnapshot(game.pages, testInfo, 'd2-hunter-shot-charm-death')
    // A day 殉情 is public: the 游戏记录 has it.
    await openLog(game.hostPage)
    await expect(game.hostPage.getByTestId('log-charm-death')).toContainText(
      charmBannerText(t, knight),
      { timeout: scale(10_000) },
    )
    await closeLog(game.hostPage)
    expect((await fetchGameState(game.hostPage, game.gameId))?.phase, 'no winner yet').toBe(
      'DAY_VOTING',
    )

    await assertCleanRun(t, testInfo)
  })

  test('a knight duel on the 狼美人 takes no 殉情', async ({ browser }, testInfo) => {
    test.setTimeout(process.env.CI ? 480_000 : 240_000)
    const t = await openTable(browser)
    const { game, bPage, beauty, seer, knight } = t

    await quietFirstRound(t, testInfo)
    // N2: the 狼美人 charms the seer.
    await reach(t, 'WEREWOLF_PICK')
    bot(t, 'WOLF_SKIP', t.wolves[0])
    await beautyTurn(t, seer)
    await restOfNight(t, t.wolves[1])
    await revealDay(t)

    // ── C11 (X7, D2): the knight duels the 狼美人 ──
    bot(t, 'KNIGHT_DUEL', knight, { target: beauty.seat })
    await waitDead(t, beauty, 'the dueled 狼美人')
    for (const page of [game.hostPage, bPage]) {
      await expect(page.getByTestId('day-banner-knight-duel')).toBeVisible({
        timeout: scale(10_000),
      })
      await expect(page.getByTestId('charm-death-banner'), 'no 殉情 banner').toHaveCount(0)
    }
    expect(await isAlive(t, seer.seat), 'the charmed seer lives').toBe(true)
    expect(await isAlive(t, knight.seat), 'the knight wins a duel on a wolf').toBe(true)
    await openLog(game.hostPage)
    await expect(game.hostPage.getByTestId('log-knight-duel')).toBeVisible({
      timeout: scale(10_000),
    })
    await expect(game.hostPage.getByTestId('log-charm-death')).toHaveCount(0)
    await closeLog(game.hostPage)
    expect(await publicEventTypes(t)).not.toContain('CHARM_DEATH')
    await captureSnapshot(game.pages, testInfo, 'd2-knight-duel')

    await assertCleanRun(t, testInfo)
  })

  test('a last-wolf 狼美人 voted out takes the last god with it and the wolves win', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 480_000 : 240_000)
    const t = await openTable(browser)
    const { game, beauty, seer } = t
    const host = game.hostPage

    // ── ROLE_REVEAL: the host declares out the pack wolves and every god but the seer ──
    for (const gone of [...t.wolves, t.witch, t.hunter, t.knight]) {
      await host.getByTestId('action-menu-btn').click()
      await host.getByTestId('action-menu-declare-out').click()
      await host.getByTestId(`action-menu-declare-${gone.userId}`).click()
      await host.getByTestId('action-menu-declare-confirm').click()
      await waitDead(t, gone, `${gone.nick} declared out`)
    }
    expect((await fetchGameState(host, game.gameId))?.phase).toBe('ROLE_REVEAL')

    // N1: the lone 狼美人 holds the knife and passes; D1 is quiet.
    await hostStartsNight(game)
    await reach(t, 'WEREWOLF_PICK')
    bot(t, 'WOLF_SKIP', beauty)
    await beautyTurn(t)
    await restOfNight(t, beauty)
    expect(await completeDay(game, testInfo, { targetSeat: null, label: 'd1' })).toBe('NIGHT')

    // N2: the 狼美人 charms the seer, the last god.
    await reach(t, 'WEREWOLF_PICK')
    bot(t, 'WOLF_SKIP', beauty)
    await beautyTurn(t, seer)
    await restOfNight(t, t.villager)
    await revealDay(t)

    // ── C13 (W15): the 狼美人 is voted out → the seer 殉情 first → the wolves win ──
    await voteOut(t, beauty)
    expect(await waitForPhase(host, game.gameId, 'GAME_OVER', 15_000)).toBe(true)
    const end = await fetchGameState(host, game.gameId)
    expect((end as { winner?: string } | null)?.winner).toBe('WEREWOLF')
    expect(await isAlive(t, seer.seat), 'the seer 殉情').toBe(false)
    await host.waitForURL(/\/result\//, { timeout: scale(15_000) })
    await expect(host.getByTestId('result-wrap')).toHaveAttribute('data-winner', 'WEREWOLF', {
      timeout: scale(10_000),
    })
    await expect(host.getByTestId(`role-reveal-${beauty.seat}`)).toContainText('狼美人')
    await captureSnapshot(game.pages, testInfo, 'result-wolves-win')

    await assertCleanRun(t, testInfo)
  })
})
