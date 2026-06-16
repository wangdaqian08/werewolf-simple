import { beforeEach, describe, expect, it, vi } from 'vitest'
import http from '@/services/http'
import { walletService } from '@/services/walletService'

vi.mock('@/services/http', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

const mockedHttp = http as unknown as {
  get: ReturnType<typeof vi.fn>
  post: ReturnType<typeof vi.fn>
}

describe('walletService', () => {
  beforeEach(() => {
    mockedHttp.get.mockReset()
  })

  it('getWallet GETs /wallet and returns the balance + recent ledger', async () => {
    const expected = {
      balance: 250,
      recent: [
        {
          type: 'GAME_REWARD',
          amount: 20,
          balanceAfter: 250,
          note: null,
          createdAt: '2026-06-10T00:00:00',
        },
      ],
    }
    mockedHttp.get.mockResolvedValueOnce({ data: expected })

    const result = await walletService.getWallet()

    expect(mockedHttp.get).toHaveBeenCalledWith('/wallet')
    expect(result).toEqual(expected)
  })
})
