import { describe, expect, it, vi } from 'vitest'
import http from '@/services/http'
import { gameService } from '@/services/gameService'

describe('gameService.getState', () => {
  it('never returns a state older than one already returned (out-of-order responses)', async () => {
    const resolvers: Array<(v: unknown) => void> = []
    vi.spyOn(http, 'get').mockImplementation(
      () => new Promise((resolve) => resolvers.push(resolve)) as never,
    )
    const older = gameService.getState('7')
    const newer = gameService.getState('7')
    resolvers[1]!({ data: { votes: 12 } })
    expect(await newer).toEqual({ votes: 12 })
    resolvers[0]!({ data: { votes: 11 } }) // the older request lands last
    expect(await older).toEqual({ votes: 12 })
  })
})
