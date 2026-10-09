/**
 * Real-backend E2E: White Wolf King (白狼王).
 *
 *  1. At night the king's browser makes the wolf kill (it acts with the wolves).
 *  2. By day the king opens the Action chip → 自爆 → picks a player to take → confirms.
 *  3. Every viewer's death banner names the king and the taken player; the host
 *     sees 进入夜晚 (no vote); the 游戏记录 drawer shows the take.
 *  4. The host ends the day and the game reaches the next night.
 *
 *  Same conventions as self-destruct-flow.spec.ts: testid locators, condition
 *  polling, assertions on observable DOM.
 */
import { expect, test } from '@playwright/test'
import { type GameContext, setupGame } from './helpers/multi-browser'
import { type RoleName } from './helpers/shell-runner'
import { verifyAllBrowsersPhase } from './helpers/assertions'
import { attachCompositeOnFailure, captureSnapshot } from './helpers/composite-screenshot'
import { driveMinimalNight1ViaDom } from './helpers/night-driver'
import {
    waitForDaySkipVoting,
    waitForDaySubPhase,
    waitForNightSubPhase,
} from './helpers/state-polling'

let ctx: GameContext

test.describe('White Wolf King (白狼王) — real-backend flow', () => {
    test.setTimeout(180_000)

    test.beforeAll(async ({ browser }, testInfo) => {
        testInfo.setTimeout(120_000)
        // The largest board (15 = 5 wolves incl. the king / 5 gods / 5 villagers),
        // so the maximum player count is played end to end.
        ctx = await setupGame(browser, {
            totalPlayers: 15,
            hasSheriff: false,
            roles: [
                'WEREWOLF',
                'VILLAGER',
                'WHITE_WOLF_KING',
                'SEER',
                'WITCH',
                'HUNTER',
                'GUARD',
                'IDIOT',
            ] as RoleName[],
            browserRoles: [
                'WEREWOLF',
                'WHITE_WOLF_KING',
                'SEER',
                'WITCH',
                'GUARD',
                'VILLAGER',
            ] as RoleName[],
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

    test('king hunts with the wolves, then self-destructs and takes a player', async ({}, testInfo) => {
        const kingPage = ctx.pages.get('WHITE_WOLF_KING')
        expect(kingPage, 'king browser must exist').toBeTruthy()
        const kp = kingPage!

        const villagers = (ctx.roleMap.VILLAGER ?? []).filter((b) => b.nick !== 'Host')
        expect(villagers.length, 'need two non-host villagers').toBeGreaterThanOrEqual(2)
        const [nightVictim, taken] = villagers

        // ── Night 1: the KING makes the wolf kill through its own browser ────
        const kingAsWolf = { ...ctx, pages: new Map([...ctx.pages, ['WEREWOLF', kp]]) }
        await driveMinimalNight1ViaDom(kingAsWolf, { wolfTargetSeat: nightVictim!.seat })
        await verifyAllBrowsersPhase(ctx.pages, 'DAY', 20_000)

        const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
        await expect(revealBtn).toBeVisible({ timeout: 10_000 })
        await revealBtn.click()
        expect(
            await waitForDaySubPhase(ctx.hostPage, ctx.gameId, 'RESULT_REVEALED', 15_000),
            'host reveal landed at DAY_DISCUSSION/RESULT_REVEALED',
        ).toBe(true)

        // ── Day: king self-destructs and takes a villager ────────────────────
        await kp.getByTestId('action-menu-btn').click()
        await kp.getByTestId('action-menu-self-destruct').click()
        const takeBtn = kp.getByTestId(`action-menu-take-${taken!.userId}`)
        await expect(takeBtn).toBeVisible({ timeout: 5_000 })
        await takeBtn.click()
        await kp.getByTestId('action-menu-confirm').click()

        expect(
            await waitForDaySkipVoting(ctx.hostPage, ctx.gameId, 10_000),
            'backend daySkipVoting=true after the king self-destructs',
        ).toBe(true)
        await expect(ctx.hostPage.getByTestId('day-enter-night')).toBeVisible({ timeout: 10_000 })

        const banner = ctx.hostPage.getByTestId('day-banner-self-destruct')
        await expect(banner).toContainText('自爆', { timeout: 10_000 })
        await expect(banner).toContainText('带走了')
        await expect(
            ctx.hostPage.getByTestId(`day-self-destruct-taken-seat-${taken!.seat}`),
        ).toContainText(taken!.nick)

        await ctx.hostPage.getByTestId('log-fab').click()
        const drawer = ctx.hostPage.locator('.action-log-drawer')
        await expect(drawer).toBeVisible({ timeout: 5_000 })
        await expect
            .poll(async () => (await drawer.textContent()) ?? '', { timeout: 5_000 })
            .toContain('带走')
        await captureSnapshot(ctx.pages, testInfo, 'white-wolf-king-take')
        await ctx.hostPage.getByTestId('action-log-close').click()
        await expect(drawer).toBeHidden({ timeout: 5_000 })

        // ── Host ends the day without a vote → next night ────────────────────
        await ctx.hostPage.getByTestId('day-enter-night').click()
        expect(
            await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 30_000),
            'night 2 reached after the king self-destructed',
        ).toBe(true)
    })
})
