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

  it('listOrders GETs one page of /payment/orders', async () => {
    const expected = {
      orders: [
        {
          orderNo: 'WW20260610001',
          productName: 'Starter Pack',
          credits: 100,
          amountCents: 499,
          currency: 'usd',
          status: 'REFUNDED',
          receiptUrl: 'https://pay.stripe.com/receipts/r1',
          createdAt: '2026-06-10T19:55:00',
        },
      ],
      hasMore: true,
    }
    mockedHttp.get.mockResolvedValueOnce({ data: expected })

    const result = await paymentService.listOrders(2)

    expect(mockedHttp.get).toHaveBeenCalledWith('/payment/orders', { params: { page: 2 } })
    expect(result).toEqual(expected)
  })
})
