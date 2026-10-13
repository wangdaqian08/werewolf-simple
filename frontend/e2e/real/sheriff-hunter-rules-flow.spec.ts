import { expect, type Page, test } from '@playwright/test'
import { setupGame, type GameContext } from './helpers/multi-browser'
import { actName, type BotInfo, type RoleName } from './helpers/shell-runner'
import { captureSnapshot } from './helpers/composite-screenshot'
import {
    assertNonNull,
    completeDay,
    completeNight,
    runSheriffElection,
    tryAct,
} from './helpers/flow-drivers'
import { hostStartsNight, waitForDayDiscussionAfterSheriff } from './helpers/night-driver'
import {
    readUnvotedAlivePlayerIds,
    waitForDaySubPhase,
    waitForPhase,
    waitForVotingSubPhase,
} from './helpers/state-polling'
import { cleanupRoomData } from './helpers/test-support'

const ROLES = ['WEREWOLF', 'VILLAGER', 'HUNTER', 'SEER', 'WITCH'] as RoleName[]

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

async function playNightOne(ctx: GameContext, killSeat: number) {
    await hostStartsNight(ctx)
    await completeNight(ctx, {
        wolfKillSeat: killSeat,
        witch: { mode: 'pass' },
        seerCheckSeat: only(ctx, 'WEREWOLF').seat,
    })
}

async function revealAndVoteOut(ctx: GameContext, seat: number) {
    const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
    await revealBtn.waitFor({ state: 'visible', timeout: 30_000 })
    await revealBtn.click()
    const startVote = ctx.hostPage.getByTestId('day-start-vote')
    await expect(startVote).toBeVisible({ timeout: 10_000 })
    await startVote.click()
    expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'VOTING', 15_000)).toBe(true)

    const unvoted = await readUnvotedAlivePlayerIds(ctx.hostPage, ctx.gameId)
    const everyone = Object.values(ctx.roleMap).flatMap((b) => b ?? [])
    for (const voter of everyone) {
        if (unvoted.has(voter.userId))
            tryAct('SUBMIT_VOTE', actName(voter), { target: String(seat), room: ctx.roomCode })
    }
    const tallyBtn = ctx.hostPage.getByTestId('voting-reveal')
    await expect(tallyBtn).toBeEnabled({ timeout: 15_000 })
    await tallyBtn.click()
}

test.describe('Sheriff + hunter rules — badge after the shot, no self-pass, no self-destruct mid-shot', () => {
    test.setTimeout(process.env.CI ? 360_000 : 240_000)

    test('a voted-out hunter who holds the badge hands it over after his shot', async ({
                                                                                           browser,
                                                                                       }, testInfo) => {
        const ctx = await setupGame(browser, {
            totalPlayers: 8,
            hasSheriff: true,
            roles: ROLES,
            browserRoles: ['WEREWOLF', 'HUNTER', 'SEER', 'WITCH'] as RoleName[],
        })
        try {
            const hunter = only(ctx, 'HUNTER')
            const seer = only(ctx, 'SEER')
            const [v1, v2] = villagers(ctx)
            assertNonNull(v2, 'need two villagers')

            await playNightOne(ctx, v1.seat)
            await runSheriffElection(ctx, [hunter.nick])
            await waitForDayDiscussionAfterSheriff(ctx)
            expect((await readState(ctx.hostPage, ctx.gameId))?.sheriffUserId).toBe(hunter.userId)

            await revealAndVoteOut(ctx, hunter.seat)
            expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'HUNTER_SHOOT', 15_000)).toBe(
                true,
            )
            expect(
                tryAct('HUNTER_SHOOT', actName(hunter), { target: String(v2.seat), room: ctx.roomCode }),
            ).toBe(true)

            expect(
                await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'BADGE_HANDOVER', 10_000),
                'the hunter still holds the badge after his shot',
            ).toBe(true)
            await captureSnapshot(ctx.pages, testInfo, 'hunter-sheriff-badge-handover')
            expect(
                tryAct('BADGE_PASS', actName(hunter), { target: String(seer.seat), room: ctx.roomCode }),
            ).toBe(true)
            expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'VOTE_RESULT', 10_000)).toBe(
                true,
            )
            expect((await readState(ctx.hostPage, ctx.gameId))?.sheriffUserId).toBe(seer.userId)
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('a sheriff shot by the hunter cannot pass the badge to himself', async ({
                                                                                     browser,
                                                                                 }, testInfo) => {
        const ctx = await setupGame(browser, {
            totalPlayers: 8,
            hasSheriff: true,
            roles: ROLES,
            browserRoles: ['WEREWOLF', 'HUNTER', 'SEER', 'WITCH'] as RoleName[],
        })
        try {
            const hunter = only(ctx, 'HUNTER')
            const seer = only(ctx, 'SEER')
            const witch = only(ctx, 'WITCH')
            const [v1] = villagers(ctx)

            await playNightOne(ctx, v1.seat)
            await runSheriffElection(ctx, [seer.nick])
            await waitForDayDiscussionAfterSheriff(ctx)

            await revealAndVoteOut(ctx, hunter.seat)
            expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'HUNTER_SHOOT', 15_000)).toBe(
                true,
            )
            expect(
                tryAct('HUNTER_SHOOT', actName(hunter), { target: String(seer.seat), room: ctx.roomCode }),
            ).toBe(true)
            expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'BADGE_HANDOVER', 10_000)).toBe(
                true,
            )

            expect(
                tryAct('BADGE_PASS', actName(seer), { target: String(seer.seat), room: ctx.roomCode }),
                'the dying sheriff cannot keep the badge by passing it to himself',
            ).toBe(false)
            expect(
                tryAct('BADGE_PASS', actName(seer), { target: String(witch.seat), room: ctx.roomCode }),
            ).toBe(true)
            expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'VOTE_RESULT', 10_000)).toBe(
                true,
            )
            const state = await readState(ctx.hostPage, ctx.gameId)
            await captureSnapshot(ctx.pages, testInfo, 'shot-sheriff-badge-passed')
            expect(state?.sheriffUserId).toBe(witch.userId)
            expect(
                state?.players?.find((p: { userId: string }) => p.userId === seer.userId)?.isAlive,
            ).toBe(false)
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('a wolf cannot self-destruct while the voted-out hunter is about to shoot', async ({
                                                                                                browser,
                                                                                            }, testInfo) => {
        const ctx = await setupGame(browser, {
            totalPlayers: 8,
            hasSheriff: false,
            roles: ROLES,
            browserRoles: ['WEREWOLF', 'HUNTER', 'SEER', 'WITCH'] as RoleName[],
        })
        try {
            const hunter = only(ctx, 'HUNTER')
            const wolf = only(ctx, 'WEREWOLF')
            const [v1, v2] = villagers(ctx)
            assertNonNull(v2, 'need two villagers')

            await playNightOne(ctx, v1.seat)
            expect(await waitForPhase(ctx.hostPage, ctx.gameId, 'DAY_DISCUSSION', 30_000)).toBe(true)
            await revealAndVoteOut(ctx, hunter.seat)
            expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'HUNTER_SHOOT', 15_000)).toBe(
                true,
            )

            expect(
                tryAct('WOLF_SELF_DESTRUCT', actName(wolf), { room: ctx.roomCode }),
                'the hunter shot resolves before a self-destruct',
            ).toBe(false)
            expect(
                tryAct('HUNTER_SHOOT', actName(hunter), { target: String(v2.seat), room: ctx.roomCode }),
            ).toBe(true)
            expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'VOTE_RESULT', 10_000)).toBe(
                true,
            )
            await captureSnapshot(ctx.pages, testInfo, 'hunter-shot-not-interrupted')
            const state = await readState(ctx.hostPage, ctx.gameId)
            expect(state?.players?.find((p: { userId: string }) => p.userId === v2.userId)?.isAlive).toBe(
                false,
            )
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('a hunter-sheriff killed at night shoots first, then hands over the badge', async ({
                                                                                                browser,
                                                                                            }, testInfo) => {
        const ctx = await setupGame(browser, {
            totalPlayers: 8,
            hasSheriff: true,
            roles: ROLES,
            browserRoles: ['WEREWOLF', 'HUNTER', 'SEER', 'WITCH'] as RoleName[],
        })
        try {
            const hunter = only(ctx, 'HUNTER')
            const seer = only(ctx, 'SEER')
            const wolf = only(ctx, 'WEREWOLF')
            const [v1, v2] = villagers(ctx)
            assertNonNull(v2, 'need two villagers')

            await playNightOne(ctx, v1.seat)
            await runSheriffElection(ctx, [hunter.nick])
            await waitForDayDiscussionAfterSheriff(ctx)
            expect(
                await completeDay(ctx, testInfo, { targetSeat: null, label: 'night-hunter-sheriff-d1' }),
            ).toBe('NIGHT')

            await completeNight(ctx, {
                wolfKillSeat: hunter.seat,
                witch: { mode: 'pass' },
                seerCheckSeat: wolf.seat,
            })
            const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
            await revealBtn.waitFor({ state: 'visible', timeout: 30_000 })
            await revealBtn.click()
            expect(
                await waitForDaySubPhase(ctx.hostPage, ctx.gameId, 'HUNTER_SHOOT_NIGHT_DEATH', 10_000),
                'the hunter shoots before the badge is handed over',
            ).toBe(true)
            expect(
                tryAct('HUNTER_SHOOT', actName(hunter), { target: String(v2.seat), room: ctx.roomCode }),
            ).toBe(true)

            expect(await waitForDaySubPhase(ctx.hostPage, ctx.gameId, 'BADGE_HANDOVER', 10_000)).toBe(
                true,
            )
            await captureSnapshot(ctx.pages, testInfo, 'night-hunter-sheriff-badge-after-shot')
            expect(
                tryAct('BADGE_PASS', actName(hunter), { target: String(v2.seat), room: ctx.roomCode }),
                'the player he shot cannot inherit the badge',
            ).toBe(false)
            expect(
                tryAct('BADGE_PASS', actName(hunter), { target: String(seer.seat), room: ctx.roomCode }),
            ).toBe(true)
            expect(await waitForDaySubPhase(ctx.hostPage, ctx.gameId, 'RESULT_REVEALED', 10_000)).toBe(
                true,
            )
            expect((await readState(ctx.hostPage, ctx.gameId))?.sheriffUserId).toBe(seer.userId)
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })
})
