import { expect, test, type APIRequestContext, type Frame, type Page } from '@playwright/test'
import fs from 'node:fs'

const BACKEND = 'http://localhost:8080'
const BACKEND_LOG = '/tmp/werewolf-payment-backend.log'
const STRIPE_KEY = process.env.STRIPE_SANDBOX_SECRET ?? ''

/**
 * Real-money-rails flows on the Stripe SANDBOX: dev-login → buy sheet → real
 * checkout.stripe.com → test card → /pay/result → credits, then refunds and disputes.
 *
 * scripts/run-payment-e2e.sh runs the @no-webhook test first with no webhook
 * forwarder, then the rest with stripe-cli forwarding events to the backend.
 *
 * Stripe's hosted page is third-party DOM: the testid-only rule cannot apply
 * there, so Stripe's stable input names are the documented exception.
 */

interface Buyer {
  token: string
}

interface Purchase extends Buyer {
  orderNo: string
  sessionId: string
}

async function loginBuyer(page: Page, request: APIRequestContext): Promise<Buyer> {
  // Guests cannot buy; the dev endpoint mints a signed-in user. A unique id per
  // test, so reruns against the same backend never share a wallet.
  const login = await request.post(`${BACKEND}/api/auth/dev`, {
    data: { nickname: 'PaymentBuyer', userId: `dev:payment-buyer-${Date.now()}` },
  })
  expect(login.ok()).toBeTruthy()
  const { token, user } = await login.json()
  await page.goto('/')
  await page.evaluate(
    ([t, uid, nick]) => {
      localStorage.setItem('jwt', t)
      localStorage.setItem('userId', uid)
      localStorage.setItem('nickname', nick)
    },
    [token, user.userId, user.nickname],
  )
  await page.reload()
  return { token }
}

// AUD shows card / Klarna / Zip collapsed; USD shows the card form directly.
const CARD_FIELD = 'input[name="number"]'
const CARD_OPTION = '[role="button"][data-value="card"]'

/** The Payment Element frame on Stripe's hosted page (the form lives in an iframe), card form open. */
async function checkoutForm(page: Page): Promise<Frame> {
  let form: Frame | undefined
  await expect
    .poll(
      async () => {
        for (const frame of page.frames()) {
          if (
            await frame
              .locator(`${CARD_FIELD}, ${CARD_OPTION}`)
              .first()
              .isVisible()
              .catch(() => false)
          ) {
            form = frame
            return true
          }
        }
        return false
      },
      { timeout: 30_000, message: 'Stripe payment form never appeared' },
    )
    .toBe(true)
  const frame = form as Frame
  const cardOption = frame.locator(CARD_OPTION)
  if ((await cardOption.count()) > 0) await cardOption.click()
  await frame.locator(CARD_FIELD).waitFor({ state: 'visible' })
  return frame
}

/** Buys the value pack (330 credits / A$4.99) with [card] and waits for the return page. */
async function buyValuePack(
  page: Page,
  request: APIRequestContext,
  card: string,
): Promise<Purchase> {
  const buyer = await loginBuyer(page, request)
  await page.getByTestId('buy-credits-toggle').click()
  await page.getByTestId('buy-value').click()

  await page.waitForURL(/checkout\.stripe\.com/, { timeout: 45_000 })
  const sessionId = page.url().match(/cs_test_[A-Za-z0-9]+/)?.[0]
  expect(sessionId, 'checkout URL carries the session id').toBeTruthy()

  const form = await checkoutForm(page)
  // A fresh email each run, so Stripe never routes us into a returning-Link-user OTP screen.
  // Stripe ticks the Link box only once its lookup of the typed email returns, so wait for that first.
  const linkLookup = page.waitForResponse((r) => r.url().includes('/v1/consumers/sessions/lookup'))
  await form.fill('input[name="email"]', `payment-e2e-${Date.now()}@example.com`)
  await form.locator('input[name="number"]').focus()
  await linkLookup
  // Stripe pre-checks "save my information" (Link) and then requires a phone number. Toggling its
  // styled box from a test leaves Stripe's own state out of step and the pay button silently does
  // nothing (2026-11-22), so leave it on and give it a test phone number (sandbox Link only).
  const linkPhone = form.locator('input[name="linkMobilePhone"]')
  if (await linkPhone.isVisible()) {
    await form.selectOption('select[name="linkMobilePhoneCountry"]', 'US')
    await linkPhone.fill('2015550123')
  }
  await form.fill('input[name="number"]', card)
  await form.fill('input[name="expiry"]', '12 / 34')
  await form.fill('input[name="cvc"]', '123')
  await form.fill('input[name="name"]', 'E2E Buyer')
  // The country defaults to the runner's location: pin US + ZIP so local and CI match.
  await form.selectOption('select[name="country"]', 'US')
  await form.fill('input[name="postalCode"]', '12345')
  const terms = form.locator('input[name="termsOfServiceConsent"]')
  await form.locator('label', { has: terms }).click({ position: { x: 5, y: 5 } })
  await expect(terms).toHaveAttribute('aria-checked', 'true')
  await form.click('[data-testid="payment-form-submit-button"]')

  await page.waitForURL(/\/pay\/result\?.*status=success.*orderNo=/, { timeout: 90_000 })
  const orderNo = new URL(page.url()).searchParams.get('orderNo') ?? ''
  const session = await stripeApi('GET', `checkout/sessions/${sessionId}`)
  expect(session.consent?.terms_of_service, 'the buyer accepted the Terms').toBe('accepted')
  expect(session.currency).toBe('aud')
  return { ...buyer, orderNo, sessionId: sessionId as string }
}

async function balance(request: APIRequestContext, token: string): Promise<number> {
  const res = await request.get(`${BACKEND}/api/wallet`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.ok()).toBeTruthy()
  return (await res.json()).balance
}

async function orderStatus(request: APIRequestContext, p: Purchase): Promise<string> {
  const res = await request.get(`${BACKEND}/api/payment/order/${p.orderNo}`, {
    headers: { Authorization: `Bearer ${p.token}` },
  })
  expect(res.ok()).toBeTruthy()
  return (await res.json()).status
}

async function stripeApi(method: 'GET' | 'POST', path: string, form?: Record<string, string>) {
  expect(STRIPE_KEY, 'test-mode key only').toMatch(/^(sk|rk)_test_/)
  const res = await fetch(`https://api.stripe.com/v1/${path}`, {
    method,
    headers: { Authorization: `Bearer ${STRIPE_KEY}` },
    body: form ? new URLSearchParams(form) : undefined,
  })
  const body = await res.json()
  expect(res.ok, `Stripe ${method} ${path}: ${JSON.stringify(body.error)}`).toBeTruthy()
  return body
}

function backendLog(): string {
  return fs.readFileSync(BACKEND_LOG, 'utf-8')
}

/** Waits until the backend logged a line matching [pattern]. */
async function expectLog(pattern: RegExp) {
  await expect
    .poll(() => pattern.test(backendLog()), { timeout: 30_000, message: `log line ${pattern}` })
    .toBe(true)
}

/** No ERROR line for this order, and none tagged [payment] at all (alerts for old sandbox sessions are expected). */
function expectCleanLog(orderNo: string) {
  const errors = backendLog()
    .split('\n')
    .filter((l) => /\bERROR\b/.test(l) && (l.includes(orderNo) || l.includes('[payment]')))
  expect(errors).toEqual([])
}

async function openAccount(page: Page) {
  await page.goto('/account')
  await expect(page.getByTestId('order-row').first()).toBeVisible()
}

test('buy the value pack with the 4242 test card → credited, and the webhook is processed', async ({
  page,
  request,
}) => {
  const p = await buyValuePack(page, request, '4242 4242 4242 4242')
  await expect(page.getByTestId('pay-credits')).toHaveText(/\+330/, { timeout: 45_000 })
  expect(await balance(request, p.token)).toBe(330)
  // The return page may credit first; either way the forwarded webhook must reach fulfill.
  await expectLog(
    new RegExp(`\\[payment\\] fulfill session=${p.sessionId} order=${p.orderNo} source=webhook`),
  )
  expectCleanLog(p.orderNo)
})

test('@no-webhook the return page alone credits the purchase', async ({ page, request }) => {
  const p = await buyValuePack(page, request, '4242 4242 4242 4242')
  await expect(page.getByTestId('pay-credits')).toHaveText(/\+330/, { timeout: 45_000 })
  expect(await balance(request, p.token)).toBe(330)
  await expectLog(
    new RegExp(
      `\\[payment\\] fulfill session=${p.sessionId} order=${p.orderNo} source=status-check outcome=CREDITED`,
    ),
  )
  expect(backendLog()).not.toMatch(new RegExp(`order=${p.orderNo} source=webhook`))
  expectCleanLog(p.orderNo)
})

test('a full refund takes the credits back', async ({ page, request }) => {
  const p = await buyValuePack(page, request, '4242 4242 4242 4242')
  await expect(page.getByTestId('pay-credits')).toHaveText(/\+330/, { timeout: 45_000 })

  // What the owner does in the Dashboard: refund the whole charge.
  const session = await stripeApi('GET', `checkout/sessions/${p.sessionId}`)
  await stripeApi('POST', 'refunds', { payment_intent: session.payment_intent })

  await expect.poll(() => orderStatus(request, p), { timeout: 60_000 }).toBe('REFUNDED')
  expect(await balance(request, p.token)).toBe(0)
  await expectLog(new RegExp(`\\[payment\\] charge \\S+ order ${p.orderNo} .*\\(source=webhook\\)`))

  await openAccount(page)
  await expect(page.getByTestId('order-status').first()).toHaveText('已退款')
  await expect(page.getByTestId('ledger-row').first()).toContainText('充值退回')
  await expect(page.getByTestId('ledger-order').first()).toHaveText(p.orderNo)
  expectCleanLog(p.orderNo)
})

test('a disputed payment takes the credits back', async ({ page, request }) => {
  // Stripe's test card that succeeds and then opens a fraud dispute.
  const p = await buyValuePack(page, request, '4000 0000 0000 0259')

  await expect.poll(() => orderStatus(request, p), { timeout: 90_000 }).toBe('DISPUTED')
  expect(await balance(request, p.token)).toBe(0)

  await openAccount(page)
  await expect(page.getByTestId('order-status').first()).toHaveText('拒付處理中')
  await expect(page.getByTestId('ledger-row').first()).toContainText('拒付扣回')
  expectCleanLog(p.orderNo)
})
