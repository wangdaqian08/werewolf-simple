/**
 * Witch self-save BLOCKED + poison-pierces-guard — the "wolf kills the witch,
 * witch poisons the guard" scenario, with the 女巫自救 room toggle OFF.
 *
 * Unique coverage (nothing else touches these):
 *   - the witchSelfSaveAllowed=false room option end-to-end (CreateRoom
 *     toggle → GameConfig → WitchHandler gate → NightPhase.vue blocked UI)
 *   - `witch-self-save-blocked` banner shown to the attacked witch, with the
 *     save button absent; the backend authoritatively REJECTS an API
 *     self-save attempt
 *   - a night-victim witch still ACTS the night she dies (kills are
 *     deferred): her poison resolves
 *   - poison pierces guard protection: the guard self-protects and still
 *     dies to the poison → TWO deaths at the reveal
 *
 * 8 players (2 WEREWOLF + WITCH + GUARD + 4 VILLAGER) so the projected
 * 2W/4H after the double kill stays clear of the CLASSIC POST_NIGHT parity
 * check — the reveal under test must happen, not GAME_OVER.
 */
import { expect, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { actName, type RoleName } from './helpers/shell-runner'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { assertActorRole, assertNonNull, resolveRolePlayer, tryAct } from './helpers/flow-drivers'
import { waitForNightSubPhase, waitForPhase } from './helpers/state-polling'
import { cleanupRoomData } from './helpers/test-support'
import {
  assertGameInvariants,
  newInvariantState,
  type GameInvariantState,
} from './helpers/invariants'

test.describe('Witch self-save blocked — victim witch poisons the guard', () => {
  test.setTimeout(process.env.CI ? 360_000 : 180_000)

  let ctx: GameContext

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(process.env.CI ? 240_000 : 120_000)
    ctx = await setupGame(browser, {
      totalPlayers: 8,
      hasSheriff: false,
      witchSelfSaveAllowed: false,
      roles: ['WEREWOLF', 'VILLAGER', 'WITCH', 'GUARD'] as RoleName[],
      browserRoles: ['WEREWOLF', 'WITCH', 'GUARD'] as RoleName[],
    })
  })

  test.afterAll(async () => {
    if (ctx) await cleanupRoomData(ctx.hostPage, ctx.roomCode)
    await ctx?.cleanup()
  })

  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status === 'failed' && ctx?.pages) {
      await attachCompositeOnFailure(ctx.pages, testInfo)
    }
  })

  test('blocked self-save UI + rejected API save + poison through the self-protecting guard', async ({}, testInfo) => {
    const hostPage = ctx.hostPage
    const gameId = ctx.gameId
    let invariants: GameInvariantState = newInvariantState()

    const witch = await resolveRolePlayer(ctx, 'WITCH')
    const guard = await resolveRolePlayer(ctx, 'GUARD')
    assertNonNull(witch, `kit must have a WITCH (bot or host=${ctx.hostRole})`)
    assertNonNull(guard, `kit must have a GUARD (bot or host=${ctx.hostRole})`)
    const witchPage = ctx.pages.get('WITCH')
    const guardPage = ctx.pages.get('GUARD')
    const wolfPage = ctx.pages.get('WEREWOLF')
    assertNonNull(witchPage, 'WITCH browser page required (blocked-UI assertions)')
    assertNonNull(guardPage, 'GUARD browser page required (self-protect click)')
    assertNonNull(wolfPage, 'WEREWOLF browser page required (kill click)')

    // The room config must carry the disabled toggle end-to-end.
    const selfSaveAllowed = await hostPage.evaluate(async (id: string) => {
      const token = localStorage.getItem('jwt')
      const res = await fetch(`/api/game/${id}/state`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      return res.ok ? (await res.json())?.witchSelfSaveAllowed : null
    }, gameId)
    expect(selfSaveAllowed, 'game state must expose witchSelfSaveAllowed=false').toBe(false)

    // ── N1 driven inline (the blocked-UI assertions must happen DURING
    // WITCH_ACT, between the wolf kill and the witch's poison) ────────────
    // Host starts the night; self-heal a stale role card first (same
    // rationale as night-driver.ts — hasConfirmedRole is a local ref).
    const staleCardReveal = hostPage.getByTestId('reveal-role-btn')
    if (await staleCardReveal.isVisible({ timeout: 1_500 }).catch(() => false)) {
      await staleCardReveal.click()
      const staleCardConfirm = hostPage.getByTestId('confirm-role-btn')
      if (await staleCardConfirm.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await staleCardConfirm.click()
      }
    }
    const startBtn = hostPage.getByTestId('start-night')
    await expect(startBtn).toBeVisible({ timeout: 15_000 })
    await startBtn.click()
    expect(await waitForPhase(hostPage, gameId, 'NIGHT', 15_000)).toBe(true)

    // Wolves kill the WITCH (DOM click on the witch's seat).
    expect(await waitForNightSubPhase(hostPage, gameId, 'WEREWOLF_PICK', 25_000)).toBe(true)
    const witchSlot = wolfPage.locator(`.player-grid [data-seat="${witch.seat}"]`)
    await expect(witchSlot, `witch seat ${witch.seat} must render on the wolf page`).toBeVisible({
      timeout: 10_000,
    })
    await witchSlot.click()
    await wolfPage.getByTestId('wolf-confirm-kill').click()

    // WITCH_ACT: the attacked witch sees the blocked banner, no save button.
    expect(await waitForNightSubPhase(hostPage, gameId, 'WITCH_ACT', 15_000)).toBe(true)
    await expect(
      witchPage.getByTestId('witch-self-save-blocked'),
      'attacked witch must see the self-save-blocked banner when the toggle is off',
    ).toBeVisible({ timeout: 10_000 })
    expect(
      await witchPage.getByTestId('witch-antidote').count(),
      'the save button must NOT render for a blocked self-save',
    ).toBe(0)
    await captureSnapshot(ctx.pages, testInfo, 'wssb-01-blocked-banner')

    // Backend is the authoritative validator: an API self-save attempt is
    // rejected outright (does not consume the witch's turn).
    const witchBot = (ctx.roleMap.WITCH ?? [])[0]
    assertNonNull(witchBot, 'roleMap must contain the witch')
    assertActorRole(ctx, witchBot, 'WITCH', 'WITCH_ACT')
    expect(
      tryAct('WITCH_ACT', actName(witchBot), {
        payload: '{"useAntidote":true}',
        room: ctx.roomCode,
      }),
      'backend must reject the blocked self-save',
    ).toBe(false)

    // The dying witch still acts: poison the GUARD via the real UI.
    await witchPage.getByTestId('use-poison').click()
    const guardSlot = witchPage.locator(`.player-grid [data-seat="${guard.seat}"]`)
    await expect(
      guardSlot,
      `guard seat ${guard.seat} must render in the poison picker`,
    ).toBeVisible({ timeout: 10_000 })
    await guardSlot.click()
    await witchPage.getByTestId('witch-poison-confirm').click()

    // GUARD_PICK: the guard protects HIMSELF — poison must pierce it.
    expect(await waitForNightSubPhase(hostPage, gameId, 'GUARD_PICK', 15_000)).toBe(true)
    const selfSlot = guardPage.locator(`.player-grid [data-seat="${guard.seat}"]`)
    await expect(selfSlot, 'guard self-protect slot must render').toBeVisible({ timeout: 10_000 })
    await selfSlot.click()
    await guardPage.getByTestId('guard-confirm-protect').click()

    // ── Reveal: TWO dead — the witch (blocked save) AND the guard (poison
    // pierced his own protection) ─────────────────────────────────────────
    expect(await waitForPhase(hostPage, gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)
    invariants = await assertGameInvariants(hostPage, gameId, invariants, 'night-resolved')

    const revealBtn = hostPage.getByTestId('day-reveal-result')
    await revealBtn.waitFor({ state: 'visible', timeout: 30_000 })
    await revealBtn.click()
    await expect(hostPage.getByTestId('day-banner-kill')).toBeVisible({ timeout: 10_000 })
    await expect(
      hostPage.getByTestId(`day-killed-seat-${witch.seat}`),
      'the witch dies — self-save was blocked',
    ).toBeVisible({ timeout: 5_000 })
    await expect(
      hostPage.getByTestId(`day-killed-seat-${guard.seat}`),
      'the guard dies — poison pierces his own protection',
    ).toBeVisible({ timeout: 5_000 })
    await captureSnapshot(ctx.pages, testInfo, 'wssb-02-two-dead-revealed')

    await assertGameInvariants(hostPage, gameId, invariants, 'revealed')
    await ctx.assertNoBackendErrors(testInfo)
  })
})
