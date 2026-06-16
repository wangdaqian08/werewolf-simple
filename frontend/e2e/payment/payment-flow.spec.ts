import { expect, test } from '@playwright/test'
import fs from 'node:fs'

const BACKEND = 'http://localhost:8080'

/**
 * Full real-money-rails flow on the Stripe SANDBOX:
 * dev-login → buy sheet → real checkout.stripe.com → 4242 test card →
 * redirect to /pay/result → webhook (forwarded by stripe-cli) fulfills →
 * wallet credited.
 *
 * Stripe's hosted page is third-party DOM: the testid-only rule cannot
 * apply there — Stripe's stable input names are the documented exception.
 */
test('buy the starter pack with the 4242 test card → wallet credited via real webhook', async ({
  page,
  request,
}) => {
  // 1. Mint a non-guest buyer (dev-profile endpoint; guests cannot buy).
  //    Unique id per run: H2 is wiped each boot but reruns against a
  //    reused backend must not collide.
  const buyerId = `dev:payment-buyer-${Date.now()}`
  const login = await request.post(`${BACKEND}/api/auth/dev`, {
    data: { nickname: 'PaymentBuyer', userId: buyerId },
  })
  expect(login.ok()).toBeTruthy()
  const { token, user } = await login.json()

  // 2. Inject the session exactly the way userStore persists it.
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

  // 3. Open the buy sheet and pick the starter pack (100 credits / $1.99).
  //    The toggle only renders once refreshWallet resolves (credits chip);
  //    Playwright's auto-wait covers that.
  await page.getByTestId('buy-credits-toggle').click()
  await page.getByTestId('buy-starter').click()

  // 4. Real Stripe hosted checkout. Fresh email each run so Stripe never
  //    routes us into a returning-Link-user OTP screen.
  await page.waitForURL(/checkout\.stripe\.com/, { timeout: 45_000 })
  // Fail fast with a clear message if Stripe's form DOM drifts.
  await page.waitForSelector('input[name="cardNumber"]', { state: 'visible', timeout: 20_000 })
  await page.fill('input[name="email"]', `payment-e2e-${Date.now()}@example.com`)
  // ADAPTATION (seen on the real page 2026-06-11): Stripe pre-CHECKS the
  // "Save my information for faster checkout" Link opt-in immediately after
  // the email field, which reveals a REQUIRED phone-number field — submitting
  // with it empty fails client-side validation and never navigates. Uncheck
  // early (before card fields) so the phone field disappears before we type
  // card details and we never enroll the throwaway email in Link.
  const linkOptIn = page.locator('input[name="enableStripePass"]')
  if ((await linkOptIn.count()) > 0 && (await linkOptIn.isChecked())) {
    await linkOptIn.uncheck()
  }
  await page.fill('input[name="cardNumber"]', '4242 4242 4242 4242')
  await page.fill('input[name="cardExpiry"]', '12 / 34')
  await page.fill('input[name="cardCvc"]', '123')
  await page.fill('input[name="billingName"]', 'E2E Buyer')
  // Country defaults follow the runner's IP — pin US + ZIP so the form is
  // deterministic on both local (AU) and CI (US) machines.
  await page.selectOption('select[name="billingCountry"]', 'US')
  await page.fill('input[name="billingPostalCode"]', '12345')
  await page.click('button[type="submit"]')

  // 5. Stripe redirects back; PayResultView polls the order until the
  //    webhook fulfills it server-side (success page is never trusted).
  await page.waitForURL(/\/pay\/result\?.*status=success.*orderNo=/, { timeout: 90_000 })
  await expect(page.getByTestId('pay-credits')).toHaveText(/\+100/, { timeout: 45_000 })

  // 6. Authoritative check: balance from the backend wallet API.
  const wallet = await request.get(`${BACKEND}/api/wallet`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(wallet.ok()).toBeTruthy()
  const body = await wallet.json()
  expect(body.balance).toBe(100)

  // 7. Backend log error scan (six-design-principles).
  const log = fs.readFileSync('/tmp/werewolf-payment-backend.log', 'utf-8')
  // Any ERROR-level [payment] line fails the run (cannot-deserialize etc. all log at ERROR).
  expect(log).not.toMatch(/^.*\bERROR\b.*\[payment\]/im)
})
