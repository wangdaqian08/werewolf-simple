import http from './http'

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
  status: 'CREATED' | 'COMPLETED' | 'EXPIRED' | 'FAILED'
  credits: number
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
  async getOrder(orderNo: string): Promise<PaymentOrderStatus> {
    const { data } = await http.get<PaymentOrderStatus>(`/payment/order/${orderNo}`)
    return data
  },
}
