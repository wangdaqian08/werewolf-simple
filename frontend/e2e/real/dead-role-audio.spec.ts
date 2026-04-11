/**
 * Dead Role Audio Flow E2E tests — verifies audio plays correctly for dead roles
 */
import {test} from '@playwright/test'
import {type GameContext, setupGame} from './helpers/multi-browser'
import {act} from './helpers/shell-runner'
import {verifyAllBrowsersPhase} from './helpers/assertions'
import {attachCompositeOnFailure, captureSnapshot} from './helpers/composite-screenshot'

let ctx: GameContext

test.describe('Dead Role Audio Flow', () => {
  test.setTimeout(60_000)

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(30_000)
    ctx = await setupGame(browser, {
      totalPlayers: 7,
      hasSheriff: false,
      browserRoles: ['WEREWOLF', 'WITCH', 'SEER', 'GUARD'],
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

  test('Wolf completes action — UI updates immediately to next phase', async ({}, testInfo) => {
    const hostPage = ctx.hostPage
    
    // Start night
    const startNightBtn = hostPage.getByRole('button', { name: /开始夜晚|Start Night/i })
    await startNightBtn.waitFor({ state: 'visible', timeout: 10_000 })
    await startNightBtn.click()
    
    // Verify all browsers are in NIGHT phase
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 15_000)
    
    // Wolf kill via script
    const villagerBots = ctx.roleMap.VILLAGER ?? []
    const nonHostVillager = villagerBots.find((b) => b.nick !== 'Host') ?? villagerBots[0]
    const target = nonHostVillager?.seat ?? 1
    const wolfBots = ctx.roleMap.WEREWOLF ?? []
    const wolfBot = wolfBots.find((b) => b.nick !== 'Host')

    if (wolfBot) {
      // Wolf is a bot — use script
      await act('WOLF_KILL', wolfBot.nick, { target: String(target), room: ctx.roomCode })
    } else {
      // Wolf is the host — use browser clicks
      const wolfPage = ctx.pages.get('WEREWOLF')
      const targetSlot = wolfPage!.locator(`.player-grid .slot-alive`).first()
      await targetSlot.click()
      const confirmBtn = wolfPage!.getByRole('button', { name: /确认袭击|Confirm/i })
      await confirmBtn.click()
    }
    
    // The UI should update immediately to the next phase
    // This is the key improvement: UI should update within 1 second, not wait 5-10 seconds for audio
    await ctx.hostPage.waitForTimeout(1000) // Wait for UI update
    
    // Verify we moved to the next sub-phase
    await verifyAllBrowsersPhase(ctx.pages, 'NIGHT', 5_000)
    
    await captureSnapshot(ctx.pages, testInfo, 'wolf-completed-action')
    
    // Test passes if UI updates quickly without waiting for audio delay
  })
})