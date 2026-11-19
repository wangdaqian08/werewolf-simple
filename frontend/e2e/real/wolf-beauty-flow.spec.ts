/**
 * 狼美人 (WOLF_BEAUTY) — real-backend evidence for
 * docs/superpowers/specs/2026-11-18-wolf-beauty-demon-hunter-design.md.
 *
 *  9 seats: 2 wolves + 狼美人, seer, witch, guard, hunter, 2 villagers (the host is one); no sheriff.
 *
 *  N1: the pack knifes the guard (never the hunter: its shot would add a death);
 *      the 狼美人's turn opens right after the wolves and offers only the night-1 確認.
 *  D1: nobody is voted out.
 *  N2: the pack passes (空刀); the 狼美人 charms the other villager — its pack, itself and
 *      the dead are greyed out.
 *  D2: the table votes the 狼美人 out → the villager 殉情 at once: the banner names both
 *      seats and the victim is dead in state.
 *  N3: the pack knifes the host, the last villager → the night ends in 屠邊, the wolves win.
 *  The review lists the charm and the CHARM_DEATH event.
 *
 *
 * CI-vs-local design (memory feedback_e2e_ci_vs_local):
 *  - the 狼美人 acts through its own browser (DOM testids); every other role via act.sh;
 *  - the host is always a villager (swapped in), so every bot actor is known up front;
 *  - every action is gated on the backend sub-phase, never on a sleep;
 *  - timeouts scale 2× under CI; the backend log must show no REJECTED action.
 *
 * Leak scan (before game over): only the 狼美人's page may receive `wolfBeauty`.
 */
import { expect, type Page, test } from '@playwright/test'
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
  waitForVotingSubPhase,
} from './helpers/state-polling'
import { hostStartsNight } from './helpers/night-driver'
import { readBackendLogLineCount, readBackendLogSince } from './helpers/backend-log'
import { findNightOrderViolations } from './helpers/audio-manifest'
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
  'GUARD',
  'HUNTER',
]

const scale = (ms: number): number => (process.env.CI ? ms * 2 : ms)

const nonHost = (ctx: GameContext, role: RoleName): BotInfo[] =>
  (ctx.roleMap[role] ?? []).filter((b) => b.nick !== 'Host')

function only(ctx: GameContext, role: RoleName): BotInfo {
  const holder = nonHost(ctx, role)[0]
  assertNonNull(holder, `a ${role} bot`)
  return holder
}

async function reach(ctx: GameContext, subPhase: string): Promise<void> {
  expect(
    await waitForSubPhase(ctx.hostPage, ctx.gameId, subPhase, 25_000),
    `night sub-phase ${subPhase} reached`,
  ).toBe(true)
}

function bot(
  ctx: GameContext,
  action: string,
  actor: BotInfo,
  opts: { target?: number; payload?: string } = {},
) {
  const ok = tryAct(action, actName(actor), {
    target: opts.target === undefined ? undefined : String(opts.target),
    payload: opts.payload,
    room: ctx.roomCode,
  })
  expect(ok, `${action} by ${actor.nick} accepted`).toBe(true)
}

async function isAlive(ctx: GameContext, seat: number): Promise<boolean | undefined> {
  const state = await fetchGameState(ctx.hostPage, ctx.gameId)
  return state?.players?.find((p) => p.seatIndex === seat)?.isAlive
}

test.describe('Wolf Beauty (real backend)', () => {
  let ctx: GameContext | undefined

  // eslint-disable-next-line no-empty-pattern
  test.afterEach(async ({}, testInfo) => {
    if (ctx && testInfo.status === 'failed') await attachCompositeOnFailure(ctx.pages, testInfo)
  })
  test.afterAll(async () => {
    if (!ctx) return
    await cleanupRoomData(ctx.hostPage, ctx.roomCode)
    await ctx.cleanup()
  })

  test('狼美人 charms a villager, who 殉情 when the 狼美人 is voted out', async ({
    browser,
  }, testInfo) => {
    test.setTimeout(process.env.CI ? 720_000 : 360_000)

    const payloads = new Map<string, PayloadLog>()
    const pageOf = new Map<string, Page>() // the page each log was recorded on (labels move on a swap)
    ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      noKillAllowed: true,
      roles: ROLES,
      // The VILLAGER page becomes the 狼美人's when the host rolled it (swap below).
      browserRoles: ['WOLF_BEAUTY', 'VILLAGER'],
      onPage: (label, p) => {
        payloads.set(label, recordPayloads(p))
        pageOf.set(label, p)
      },
    })
    const game = ctx
    const logStart = readBackendLogLineCount()
    game.markBackendLogPosition()
    const hostId = await readHostUserId(game.hostPage)
    assertNonNull(hostId, 'host userId')

    // ── The host is a villager: knifed only on the last night, never voted out ──
    const dealtHostRole = game.hostRole
    let bPageOrNone = game.pages.get('WOLF_BEAUTY')
    if (game.hostRole !== 'VILLAGER') {
      const pagedVillager = game.bots.get('VILLAGER')
      const villagerPage = game.pages.get('VILLAGER')
      assertNonNull(pagedVillager, 'a villager bot with a page')
      assertNonNull(villagerPage, 'the villager browser page')
      const hostWasBeauty = game.hostRole === 'WOLF_BEAUTY'
      // A host 狼美人 hands its role to the villager whose page is open; any other host
      // role goes to the villager without one.
      const partner = hostWasBeauty
        ? pagedVillager
        : nonHost(game, 'VILLAGER').find((v) => v.userId !== pagedVillager.userId)
      assertNonNull(partner, 'a dealt villager to swap with')
      await swapRoles(game.hostPage, Number(game.gameId), hostId, partner.userId)
      if (hostWasBeauty) {
        // The paged villager now holds the 狼美人: relabel so failure composites name pages right.
        bPageOrNone = villagerPage
        game.pages.set('WOLF_BEAUTY', villagerPage)
        game.pages.set('VILLAGER', game.hostPage)
        game.bots.delete('VILLAGER')
        game.bots.set('WOLF_BEAUTY', pagedVillager)
      }
      game.roleMap = getRoles(game.roomCode)
      game.hostRole = 'VILLAGER'
      game.isHostRole = (role) => role === 'VILLAGER'
      // Scan only what each page receives in its final role.
      for (const log of payloads.values()) log.bodies.length = 0
      // GameView's confirmed flag is local: a reload brings the card back, and it must show
      // the page's NEW role (the swap happens in ROLE_REVEAL, before the night).
      const reconfirm = async (page: Page, role: RoleName) => {
        await page.reload()
        const reveal = page.getByTestId('reveal-role-btn')
        await reveal.waitFor({ state: 'visible', timeout: scale(15_000) })
        await reveal.click()
        await expect(page.getByTestId('role-reveal-card')).toHaveAttribute('data-role', role)
        await page.getByTestId('confirm-role-btn').click()
      }
      if (hostWasBeauty) await reconfirm(villagerPage, 'WOLF_BEAUTY')
      await reconfirm(game.hostPage, 'VILLAGER')
      await expect(game.hostPage.getByTestId('start-night')).toBeVisible({
        timeout: scale(15_000),
      })
    }
    assertNonNull(bPageOrNone, 'the 狼美人 browser page')
    const bPage: Page = bPageOrNone

    const beauty = only(game, 'WOLF_BEAUTY')
    const seer = only(game, 'SEER')
    const witch = only(game, 'WITCH')
    const guard = only(game, 'GUARD')
    const hunter = only(game, 'HUNTER')
    const victim = only(game, 'VILLAGER')
    const [wolfA, wolfB] = nonHost(game, 'WEREWOLF')
    assertNonNull(wolfA, 'pack wolf A')
    assertNonNull(wolfB, 'pack wolf B')
    const hostSeat = (game.roleMap.VILLAGER ?? []).find((b) => b.userId === hostId)?.seat
    assertNonNull(hostSeat, 'host seat')
    console.warn(
      `[wolf-beauty] game=${game.gameId} host=${hostSeat} (dealt ${dealtHostRole}) beauty=${beauty.seat} ` +
        `wolves=${wolfA.seat},${wolfB.seat} seer=${seer.seat} witch=${witch.seat} ` +
        `guard=${guard.seat} hunter=${hunter.seat} victim=${victim.seat}`,
    )

    const turn = bPage.getByTestId('wolf-beauty-turn')

    /** Witch passes, the seer checks, and a living guard protects; then the day (or the end). */
    async function restOfNight(
      plan: { seerChecks: BotInfo; guardProtects?: BotInfo },
      endPhase = 'DAY_DISCUSSION',
    ) {
      await reach(game, 'WITCH_ACT')
      bot(game, 'WITCH_ACT', witch, { payload: '{"useAntidote":false}' })
      await reach(game, 'SEER_PICK')
      bot(game, 'SEER_CHECK', seer, { target: plan.seerChecks.seat })
      await reach(game, 'SEER_RESULT')
      bot(game, 'SEER_CONFIRM', seer)
      if (plan.guardProtects) {
        await reach(game, 'GUARD_PICK')
        bot(game, 'GUARD_PROTECT', guard, { target: plan.guardProtects.seat })
      }
      expect(await waitForPhase(game.hostPage, game.gameId, endPhase, 30_000)).toBe(true)
    }

    // ── N1: the knife avoids the hunter; the 狼美人 only confirms ──
    await hostStartsNight(game)
    await reach(game, 'WEREWOLF_PICK')
    bot(game, 'WOLF_KILL', wolfA, { target: guard.seat })
    await reach(game, 'WOLF_BEAUTY_PICK')
    await expect(turn).toBeVisible({ timeout: scale(10_000) })
    await expect(bPage.getByTestId('wolf-beauty-confirm'), 'no charm on night 1').toHaveCount(0)
    await captureSnapshot(game.pages, testInfo, 'n1-wolf-beauty-confirm')
    await bPage.getByTestId('wolf-beauty-n1-confirm').click()
    await restOfNight({ seerChecks: wolfA, guardProtects: witch })

    expect(await completeDay(game, testInfo, { targetSeat: null, label: 'wb-d1' })).toBe('NIGHT')
    expect(await isAlive(game, guard.seat), 'the guard was knifed on N1').toBe(false)

    // ── N2: the pack passes; the 狼美人 charms the villager ──
    await reach(game, 'WEREWOLF_PICK')
    bot(game, 'WOLF_SKIP', wolfA)
    await reach(game, 'WOLF_BEAUTY_PICK')
    await expect(turn).toBeVisible({ timeout: scale(10_000) })
    for (const [seat, why] of [
      [wolfA.seat, 'pack'],
      [wolfB.seat, 'pack'],
      [beauty.seat, 'self'],
      [guard.seat, 'dead'],
    ] as const) {
      await expect(bPage.getByTestId(`wolf-beauty-target-${seat}`), `${why} greyed`).toBeDisabled()
    }
    await bPage.getByTestId(`wolf-beauty-target-${victim.seat}`).click()
    await bPage.getByTestId('wolf-beauty-confirm').click()
    await captureSnapshot(game.pages, testInfo, 'n2-wolf-beauty-charm')
    await restOfNight({ seerChecks: hunter })

    // ── D2: the 狼美人 is voted out → the charmed villager 殉情 ──
    const revealBtn = game.hostPage.getByTestId('day-reveal-result')
    await expect(revealBtn).toBeVisible({ timeout: scale(15_000) })
    await revealBtn.click()
    expect(await waitForDayRevealed(game.hostPage, game.gameId)).toBe('DAY_DISCUSSION')
    await game.hostPage.getByTestId('day-start-vote').click()
    expect(await waitForVotingSubPhase(game.hostPage, game.gameId, 'VOTING', 15_000)).toBe(true)
    const unvoted = await readUnvotedAlivePlayerIds(game.hostPage, game.gameId)
    for (const voter of [...game.allBots, { nick: 'Host', userId: hostId }]) {
      if (!unvoted.has(voter.userId)) continue
      expect(
        tryAct('SUBMIT_VOTE', voter.nick, { target: String(beauty.seat), room: game.roomCode }),
        `${voter.nick} votes the 狼美人`,
      ).toBe(true)
    }
    await waitForCondition(
      async () => (await readUnvotedAlivePlayerIds(game.hostPage, game.gameId)).size === 0,
      'all alive players voted',
      scale(15_000),
    )
    await game.hostPage.getByTestId('voting-reveal').click()
    expect(await waitForVotingSubPhase(game.hostPage, game.gameId, 'VOTE_RESULT', 15_000)).toBe(
      true,
    )
    const bannerText =
      `${beauty.seat}號 · ${beauty.nick}（狼美人）出局，` +
      `${victim.seat}號 · ${victim.nick}殉情出局`
    for (const page of [game.hostPage, bPage]) {
      await expect(page.getByTestId('charm-death-banner')).toContainText(bannerText, {
        timeout: scale(10_000),
      })
    }
    expect(await isAlive(game, beauty.seat), 'the 狼美人 was voted out').toBe(false)
    expect(await isAlive(game, victim.seat), 'the charmed villager 殉情').toBe(false)
    await captureSnapshot(game.pages, testInfo, 'd2-charm-death')
    await game.hostPage.getByTestId('voting-continue').click()
    expect(await waitForPhase(game.hostPage, game.gameId, 'NIGHT', 15_000)).toBe(true)

    // ── Leak scan before game over (it reveals every role): only the 狼美人's page gets `wolfBeauty` ──
    for (const [label, log] of payloads) {
      const page = pageOf.get(label)
      assertNonNull(page, `${label} page`)
      const role = page === bPage ? 'WOLF_BEAUTY' : 'VILLAGER'
      const me = await page.evaluate(() => localStorage.getItem('userId'))
      assertNonNull(me, `${label} userId`)
      expect(log.bodies.length, `${label} recorded payloads`).toBeGreaterThan(0)
      expect(findRoleLeaks(log, me, role), `${label} (${role}) got a role`).toEqual([])
      const carriers = log.bodies.filter((b) => b.body.includes('"wolfBeauty":{'))
      if (role === 'WOLF_BEAUTY') {
        expect(carriers.length, 'the 狼美人 received its state').toBeGreaterThan(0)
      } else {
        expect(
          carriers.map((b) => b.src),
          `${label} must never receive the 狼美人 state`,
        ).toEqual([])
      }
      console.log(
        `[wolf-beauty] leak-scan ${label}=${role} scanned=${log.bodies.length} carriers=${carriers.length}`,
      )
    }

    // ── N3: the pack knifes the host, the last villager → the night ends in 屠邊 ──
    await reach(game, 'WEREWOLF_PICK')
    bot(game, 'WOLF_KILL', wolfA, { target: hostSeat })
    await restOfNight({ seerChecks: witch }, 'GAME_OVER')
    const end = await fetchGameState(game.hostPage, game.gameId)
    expect((end as { winner?: string } | null)?.winner).toBe('WEREWOLF')

    // ── Review: the N2 charm and the D2 殉情 ──
    await game.hostPage.goto(`/review/${game.gameId}`)
    const review = game.hostPage.getByTestId('game-review')
    await expect(review).toBeVisible({ timeout: scale(10_000) })
    await expect(review, 'N2 charm').toContainText(
      new RegExp(`🌹\\s*狼美人\\s*(?<!\\d)${beauty.seat}號\\s*魅惑\\s*(?<!\\d)${victim.seat}號`),
    )
    await expect(review, 'D2 CHARM_DEATH').toContainText(
      new RegExp(
        `🌹\\s*(?<!\\d)${beauty.seat}號[^〔\\n]*〔狼美人〕\\s*出局，殉情帶走\\s*` +
          `(?<!\\d)${victim.seat}號[^〔\\n]*〔村民〕`,
      ),
    )
    await captureSnapshot(game.pages, testInfo, 'review')

    // ── Backend evidence ──
    const lines = readBackendLogSince(logStart)
    const gameTag = new RegExp(`game=${game.gameId}(?=[\\s:,])`)
    expect(
      lines.filter((l) => l.includes('-> REJECTED') && gameTag.test(l)),
      'no action was rejected',
    ).toEqual([])
    // The 狼美人's cues follow the wolves every night, dead or alive.
    expect(findNightOrderViolations(lines, game.gameId, 3, { wolfBeauty: true })).toEqual([])
    await assertNoBrowserErrors(game.errors, testInfo)
    await game.assertNoBackendErrors(testInfo)
  })
})
