/**
 * Real-backend E2E: Day 1 outcome scenarios — explicit end-state coverage.
 *
 * Split out of game-flow.spec.ts to let CI shard them onto a different runner
 * from the long sequential N1/N2/N3 flow. Each `row` test boots its own
 * setupGame so they are individually shardable; nothing here depends on the
 * sequential describe block in game-flow.spec.ts.
 *
 * Row 1 (NIGHT/day=2) is covered by game-flow.spec.ts's test 8.
 * Row 5 (BADGE_HANDOVER) is covered by flow-12p-sheriff.spec.ts.
 */
import { expect, test } from '@playwright/test'
import { setupGame } from './helpers/multi-browser'
import { act, actName, type RoleName } from './helpers/shell-runner'
import { waitForPhase } from './helpers/state-polling'

test.describe('Day 1 outcome scenarios — explicit end-state coverage', () => {
  test.setTimeout(180_000)

  // ── Row 2: villager-win when D1 vote eliminates the last wolf ──
  test('row 2 — villager-win after D1 vote kills the last wolf', async ({ browser }, testInfo) => {
    testInfo.setTimeout(240_000)
    // 6p kit: GameService.kt:316 → 2 wolves. Plus SEER + WITCH + 2 villagers
    // (HUNTER and GUARD intentionally off so D1's elimination cleanly
    // resolves to a win check, no HUNTER_SHOOT detour).
    const localCtx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
    })
    try {
      const hostPage = localCtx.hostPage
      // Don't filter host out: act.sh handles PLAYER='HOST' via the cached
      // host token (act.sh:378), so a host-as-WITCH/SEER/WEREWOLF row is
      // driveable through the same script path. The role lookup just needs
      // to return whoever holds the role, host or bot.
      const wolves = localCtx.roleMap.WEREWOLF ?? []
      const seer = (localCtx.roleMap.SEER ?? [])[0]
      const witch = (localCtx.roleMap.WITCH ?? [])[0]
      expect(wolves.length, 'kit must have 2 wolves').toBe(2)
      expect(seer, 'kit must have 1 seer').toBeDefined()
      expect(witch, 'kit must have 1 witch').toBeDefined()
      // eslint-disable-next-line no-console
      console.warn(
        `[row 2] hostRole=${localCtx.hostRole} wolves=${wolves.map((b) => b.nick).join(',')} ` +
          `seer=${seer.nick} witch=${witch.nick}`,
      )

      // Start night
      await hostPage.getByTestId('start-night').click()
      await waitForPhase(hostPage, localCtx.gameId, 'NIGHT', 15_000)

      // ── N1 ──
      // Wolves kill SOME victim. Backend rule (verified by log on a prior
      // run: REJECTED reason="Cannot use antidote and poison on the same
      // night"): witch can't use BOTH potions in one WITCH_ACT. So we
      // use poison only — the wolf-kill victim dies, AND wolves[1] dies
      // from poison. After N1: 2 deaths. wolves=1, humans=3. D1 vote of
      // wolves[0] then closes out the wolves → villager-win.
      const wolfIds = new Set(wolves.map((w) => w.userId))
      const victim =
        (localCtx.roleMap.VILLAGER ?? []).find((b) => !wolfIds.has(b.userId)) ??
        localCtx.allBots.find(
          (b) => !wolfIds.has(b.userId) && b.userId !== seer.userId && b.userId !== witch.userId,
        )
      expect(victim, 'need a non-wolf victim for the wolves to kill').toBeDefined()
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WEREWOLF_PICK', 15_000),
        'expected NIGHT/WEREWOLF_PICK before firing WOLF_KILL',
      ).toBe(true)
      act('WOLF_KILL', actName(wolves[0]), {
        target: String(victim!.seat),
        room: localCtx.roomCode,
      })

      // Seer checks (just to advance the phase deterministically). Assert
      // each gate so a wrong-sub-phase doesn't silently fire act() with
      // a "Not in <X> sub-phase" rejection in the CI log.
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_PICK', 15_000),
        'expected NIGHT/SEER_PICK before firing SEER_CHECK',
      ).toBe(true)
      act('SEER_CHECK', actName(seer), { target: String(wolves[0].seat), room: localCtx.roomCode })
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_RESULT', 10_000),
        'expected NIGHT/SEER_RESULT before firing SEER_CONFIRM',
      ).toBe(true)
      act('SEER_CONFIRM', actName(seer), { room: localCtx.roomCode })

      // Witch: poison-only on wolves[1]. No antidote (backend forbids
      // combined antidote+poison in a single WITCH_ACT).
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WITCH_ACT', 15_000),
        'expected NIGHT/WITCH_ACT before firing WITCH_ACT',
      ).toBe(true)
      act('WITCH_ACT', actName(witch), {
        room: localCtx.roomCode,
        payload: JSON.stringify({
          useAntidote: false,
          poisonTargetUserId: wolves[1].userId,
        }),
      })

      // Night resolves. victim dead (wolf kill, no save), wolves[1] dead (poison).
      await waitForPhase(hostPage, localCtx.gameId, 'DAY_DISCUSSION', 20_000)

      // ── D1 ──
      await hostPage.getByTestId('day-reveal-result').click()
      await hostPage.getByTestId('day-start-vote').click()
      await waitForVotingSubPhase(hostPage, localCtx.gameId, 'VOTING', 10_000)

      // Vote out wolves[0] (the surviving wolf). Iterate alive non-host
      // unvoted bots explicitly — `act('SUBMIT_VOTE', undefined, ...)` does
      // act.sh's full bot fan-out which includes dead bots, producing
      // "Dead players cannot vote" rejections in the CI log (the wolf
      // killed villagers[0] at N1, so by D1 they're dead).
      const unvoted1 = await readUnvotedAlivePlayerIds(hostPage, localCtx.gameId)
      for (const bot of localCtx.allBots) {
        if (bot.nick === 'Host') continue
        if (!unvoted1.has(bot.userId)) continue
        act('SUBMIT_VOTE', bot.nick, { target: String(wolves[0].seat), room: localCtx.roomCode })
      }
      const hostAbstain = hostPage.locator('.skip-btn').first()
      if (await hostAbstain.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await hostAbstain.click()
      }

      // Reveal tally → wolf eliminated → POST_VOTE check sees wolves=0 → villager_win.
      await hostPage.getByTestId('voting-reveal').click()

      // Assert game-over state via the API (authoritative) AND the result URL.
      await hostPage.waitForURL(/\/result\//, { timeout: 30_000 })
      const finalState = await hostPage.evaluate(async (id: string) => {
        const token = localStorage.getItem('jwt')
        const res = await fetch(`/api/game/${id}/state`, {
          headers: { Authorization: `Bearer ${token}` },
        })
        return res.ok ? res.json() : null
      }, localCtx.gameId)
      expect(finalState?.phase, 'phase=GAME_OVER expected').toBe('GAME_OVER')
      expect(finalState?.winner, 'winner=VILLAGER expected').toBe('VILLAGER')
      await captureSnapshot(localCtx.pages, testInfo, 'row2-villager-win')
    } finally {
      await localCtx.cleanup()
    }
  })

  // ── Row 3: wolf-win at parity when D1 vote eliminates a villager ──
  test('row 3 — wolf-win at parity after D1 mis-vote', async ({ browser }, testInfo) => {
    testInfo.setTimeout(240_000)
    const localCtx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
    })
    try {
      const hostPage = localCtx.hostPage
      const wolves = localCtx.roleMap.WEREWOLF ?? []
      const seer = (localCtx.roleMap.SEER ?? [])[0]
      const witch = (localCtx.roleMap.WITCH ?? [])[0]
      const villagers = localCtx.roleMap.VILLAGER ?? []
      expect(wolves.length, 'kit must have 2 wolves').toBe(2)
      expect(seer, 'kit must have 1 seer').toBeDefined()
      expect(witch, 'kit must have 1 witch').toBeDefined()
      expect(villagers.length, 'kit must have 2 villagers').toBe(2)
      // eslint-disable-next-line no-console
      console.warn(
        `[row 3] hostRole=${localCtx.hostRole} villagers=${villagers.map((b) => b.nick).join(',')}`,
      )

      await hostPage.getByTestId('start-night').click()
      await waitForPhase(hostPage, localCtx.gameId, 'NIGHT', 15_000)

      // ── N1 ── wolves kill villager-1, witch declines (villager-1 dies).
      // Assert each sub-phase gate so a wrong-sub-phase doesn't silently
      // fire act() with a "Not in <X> sub-phase" rejection in CI logs.
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WEREWOLF_PICK', 15_000),
        'expected NIGHT/WEREWOLF_PICK before firing WOLF_KILL',
      ).toBe(true)
      act('WOLF_KILL', actName(wolves[0]), {
        target: String(villagers[0].seat),
        room: localCtx.roomCode,
      })

      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_PICK', 15_000),
        'expected NIGHT/SEER_PICK before firing SEER_CHECK',
      ).toBe(true)
      act('SEER_CHECK', actName(seer), { target: String(wolves[0].seat), room: localCtx.roomCode })
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_RESULT', 10_000),
        'expected NIGHT/SEER_RESULT before firing SEER_CONFIRM',
      ).toBe(true)
      act('SEER_CONFIRM', actName(seer), { room: localCtx.roomCode })

      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WITCH_ACT', 15_000),
        'expected NIGHT/WITCH_ACT before firing WITCH_ACT',
      ).toBe(true)
      act('WITCH_ACT', actName(witch), {
        room: localCtx.roomCode,
        payload: '{"useAntidote":false}',
      })

      // After N1: 2 wolves + 3 humans (host + seer + witch + villagers[1] minus villagers[0]).
      await waitForPhase(hostPage, localCtx.gameId, 'DAY_DISCUSSION', 20_000)

      // ── D1 ── vote villagers[1] (NOT a wolf). After D1: 2 wolves + 2 humans → parity.
      await hostPage.getByTestId('day-reveal-result').click()
      await hostPage.getByTestId('day-start-vote').click()
      await waitForVotingSubPhase(hostPage, localCtx.gameId, 'VOTING', 10_000)

      // Per-bot fan-out filtering dead bots (villagers[0] died at N1).
      const unvoted2 = await readUnvotedAlivePlayerIds(hostPage, localCtx.gameId)
      for (const bot of localCtx.allBots) {
        if (bot.nick === 'Host') continue
        if (!unvoted2.has(bot.userId)) continue
        act('SUBMIT_VOTE', bot.nick, { target: String(villagers[1].seat), room: localCtx.roomCode })
      }
      const hostAbstain = hostPage.locator('.skip-btn').first()
      if (await hostAbstain.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await hostAbstain.click()
      }
      await hostPage.getByTestId('voting-reveal').click()

      await hostPage.waitForURL(/\/result\//, { timeout: 30_000 })
      const finalState = await hostPage.evaluate(async (id: string) => {
        const token = localStorage.getItem('jwt')
        const res = await fetch(`/api/game/${id}/state`, {
          headers: { Authorization: `Bearer ${token}` },
        })
        return res.ok ? res.json() : null
      }, localCtx.gameId)
      expect(finalState?.phase, 'phase=GAME_OVER expected').toBe('GAME_OVER')
      expect(finalState?.winner, 'winner=WEREWOLF expected (parity)').toBe('WEREWOLF')
      await captureSnapshot(localCtx.pages, testInfo, 'row3-wolf-win')
    } finally {
      await localCtx.cleanup()
    }
  })

  // ── Row 4: HUNTER_SHOOT subPhase fires when hunter is voted out at D1 ──
  test('row 4 — HUNTER_SHOOT subPhase when D1 votes the hunter', async ({ browser }, testInfo) => {
    testInfo.setTimeout(240_000)
    const localCtx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'HUNTER', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'HUNTER'] as RoleName[],
    })
    try {
      const hostPage = localCtx.hostPage
      // Don't filter host out: act.sh handles PLAYER='HOST' via host token.
      const wolves = localCtx.roleMap.WEREWOLF ?? []
      const seer = (localCtx.roleMap.SEER ?? [])[0]
      const witch = (localCtx.roleMap.WITCH ?? [])[0]
      const hunter = (localCtx.roleMap.HUNTER ?? [])[0]
      const villagers = localCtx.roleMap.VILLAGER ?? []
      expect(wolves.length, 'kit must have wolves').toBeGreaterThan(0)
      expect(seer, 'kit must have a seer').toBeDefined()
      expect(witch, 'kit must have a witch').toBeDefined()
      expect(hunter, 'kit must have a hunter').toBeDefined()
      expect(villagers.length, 'kit must have a villager for the wolf to kill').toBeGreaterThan(0)
      // eslint-disable-next-line no-console
      console.warn(
        `[row 4] hostRole=${localCtx.hostRole} hunter=${hunter.nick}(seat=${hunter.seat})`,
      )

      await hostPage.getByTestId('start-night').click()
      await waitForPhase(hostPage, localCtx.gameId, 'NIGHT', 15_000)

      // ── N1 ── wolves kill a villager. Witch saves them (no death) so D1
      // alive count is full and the hunter-vote elimination is the only D1
      // event. Assert each sub-phase gate so a wrong-sub-phase doesn't
      // silently fire act() with a "Not in <X> sub-phase" rejection.
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WEREWOLF_PICK', 15_000),
        'expected NIGHT/WEREWOLF_PICK before firing WOLF_KILL',
      ).toBe(true)
      act('WOLF_KILL', actName(wolves[0]), {
        target: String(villagers[0].seat),
        room: localCtx.roomCode,
      })

      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_PICK', 15_000),
        'expected NIGHT/SEER_PICK before firing SEER_CHECK',
      ).toBe(true)
      act('SEER_CHECK', actName(seer), { target: String(wolves[0].seat), room: localCtx.roomCode })
      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'SEER_RESULT', 10_000),
        'expected NIGHT/SEER_RESULT before firing SEER_CONFIRM',
      ).toBe(true)
      act('SEER_CONFIRM', actName(seer), { room: localCtx.roomCode })

      expect(
        await waitForNightSubPhase(hostPage, localCtx.gameId, 'WITCH_ACT', 15_000),
        'expected NIGHT/WITCH_ACT before firing WITCH_ACT',
      ).toBe(true)
      act('WITCH_ACT', actName(witch), {
        room: localCtx.roomCode,
        payload: '{"useAntidote":true}',
      })

      await waitForPhase(hostPage, localCtx.gameId, 'DAY_DISCUSSION', 20_000)

      // ── D1 ── vote the hunter.
      await hostPage.getByTestId('day-reveal-result').click()
      await hostPage.getByTestId('day-start-vote').click()
      await waitForVotingSubPhase(hostPage, localCtx.gameId, 'VOTING', 10_000)

      // Per-bot fan-out — witch saved villagers[0] at N1 so all bots alive,
      // but iterate explicitly to match the pattern (and to surface any
      // dead bot via a positive readUnvotedAlivePlayerIds gate).
      const unvotedRow4 = await readUnvotedAlivePlayerIds(hostPage, localCtx.gameId)
      for (const bot of localCtx.allBots) {
        if (bot.nick === 'Host') continue
        if (!unvotedRow4.has(bot.userId)) continue
        act('SUBMIT_VOTE', bot.nick, { target: String(hunter.seat), room: localCtx.roomCode })
      }
      const hostAbstain = hostPage.locator('.skip-btn').first()
      if (await hostAbstain.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await hostAbstain.click()
      }
      await hostPage.getByTestId('voting-reveal').click()

      // Backend transitions to HUNTER_SHOOT (not VOTE_RESULT or GAME_OVER yet).
      const reachedHunterShoot = await waitForVotingSubPhase(
        hostPage,
        localCtx.gameId,
        'HUNTER_SHOOT',
        15_000,
      )
      expect(reachedHunterShoot, 'expected DAY_VOTING/HUNTER_SHOOT after voting hunter out').toBe(
        true,
      )
      await captureSnapshot(localCtx.pages, testInfo, 'row4-hunter-shoot-entered')

      // Drive the hunter's pass (no shoot) so the game can advance — the
      // important contract here is the SUB-PHASE TRANSITION, not which seat
      // hunter targets.
      act('HUNTER_PASS', actName(hunter), { room: localCtx.roomCode })

      // Sub-phase advances out of HUNTER_SHOOT (to VOTE_RESULT, NIGHT, or
      // GAME_OVER depending on remaining state). Either is acceptable —
      // we're not asserting a specific downstream state, only that the
      // transition out of HUNTER_SHOOT happened.
      await waitForCondition(
        async () => {
          const state = await hostPage.evaluate(async (id: string) => {
            const token = localStorage.getItem('jwt')
            const res = await fetch(`/api/game/${id}/state`, {
              headers: { Authorization: `Bearer ${token}` },
            })
            return res.ok ? res.json() : null
          }, localCtx.gameId)
          return state?.votingPhase?.subPhase !== 'HUNTER_SHOOT'
        },
        'sub-phase to leave HUNTER_SHOOT after HUNTER_PASS',
        10_000,
      )
      await captureSnapshot(localCtx.pages, testInfo, 'row4-hunter-shoot-resolved')
    } finally {
      await localCtx.cleanup()
    }
  })
})
