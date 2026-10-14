import { expect, type Page, test } from '@playwright/test'
import { setupGame, type GameContext } from './helpers/multi-browser'
import { actName, type BotInfo, type RoleName } from './helpers/shell-runner'
import { captureSnapshot } from './helpers/composite-screenshot'
import { completeNight, type WitchPlan, tryAct } from './helpers/flow-drivers'
import { waitForCondition, waitForDaySubPhase, waitForPhase } from './helpers/state-polling'
import { cleanupRoomData } from './helpers/test-support'
import { hostStartsNight } from './helpers/night-driver'

const ROLES = ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH'] as RoleName[]

async function readState(page: Page, gameId: string) {
    return page.evaluate(async (id: string) => {
        const res = await fetch(`/api/game/${id}/state`, {
            headers: { Authorization: `Bearer ${localStorage.getItem('jwt')}` },
        })
        return res.ok ? res.json() : null
    }, gameId)
}

async function postAction(page: Page, body: Record<string, unknown>) {
    return page.evaluate(async (b) => {
        const res = await fetch('/api/game/action', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                Authorization: `Bearer ${localStorage.getItem('jwt')}`,
            },
            body: JSON.stringify(b),
        })
        return res.ok
    }, body)
}

function wolves(ctx: GameContext): BotInfo[] {
    const w = ctx.roleMap.WEREWOLF ?? []
    expect(w.length, 'kit must have 2 wolves').toBe(2)
    return w
}

function villagers(ctx: GameContext): BotInfo[] {
    return ctx.roleMap.VILLAGER ?? []
}

async function playNightOne(ctx: GameContext, killSeat: number, witch: WitchPlan) {
    await hostStartsNight(ctx)
    await completeNight(ctx, { wolfKillSeat: killSeat, witch, seerCheckSeat: wolves(ctx)[0].seat })
}

async function waitForResultHidden(ctx: GameContext) {
    await waitForCondition(
        async () => {
            const s = await readState(ctx.hostPage, ctx.gameId)
            return s?.phase === 'DAY_DISCUSSION' && s?.subPhase === 'RESULT_HIDDEN'
        },
        'DAY_DISCUSSION/RESULT_HIDDEN',
        30_000,
    )
}

test.describe('Self-destruct rules — pending night kills, window, one per day', () => {
    test.setTimeout(process.env.CI ? 360_000 : 240_000)

    test('the last wolf self-destructing in the election (the other wolf poisoned last night) ends the game', async ({
                                                                                                                         browser,
                                                                                                                     }, testInfo) => {
        const ctx = await setupGame(browser, {
            totalPlayers: 6,
            hasSheriff: true,
            roles: ROLES,
            browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
        })
        try {
            const [wolfA, wolfB] = wolves(ctx)
            const [v1] = villagers(ctx)

            await playNightOne(ctx, v1.seat, { mode: 'poison', targetSeat: wolfA.seat })
            expect(await waitForPhase(ctx.hostPage, ctx.gameId, 'SHERIFF_ELECTION', 30_000)).toBe(true)

            expect(tryAct('WOLF_SELF_DESTRUCT', actName(wolfB), { room: ctx.roomCode })).toBe(true)
            await waitForCondition(
                async () => (await readState(ctx.hostPage, ctx.gameId))?.phase === 'GAME_OVER',
                'GAME_OVER',
                15_000,
            ).catch(() => {})
            const state = await readState(ctx.hostPage, ctx.gameId)
            await captureSnapshot(ctx.pages, testInfo, 'self-destruct-pending-kill-game-over')
            expect(state?.phase, 'wolf A died to last night\'s poison and wolf B self-destructed').toBe(
                'GAME_OVER',
            )
            expect(state?.winner).toBe('VILLAGER')
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('a wolf cannot self-destruct before the host reveals the night result', async ({
                                                                                            browser,
                                                                                        }, testInfo) => {
        const ctx = await setupGame(browser, {
            totalPlayers: 6,
            hasSheriff: false,
            roles: ROLES,
            browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
        })
        try {
            const [wolfA] = wolves(ctx)
            const [v1] = villagers(ctx)

            await playNightOne(ctx, v1.seat, { mode: 'pass' })
            await waitForResultHidden(ctx)
            expect(
                tryAct('WOLF_SELF_DESTRUCT', actName(wolfA), { room: ctx.roomCode }),
                'self-destruct opens only after the night result is revealed',
            ).toBe(false)

            const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
            await revealBtn.waitFor({ state: 'visible', timeout: 10_000 })
            await revealBtn.click()
            await expect(ctx.hostPage.getByTestId(`day-killed-seat-${v1.seat}`)).toBeVisible({
                timeout: 10_000,
            })
            const state = await readState(ctx.hostPage, ctx.gameId)
            await captureSnapshot(ctx.pages, testInfo, 'self-destruct-rejected-before-reveal')
            expect(
                state?.players?.find((p: { userId: string }) => p.userId === wolfA.userId)?.isAlive,
            ).toBe(true)
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('only one wolf can self-destruct per day', async ({ browser }, testInfo) => {
        const ctx = await setupGame(browser, {
            totalPlayers: 6,
            hasSheriff: false,
            roles: ROLES,
            browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
        })
        try {
            const [wolfA, wolfB] = wolves(ctx)
            const [v1] = villagers(ctx)

            await playNightOne(ctx, v1.seat, { mode: 'pass' })
            await waitForResultHidden(ctx)
            await ctx.hostPage.getByTestId('day-reveal-result').click()
            expect(
                await waitForDaySubPhase(ctx.hostPage, ctx.gameId, 'RESULT_REVEALED', 15_000),
            ).toBe(true)
            expect(tryAct('WOLF_SELF_DESTRUCT', actName(wolfA), { room: ctx.roomCode })).toBe(true)
            expect(
                tryAct('WOLF_SELF_DESTRUCT', actName(wolfB), { room: ctx.roomCode }),
                'the day already ended with the first self-destruct',
            ).toBe(false)
            const state = await readState(ctx.hostPage, ctx.gameId)
            await captureSnapshot(ctx.pages, testInfo, 'second-self-destruct-rejected')
            expect(
                state?.players?.find((p: { userId: string }) => p.userId === wolfB.userId)?.isAlive,
            ).toBe(true)
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })
})
