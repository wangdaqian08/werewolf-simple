/**
 * Black Wolf King (黑狼王) — every seat in its own browser, five real games.
 *
 *  0. Create room: one wolf king per room, 9+ players only.
 *  1. Happy: wolves knife the king → the king shoots the hunter → the hunter
 *     shoots a villager → the day vote ends the game → result + review.
 *  2. The king is voted out and shoots the seer (a god) → night 2 still plays
 *     the dead seer's cues → the wolves win at night → review.
 *  3. The hunter is voted out and shoots the king → the king fires back and
 *     its shot decides the game → review shows both shots.
 *  4. Edge: the witch poisons the king → no shot at the reveal.
 *  5. Edge: the king self-destructs (自爆) → no shot, no take.
 *  6. Edge: the host starts the vote before the knifed king acts → shot lost.
 *  7. Edge: a voted-out hunter passes → nothing reaches the other browsers.
 *
 * The shot is private: only the shooter's browser shows it, the day looks the
 * same for everyone else, and no browser receives another player's role
 * (every STOMP frame and /api/game JSON is scanned, see helpers/role-leak.ts).
 *
 * Every action is a DOM click in the acting player's own browser. Each
 * milestone writes a 9-seat composite to test-results/bwk/<test>/.
 */
import {
  type Browser,
  type BrowserContext,
  expect,
  type Page,
  test,
  type TestInfo,
} from '@playwright/test'
import { mkdirSync, writeFileSync } from 'fs'
import path from 'path'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { getConsoleLogin, type RoleName } from './helpers/shell-runner'
import { driveMinimalNight1ViaDom } from './helpers/night-driver'
import { takeCompositeScreenshot } from './helpers/composite-screenshot'
import {
  fetchGameState,
  waitForCondition,
  waitForDaySkipVoting,
  waitForDaySubPhase,
  waitForNightSubPhase,
  waitForPhase,
  waitForVotingSubPhase,
} from './helpers/state-polling'
import {
  type AudioCapture,
  attachAudioCapture,
  countStompAudioFiles,
  extractPerNightRoleAudio,
  NIGHT_ROLE_ORDER,
} from './helpers/audio-manifest'
import { readBackendLogLineCount, readBackendLogSince } from './helpers/backend-log'
import { findRoleLeaks, type PayloadLog, recordPayloads } from './helpers/role-leak'

const BASE_URL = 'http://localhost:5174'
// Set BWK_SHOTS_DIR to keep shots: Playwright wipes test-results/ on every run.
const SHOTS = process.env.BWK_SHOTS_DIR ?? path.join('test-results', 'bwk')
const scale = (ms: number): number => (process.env.CI ? ms * 2 : ms)
const ROLES: RoleName[] = [
  'WEREWOLF',
  'VILLAGER',
  'BLACK_WOLF_KING',
  'SEER',
  'WITCH',
  'GUARD',
  'HUNTER',
]
// Words that would tell a non-shooter who is shooting.
const ROLE_WORDS = ['猎人', 'Hunter', 'HUNTER', '黑狼王', 'Wolf King', 'WOLF KING']
const KING_RULE = '被女巫毒死或自爆时无法开枪'

interface Table {
  ctx: GameContext
  seatPage: Map<number, Page>
  seatRole: Map<number, RoleName>
  /** `S<seat>-<ROLE>` → page, for composites. */
  labeled: Map<string, Page>
  extra: BrowserContext[]
  /** STOMP audio receipts on one villager's browser. */
  audio: AudioCapture
  /** Server payloads per non-host seat (role-leak scan). */
  payloads: Map<number, PayloadLog>
  seatUser: Map<number, string>
  dir: string
  logStart: number
}

let table: Table | undefined

// eslint-disable-next-line no-empty-pattern -- Playwright needs the fixtures argument to reach testInfo
test.afterEach(async ({}, testInfo) => {
  // Keep what each seat received when a test fails: the evidence for a leak.
  if (testInfo.status !== testInfo.expectedStatus) {
    for (const [seat, log] of table?.payloads ?? []) {
      writeFileSync(path.join(table!.dir, `payloads-S${seat}.json`), JSON.stringify(log.bodies))
    }
  }
  for (const c of table?.extra ?? []) await c.close().catch(() => {})
  await table?.ctx.cleanup()
  table = undefined
})

/** setupGame, then one browser per remaining seat. ctx.pages gets one page per role. */
async function setupTable(browser: Browser, testInfo: TestInfo): Promise<Table> {
  const logStart = readBackendLogLineCount()
  const ctx = await setupGame(browser, {
    totalPlayers: 9,
    hasSheriff: false,
    roles: ROLES,
    browserRoles: [],
  })
  const seatPage = new Map<number, Page>()
  const seatRole = new Map<number, RoleName>()
  const extra: BrowserContext[] = []
  const payloads = new Map<number, PayloadLog>()
  const seatUser = new Map<number, string>()
  for (const [role, bots] of Object.entries(ctx.roleMap) as [
    RoleName,
    { nick: string; seat: number }[],
  ][]) {
    for (const bot of bots) {
      seatRole.set(bot.seat, role)
      if (bot.nick === 'Host') {
        seatPage.set(bot.seat, ctx.hostPage)
        seatUser.set(bot.seat, await ctx.hostPage.evaluate(() => localStorage.getItem('userId')!))
        continue
      }
      const creds = getConsoleLogin(bot.nick, ctx.roomCode)
      seatUser.set(bot.seat, creds.userId)
      const c = await browser.newContext({ viewport: { width: 417, height: 800 } })
      extra.push(c)
      const page = await c.newPage()
      payloads.set(bot.seat, recordPayloads(page))
      await page.goto(`${BASE_URL}/`)
      await page.evaluate((cr) => {
        localStorage.setItem('jwt', cr.jwt)
        localStorage.setItem('nickname', cr.nickname)
        localStorage.setItem('userId', cr.userId)
      }, creds)
      await page.goto(`${BASE_URL}/game/${ctx.gameId}`)
      await page.locator('.game-wrap').waitFor({ state: 'visible', timeout: 15_000 })
      seatPage.set(bot.seat, page)
    }
  }
  expect(seatPage.size, 'every seat has a browser').toBe(9)
  const labeled = new Map<string, Page>()
  for (const seat of [...seatPage.keys()].sort((a, b) => a - b)) {
    labeled.set(`S${seat}-${seatRole.get(seat)}`, seatPage.get(seat)!)
  }
  for (const role of ROLES) {
    const seat = seatsOf({ seatRole } as Table, role)[0]
    if (seat != null) ctx.pages.set(role, seatPage.get(seat)!)
  }
  const villager = seatsOf({ seatRole } as Table, 'VILLAGER').find(
    (s) => seatPage.get(s) !== ctx.hostPage,
  )
  const audio = attachAudioCapture(seatPage.get(villager!)!)
  const dir = path.join(SHOTS, testInfo.title.replace(/[^\w]+/g, '-').slice(0, 40))
  mkdirSync(dir, { recursive: true })
  console.warn(`[bwk] game=${ctx.gameId} seats=${[...labeled.keys()].join(' ')}`)
  return { ctx, seatPage, seatRole, labeled, extra, audio, payloads, seatUser, dir, logStart }
}

function seatsOf(t: Pick<Table, 'seatRole'>, role: RoleName): number[] {
  return [...t.seatRole]
    .filter(([, r]) => r === role)
    .map(([s]) => s)
    .sort((a, b) => a - b)
}
const one = (t: Table, role: RoleName): number => {
  const s = seatsOf(t, role)[0]
  if (s == null) throw new Error(`no seat for ${role}`)
  return s
}
const pageOf = (t: Table, seat: number): Page => t.seatPage.get(seat)!

async function snap(t: Table, label: string, testInfo: TestInfo) {
  const file = path.join(t.dir, `${label}.png`)
  await takeCompositeScreenshot(t.labeled, file)
  await testInfo.attach(label, { path: file, contentType: 'image/png' })
}
async function snapOne(t: Table, page: Page, label: string, testInfo: TestInfo) {
  const file = path.join(t.dir, `${label}.png`)
  await page.screenshot({ path: file, fullPage: true, animations: 'disabled' })
  await testInfo.attach(label, { path: file, contentType: 'image/png' })
}

async function aliveSeats(t: Table): Promise<number[]> {
  const s = await fetchGameState(t.ctx.hostPage, t.ctx.gameId)
  return (s?.players ?? [])
    .filter((p) => p.isAlive)
    .map((p) => p.seatIndex!)
    .sort((a, b) => a - b)
}

/** Non-wolf, non-shooter pages must not learn the shooter's role. */
async function assertAnonymous(t: Table, shooterSeat: number) {
  for (const [seat, page] of t.seatPage) {
    const role = t.seatRole.get(seat)!
    if (seat === shooterSeat || role === 'WEREWOLF' || role === 'BLACK_WOLF_KING') continue
    const text = await page.locator('.game-wrap').innerText()
    for (const w of ROLE_WORDS) {
      // A player may see its own role name (e.g. the hunter's own page); nobody else's.
      if (role === 'HUNTER' && (w === '猎人' || /hunter/i.test(w))) continue
      expect(
        text,
        `seat ${seat} (${role}) must not see "${w}" while seat ${shooterSeat} shoots`,
      ).not.toContain(w)
    }
  }
}

/** Only the shooter's browser shows the shot; every other screen is the ordinary day. */
async function assertShotPrivate(t: Table, shooterSeat: number) {
  const shotIds = [
    'day-hunter-night-banner',
    'day-hunter-night-shoot',
    'voting-shoot-banner',
    'voting-hunter-shoot',
  ]
  for (const [seat, page] of t.seatPage) {
    if (seat === shooterSeat) continue
    for (const id of shotIds) {
      await expect(page.getByTestId(id), `seat ${seat} must not see ${id}`).toHaveCount(0)
    }
  }
  await assertAnonymous(t, shooterSeat)
}

/** A villager reads the action log mid-game (the recorder keeps the response). */
async function readEventsAsVillager(t: Table) {
  const seat = seatsOf(t, 'VILLAGER').find((s) => t.payloads.has(s))!
  await pageOf(t, seat).evaluate(async (id) => {
    await fetch(`/api/game/${id}/events`, {
      headers: { Authorization: `Bearer ${localStorage.getItem('jwt')}` },
    })
  }, t.ctx.gameId)
}

/**
 * No non-wolf browser received another player's role, a shooter flag that is
 * not its own, or a sub-phase naming a shot — up to game over, where roles
 * are public. Wolves are skipped: they see their pack by design.
 */
function assertNoRoleLeak(t: Table) {
  for (const [seat, log] of t.payloads) {
    const role = t.seatRole.get(seat)!
    if (role === 'WEREWOLF' || role === 'BLACK_WOLF_KING') continue
    const over = log.bodies.findIndex((b) => /"GameOver"|"GAME_OVER"/.test(b.body))
    const live = log.bodies.slice(0, over < 0 ? undefined : over)
    expect(live.length, `seat ${seat} recorded payloads`).toBeGreaterThan(0)
    expect(
      findRoleLeaks(log, t.seatUser.get(seat)!, 0, over < 0 ? undefined : over),
      `seat ${seat} (${role}) got a role`,
    ).toEqual([])
    for (const { src, body } of live) {
      expect(body, `seat ${seat} ${src}: no shot sub-phase`).not.toMatch(
        /HUNTER_SHOOT_NIGHT_DEATH|"subPhase":"HUNTER_SHOOT"/,
      )
      if (src.endsWith('/state'))
        expect(body, `seat ${seat}: no shooter id in state`).not.toContain('"hunterUserId"')
      if (role !== 'HUNTER' && src.endsWith('/state')) {
        expect(body, `seat ${seat} (${role}) must never be told it can shoot`).not.toContain(
          '"canShoot":true',
        )
      }
    }
    console.warn(
      `[bwk] game=${t.ctx.gameId} seat=${seat} role=${role} scanned=${live.length} leaks=0`,
    )
  }
}

/** Night-death shot from the shooter's own browser. */
async function nightShoot(t: Table, shooter: number, target: number) {
  const page = pageOf(t, shooter)
  const banner = page.getByTestId('day-hunter-night-banner')
  await expect(banner).toBeVisible({ timeout: scale(15_000) })
  for (const w of ROLE_WORDS) await expect(banner).not.toContainText(w)
  await page.locator(`.player-grid [data-seat="${target}"]`).click()
  await page.getByTestId('day-hunter-night-shoot').click()
}

/** Vote-out shot from the shooter's own browser. */
async function voteShoot(t: Table, shooter: number, target: number) {
  const page = pageOf(t, shooter)
  const banner = page.getByTestId('voting-shoot-banner')
  await expect(banner).toBeVisible({ timeout: scale(15_000) })
  for (const w of ROLE_WORDS) await expect(banner).not.toContainText(w)
  await page.locator(`.player-grid [data-seat="${target}"]`).click()
  await page.getByTestId('voting-hunter-shoot').click()
}

const seatDead = (t: Table, seat: number) =>
  waitForCondition(async () => !(await aliveSeats(t)).includes(seat), `seat ${seat} dead`, 10_000)

/** Host opens the vote; every alive player votes `target` in its own browser; host reveals. */
async function voteOut(t: Table, target: number) {
  const host = t.ctx.hostPage
  await host.getByTestId('day-start-vote').click()
  expect(await waitForVotingSubPhase(host, t.ctx.gameId, 'VOTING', 10_000)).toBe(true)
  for (const seat of await aliveSeats(t)) {
    const page = pageOf(t, seat)
    // Wait for this browser's voting screen: a tap that lands on the day screen is lost.
    await expect(page.getByTestId('voting-skip')).toBeVisible({ timeout: scale(10_000) })
    if (seat === target) {
      await page.getByTestId('voting-skip').click()
    } else {
      await page.locator(`.player-grid [data-seat="${target}"]`).click()
      await expect(page.getByTestId('voting-vote')).toBeEnabled()
      await page.getByTestId('voting-vote').click()
    }
    await expect(page.getByTestId('voting-unvote')).toBeVisible({ timeout: scale(10_000) })
  }
  const reveal = host.getByTestId('voting-reveal')
  await expect(reveal).toBeEnabled({ timeout: scale(10_000) })
  await reveal.click()
}

async function revealNight(t: Table) {
  const btn = t.ctx.hostPage.getByTestId('day-reveal-result')
  await expect(btn).toBeVisible({ timeout: scale(20_000) })
  await btn.click()
}

/** Result page + review page on the host. Returns the review text. */
async function checkResultAndReview(t: Table, testInfo: TestInfo, label: string): Promise<string> {
  const host = t.ctx.hostPage
  expect(await waitForPhase(host, t.ctx.gameId, 'GAME_OVER', 15_000), 'game over').toBe(true)
  await host.waitForURL(/\/result\//, { timeout: scale(15_000) })
  const king = one(t, 'BLACK_WOLF_KING')
  await expect(host.getByTestId(`role-reveal-${king}`)).toContainText('黑狼王')
  await snap(t, `${label}-result`, testInfo)
  await host.getByTestId('open-review').click()
  await host.waitForURL(/\/review\//)
  const review = host.getByTestId('game-review')
  await expect(review).toBeVisible({ timeout: scale(10_000) })
  await host.setViewportSize({ width: 417, height: 900 })
  await snapOne(t, host, `${label}-review`, testInfo)
  return review.innerText()
}

/** Backend role audio per night: every role's cues, in order, dead or alive. */
function assertNightAudio(t: Table, nights: number) {
  const perNight = extractPerNightRoleAudio(readBackendLogSince(t.logStart), t.ctx.gameId)
  console.warn(`[bwk] game=${t.ctx.gameId} night audio=${JSON.stringify(perNight)}`)
  expect(perNight.length, 'role-loop nights in the backend log').toBe(nights)
  for (const night of perNight) expect(night).toEqual([...NIGHT_ROLE_ORDER])
  const received = countStompAudioFiles(t.audio.stompFrames)
  console.warn(`[bwk] game=${t.ctx.gameId} villager received=${JSON.stringify(received)}`)
  for (const f of NIGHT_ROLE_ORDER)
    expect(received[f] ?? 0, `villager browser got ${f}`).toBe(nights)
}

// ── 0. Create room ──────────────────────────────────────────────────────────

test('create room: one wolf king per room, 9+ players', async ({ browser }) => {
  const c = await browser.newContext({ viewport: { width: 417, height: 1400 } })
  const page = await c.newPage()
  const dir = path.join(SHOTS, 'create-room')
  mkdirSync(dir, { recursive: true })
  try {
    await page.goto(`${BASE_URL}/`)
    await page.evaluate(() => localStorage.clear())
    await page.goto(`${BASE_URL}/`)
    const guest = page.getByRole('button', { name: /Continue as guest|继续以访客/i })
    if (await guest.count()) await guest.first().click()
    await page.getByPlaceholder('Enter your nickname').fill('KingUi')
    await page
      .getByRole('button', { name: /Create Room/i })
      .first()
      .click()
    await page.waitForURL(/\/create-room/)

    const black = page.getByTestId('role-toggle-BLACK_WOLF_KING')
    const white = page.getByTestId('role-toggle-WHITE_WOLF_KING')
    await expect(page.getByTestId('player-count-value')).toHaveText('9')
    await expect(page.getByTestId('role-hint-BLACK_WOLF_KING')).toBeVisible()
    await black.click()
    await expect(black).toHaveClass(/toggle-on/)
    await expect(white, '白狼王 locked while 黑狼王 is on').toBeDisabled()
    await expect(white).toHaveAttribute('title', /一局只能选一个狼王/)
    await page.screenshot({
      path: path.join(dir, '9p-black-on.png'),
      fullPage: true,
      animations: 'disabled',
    })

    await page.getByTestId('player-count-decrement').click()
    await expect(page.getByTestId('player-count-value')).toHaveText('8')
    await expect(black, '8 players switches the king off').toHaveClass(/toggle-off/)
    await expect(black).toBeDisabled()
    await expect(black).toHaveAttribute('title', /9 人及以上才能开启/)
    await page.screenshot({
      path: path.join(dir, '8p-king-locked.png'),
      fullPage: true,
      animations: 'disabled',
    })
  } finally {
    await c.close()
  }
})

// ── 1. Happy path ───────────────────────────────────────────────────────────

test('happy: knifed king shoots the hunter, the hunter shoots back, wolves win', async ({
  browser,
}, testInfo) => {
  testInfo.setTimeout(scale(300_000))
  const t = (table = await setupTable(browser, testInfo))
  const king = one(t, 'BLACK_WOLF_KING')
  const hunter = one(t, 'HUNTER')
  const [wolf] = seatsOf(t, 'WEREWOLF')
  const [v1, v2] = seatsOf(t, 'VILLAGER')
  const kingPage = pageOf(t, king)

  // The rules show on the reveal card (if this page is still on it) …
  const revealBtn = kingPage.getByTestId('reveal-role-btn')
  if (await revealBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await revealBtn.click()
    await expect(kingPage.getByTestId('role-reveal-desc')).toContainText(KING_RULE)
    await snapOne(t, kingPage, '01-king-reveal-card', testInfo)
    await kingPage.getByTestId('confirm-role-btn').click()
  }

  await driveMinimalNight1ViaDom(t.ctx, { wolfTargetSeat: king, guardTargetSeat: wolf })
  await snap(t, '02-night1-done', testInfo)
  await revealNight(t)
  // The shot is private: the public day is the ordinary revealed result.
  expect(await waitForDaySubPhase(t.ctx.hostPage, t.ctx.gameId, 'RESULT_REVEALED', 15_000)).toBe(
    true,
  )

  // … and on the role chip during the day.
  await kingPage.getByTestId('my-role-chip').click()
  await expect(kingPage.getByTestId('role-card-desc')).toContainText(KING_RULE)
  await snapOne(t, kingPage, '03-king-role-chip-rules', testInfo)
  await kingPage.mouse.click(10, 10) // tap the overlay to close the sheet
  await expect(kingPage.getByTestId('role-card-desc')).toHaveCount(0)

  await expect(kingPage.getByTestId('day-hunter-night-banner')).toBeVisible()
  await assertShotPrivate(t, king)
  await expect(
    t.ctx.hostPage.getByTestId('day-start-vote'),
    'the host is not held back',
  ).toBeVisible()
  await readEventsAsVillager(t)
  await snap(t, '04-king-shooting', testInfo)
  await snapOne(t, kingPage, '04a-king-shot-screen', testInfo)
  await snapOne(t, pageOf(t, v1), '04b-villager-screen', testInfo)

  await nightShoot(t, king, hunter)
  await seatDead(t, hunter)
  expect(await waitForDaySubPhase(t.ctx.hostPage, t.ctx.gameId, 'RESULT_REVEALED', 10_000)).toBe(
    true,
  )
  await expect(kingPage.getByTestId('day-hunter-night-banner'), 'the king fired once').toHaveCount(
    0,
  )
  await expect(pageOf(t, hunter).getByTestId('day-hunter-night-banner')).toBeVisible()
  await assertShotPrivate(t, hunter)
  await snap(t, '05-chain-hunter-shooting', testInfo)
  await snapOne(t, pageOf(t, hunter), '05a-hunter-chain-shot-screen', testInfo)

  await nightShoot(t, hunter, v1)
  await seatDead(t, v1)
  expect(await waitForDaySubPhase(t.ctx.hostPage, t.ctx.gameId, 'RESULT_REVEALED', 10_000)).toBe(
    true,
  )
  await snap(t, '06-after-chain', testInfo)

  // 游戏记录 on a villager: two anonymous shots.
  const vPage = pageOf(t, v2)
  await vPage.getByTestId('log-fab').click()
  const drawer = vPage.locator('.action-log-drawer')
  await expect(drawer).toBeVisible()
  await expect(vPage.getByTestId('log-shot-row')).toHaveCount(2)
  const log = await drawer.innerText()
  for (const w of ROLE_WORDS) expect(log, `log must not name "${w}"`).not.toContain(w)
  await snapOne(t, vPage, '07-villager-log', testInfo)
  await vPage.getByTestId('action-log-close').click()

  // Day vote: the last villager goes → 屠边.
  await voteOut(t, v2)
  const review = await checkResultAndReview(t, testInfo, '08')
  expect(review).toContain('自刀')
  expect(review.match(/开枪带走/g)?.length, 'review shows both shots').toBe(2)
  expect(review).toContain('黑狼王')
  assertNightAudio(t, 1)
  assertNoRoleLeak(t)
})

// ── 2. Vote-out shot at a god; the dead god's audio keeps playing ──────────

test('vote-out: king shoots the seer, night 2 still plays seer cues', async ({
  browser,
}, testInfo) => {
  testInfo.setTimeout(scale(360_000))
  const t = (table = await setupTable(browser, testInfo))
  const king = one(t, 'BLACK_WOLF_KING')
  const seer = one(t, 'SEER')
  const [wolf] = seatsOf(t, 'WEREWOLF')
  const [v1, v2] = seatsOf(t, 'VILLAGER')
  const guard = one(t, 'GUARD')

  await driveMinimalNight1ViaDom(t.ctx, { wolfTargetSeat: v1, guardTargetSeat: wolf })
  await revealNight(t)
  expect(await waitForDaySubPhase(t.ctx.hostPage, t.ctx.gameId, 'RESULT_REVEALED', 15_000)).toBe(
    true,
  )

  await voteOut(t, king)
  expect(await waitForVotingSubPhase(t.ctx.hostPage, t.ctx.gameId, 'VOTE_RESULT', 10_000)).toBe(
    true,
  )
  await expect(pageOf(t, king).getByTestId('voting-shoot-banner')).toBeVisible()
  await expect(
    t.ctx.hostPage.getByTestId('voting-continue'),
    'the host is not held back (continuing would end the shot)',
  ).toBeVisible()
  await assertShotPrivate(t, king)
  await readEventsAsVillager(t)
  await snap(t, '01-king-voted-out-shooting', testInfo)
  await snapOne(t, pageOf(t, king), '01a-king-vote-shot-screen', testInfo)
  await snapOne(t, pageOf(t, v2), '01b-villager-during-shot', testInfo)

  await voteShoot(t, king, seer)
  await seatDead(t, seer)
  expect(await waitForVotingSubPhase(t.ctx.hostPage, t.ctx.gameId, 'VOTE_RESULT', 10_000)).toBe(
    true,
  )
  await snap(t, '02-seer-shot', testInfo)
  await t.ctx.hostPage.getByTestId('voting-continue').click()

  // Night 2: wolves take the last villager; the dead seer's turn still runs.
  const host = t.ctx.hostPage
  expect(await waitForNightSubPhase(host, t.ctx.gameId, 'WEREWOLF_PICK', 25_000)).toBe(true)
  const wolfPage = pageOf(t, wolf)
  await expect(wolfPage.getByTestId('wolf-confirm-kill')).toBeVisible({ timeout: scale(10_000) })
  await wolfPage.locator(`.player-grid [data-seat="${v2}"]`).click()
  await wolfPage.getByTestId('wolf-confirm-kill').click()
  expect(await waitForNightSubPhase(host, t.ctx.gameId, 'WITCH_ACT', 15_000)).toBe(true)
  const witchPage = pageOf(t, one(t, 'WITCH'))
  for (const id of ['switch-pass-antidote', 'switch-pass-poison']) {
    const b = witchPage.getByTestId(id)
    if (await b.isVisible({ timeout: 5_000 }).catch(() => false)) await b.click()
  }
  expect(
    await waitForNightSubPhase(host, t.ctx.gameId, 'GUARD_PICK', 30_000),
    'dead seer turn passes',
  ).toBe(true)
  const guardPage = pageOf(t, guard)
  await expect(guardPage.getByTestId('guard-confirm-protect')).toBeVisible({
    timeout: scale(10_000),
  })
  await guardPage.locator(`.player-grid [data-seat="${guard}"]`).click()
  await guardPage.getByTestId('guard-confirm-protect').click()

  const review = await checkResultAndReview(t, testInfo, '03')
  expect(review.match(/开枪带走/g)?.length).toBe(1)
  assertNightAudio(t, 2)
  assertNoRoleLeak(t)
})

// ── 3. Hunter voted out → shoots the king → the king's shot decides it ─────

test('vote-out chain: hunter shoots the king, the king fires the deciding shot', async ({
  browser,
}, testInfo) => {
  testInfo.setTimeout(scale(300_000))
  const t = (table = await setupTable(browser, testInfo))
  const king = one(t, 'BLACK_WOLF_KING')
  const hunter = one(t, 'HUNTER')
  const [wolf] = seatsOf(t, 'WEREWOLF')
  const [v1, v2] = seatsOf(t, 'VILLAGER')

  await driveMinimalNight1ViaDom(t.ctx, { wolfTargetSeat: v1, guardTargetSeat: wolf })
  await revealNight(t)
  expect(await waitForDaySubPhase(t.ctx.hostPage, t.ctx.gameId, 'RESULT_REVEALED', 15_000)).toBe(
    true,
  )

  await voteOut(t, hunter)
  await assertShotPrivate(t, hunter)
  await voteShoot(t, hunter, king)
  await seatDead(t, king)
  expect(await waitForVotingSubPhase(t.ctx.hostPage, t.ctx.gameId, 'VOTE_RESULT', 10_000)).toBe(
    true,
  )
  await expect(
    pageOf(t, hunter).getByTestId('voting-shoot-banner'),
    'the hunter fired once',
  ).toHaveCount(0)
  await expect(pageOf(t, king).getByTestId('voting-shoot-banner')).toBeVisible()
  await assertShotPrivate(t, king)
  await snap(t, '01-chain-king-shooting', testInfo)
  await snapOne(t, pageOf(t, king), '01a-king-chain-shot-screen', testInfo)

  await voteShoot(t, king, v2)
  const review = await checkResultAndReview(t, testInfo, '02')
  expect(review.match(/开枪带走/g)?.length, 'review shows both shots').toBe(2)
  assertNightAudio(t, 1)
  assertNoRoleLeak(t)
})

// ── 3b. Night chain, reverse: knifed hunter shoots the king; the king reloads ──

test('night chain: knifed hunter shoots the king, the king reloads and fires back', async ({
  browser,
}, testInfo) => {
  testInfo.setTimeout(scale(300_000))
  const t = (table = await setupTable(browser, testInfo))
  const king = one(t, 'BLACK_WOLF_KING')
  const hunter = one(t, 'HUNTER')
  const [wolf] = seatsOf(t, 'WEREWOLF')
  const [v1] = seatsOf(t, 'VILLAGER')

  await driveMinimalNight1ViaDom(t.ctx, { wolfTargetSeat: hunter, guardTargetSeat: wolf })
  await revealNight(t)
  expect(await waitForDaySubPhase(t.ctx.hostPage, t.ctx.gameId, 'RESULT_REVEALED', 15_000)).toBe(
    true,
  )
  await assertShotPrivate(t, hunter)
  await nightShoot(t, hunter, king)
  await seatDead(t, king)

  // The chained shooter drops and comes back: the shot screen is server state, not page state.
  const kingPage = pageOf(t, king)
  await kingPage.reload()
  await kingPage.locator('.game-wrap').waitFor({ state: 'visible', timeout: scale(15_000) })
  await expect(kingPage.getByTestId('day-hunter-night-banner')).toBeVisible()
  await assertShotPrivate(t, king)
  await snapOne(t, kingPage, '01-king-after-reload-shot-screen', testInfo)
  await nightShoot(t, king, v1)
  await seatDead(t, v1)
  expect(await waitForDaySubPhase(t.ctx.hostPage, t.ctx.gameId, 'RESULT_REVEALED', 10_000)).toBe(
    true,
  )
  await snap(t, '02-after-reverse-chain', testInfo)
  assertNoRoleLeak(t)
})

// ── 4. Poisoned king: no shot ───────────────────────────────────────────────

test('edge: a poisoned king gets no shot', async ({ browser }, testInfo) => {
  testInfo.setTimeout(scale(240_000))
  const t = (table = await setupTable(browser, testInfo))
  const king = one(t, 'BLACK_WOLF_KING')
  const [wolf] = seatsOf(t, 'WEREWOLF')
  const [v1] = seatsOf(t, 'VILLAGER')

  await driveMinimalNight1ViaDom(t.ctx, {
    wolfTargetSeat: v1,
    guardTargetSeat: wolf,
    witch: { mode: 'poison', targetSeat: king },
  })
  await revealNight(t)
  expect(
    await waitForDaySubPhase(t.ctx.hostPage, t.ctx.gameId, 'RESULT_REVEALED', 15_000),
    'the reveal lands on the ordinary day',
  ).toBe(true)
  expect(await aliveSeats(t)).not.toContain(king)
  const kingPage = pageOf(t, king)
  await expect(kingPage.getByTestId('day-hunter-night-banner')).toHaveCount(0)
  await expect(kingPage.getByTestId('day-hunter-night-shoot')).toHaveCount(0)
  await expect(t.ctx.hostPage.getByTestId('day-start-vote')).toBeVisible()
  await snap(t, '01-poisoned-king-no-shot', testInfo)
  await snapOne(t, kingPage, '01a-poisoned-king-screen', testInfo)
  assertNoRoleLeak(t)
})

// ── 5. Self-destructing king: no shot, no take ──────────────────────────────

test('edge: a self-destructing king gets no shot and takes no one', async ({
  browser,
}, testInfo) => {
  testInfo.setTimeout(scale(240_000))
  const t = (table = await setupTable(browser, testInfo))
  const king = one(t, 'BLACK_WOLF_KING')
  const [wolf] = seatsOf(t, 'WEREWOLF')
  const [v1] = seatsOf(t, 'VILLAGER')

  await driveMinimalNight1ViaDom(t.ctx, { wolfTargetSeat: v1, guardTargetSeat: wolf })
  await revealNight(t)
  expect(await waitForDaySubPhase(t.ctx.hostPage, t.ctx.gameId, 'RESULT_REVEALED', 15_000)).toBe(
    true,
  )
  const before = await aliveSeats(t)

  const kingPage = pageOf(t, king)
  await kingPage.getByTestId('action-menu-btn').click()
  await kingPage.getByTestId('action-menu-self-destruct').click()
  await snapOne(t, kingPage, '01-king-self-destruct-confirm', testInfo)
  await kingPage.getByTestId('action-menu-confirm').click()

  expect(await waitForDaySkipVoting(t.ctx.hostPage, t.ctx.gameId, 10_000)).toBe(true)
  const after = await aliveSeats(t)
  expect(after, 'only the king died').toEqual(before.filter((s) => s !== king))
  const state = await fetchGameState(t.ctx.hostPage, t.ctx.gameId)
  expect(state?.dayPhase?.subPhase).toBe('RESULT_REVEALED')
  await expect(kingPage.getByTestId('day-hunter-night-banner')).toHaveCount(0)
  await expect(t.ctx.hostPage.getByTestId('day-enter-night')).toBeVisible()
  await snap(t, '02-after-self-destruct', testInfo)
  await snapOne(t, t.ctx.hostPage, '02a-host-after-self-destruct', testInfo)
  assertNoRoleLeak(t)
})

// ── 6. The host moves on before the shooter acts: the shot is lost ──────────

test('edge: the host starts the vote before the knifed king acts - the shot is lost', async ({
  browser,
}, testInfo) => {
  testInfo.setTimeout(scale(240_000))
  const t = (table = await setupTable(browser, testInfo))
  const king = one(t, 'BLACK_WOLF_KING')
  const hunter = one(t, 'HUNTER')
  const [wolf] = seatsOf(t, 'WEREWOLF')

  await driveMinimalNight1ViaDom(t.ctx, { wolfTargetSeat: king, guardTargetSeat: wolf })
  await revealNight(t)
  const kingPage = pageOf(t, king)
  await expect(kingPage.getByTestId('day-hunter-night-banner')).toBeVisible({
    timeout: scale(15_000),
  })
  await assertShotPrivate(t, king)
  await snap(t, '01-king-may-shoot', testInfo)

  // The host cannot tell a shot is open, and starts the vote at once.
  await t.ctx.hostPage.getByTestId('day-start-vote').click()
  expect(await waitForVotingSubPhase(t.ctx.hostPage, t.ctx.gameId, 'VOTING', 10_000)).toBe(true)
  await expect(kingPage.getByTestId('day-hunter-night-banner')).toHaveCount(0)
  await expect(kingPage.getByTestId('voting-shoot-banner')).toHaveCount(0)

  // The server refuses a late shot sent straight from the king's browser.
  const late = await kingPage.evaluate(
    async ({ gameId, target }) => {
      const res = await fetch('/api/game/action', {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${localStorage.getItem('jwt')}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ gameId, actionType: 'HUNTER_SHOOT', targetUserId: target }),
      })
      return { status: res.status, body: await res.text() }
    },
    { gameId: Number(t.ctx.gameId), target: t.seatUser.get(hunter)! },
  )
  expect(late.status, `late shot rejected: ${late.body}`).toBe(400)
  expect(await aliveSeats(t)).toContain(hunter)
  await snap(t, '02-vote-started-shot-lost', testInfo)
  await snapOne(t, kingPage, '02a-king-screen-after-vote-start', testInfo)
  assertNoRoleLeak(t)
})

// ── 7. A pass is private: nothing reaches the other browsers ────────────────

test('edge: a voted-out hunter passes - no other browser receives anything', async ({
  browser,
}, testInfo) => {
  testInfo.setTimeout(scale(240_000))
  const t = (table = await setupTable(browser, testInfo))
  const hunter = one(t, 'HUNTER')
  const [wolf] = seatsOf(t, 'WEREWOLF')
  const [v1] = seatsOf(t, 'VILLAGER')

  await driveMinimalNight1ViaDom(t.ctx, { wolfTargetSeat: v1, guardTargetSeat: wolf })
  await revealNight(t)
  expect(await waitForDaySubPhase(t.ctx.hostPage, t.ctx.gameId, 'RESULT_REVEALED', 15_000)).toBe(
    true,
  )
  await voteOut(t, hunter)
  const hunterPage = pageOf(t, hunter)
  await expect(hunterPage.getByTestId('voting-shoot-banner')).toBeVisible({
    timeout: scale(10_000),
  })
  await assertShotPrivate(t, hunter)
  await snapOne(t, hunterPage, '01-hunter-shot-screen', testInfo)

  // Any recorded browser other than the shooter's (the host's page is not recorded).
  const watcher = [...t.payloads].find(([seat]) => seat !== hunter)![1]
  const before = watcher.bodies.length // index into everything this browser recorded
  await hunterPage.getByTestId('voting-hunter-pass').click()
  // The hunter's own screen closes the shot (it re-reads its own state).
  await expect(hunterPage.getByTestId('voting-shoot-banner')).toHaveCount(0, {
    timeout: scale(10_000),
  })
  await expect(hunterPage.locator('.elim-banner-name')).toBeVisible()
  await snap(t, '02-after-private-pass', testInfo)

  // Ordering fence: the host's next public step reaches the watcher after
  // anything the pass might have broadcast (STOMP is FIFO per subscription).
  await t.ctx.hostPage.getByTestId('voting-continue').click()
  const newStomp = () => watcher.bodies.slice(before).filter((b) => b.src === 'stomp')
  await expect
    .poll(() => newStomp().some((b) => b.body.includes('"NIGHT"')), { timeout: scale(15_000) })
    .toBe(true)
  const frames = newStomp()
  const firstNight = frames.findIndex((b) => b.body.includes('"NIGHT"'))
  expect(
    frames.slice(0, firstNight).map((b) => b.body.slice(0, 80)),
    'the pass broadcast nothing before the host went to night',
  ).toEqual([])
  assertNoRoleLeak(t)
})
