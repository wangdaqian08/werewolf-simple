import { expect, type Page, test } from '@playwright/test'
import { setupGame, type GameContext } from './helpers/multi-browser'
import { actName, type BotInfo, type RoleName } from './helpers/shell-runner'
import { captureSnapshot } from './helpers/composite-screenshot'
import { assertNonNull, completeDay, tryAct } from './helpers/flow-drivers'
import { waitForNightSubPhase, waitForPhase } from './helpers/state-polling'
import { cleanupRoomData } from './helpers/test-support'
import { hostStartsNight } from './helpers/night-driver'

const BOARD = {
    totalPlayers: 6,
    hasSheriff: false,
    roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH'] as RoleName[],
    browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
}

const GUARD_BOARD = {
    totalPlayers: 6,
    hasSheriff: false,
    roles: ['WEREWOLF', 'VILLAGER', 'WITCH', 'GUARD'] as RoleName[],
    browserRoles: ['WEREWOLF', 'WITCH', 'GUARD'] as RoleName[],
}

type ActionResult = { ok: boolean; error: string | null }

async function postAction(page: Page, body: Record<string, unknown>): Promise<ActionResult> {
    return page.evaluate(async (b) => {
        const res = await fetch('/api/game/action', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                Authorization: `Bearer ${localStorage.getItem('jwt')}`,
            },
            body: JSON.stringify(b),
        })
        const json = await res.json().catch(() => null)
        return { ok: res.ok, error: json?.error ?? null }
    }, body)
}

async function readState(page: Page, gameId: string) {
    return page.evaluate(async (id: string) => {
        const res = await fetch(`/api/game/${id}/state`, {
            headers: { Authorization: `Bearer ${localStorage.getItem('jwt')}` },
        })
        return res.ok ? res.json() : null
    }, gameId)
}

function only(ctx: GameContext, role: RoleName): BotInfo {
    const bot = (ctx.roleMap[role] ?? [])[0]
    assertNonNull(bot, `kit must have a ${role}`)
    return bot
}

function villagers(ctx: GameContext): BotInfo[] {
    return ctx.roleMap.VILLAGER ?? []
}

function page(ctx: GameContext, role: RoleName): Page {
    const p = ctx.pages.get(role)
    assertNonNull(p, `${role} browser page required`)
    return p
}

async function startNight(ctx: GameContext) {
    await hostStartsNight(ctx)
}

async function wolvesKill(ctx: GameContext, seat: number) {
    expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 25_000)).toBe(true)
    expect(
        tryAct('WOLF_KILL', actName(only(ctx, 'WEREWOLF')), {
            target: String(seat),
            room: ctx.roomCode,
        }),
    ).toBe(true)
}

async function seerActs(ctx: GameContext, checkSeat: number) {
    const seer = only(ctx, 'SEER')
    expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'SEER_PICK', 15_000)).toBe(true)
    expect(
        tryAct('SEER_CHECK', actName(seer), { target: String(checkSeat), room: ctx.roomCode }),
    ).toBe(true)
    expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'SEER_RESULT', 10_000)).toBe(true)
    expect(tryAct('SEER_CONFIRM', actName(seer), { room: ctx.roomCode })).toBe(true)
}

async function revealDay(ctx: GameContext) {
    expect(await waitForPhase(ctx.hostPage, ctx.gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)
    const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
    await revealBtn.waitFor({ state: 'visible', timeout: 30_000 })
    await revealBtn.click()
    await expect(ctx.hostPage.getByTestId('day-start-vote')).toBeVisible({ timeout: 10_000 })
}

async function witchPasses(ctx: GameContext) {
    expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 15_000)).toBe(true)
    const r = await postAction(page(ctx, 'WITCH'), {
        gameId: Number(ctx.gameId),
        actionType: 'WITCH_ACT',
        payload: { useAntidote: false },
    })
    expect(r.ok, `witch pass: ${r.error}`).toBe(true)
}

test.describe('Night rules — repeated actions, dead seer, 空刀, 空守, witch self-save', () => {
    test.setTimeout(process.env.CI ? 360_000 : 240_000)

    test('a repeated witch action is rejected and the seer keeps her turn', async ({
                                                                                       browser,
                                                                                   }, testInfo) => {
        const ctx = await setupGame(browser, BOARD)
        try {
            const witch = only(ctx, 'WITCH')
            const wolf = only(ctx, 'WEREWOLF')
            const [v1] = villagers(ctx)
            assertNonNull(v1, 'need a villager')

            await startNight(ctx)
            // 空刀 is off by default: the wolves get no skip button.
            expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 25_000)).toBe(
                true,
            )
            await expect(page(ctx, 'WEREWOLF').getByTestId('wolf-confirm-kill')).toBeVisible({
                timeout: 10_000,
            })
            await expect(page(ctx, 'WEREWOLF').getByTestId('wolf-skip-kill')).toHaveCount(0)
            await wolvesKill(ctx, witch.seat)
            expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 15_000)).toBe(true)
            const [save, repeat] = await page(ctx, 'WITCH').evaluate(
                async ({ gameId, wolfId }) => {
                    const send = async (payload: Record<string, unknown>) => {
                        const res = await fetch('/api/game/action', {
                            method: 'POST',
                            headers: {
                                'Content-Type': 'application/json',
                                Authorization: `Bearer ${localStorage.getItem('jwt')}`,
                            },
                            body: JSON.stringify({ gameId, actionType: 'WITCH_ACT', payload }),
                        })
                        return res.ok
                    }
                    const first = await send({ useAntidote: true })
                    const second = await send({ useAntidote: false, poisonTargetUserId: wolfId })
                    return [first, second]
                },
                { gameId: Number(ctx.gameId), wolfId: wolf.userId },
            )
            expect(save, 'night-1 self-save is allowed').toBe(true)
            expect(repeat, 'a second WITCH_ACT in the same turn must be rejected').toBe(false)

            expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'SEER_PICK', 15_000)).toBe(true)
            await ctx.hostPage.waitForTimeout(1_500)
            expect(
                (await readState(ctx.hostPage, ctx.gameId))?.nightPhase?.subPhase,
                'the seer turn was not skipped',
            ).toBe('SEER_PICK')
            await seerActs(ctx, v1.seat)

            await revealDay(ctx)
            await expect(ctx.hostPage.getByTestId('day-banner-peaceful')).toBeVisible({ timeout: 10_000 })
            const players = (await readState(ctx.hostPage, ctx.gameId))?.players ?? []
            expect(
                players.find((p: { userId: string }) => p.userId === wolf.userId)?.isAlive,
                'no poison landed',
            ).toBe(true)
            await captureSnapshot(ctx.pages, testInfo, 'night-repeat-rejected')

            await completeDay(ctx, testInfo, { targetSeat: null, label: 'night-repeat-d1' })
            await wolvesKill(ctx, v1.seat)
            expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 15_000)).toBe(true)
            const witchView = (await readState(page(ctx, 'WITCH'), ctx.gameId))?.nightPhase
            expect(witchView?.hasAntidote).toBe(false)
            expect(
                witchView?.attackedPlayerId,
                'without the antidote the witch is not told the victim',
            ).toBeUndefined()
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('a dead seer still shows the 查验结果 step', async ({ browser }, testInfo) => {
        const ctx = await setupGame(browser, BOARD)
        try {
            const seer = only(ctx, 'SEER')
            const [v1] = villagers(ctx)
            assertNonNull(v1, 'need a villager')

            await startNight(ctx)
            await wolvesKill(ctx, seer.seat)
            await witchPasses(ctx)
            await seerActs(ctx, v1.seat)
            await revealDay(ctx)
            await completeDay(ctx, testInfo, { targetSeat: null, label: 'dead-seer-d1' })

            await ctx.hostPage.evaluate((id: string) => {
                const w = window as unknown as { __subPhases: string[]; __stopPoll: boolean }
                w.__subPhases = []
                w.__stopPoll = false
                void (async () => {
                    while (!w.__stopPoll) {
                        const res = await fetch(`/api/game/${id}/state`, {
                            headers: { Authorization: `Bearer ${localStorage.getItem('jwt')}` },
                        })
                        const sp = res.ok ? (await res.json())?.nightPhase?.subPhase : null
                        if (sp && w.__subPhases[w.__subPhases.length - 1] !== sp) w.__subPhases.push(sp)
                        await new Promise((r) => setTimeout(r, 40))
                    }
                })()
            }, ctx.gameId)
            await wolvesKill(ctx, v1.seat)
            await witchPasses(ctx)
            await expect
                .poll(async () => (await readState(ctx.hostPage, ctx.gameId))?.phase, { timeout: 30_000 })
                .not.toBe('NIGHT')
            const seen = await ctx.hostPage.evaluate(() => {
                const w = window as unknown as { __subPhases: string[]; __stopPoll: boolean }
                w.__stopPoll = true
                return w.__subPhases
            })
            await captureSnapshot(ctx.pages, testInfo, 'dead-seer-night2')
            expect(seen, `night-2 sub-phases ${seen.join(' → ')}`).toEqual(
                expect.arrayContaining(['SEER_PICK', 'SEER_RESULT']),
            )
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('wolves choose 空刀: the witch sees no victim and the night is peaceful', async ({
                                                                                              browser,
                                                                                          }, testInfo) => {
        const ctx = await setupGame(browser, { ...BOARD, noKillAllowed: true })
        try {
            const [v1] = villagers(ctx)
            assertNonNull(v1, 'need a villager')

            await startNight(ctx)
            expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WEREWOLF_PICK', 25_000)).toBe(
                true,
            )
            await page(ctx, 'WEREWOLF').getByTestId('wolf-skip-kill').click()

            expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 15_000)).toBe(true)
            const witchPage = page(ctx, 'WITCH')
            await expect(witchPage.getByTestId('witch-no-victim')).toBeVisible({ timeout: 10_000 })
            await expect(witchPage.getByTestId('witch-antidote')).toHaveCount(0)
            await captureSnapshot(ctx.pages, testInfo, 'empty-kill-witch-view')
            const save = await postAction(witchPage, {
                gameId: Number(ctx.gameId),
                actionType: 'WITCH_ACT',
                payload: { useAntidote: true },
            })
            expect(save.ok, 'no victim, so the antidote has no target').toBe(false)
            await witchPage.getByTestId('switch-pass-antidote').click()

            await seerActs(ctx, v1.seat)
            await revealDay(ctx)
            await expect(ctx.hostPage.getByTestId('day-banner-peaceful')).toBeVisible({ timeout: 10_000 })
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('after night 1 the witch cannot save herself, and she can never poison herself', async ({
                                                                                                     browser,
                                                                                                 }, testInfo) => {
        const ctx = await setupGame(browser, BOARD)
        try {
            const witch = only(ctx, 'WITCH')
            const [v1] = villagers(ctx)
            assertNonNull(v1, 'need a villager')

            await startNight(ctx)
            await wolvesKill(ctx, v1.seat)
            await witchPasses(ctx)
            await seerActs(ctx, witch.seat)
            await revealDay(ctx)
            await completeDay(ctx, testInfo, { targetSeat: null, label: 'witch-n2-d1' })

            await wolvesKill(ctx, witch.seat)
            expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'WITCH_ACT', 15_000)).toBe(true)
            const witchPage = page(ctx, 'WITCH')
            await expect(witchPage.getByTestId('witch-self-save-blocked')).toBeVisible({
                timeout: 10_000,
            })
            await expect(witchPage.getByTestId('witch-antidote')).toHaveCount(0)
            await captureSnapshot(ctx.pages, testInfo, 'witch-n2-self-save-blocked')
            const save = await postAction(witchPage, {
                gameId: Number(ctx.gameId),
                actionType: 'WITCH_ACT',
                payload: { useAntidote: true },
            })
            expect(save.ok, 'night-2 self-save must be rejected').toBe(false)
            const selfPoison = await postAction(witchPage, {
                gameId: Number(ctx.gameId),
                actionType: 'WITCH_ACT',
                payload: { useAntidote: false, poisonTargetUserId: witch.userId },
            })
            expect(selfPoison.ok, 'self-poison must be rejected').toBe(false)
            await witchPasses(ctx)
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('空守 on: the guard protects no one and the wolf target dies', async ({ browser }, testInfo) => {
        const ctx = await setupGame(browser, { ...GUARD_BOARD, guardSkipAllowed: true })
        try {
            const v1 = villagers(ctx).find((b) => b.nick !== 'Host')
            assertNonNull(v1, 'need a non-host villager')

            await startNight(ctx)
            await wolvesKill(ctx, v1.seat)
            await witchPasses(ctx)
            expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'GUARD_PICK', 15_000)).toBe(true)
            const guardPage = page(ctx, 'GUARD')
            await expect(guardPage.getByTestId('guard-skip')).toBeVisible({ timeout: 10_000 })
            await expect(guardPage.getByTestId('guard-confirm-protect')).toBeVisible()
            await captureSnapshot(ctx.pages, testInfo, 'guard-skip-on')
            await guardPage.getByTestId('guard-skip').click()

            await revealDay(ctx)
            await expect(ctx.hostPage.getByTestId('day-banner-kill')).toBeVisible({ timeout: 10_000 })
            await expect(ctx.hostPage.getByTestId(`day-killed-seat-${v1.seat}`)).toBeVisible()
            await captureSnapshot(ctx.pages, testInfo, 'guard-skip-on-reveal')
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('空守 off (default): no skip button, the server rejects GUARD_SKIP, the guard keeps the turn', async ({
                                                                                                                   browser,
                                                                                                               }, testInfo) => {
        const ctx = await setupGame(browser, GUARD_BOARD)
        try {
            const v1 = villagers(ctx).find((b) => b.nick !== 'Host')
            assertNonNull(v1, 'need a non-host villager')

            await startNight(ctx)
            await wolvesKill(ctx, v1.seat)
            await witchPasses(ctx)
            expect(await waitForNightSubPhase(ctx.hostPage, ctx.gameId, 'GUARD_PICK', 15_000)).toBe(true)
            const guardPage = page(ctx, 'GUARD')
            await expect(guardPage.getByTestId('guard-confirm-protect')).toBeVisible({ timeout: 10_000 })
            await expect(guardPage.getByTestId('guard-skip')).toHaveCount(0)
            await captureSnapshot(ctx.pages, testInfo, 'guard-skip-off')
            const skip = await postAction(guardPage, {
                gameId: Number(ctx.gameId),
                actionType: 'GUARD_SKIP',
            })
            expect(skip.ok, '空守 is off, so GUARD_SKIP must be rejected').toBe(false)
            expect(skip.error).toContain('空守')

            await guardPage.locator(`.player-grid [data-seat="${v1.seat}"]`).click()
            await guardPage.getByTestId('guard-confirm-protect').click()
            await revealDay(ctx)
            await expect(ctx.hostPage.getByTestId('day-banner-peaceful')).toBeVisible({ timeout: 10_000 })
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })
})
