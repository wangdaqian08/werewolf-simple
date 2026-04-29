/**
 * Real-backend E2E: dead-role night audio.
 *
 * When a special role is eliminated in a prior round, the backend MUST still
 * broadcast an AudioSequence containing that role's open_eyes + close_eyes
 * mp3 files at the appropriate point in the night-time role-call. Without
 * those files, wolves listening would be able to infer "this role is dead"
 * from the absence of audio — leaking information about who the village
 * voted out / who the wolves killed earlier.
 *
 * The unit-test layer (`AudioServiceTest`) verifies the function that emits
 * those files (`audioService.calculateDeadRoleAudioSequence`) for a matrix
 * of dead-role combinations. This spec verifies the END-TO-END behaviour:
 * a real game with a real eliminated role really emits the dead-role audio
 * at the next night.
 *
 * Two scenarios:
 *   1. ONE special role dead: wolves kill the seer at N1 → at N2 the role
 *      loop walks past SEER_PICK with the seer dead, and the backend emits
 *      seer_open_eyes.mp3 + seer_close_eyes.mp3.
 *
 *   2. TWO special roles dead: wolves kill the seer at N1, witch poisons
 *      the guard at N1 (no antidote — backend forbids combined potions).
 *      At N2, BOTH dead roles' audio fires before the witch's open_eyes.
 *
 * The 3-special-roles-dead scenario is covered exhaustively by
 * AudioServiceTest's unit test (`DEAD ROLE - all three special roles dead
 * plays full audio chain`) — reaching that state in E2E is awkward because
 * eliminating 3 specials usually triggers a wolf-win check before N3.
 *
 * Audio assertion strategy: the actual production code path is in
 * `NightOrchestrator.nightRoleLoop` (NightOrchestrator.kt:471). For each
 * active role (alive OR dead) the role-loop:
 *   1. Broadcasts `RoleRegistry.getOpenEyesAudio(role)` via `broadcastAudio`.
 *   2. If alive: awaits player action. If dead: delays `deadRoleDelayMs`.
 *   3. Broadcasts `RoleRegistry.getCloseEyesAudio(role)` via `broadcastAudio`.
 *
 * The dead-role open/close audio fires unconditionally; the only difference
 * for dead roles is the delay-vs-await branch. The role-loop logs
 * `[nightRoleLoop] game=N: role=ROLE alive=BOOL` (NightOrchestrator.kt:498)
 * before each role's audio. We assert that line for the dead role at N2 —
 * that proves the role-loop entered the dead-role path, which means open +
 * close audio was broadcast for that role.
 *
 * NOTE: `audioService.calculateDeadRoleAudioSequence(...)` exists as a
 * standalone function (and AudioServiceTest covers it heavily) but is NOT
 * invoked by the production NightOrchestrator. Don't grep the backend log
 * for "calculated dead-role audio sequence" — it never fires.
 */
import { expect, test } from '@playwright/test'
import { setupGame } from './helpers/multi-browser'
import { act, actName, type RoleName } from './helpers/shell-runner'
import {
  readHostUserId,
  readUnvotedAlivePlayerIds,
  waitForNightSubPhase,
  waitForPhase,
  waitForVoteRegistered,
  waitForVotingSubPhase,
} from './helpers/state-polling'
import {
  attachBackendLogOnFailure,
  readBackendLogLineCount,
  readBackendLogSince,
} from './helpers/backend-log'

test.describe('Dead-role night audio — eliminated specials still play role-call', () => {
  test.setTimeout(180_000)

  // Attach backend log tail on failure so phase/sub-phase progression is
  // visible in the artifact — without it, only screenshots ship and the
  // failing sub-phase has to be inferred from a single frame.
  test.afterEach(async ({}, testInfo) => {
    if (testInfo.status === 'failed') {
      await attachBackendLogOnFailure(testInfo)
    }
  })

  // ── Test 1: ONE special role dead (seer killed at N1) ─────────────────

  test('1. seer killed at N1 → N2 dead-role audio includes seer_open + seer_close', async ({
    browser,
  }, testInfo) => {
    testInfo.setTimeout(180_000)

    // 6p kit: 2 wolves + 1 seer + 1 witch + 1 guard + 1 villager.
    // GameService.kt:316 → 2 wolves at totalPlayers=6.
    const ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
    })

    try {
      const hostPage = ctx.hostPage

      // ── N1: wolves kill the seer ────────────────────────────────────────
      await hostPage.getByTestId('start-night').click()
      expect(await waitForPhase(hostPage, ctx.gameId, 'NIGHT', 15_000)).toBe(true)

      const wolves = ctx.roleMap.WEREWOLF ?? []
      const seer = (ctx.roleMap.SEER ?? [])[0]
      const witch = (ctx.roleMap.WITCH ?? [])[0]
      const guard = (ctx.roleMap.GUARD ?? [])[0]
      expect(wolves.length, 'kit must have 2 wolves').toBe(2)
      expect(seer, 'kit must have a seer').toBeDefined()
      expect(witch, 'kit must have a witch').toBeDefined()
      expect(guard, 'kit must have a guard').toBeDefined()

      // Wolf kills SEER. With seer killed, the role-loop will skip SEER_PICK
      // and SEER_RESULT at N2; that's where the dead-role audio fires.
      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'WEREWOLF_PICK', 15_000),
        'expected WEREWOLF_PICK at N1',
      ).toBe(true)
      act('WOLF_KILL', actName(wolves[0]), {
        target: String(seer.seat),
        room: ctx.roomCode,
      })

      // Seer's own check fires before the kill resolves — the seer is still
      // technically alive during their pick sub-phase. Drive their action so
      // the night progresses.
      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'SEER_PICK', 15_000),
        'expected SEER_PICK at N1 (seer still alive at this point)',
      ).toBe(true)
      act('SEER_CHECK', actName(seer), {
        target: String(wolves[0].seat),
        room: ctx.roomCode,
      })
      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'SEER_RESULT', 10_000),
      ).toBe(true)
      act('SEER_CONFIRM', actName(seer), { room: ctx.roomCode })

      // Witch declines save (otherwise seer doesn't actually die at night
      // resolve and the dead-seer audio at N2 won't fire).
      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'WITCH_ACT', 15_000),
      ).toBe(true)
      act('WITCH_ACT', actName(witch), {
        room: ctx.roomCode,
        payload: '{"useAntidote":false}',
      })

      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'GUARD_PICK', 15_000),
      ).toBe(true)
      act('GUARD_SKIP', actName(guard), { room: ctx.roomCode })

      // Night resolves; seer is dead.
      expect(
        await waitForPhase(hostPage, ctx.gameId, 'DAY_DISCUSSION', 30_000),
      ).toBe(true)

      // ── D1: vote out a wolf to keep witch + guard alive for N2. ─────────
      await hostPage.getByTestId('day-reveal-result').click()
      await hostPage.getByTestId('day-start-vote').click()
      expect(
        await waitForVotingSubPhase(hostPage, ctx.gameId, 'VOTING', 10_000),
      ).toBe(true)

      // Host abstain — only if host is alive. When host happens to roll
      // SEER, the wolf-kill at N1 made host dead and the .skip-btn doesn't
      // render. Bots' fan-out alone supplies enough votes for VOTE_RESULT.
      const hostId = await readHostUserId(hostPage)
      const abstainBtn = hostPage.locator('.skip-btn').first()
      const abstainVisible = await abstainBtn
        .waitFor({ state: 'visible', timeout: 5_000 })
        .then(() => true)
        .catch(() => false)
      if (abstainVisible) {
        await abstainBtn.click()
        if (hostId) await waitForVoteRegistered(hostPage, ctx.gameId, hostId, 5_000)
      }

      const unvoted = await readUnvotedAlivePlayerIds(hostPage, ctx.gameId)
      for (const bot of ctx.allBots) {
        if (bot.nick === 'Host' || bot.userId === hostId) continue
        if (!unvoted.has(bot.userId)) continue
        act('SUBMIT_VOTE', bot.nick, {
          target: String(wolves[0].seat),
          room: ctx.roomCode,
        })
      }

      const revealTallyBtn = hostPage.getByTestId('voting-reveal')
      await revealTallyBtn.waitFor({ state: 'visible', timeout: 10_000 })
      await revealTallyBtn.click()
      expect(
        await waitForVotingSubPhase(hostPage, ctx.gameId, 'VOTE_RESULT', 10_000),
      ).toBe(true)

      // ── Mark backend log position before N2 starts ──────────────────────
      // Anything after this point is N2 / D1-aftermath; the dead-role audio
      // emission happens during N2's role-loop walk-past of SEER_PICK.
      const logLineBeforeN2 = readBackendLogLineCount()

      // Continue → N2 starts
      const continueBtn = hostPage.getByTestId('voting-continue')
      const continueVisible = await continueBtn
        .waitFor({ state: 'visible', timeout: 5_000 })
        .then(() => true)
        .catch(() => false)
      if (continueVisible) await continueBtn.click()

      // Wait for N2 entry
      expect(
        await waitForPhase(hostPage, ctx.gameId, 'NIGHT', 30_000),
        'expected N2 (NIGHT day=2) after voting-continue',
      ).toBe(true)

      // Drive every alive role at N2 so the role-loop progresses through
      // every special role; that's where the dead-role audio fires.
      // wolves[0] was voted out at D1, so use wolves[1] for N2 wolf-kill.
      // Target = witch (always alive at N2 in this scenario).
      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'WEREWOLF_PICK', 15_000),
      ).toBe(true)
      act('WOLF_KILL', actName(wolves[1]), {
        target: String(witch.seat),
        room: ctx.roomCode,
      })

      // Witch decline (alive). Without this, role-loop hangs waiting for
      // her action and never reaches the GUARD step.
      if (await waitForNightSubPhase(hostPage, ctx.gameId, 'WITCH_ACT', 15_000)) {
        act('WITCH_ACT', actName(witch), {
          room: ctx.roomCode,
          payload: '{"useAntidote":false}',
        })
      }

      // Guard skip (alive in test 1).
      if (await waitForNightSubPhase(hostPage, ctx.gameId, 'GUARD_PICK', 15_000)) {
        act('GUARD_SKIP', actName(guard), { room: ctx.roomCode })
      }

      // The night-role-loop iterates each active role and broadcasts that
      // role's open_eyes + close_eyes audio regardless of alive status.
      // For a dead seer at N2, the loop logs `role=SEER alive=false` —
      // proving the dead-role path was taken (and thus seer_open_eyes +
      // seer_close_eyes were broadcast via NightOrchestrator.broadcastAudio).
      let logTail: string[] = []
      const deadline = Date.now() + 30_000
      while (Date.now() < deadline) {
        logTail = readBackendLogSince(logLineBeforeN2)
        if (
          logTail.some((line) =>
            /\[nightRoleLoop\].+game=\d+:.+role=SEER alive=false/.test(line),
          )
        ) {
          break
        }
        await hostPage.waitForTimeout(500)
      }

      const deadSeerLine = logTail.find((line) =>
        /\[nightRoleLoop\].+game=\d+:.+role=SEER alive=false/.test(line),
      )
      expect(
        deadSeerLine,
        `backend should log "role=SEER alive=false" at N2;\n` +
          `last 30 lines:\n${logTail.slice(-30).join('\n')}`,
      ).toBeDefined()
    } finally {
      await ctx.cleanup()
    }
  })

  // ── Test 2: TWO special roles dead (seer + guard at N1) ─────────────────

  test('2. seer + guard dead at N1 → N2 dead-role audio includes both chains', async ({
    browser,
  }, testInfo) => {
    testInfo.setTimeout(180_000)

    // 9p kit (3 wolves + seer + witch + guard + 3 villagers). 6p doesn't
    // work for this scenario: with 2 deaths at N1 (seer wolf-kill + guard
    // poison), the post-night wolf-win check fires (2W ≥ 2H at parity)
    // and the game ends before D1 → no N2 to assert. 9p has enough humans
    // (5 or 4 alive after N1) for the game to continue past D1 into N2.
    const ctx = await setupGame(browser, {
      totalPlayers: 9,
      hasSheriff: false,
      roles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD', 'VILLAGER'] as RoleName[],
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'GUARD'] as RoleName[],
    })

    try {
      const hostPage = ctx.hostPage

      // ── N1: wolves kill seer; witch poisons guard ───────────────────────
      // Witch CAN'T use antidote + poison in the same WITCH_ACT (backend rule:
      // "Cannot use antidote and poison on the same night" — verified in
      // PR #75). So we use poison only; seer dies from wolf kill, guard dies
      // from witch poison. Two specials dead by end of N1.
      await hostPage.getByTestId('start-night').click()
      expect(await waitForPhase(hostPage, ctx.gameId, 'NIGHT', 15_000)).toBe(true)

      const wolves = ctx.roleMap.WEREWOLF ?? []
      const seer = (ctx.roleMap.SEER ?? [])[0]
      const witch = (ctx.roleMap.WITCH ?? [])[0]
      const guard = (ctx.roleMap.GUARD ?? [])[0]
      // 9p kit produces 3 wolves (GameService.kt:316: playerCount/3 at 9
      // players = 3 wolves).
      expect(wolves.length, '9p kit must have 3 wolves').toBe(3)
      expect(seer).toBeDefined()
      expect(witch).toBeDefined()
      expect(guard).toBeDefined()

      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'WEREWOLF_PICK', 15_000),
      ).toBe(true)
      act('WOLF_KILL', actName(wolves[0]), {
        target: String(seer.seat),
        room: ctx.roomCode,
      })

      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'SEER_PICK', 15_000),
      ).toBe(true)
      act('SEER_CHECK', actName(seer), {
        target: String(wolves[0].seat),
        room: ctx.roomCode,
      })
      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'SEER_RESULT', 10_000),
      ).toBe(true)
      act('SEER_CONFIRM', actName(seer), { room: ctx.roomCode })

      // Witch poisons guard (no antidote — backend forbids combined potions).
      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'WITCH_ACT', 15_000),
      ).toBe(true)
      act('WITCH_ACT', actName(witch), {
        room: ctx.roomCode,
        payload: JSON.stringify({
          useAntidote: false,
          poisonTargetUserId: guard.userId,
        }),
      })

      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'GUARD_PICK', 15_000),
      ).toBe(true)
      act('GUARD_SKIP', actName(guard), { room: ctx.roomCode })

      // Night resolves: seer (wolf kill) + guard (witch poison) both dead.
      expect(
        await waitForPhase(hostPage, ctx.gameId, 'DAY_DISCUSSION', 30_000),
      ).toBe(true)

      // ── D1: vote out a wolf so game continues to N2 ─────────────────────
      // After N1: 2W + Wi + V + host alive (assuming host is not seer/guard).
      // After D1 vote-out of a wolf: 1W + Wi + V + host = 1W vs 3H. Game
      // continues to N2.
      await hostPage.getByTestId('day-reveal-result').click()
      await hostPage.getByTestId('day-start-vote').click()
      expect(
        await waitForVotingSubPhase(hostPage, ctx.gameId, 'VOTING', 10_000),
      ).toBe(true)

      // Host abstain — only if host is alive. When host happens to roll
      // SEER (killed at N1 by the wolf) or GUARD (poisoned at N1 by the
      // witch), host is dead at D1 and the .skip-btn doesn't render
      // (VotingPhase only shows the abstain control to alive players).
      // In that case the bots' fan-out alone produces enough votes for
      // VOTE_RESULT to fire.
      const hostId = await readHostUserId(hostPage)
      const abstainBtn = hostPage.locator('.skip-btn').first()
      const abstainVisible = await abstainBtn
        .waitFor({ state: 'visible', timeout: 5_000 })
        .then(() => true)
        .catch(() => false)
      if (abstainVisible) {
        await abstainBtn.click()
        if (hostId) await waitForVoteRegistered(hostPage, ctx.gameId, hostId, 5_000)
      }

      const unvoted = await readUnvotedAlivePlayerIds(hostPage, ctx.gameId)
      for (const bot of ctx.allBots) {
        if (bot.nick === 'Host' || bot.userId === hostId) continue
        if (!unvoted.has(bot.userId)) continue
        act('SUBMIT_VOTE', bot.nick, {
          target: String(wolves[0].seat),
          room: ctx.roomCode,
        })
      }

      const revealTallyBtn = hostPage.getByTestId('voting-reveal')
      await revealTallyBtn.waitFor({ state: 'visible', timeout: 10_000 })
      await revealTallyBtn.click()
      expect(
        await waitForVotingSubPhase(hostPage, ctx.gameId, 'VOTE_RESULT', 10_000),
      ).toBe(true)

      const logLineBeforeN2 = readBackendLogLineCount()
      const continueBtn = hostPage.getByTestId('voting-continue')
      const continueVisible = await continueBtn
        .waitFor({ state: 'visible', timeout: 5_000 })
        .then(() => true)
        .catch(() => false)
      if (continueVisible) await continueBtn.click()

      // ── N2: wolf kills, then role-loop walks past dead seer + guard ─────
      // With the witch in the middle, the audio sequence at N2 is:
      //   wolf_open + wolf_close          (alive wolf actor)
      //   seer_open + seer_close (dead)   (dead-role audio for seer)
      //   witch_open + witch_close        (alive witch actor)
      //   guard_open + guard_close (dead) (dead-role audio for guard)
      // The seer's dead-role audio fires when the role-loop transitions
      // past SEER_PICK; the guard's fires after WITCH_ACT.
      expect(
        await waitForPhase(hostPage, ctx.gameId, 'NIGHT', 30_000),
        'expected N2 to start; if game ended at D1 the host-role roll left ' +
          'too few alive humans for the game to continue',
      ).toBe(true)

      expect(
        await waitForNightSubPhase(hostPage, ctx.gameId, 'WEREWOLF_PICK', 15_000),
      ).toBe(true)
      // Target = the witch. Witch is alive at N2 entry; her death is
      // resolved at end-of-night (after the role-loop completes), so her
      // WITCH_ACT can still be driven below.
      act('WOLF_KILL', actName(wolves[1]), {
        target: String(witch.seat),
        room: ctx.roomCode,
      })

      // Witch alive — drive her decline so the loop reaches GUARD_PICK
      // (where the dead-guard audio fires). Without this the loop hangs
      // waiting for witch's action.
      if (await waitForNightSubPhase(hostPage, ctx.gameId, 'WITCH_ACT', 15_000)) {
        act('WITCH_ACT', actName(witch), {
          room: ctx.roomCode,
          payload: '{"useAntidote":false}',
        })
      }

      // Both dead specials must show up as alive=false in the role-loop.
      // The loop iterates every active role at N2; each dead role logs
      // `role=X alive=false` before broadcastAudio fires its open+close
      // eyes mp3.
      let logTail: string[] = []
      const deadline = Date.now() + 45_000
      let deadSeer = false
      let deadGuard = false
      while (Date.now() < deadline) {
        logTail = readBackendLogSince(logLineBeforeN2)
        deadSeer = logTail.some((line) =>
          /\[nightRoleLoop\].+game=\d+:.+role=SEER alive=false/.test(line),
        )
        deadGuard = logTail.some((line) =>
          /\[nightRoleLoop\].+game=\d+:.+role=GUARD alive=false/.test(line),
        )
        if (deadSeer && deadGuard) break
        await hostPage.waitForTimeout(500)
      }

      expect(
        deadSeer,
        `backend should log "role=SEER alive=false" at N2;\n` +
          `last 30 lines:\n${logTail.slice(-30).join('\n')}`,
      ).toBe(true)
      expect(
        deadGuard,
        `backend should log "role=GUARD alive=false" at N2;\n` +
          `last 30 lines:\n${logTail.slice(-30).join('\n')}`,
      ).toBe(true)
    } finally {
      await ctx.cleanup()
    }
  })
})
