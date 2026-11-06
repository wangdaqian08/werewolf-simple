import { beforeEach, describe, expect, it, vi } from 'vitest'
import { roomService } from '@/services/roomService'

vi.mock('@/services/http', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn().mockResolvedValue({ data: { success: true } }),
  },
}))

import http from '@/services/http'

const mockedHttp = http as unknown as {
  get: ReturnType<typeof vi.fn>
  post: ReturnType<typeof vi.fn>
}

/**
 * Wire-format contracts for the perk endpoints. roomId is coerced to Number
 * (the backend DTO is Int); perkCode is passed through. If the shape drifts,
 * these fail before a live request reaches the backend.
 */
describe('roomService perk endpoints', () => {
  beforeEach(() => {
    mockedHttp.get.mockReset()
    mockedHttp.post.mockReset()
    mockedHttp.post.mockResolvedValue({ data: { success: true } })
  })

  it('getPerks GETs /perks and returns the catalog', async () => {
    const catalog = [
      {
        perkCode: 'NIGHT1_IMMUNITY',
        name: 'First Night Immunity',
        description: 'Safe night 1',
        priceCredits: 30,
      },
    ]
    mockedHttp.get.mockResolvedValueOnce({ data: catalog })

    const result = await roomService.getPerks()

    expect(mockedHttp.get).toHaveBeenCalledWith('/perks')
    expect(result).toEqual(catalog)
  })

  it('activatePerk POSTs /room/perk/activate with numeric roomId and perkCode', async () => {
    await roomService.activatePerk('42', 'NIGHT1_IMMUNITY')

    expect(mockedHttp.post).toHaveBeenCalledWith('/room/perk/activate', {
      roomId: 42,
      perkCode: 'NIGHT1_IMMUNITY',
    })
  })

  it('withdrawPerk POSTs /room/perk/withdraw with numeric roomId and perkCode', async () => {
    await roomService.withdrawPerk('7', 'NIGHT1_IMMUNITY')

    expect(mockedHttp.post).toHaveBeenCalledWith('/room/perk/withdraw', {
      roomId: 7,
      perkCode: 'NIGHT1_IMMUNITY',
    })
  })

  it('getRolePick GETs /room/{id}/role-pick', async () => {
    const v = {
      choice: null,
      frozenCredits: 0,
      prices: {},
      seats: { WOLF: 1, GOD: 1, VILLAGER: 1 },
    }
    mockedHttp.get.mockResolvedValueOnce({ data: v })
    expect(await roomService.getRolePick('42')).toEqual(v)
    expect(mockedHttp.get).toHaveBeenCalledWith('/room/42/role-pick')
  })

  it('chooseRolePick POSTs /room/role-pick with numeric roomId and choice', async () => {
    mockedHttp.post.mockResolvedValueOnce({ data: { choice: 'GOD' } })
    expect(await roomService.chooseRolePick('42', 'GOD')).toEqual({ choice: 'GOD' })
    expect(mockedHttp.post).toHaveBeenCalledWith('/room/role-pick', { roomId: 42, choice: 'GOD' })
  })
})
