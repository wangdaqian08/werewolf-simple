import { beforeEach, describe, expect, it, vi } from 'vitest'
import http from '@/services/http'
import { paymentService } from '@/services/paymentService'

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

describe('paymentService', () => {
  beforeEach(() => {
    mockedHttp.get.mockReset()
  })

  it('listOrders GETs /payment/orders and returns the order list', async () => {
    const expected = [
      {
        orderNo: 'WW20260610001',
        productName: 'Starter Pack',
        credits: 100,
        amountCents: 499,
        currency: 'usd',
        status: 'COMPLETED',
        createdAt: '2026-06-10T19:55:00',
      },
    ]
    mockedHttp.get.mockResolvedValueOnce({ data: expected })

    const result = await paymentService.listOrders()

    expect(mockedHttp.get).toHaveBeenCalledWith('/payment/orders')
    expect(result).toEqual(expected)
  })
})
