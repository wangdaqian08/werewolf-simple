import { expect, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { type RoleName } from './helpers/shell-runner'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { driveMinimalNight1ViaDom } from './helpers/night-driver'
import { waitForPhase } from './helpers/state-polling'
import { cleanupRoomData } from './helpers/test-support'

test.describe('同守同救 — guard and witch save the same wolf target', () => {
    test.setTimeout(process.env.CI ? 360_000 : 180_000)

    let ctx: GameContext

    test.beforeAll(async ({ browser }, testInfo) => {
        testInfo.setTimeout(process.env.CI ? 240_000 : 120_000)
        ctx = await setupGame(browser, {
            totalPlayers: 8,
            hasSheriff: false,
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

    test('the guarded and antidote-saved N1 victim dies at the reveal', async ({}, testInfo) => {
        const hostPage = ctx.hostPage
        const victim = (ctx.roleMap.VILLAGER ?? []).find((b) => b.nick !== 'Host')
        expect(victim, 'need a non-host villager as the wolf target').toBeDefined()

        await driveMinimalNight1ViaDom(ctx, {
            wolfTargetSeat: victim!.seat,
            guardTargetSeat: victim!.seat,
            witch: { mode: 'save' },
        })
        expect(await waitForPhase(hostPage, ctx.gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)

        const revealBtn = hostPage.getByTestId('day-reveal-result')
        await revealBtn.waitFor({ state: 'visible', timeout: 30_000 })
        await revealBtn.click()
        const killBanner = hostPage.getByTestId('day-banner-kill')
        const peacefulBanner = hostPage.getByTestId('day-banner-peaceful')
        await expect(killBanner.or(peacefulBanner)).toBeVisible({ timeout: 10_000 })
        await captureSnapshot(ctx.pages, testInfo, 'gwst-01-revealed')

        await expect(peacefulBanner).toHaveCount(0)
        await expect(killBanner).toBeVisible()
        await expect(hostPage.getByTestId(`day-killed-seat-${victim!.seat}`)).toBeVisible()

        await ctx.assertNoBackendErrors(testInfo)
    })
})
