/**
 * Real-backend E2E: Idiot role reveal and voting flow with multi-browser STOMP verification.
 *
 * Opens 6 isolated browser contexts (host + idiot + other roles)
 * and verifies that idiot reveal mechanics work correctly across all browsers.
 *
 * Key scenarios tested:
 *   - Idiot receives highest votes in first round
 *   - Idiot reveal banner appears in all browsers
 *   - 🃏 overlay appears on idiot's card
 *   - Idiot loses voting right permanently
 *   - Phase transitions correctly from VOTE_RESULT to NIGHT
 *
 * NOTE: This test suite is currently a placeholder. Full implementation requires:
 * 1. Support for custom role configuration in setupGame() 
 * 2. Explicit IDIOT role assignment in room creation
 * 3. Voting phase orchestration to trigger idiot reveal
 */
import {expect, test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act, type RoleName} from './helpers/shell-runner'
import {verifyAllBrowsersPhase,} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'

let ctx: GameContext

test.describe('Idiot flow — multi-browser STOMP verification (TODO)', () => {
  test.setTimeout(60_000) // 3 minutes for the full flow

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(30_000) // setup can take a while with shell scripts
    ctx = await setupGame(browser, {
      totalPlayers: 6,
      hasSheriff: false,
      browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
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

  // ── Test 1: Setup verification ──────────────────────────────────────────────

  test.skip('1. Setup — idiot role assigned correctly (TODO: requires custom role config)', async ({}, testInfo) => {
    // This test is skipped because setupGame() does not currently support
    // custom role configuration. To enable IDIOT role assignment, we need:
    // 1. Add 'roles' parameter to GameSetupOptions
    // 2. Pass roles to room creation API in setupGame()
    // 3. Include 'IDIOT' in the roles list
    
    // TODO: Re-enable this test when setupGame supports custom roles
    testInfo.attach('info', { body: 'TODO: setupGame needs to support custom role configuration' })
  })

  // ── Test 2: Idiot reveal in voting phase ──────────────────────────────────────

  test.skip('2. Idiot reveal — all browsers show idiot reveal banner (TODO: requires setupGame role config)', async ({}, testInfo) => {
    // This test depends on Test 1 succeeding
    testInfo.attach('info', { body: 'TODO: implement after setupGame supports custom roles' })
  })

  // ── Test 3: Phase transition after idiot reveal ──────────────────────────────

  test.skip('3. Phase transition — VOTE_RESULT to NIGHT (TODO: requires setupGame role config)', async ({}, testInfo) => {
    // This test depends on Tests 1 and 2 succeeding
    testInfo.attach('info', { body: 'TODO: implement after setupGame supports custom roles' })
  })
})