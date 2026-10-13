import { expect, test } from '@playwright/test'
import { setupGame } from './helpers/multi-browser'
import { actName, sheriff, type RoleName } from './helpers/shell-runner'
import {
    advanceAllSheriffSpeeches,
    assertNonNull,
    completeNight,
    tryAct,
    waitForSheriffSubPhase,
} from './helpers/flow-drivers'
import { readAlivePlayerIds, readHostUserId, waitForPhase } from './helpers/state-polling'
import { cleanupRoomData } from './helpers/test-support'
import { hostStartsNight } from './helpers/night-driver'

test.describe('API guards — the backend enforces what the UI hides', () => {
    test.setTimeout(process.env.CI ? 360_000 : 240_000)

    test('IDIOT_REVEAL cannot be sent by hand (the idiot is revealed only by being voted out)', async ({
                                                                                                           browser,
                                                                                                       }) => {
        const ctx = await setupGame(browser, {
            totalPlayers: 6,
            hasSheriff: false,
            roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'IDIOT'] as RoleName[],
            browserRoles: ['WEREWOLF', 'SEER', 'IDIOT'] as RoleName[],
        })
        try {
            const idiot = (ctx.roleMap.IDIOT ?? [])[0]
            assertNonNull(idiot, 'kit must have an IDIOT')
            await hostStartsNight(ctx)

            expect(tryAct('IDIOT_REVEAL', actName(idiot), { room: ctx.roomCode })).toBe(false)
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })

    test('a user who is not in the game cannot vote in the sheriff election', async ({ browser }) => {
        const ctx = await setupGame(browser, {
            totalPlayers: 6,
            hasSheriff: true,
            roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH'] as RoleName[],
            browserRoles: ['WEREWOLF', 'SEER', 'WITCH'] as RoleName[],
        })
        try {
            const seer = (ctx.roleMap.SEER ?? [])[0]
            const wolf = (ctx.roleMap.WEREWOLF ?? [])[0]
            const villager = (ctx.roleMap.VILLAGER ?? [])[0]
            assertNonNull(seer, 'kit must have a SEER')
            await hostStartsNight(ctx)
            await completeNight(ctx, {
                wolfKillSeat: villager.seat,
                witch: { mode: 'pass' },
                seerCheckSeat: wolf.seat,
            })
            expect(await waitForPhase(ctx.hostPage, ctx.gameId, 'SHERIFF_ELECTION', 30_000)).toBe(true)

            sheriff('campaign', { player: seer.nick, room: ctx.roomCode })
            const hostUserId = await readHostUserId(ctx.hostPage)
            const everyone = Object.values(ctx.roleMap).flatMap((b) => b ?? [])
            for (const userId of await readAlivePlayerIds(ctx.hostPage, ctx.gameId)) {
                if (userId === seer.userId) continue
                const nick =
                    userId === hostUserId ? 'HOST' : everyone.find((b) => b.userId === userId)?.nick
                if (nick) sheriff('pass', { player: nick, room: ctx.roomCode })
            }
            expect(await waitForSheriffSubPhase(ctx.hostPage, ctx.gameId, 'SPEECH', 15_000)).toBe(true)
            await advanceAllSheriffSpeeches(ctx.hostPage, ctx.gameId)
            expect(await waitForSheriffSubPhase(ctx.hostPage, ctx.gameId, 'VOTING', 15_000)).toBe(true)

            const outsiderVoted = await ctx.hostPage.evaluate(
                async ({ gameId, target }) => {
                    const login = await fetch('/api/user/login', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ nickname: `Outsider${Date.now() % 100000}` }),
                    })
                    const { token } = await login.json()
                    if (!token) throw new Error('outsider login failed')
                    const res = await fetch('/api/game/action', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
                        body: JSON.stringify({ gameId, actionType: 'SHERIFF_VOTE', targetUserId: target }),
                    })
                    return res.ok
                },
                { gameId: Number(ctx.gameId), target: seer.userId },
            )
            expect(outsiderVoted, 'an outsider must not stuff the sheriff ballot').toBe(false)
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })
})
