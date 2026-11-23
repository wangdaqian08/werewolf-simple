import http from './http'
import type { PaymentOrderPage, PaymentOrderSummary } from '@/types'

export interface CreditProduct {
  productKey: string
  name: string
  credits: number
  bonusCredits: number
  priceCents: number
  currency: string
}

export interface PaymentOrderStatus {
  orderNo: string
  status: PaymentOrderSummary['status']
  credits: number
  receiptUrl?: string | null
}

export const paymentService = {
  async getProducts(): Promise<CreditProduct[]> {
    const { data } = await http.get<CreditProduct[]>('/payment/products')
    return data
  },

  /** Returns the Stripe Checkout URL; caller redirects the browser there. */
  async createCheckout(productKey: string): Promise<string> {
    const { data } = await http.post<{ checkoutUrl: string }>('/payment/checkout', { productKey })
    return data.checkoutUrl
  },

  /** Owner-only fulfillment poll — the redirect back from Stripe is not trusted. */
  /** The player's change-of-mind refund of one order; the backend checks every rule again. */
  async requestRefund(orderNo: string): Promise<{ status: string; refundPending: boolean }> {
    const { data } = await http.post<{ status: string; refundPending: boolean }>(
      `/payment/order/${orderNo}/refund`,
    )
    return data
  },

  async getOrder(orderNo: string): Promise<PaymentOrderStatus> {
    const { data } = await http.get<PaymentOrderStatus>(`/payment/order/${orderNo}`)
    return data
  },

  /** One page (0-based) of the caller's own orders, newest first. */
  async listOrders(page = 0): Promise<PaymentOrderPage> {
    const { data } = await http.get<PaymentOrderPage>('/payment/orders', { params: { page } })
    return data
  },
}
