import { expect, type Page, test } from '@playwright/test'
import { setupGame, type GameContext } from './helpers/multi-browser'
import { actName, type BotInfo, type RoleName } from './helpers/shell-runner'
import { captureSnapshot } from './helpers/composite-screenshot'
import { assertNonNull, completeNight, tryAct } from './helpers/flow-drivers'
import { waitForVotingSubPhase } from './helpers/state-polling'
import { cleanupRoomData } from './helpers/test-support'
import { hostStartsNight } from './helpers/night-driver'

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

function vote(ctx: GameContext, voter: BotInfo, target: BotInfo | null): boolean {
    return tryAct(
        'SUBMIT_VOTE',
        actName(voter),
        target ? { target: String(target.seat), room: ctx.roomCode } : { room: ctx.roomCode },
    )
}

test.describe('PK re-vote — after a tie only the tied players can be voted for, and they do not vote', () => {
    test.setTimeout(process.env.CI ? 360_000 : 240_000)

    test('a 2:2 tie opens a PK between the two tied players', async ({ browser }, testInfo) => {
        const ctx = await setupGame(browser, {
            totalPlayers: 6,
            hasSheriff: false,
            roles: ['WEREWOLF', 'VILLAGER', 'SEER', 'WITCH'] as RoleName[],
            browserRoles: ['WEREWOLF', 'SEER', 'WITCH', 'VILLAGER'] as RoleName[],
        })
        try {
            const [wolfA, wolfB] = ctx.roleMap.WEREWOLF ?? []
            assertNonNull(wolfB, 'kit must have 2 wolves')
            const seer = only(ctx, 'SEER')
            const witch = only(ctx, 'WITCH')
            const [v1, v2] = ctx.roleMap.VILLAGER ?? []
            assertNonNull(v2, 'need two villagers')

            await hostStartsNight(ctx)
            await completeNight(ctx, {
                wolfKillSeat: v1.seat,
                witch: { mode: 'pass' },
                seerCheckSeat: wolfA.seat,
            })

            const revealBtn = ctx.hostPage.getByTestId('day-reveal-result')
            await revealBtn.waitFor({ state: 'visible', timeout: 30_000 })
            await revealBtn.click()
            const startVote = ctx.hostPage.getByTestId('day-start-vote')
            await expect(startVote).toBeVisible({ timeout: 10_000 })
            await startVote.click()
            expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'VOTING', 15_000)).toBe(true)

            for (const [voter, target] of [
                [wolfA, seer],
                [witch, seer],
                [wolfB, v2],
                [seer, v2],
                [v2, null],
            ] as const) {
                expect(vote(ctx, voter, target), `round-1 vote by ${voter.nick}`).toBe(true)
            }
            const tallyBtn = ctx.hostPage.getByTestId('voting-reveal')
            await expect(tallyBtn).toBeEnabled({ timeout: 15_000 })
            await tallyBtn.click()
            expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'RE_VOTING', 15_000)).toBe(true)

            const voting = (await readState(ctx.hostPage, ctx.gameId))?.votingPhase
            expect(
                [...(voting?.pkCandidates ?? [])].sort(),
                'the tied players stand on the PK stage',
            ).toEqual([seer.userId, v2.userId].sort())
            expect(voting?.totalVoters, 'PK candidates do not vote').toBe(3)
            await captureSnapshot(ctx.pages, testInfo, 'pk-revote-open')

            expect(vote(ctx, seer, v2), 'a PK candidate cannot vote').toBe(false)
            expect(vote(ctx, witch, wolfA), 'only a PK candidate can be voted for').toBe(false)
            expect(vote(ctx, witch, seer)).toBe(true)
            expect(vote(ctx, wolfA, seer)).toBe(true)
            expect(vote(ctx, wolfB, seer)).toBe(true)

            await expect(tallyBtn).toBeEnabled({ timeout: 15_000 })
            await tallyBtn.click()
            expect(await waitForVotingSubPhase(ctx.hostPage, ctx.gameId, 'VOTE_RESULT', 15_000)).toBe(
                true,
            )
            const after = await readState(ctx.hostPage, ctx.gameId)
            expect(
                after?.players?.find((p: { userId: string }) => p.userId === seer.userId)?.isAlive,
            ).toBe(false)
        } finally {
            await cleanupRoomData(ctx.hostPage, ctx.roomCode)
            await ctx.cleanup()
        }
    })
})
