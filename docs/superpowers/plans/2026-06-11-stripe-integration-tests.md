# Stripe Payment Integration Tests + CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Test the Phase C Stripe Checkout integration against the real Stripe sandbox — backend JUnit tests as a PR gate, a full-browser payment E2E as a nightly/manual job — with keys supplied locally via shell env and in CI via GitHub Actions secrets.

**Architecture:** Backend sandbox tests live in a new `StripeSandboxIntegrationTest` gated by the boolean env flag `STRIPE_SANDBOX_TESTS_ENABLED` (no value-matching on secrets). The browser E2E lives in its own `frontend/e2e/payment/` directory + `playwright.payment.config.ts` (outside the 6-shard `e2e-integration` matrix), boots the backend with `SPRING_PROFILES_ACTIVE=dev,e2e`, and receives real webhooks through `stripe listen --forward-to`.

**Tech Stack:** Spring Boot 3 / Kotlin / JUnit 5 / stripe-java 25.13.0; Playwright + stripe-cli; GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-06-11-stripe-integration-tests-design.md`

**Conventions that apply (from memory/CLAUDE.md):** no `!!` in `src/main` (tests may, but prefer `?: error(...)`); Playwright locators use `getByTestId` (documented exception: Stripe's third-party checkout DOM); never label a failure "flake/timeout" without reading the log.

---

### Task 1: Feature-flag config property

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/config/StripeProperties.kt`
- Modify: `backend/src/main/resources/application.yml` (payment block, ~line 44)
- Modify: `backend/src/main/resources/application-dev.yml` (app block)

- [ ] **Step 1: Add the flag field to PaymentProperties**

In `StripeProperties.kt`, add one field to the data class:

```kotlin
@ConfigurationProperties(prefix = "app.payment")
data class PaymentProperties(
    /** Base URL the Checkout success/cancel redirects return to. */
    val frontendBaseUrl: String = "https://www.youplay123.online",
    val stripeSecretKey: String = "",
    val stripeWebhookSecret: String = "",
    /**
     * Feature flag for the Stripe sandbox integration tests
     * (StripeSandboxIntegrationTest). Never true in prod.
     */
    val sandboxTestsEnabled: Boolean = false,
)
```

- [ ] **Step 2: Declare the flag in application.yml**

In `backend/src/main/resources/application.yml`, extend the `app.payment` block:

```yaml
  payment:
    frontend-base-url: ${FRONTEND_BASE_URL:https://www.youplay123.online}
    # Test-mode keys; empty default disables checkout (503) rather than failing boot
    stripe-secret-key: ${STRIPE_SECRET_KEY:}
    stripe-webhook-secret: ${STRIPE_WEBHOOK_SECRET:}
    # Feature flag: lets the Stripe sandbox integration tests run (CI/local only)
    sandbox-tests-enabled: ${STRIPE_SANDBOX_TESTS_ENABLED:false}
```

- [ ] **Step 3: Declare the flag in application-dev.yml**

In `backend/src/main/resources/application-dev.yml`, inside the existing `app:` block add:

```yaml
  payment:
    sandbox-tests-enabled: ${STRIPE_SANDBOX_TESTS_ENABLED:false}
```

- [ ] **Step 4: Compile**

Run: `cd backend && ./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/com/werewolf/config/StripeProperties.kt backend/src/main/resources/application.yml backend/src/main/resources/application-dev.yml
git commit -m "feat(payment): add STRIPE_SANDBOX_TESTS_ENABLED feature flag"
```

---

### Task 2: Signed-webhook-accepted test (offline, closes the signature-path gap)

**Files:**
- Modify: `backend/src/test/kotlin/com/werewolf/integration/PaymentWebhookIntegrationTest.kt`

Currently only the bad-signature 400 path is tested. Add the happy path: a payload signed with the configured `whsec` is accepted end-to-end and fulfills the order.

- [ ] **Step 1: Pin a known webhook secret on the test context**

Change the class annotation (add `properties`):

```kotlin
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = ["app.payment.stripe-webhook-secret=whsec_test_secret"],
)
@ActiveProfiles("test")
class PaymentWebhookIntegrationTest {
```

Add import: `import com.stripe.net.Webhook`

- [ ] **Step 2: Write the test**

Append inside the class:

```kotlin
@Test
fun `correctly signed webhook is accepted and fulfills the order`() {
    val (userId, order) = seedOrder()
    val sessionId = order.stripeSessionId ?: error("no session id")
    val payload = """
        {
          "id": "evt_signed_$sessionId",
          "object": "event",
          "api_version": "${Stripe.API_VERSION}",
          "type": "checkout.session.completed",
          "data": {
            "object": {
              "id": "$sessionId",
              "object": "checkout.session",
              "payment_intent": "pi_signed_123"
            }
          }
        }
    """.trimIndent()

    // Same HMAC scheme Stripe uses: v1 = HMAC-SHA256(secret, "<ts>.<payload>")
    val ts = System.currentTimeMillis() / 1000
    val signature = Webhook.Util.computeHmacSha256("whsec_test_secret", "$ts.$payload")
    val headers = HttpHeaders().also {
        it.contentType = MediaType.APPLICATION_JSON
        it.set("Stripe-Signature", "t=$ts,v1=$signature")
    }

    val resp = restTemplate.postForEntity(
        "/api/payment/webhook", HttpEntity(payload, headers), Map::class.java,
    )

    assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
    assertThat(walletService.balance(userId)).isEqualTo(100)
    assertThat(paymentOrderRepository.findByOrderNo(order.orderNo).orElseThrow().status)
        .isEqualTo(PaymentOrderStatus.COMPLETED)
}
```

- [ ] **Step 3: Run the whole class (new test should pass — it covers existing prod code; the pre-existing 3 tests must stay green under the pinned whsec)**

Run: `cd backend && ./gradlew test --tests 'com.werewolf.integration.PaymentWebhookIntegrationTest' -q`
Expected: 5 tests pass (4 existing + 1 new). If the new test gets 400, the signature computation is wrong — check the `"$ts.$payload"` concatenation; do NOT loosen the verifier.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/kotlin/com/werewolf/integration/PaymentWebhookIntegrationTest.kt
git commit -m "test(payment): cover the accepted-signature webhook path"
```

---

### Task 3: StripeSandboxIntegrationTest (real sandbox, flag-gated)

**Files:**
- Create: `backend/src/test/kotlin/com/werewolf/integration/StripeSandboxIntegrationTest.kt`

- [ ] **Step 1: Write the test class (complete file)**

```kotlin
package com.werewolf.integration

import com.stripe.model.Event
import com.stripe.model.checkout.Session
import com.stripe.param.EventListParams
import com.werewolf.config.PaymentProperties
import com.werewolf.model.PaymentOrderStatus
import com.werewolf.model.Product
import com.werewolf.model.User
import com.werewolf.repository.PaymentOrderRepository
import com.werewolf.repository.ProductRepository
import com.werewolf.repository.UserRepository
import com.werewolf.service.PaymentService
import com.werewolf.service.WalletService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Talks to the REAL Stripe sandbox over the network. Gated by the
 * STRIPE_SANDBOX_TESTS_ENABLED feature flag (boolean — no value-matching
 * on secrets): machines without the flag skip the class cleanly. The key
 * comes from the STRIPE_SANDBOX_SECRET env var — your shell locally, a
 * GitHub Actions repository secret in CI.
 */
@EnabledIfEnvironmentVariable(named = "STRIPE_SANDBOX_TESTS_ENABLED", matches = "true")
@SpringBootTest(properties = ["app.payment.stripe-secret-key=\${STRIPE_SANDBOX_SECRET:}"])
@ActiveProfiles("test")
class StripeSandboxIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var walletService: WalletService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var paymentOrderRepository: PaymentOrderRepository
    @Autowired lateinit var props: PaymentProperties

    @BeforeEach
    fun guardTestModeKey() {
        // Safety assert (not gating): this class must never run against a live key.
        check(
            props.stripeSecretKey.startsWith("sk_test_") ||
                props.stripeSecretKey.startsWith("rk_test_"),
        ) { "STRIPE_SANDBOX_SECRET must be a Stripe TEST-mode key (sk_test_/rk_test_)" }
    }

    private fun seedUserAndProduct(): Pair<String, Product> {
        val userId = "google:sandbox-${UUID.randomUUID()}"
        userRepository.save(User(userId = userId, nickname = "SandboxBuyer"))
        val product = productRepository.findByProductKeyAndActiveTrue("starter").orElseGet {
            productRepository.save(
                Product(productKey = "starter", name = "Starter", credits = 100, priceCents = 199),
            )
        }
        return userId to product
    }

    @Test
    fun `createCheckout creates a real sandbox session with correct amount and metadata`() {
        val (userId, product) = seedUserAndProduct()

        val url = paymentService.createCheckout(userId, product.productKey)

        assertThat(url).contains("checkout.stripe.com")
        val order = paymentOrderRepository.findAll().single { it.userId == userId }
        val sessionId = order.stripeSessionId ?: error("order has no session id")

        val session = Session.retrieve(sessionId)
        assertThat(session.mode).isEqualTo("payment")
        assertThat(session.amountTotal).isEqualTo(product.priceCents.toLong())
        assertThat(session.currency).isEqualTo(product.currency)
        assertThat(session.clientReferenceId).isEqualTo(order.orderNo)
        assertThat(session.metadata["orderNo"]).isEqualTo(order.orderNo)
        assertThat(session.metadata["userId"]).isEqualTo(userId)
        assertThat(session.successUrl).startsWith(props.frontendBaseUrl)

        session.expire() // tidy up the sandbox
    }

    @Test
    fun `a real checkout_session_expired event round-trips through handleEvent`() {
        val (userId, product) = seedUserAndProduct()
        paymentService.createCheckout(userId, product.productKey)
        val order = paymentOrderRepository.findAll().single { it.userId == userId }
        val sessionId = order.stripeSessionId ?: error("order has no session id")

        Session.retrieve(sessionId).expire()

        // Poll the real Events API until Stripe records the expiry (usually < 5 s).
        val params = EventListParams.builder()
            .setType("checkout.session.expired")
            .setLimit(50L)
            .build()
        var event: Event? = null
        val deadline = System.currentTimeMillis() + 60_000
        while (event == null && System.currentTimeMillis() < deadline) {
            event = Event.list(params).data
                .firstOrNull { it.dataObjectDeserializer.rawJson.contains(sessionId) }
            if (event == null) Thread.sleep(2_000)
        }
        val expiredEvent = event
            ?: error("checkout.session.expired for $sessionId not visible on the Events API after 60s")

        paymentService.handleEvent(expiredEvent)

        val updated = paymentOrderRepository.findByOrderNo(order.orderNo).orElseThrow()
        assertThat(updated.status).isEqualTo(PaymentOrderStatus.EXPIRED)
        assertThat(walletService.balance(userId)).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Verify the gate — run WITHOUT the flag, class must be skipped**

Run: `cd backend && ./gradlew test --tests 'com.werewolf.integration.StripeSandboxIntegrationTest' -q --info 2>&1 | grep -i "skip\|SKIPPED" | head -3`
Expected: the class is reported skipped/disabled, not failed.

- [ ] **Step 3: Run WITH the flag against the real sandbox**

```bash
cd backend && STRIPE_SANDBOX_TESTS_ENABLED=true \
  STRIPE_SANDBOX_SECRET="$(zsh -c 'source ~/.zshrc >/dev/null 2>&1; printf %s "$STRIPE_SANDBOX_SECRET"')" \
  ./gradlew test --tests 'com.werewolf.integration.StripeSandboxIntegrationTest'
```

Expected: 2 tests pass (network: needs api.stripe.com).

**Known-real-failure contingency (do not paper over):** if test 2 fails because `handleEvent` logs `cannot deserialize session from event`, that is a genuine API-version mismatch between the sandbox account's event payload version and stripe-java 25.13.0's pinned version — the same mismatch would break prod webhooks. The fix is to upgrade `com.stripe:stripe-java` in `backend/build.gradle.kts` to the latest major and re-run all payment tests (`stripe:upgrade-stripe` skill has the migration guide). Do NOT switch the test to hand-built JSON.

- [ ] **Step 4: Full backend build still green (flag off)**

Run: `cd backend && ./gradlew build -q`
Expected: BUILD SUCCESSFUL (sandbox class skipped).

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/kotlin/com/werewolf/integration/StripeSandboxIntegrationTest.kt
git commit -m "test(payment): real Stripe sandbox integration tests behind feature flag"
```

---

### Task 4: CI wiring for the backend sandbox tests

**Files:**
- Modify: `.github/workflows/ci.yml` (backend job, `Build & test` step, ~line 55)

- [ ] **Step 1: Set the repository secret (value piped from the local shell, never echoed/committed)**

```bash
zsh -c 'source ~/.zshrc >/dev/null 2>&1; printf %s "$STRIPE_SANDBOX_SECRET"' | gh secret set STRIPE_SANDBOX_SECRET
gh secret list
```

Expected: `STRIPE_SANDBOX_SECRET` listed with an update timestamp.

- [ ] **Step 2: Pass flag + secret to the backend test step**

In `.github/workflows/ci.yml`, the `Build & test` step becomes:

```yaml
      - name: Build & test
        run: ./gradlew build
        env:
          SPRING_DATASOURCE_URL: jdbc:postgresql://localhost:5432/werewolf_test
          SPRING_DATASOURCE_USERNAME: werewolf
          SPRING_DATASOURCE_PASSWORD: werewolf
          # Sandbox tests run only when the secret exists (fork PRs get no
          # secrets → flag stays false → class skips cleanly).
          STRIPE_SANDBOX_TESTS_ENABLED: ${{ secrets.STRIPE_SANDBOX_SECRET != '' && 'true' || 'false' }}
          STRIPE_SANDBOX_SECRET: ${{ secrets.STRIPE_SANDBOX_SECRET }}
```

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: run Stripe sandbox tests in the backend job via repo secret"
```

---

### Task 5: Seed products in the e2e profile (buy sheet is empty without it)

**Files:**
- Create: `backend/src/main/kotlin/com/werewolf/config/E2eProductSeeder.kt`

The e2e profile runs H2 with Flyway disabled, so the product rows seeded by `V19__credits_perks_payments.sql` don't exist → `GET /api/payment/products` returns `[]` → the lobby buy sheet renders nothing and the payment E2E can't click `buy-starter`.

- [ ] **Step 1: Write the seeder (complete file)**

```kotlin
package com.werewolf.config

import com.werewolf.model.Product
import com.werewolf.repository.ProductRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * The e2e profile runs on H2 with Flyway disabled, so the catalog rows that
 * V19__credits_perks_payments.sql seeds in real databases don't exist.
 * Mirror them here so the lobby buy sheet (and the payment E2E) sees the
 * same products as prod.
 */
@Configuration
@Profile("e2e")
class E2eProductSeeder {

    @Bean
    fun seedProducts(products: ProductRepository) = CommandLineRunner {
        if (products.count() == 0L) {
            products.save(Product(productKey = "starter", name = "入门包", credits = 100, bonusCredits = 0, priceCents = 199, sortOrder = 1))
            products.save(Product(productKey = "value", name = "超值包", credits = 300, bonusCredits = 30, priceCents = 499, sortOrder = 2))
            products.save(Product(productKey = "big", name = "豪华包", credits = 700, bonusCredits = 100, priceCents = 999, sortOrder = 3))
        }
    }
}
```

(Verified: `Product` in `Economy.kt` has constructor params `productKey`, `name`, `credits`, `bonusCredits = 0`, `priceCents`, `currency = "usd"`, `active = true`, `sortOrder = 0` — the named args above all exist.)

- [ ] **Step 2: Verify by booting the e2e profile and hitting the endpoint**

```bash
cd backend && SPRING_PROFILES_ACTIVE=e2e ./gradlew bootRun -q --console=plain &
sleep 25 && curl -s http://localhost:8080/api/payment/products
kill %1
```

Expected: JSON array with 3 products (`starter`, `value`, `big`).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/com/werewolf/config/E2eProductSeeder.kt
git commit -m "feat(e2e): seed credit products under the e2e profile"
```

---

### Task 6: Payment Playwright config + launcher script + npm script

**Files:**
- Create: `frontend/playwright.payment.config.ts`
- Create: `frontend/scripts/run-payment-e2e.sh`
- Modify: `frontend/package.json` (scripts block)

- [ ] **Step 1: Write the Playwright config (complete file)**

```typescript
import {defineConfig} from '@playwright/test'

/**
 * Stripe payment E2E config — REAL Stripe sandbox + real hosted checkout.
 *
 * Deliberately NOT part of the PR-blocking e2e-integration shards: it
 * depends on checkout.stripe.com and stripe-cli webhook forwarding, so it
 * runs nightly / on demand via .github/workflows/payment-e2e.yml.
 *
 * Run locally with: npm run test:e2e:payment
 * (requires stripe-cli and STRIPE_SANDBOX_SECRET in the environment —
 * the npm script wraps scripts/run-payment-e2e.sh which starts the
 * webhook forwarder and exports STRIPE_WEBHOOK_SECRET.)
 */
export default defineConfig({
  testDir: './e2e/payment',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 180_000,
  use: {
    baseURL: 'http://localhost:5174',
    headless: true,
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    trace: 'retain-on-failure',
  },
  reporter: process.env.CI
    ? [['html', {open: 'never'}], ['github']]
    : [['html', {open: 'never'}]],
  webServer: [
    {
      // Profile order matters: dev first (activates DevAuthController so the
      // spec can mint a non-guest buyer), e2e LAST so its H2 datasource and
      // flyway-off settings win over dev's postgres/flyway settings.
      // Dummy GOOGLE_* values keep dev's oauth2 placeholders resolvable
      // (the e2e profile excludes the OAuth2 client auto-config anyway).
      command:
        "bash -c 'cd ../backend && SPRING_PROFILES_ACTIVE=dev,e2e GOOGLE_CLIENT_ID=dummy GOOGLE_CLIENT_SECRET=dummy STRIPE_SECRET_KEY=$STRIPE_SANDBOX_SECRET STRIPE_WEBHOOK_SECRET=$STRIPE_WEBHOOK_SECRET FRONTEND_BASE_URL=http://localhost:5174 ./gradlew bootRun -q --console=plain 2>&1 | tee /tmp/werewolf-payment-backend.log'",
      url: 'http://localhost:8080/api/health',
      timeout: 120_000,
      // Never reuse: a backend booted by the regular real config lacks the
      // Stripe env and would fail the run in a confusing way.
      reuseExistingServer: false,
    },
    {
      command: 'npm run dev -- --port 5174',
      url: 'http://localhost:5174',
      timeout: 30_000,
      reuseExistingServer: true,
      env: {VITE_MOCK: 'false'},
    },
  ],
})
```

- [ ] **Step 2: Write the launcher script**

`frontend/scripts/run-payment-e2e.sh`:

```bash
#!/usr/bin/env bash
# Stripe payment E2E launcher: starts the webhook forwarder, then Playwright.
# Requires: stripe-cli on PATH, STRIPE_SANDBOX_SECRET in the environment.
set -euo pipefail

: "${STRIPE_SANDBOX_SECRET:?export STRIPE_SANDBOX_SECRET (Stripe sandbox secret key) first}"
case "$STRIPE_SANDBOX_SECRET" in
  sk_test_* | rk_test_*) ;;
  *)
    echo "STRIPE_SANDBOX_SECRET is not a test-mode key — refusing to run" >&2
    exit 1
    ;;
esac

# stripe-cli auths via env — no `stripe login` browser dance needed.
export STRIPE_API_KEY="$STRIPE_SANDBOX_SECRET"

# --print-secret returns the same whsec the subsequent `listen` signs with.
STRIPE_WEBHOOK_SECRET="$(stripe listen --print-secret)"
export STRIPE_WEBHOOK_SECRET

stripe listen --forward-to localhost:8080/api/payment/webhook >/tmp/stripe-listen.log 2>&1 &
LISTEN_PID=$!
trap 'kill "$LISTEN_PID" 2>/dev/null || true' EXIT

npx playwright test --config=playwright.payment.config.ts "$@"
```

Then: `chmod +x frontend/scripts/run-payment-e2e.sh`

- [ ] **Step 3: Add the npm script**

In `frontend/package.json` scripts block, after `"test:e2e:integration"`:

```json
    "test:e2e:payment": "bash scripts/run-payment-e2e.sh",
```

- [ ] **Step 4: Commit**

```bash
git add frontend/playwright.payment.config.ts frontend/scripts/run-payment-e2e.sh frontend/package.json
git commit -m "feat(e2e): payment E2E harness — stripe-cli forwarding + dedicated Playwright config"
```

---

### Task 7: The payment E2E spec

**Files:**
- Create: `frontend/e2e/payment/payment-flow.spec.ts`

Consult the `write-real-e2e-test` skill before writing (CI-vs-local pitfalls). Note the documented deviations: Stripe's hosted page is third-party DOM (no testids possible — use Stripe's stable input names), and the run is nightly/manual so checkout.stripe.com latency tolerances are generous.

- [ ] **Step 1: Write the spec (complete file)**

```typescript
import {expect, test} from '@playwright/test'
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
    data: {nickname: 'PaymentBuyer', userId: buyerId},
  })
  expect(login.ok()).toBeTruthy()
  const {token, user} = await login.json()

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
  await page.getByTestId('buy-credits-toggle').click()
  await page.getByTestId('buy-starter').click()

  // 4. Real Stripe hosted checkout. Fresh email each run so Stripe never
  //    routes us into a returning-Link-user OTP screen.
  await page.waitForURL(/checkout\.stripe\.com/, {timeout: 45_000})
  await page.fill('input[name="email"]', `payment-e2e-${Date.now()}@example.com`)
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
  await page.waitForURL(/\/pay\/result\?status=success/, {timeout: 90_000})
  await expect(page.getByTestId('pay-credits')).toHaveText(/\+100/, {timeout: 45_000})

  // 6. Authoritative check: balance from the backend wallet API.
  const wallet = await request.get(`${BACKEND}/api/wallet`, {
    headers: {Authorization: `Bearer ${token}`},
  })
  expect(wallet.ok()).toBeTruthy()
  const body = await wallet.json()
  expect(body.balance).toBe(100)

  // 7. Backend log error scan (six-design-principles).
  const log = fs.readFileSync('/tmp/werewolf-payment-backend.log', 'utf-8')
  expect(log).not.toMatch(/ERROR.*\[payment\]|\[payment\].*(error|cannot deserialize)/i)
})
```

- [ ] **Step 2: Lint/format the new frontend files**

Run: `cd frontend && npx prettier --write e2e/payment/payment-flow.spec.ts playwright.payment.config.ts && npm run lint`
Expected: no errors.

- [ ] **Step 3: Run it locally (requires stripe-cli: `brew install stripe/stripe-cli/stripe`)**

```bash
cd frontend && STRIPE_SANDBOX_SECRET="$(zsh -c 'source ~/.zshrc >/dev/null 2>&1; printf %s "$STRIPE_SANDBOX_SECRET"')" npm run test:e2e:payment
```

Expected: 1 passed (~60–120 s). On failure: read `/tmp/werewolf-payment-backend.log` and `/tmp/stripe-listen.log` FIRST (never hand-wave "flake"); a failure filling Stripe's form means their DOM changed — update the selector, screenshot is in test-results/.

- [ ] **Step 4: Commit**

```bash
git add frontend/e2e/payment/payment-flow.spec.ts
git commit -m "test(e2e): full Stripe sandbox payment flow with real webhook fulfillment"
```

---

### Task 8: payment-e2e.yml workflow (nightly + manual + self-proving on payment-file PRs)

**Files:**
- Create: `.github/workflows/payment-e2e.yml`

`workflow_dispatch` only registers once the file is on the default branch, so a `pull_request` trigger scoped to the payment-E2E files themselves lets this very PR prove the job green (same-repo PRs do receive secrets).

- [ ] **Step 1: Write the workflow (complete file)**

```yaml
name: Payment E2E (Stripe sandbox)

on:
  schedule:
    - cron: '17 16 * * *' # daily, ~02:17 AEST
  workflow_dispatch:
  pull_request:
    paths:
      - '.github/workflows/payment-e2e.yml'
      - 'frontend/e2e/payment/**'
      - 'frontend/playwright.payment.config.ts'
      - 'frontend/scripts/run-payment-e2e.sh'

jobs:
  payment-e2e:
    name: Stripe sandbox payment flow
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: 17
          distribution: temurin

      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('backend/**/*.gradle.kts', 'backend/gradle/wrapper/gradle-wrapper.properties') }}
          restore-keys: gradle-${{ runner.os }}-

      - uses: actions/setup-node@v4
        with:
          node-version: 24
          cache: npm
          cache-dependency-path: frontend/package-lock.json

      - name: Install dependencies
        run: npm ci
        working-directory: frontend

      - name: Cache Playwright browsers
        id: playwright-cache
        uses: actions/cache@v4
        with:
          path: ~/.cache/ms-playwright
          key: playwright-${{ runner.os }}-${{ hashFiles('frontend/package-lock.json') }}
          restore-keys: |
            playwright-${{ runner.os }}-

      - name: Install Playwright browsers
        if: steps.playwright-cache.outputs.cache-hit != 'true'
        run: npx playwright install --with-deps chromium
        working-directory: frontend

      - name: Install Playwright OS dependencies (cache hit)
        if: steps.playwright-cache.outputs.cache-hit == 'true'
        run: npx playwright install-deps chromium
        working-directory: frontend

      - name: Install stripe-cli
        run: |
          curl -fsSL https://packages.stripe.dev/api/security/keypair/stripe-cli-gpg/public | gpg --dearmor | sudo tee /usr/share/keyrings/stripe.gpg >/dev/null
          echo "deb [signed-by=/usr/share/keyrings/stripe.gpg] https://packages.stripe.dev/stripe-cli-debian-local stable main" | sudo tee /etc/apt/sources.list.d/stripe.list
          sudo apt-get update -qq && sudo apt-get install -y stripe
          stripe --version

      - name: Payment E2E
        env:
          STRIPE_SANDBOX_SECRET: ${{ secrets.STRIPE_SANDBOX_SECRET }}
        run: npm run test:e2e:payment
        working-directory: frontend

      - name: Upload artifacts on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: payment-e2e-artifacts
          path: |
            frontend/playwright-report/
            frontend/test-results/
            /tmp/werewolf-payment-backend.log
            /tmp/stripe-listen.log
          retention-days: 7
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/payment-e2e.yml
git commit -m "ci: nightly + manual Stripe sandbox payment E2E workflow"
```

---

### Task 9: Prod key runbook

**Files:**
- Create: `docs/payments-prod-keys.md`

- [ ] **Step 1: Write the runbook (complete file)**

```markdown
# Going live: Stripe production keys

Zero code change — `application.yml` already reads `STRIPE_SECRET_KEY` and
`STRIPE_WEBHOOK_SECRET` from the environment. Do this only after the backend
sandbox tests AND the payment E2E are green locally and in CI.

## 1. Create a restricted live key (never a full secret key on the VM)

Dashboard (live mode, toggle top-right) → Developers → API keys →
"Create restricted key":

| Permission        | Level |
| ----------------- | ----- |
| Checkout Sessions | Write |
| Customers         | Write |
| All others        | None  |

(Add Refunds: Write + Invoicing when those features ship.)
Name it `werewolf-backend-prod`. Copy the `rk_live_…` value once — Stripe
won't show it again.

## 2. Create the live webhook endpoint

Dashboard (live mode) → Developers → Webhooks → "Add endpoint":

- URL: `https://www.youplay123.online/api/payment/webhook`
- Events: `checkout.session.completed`, `checkout.session.expired`
- Copy the signing secret (`whsec_…`).

## 3. Configure the prod VM

Append to the untracked `.env.prod` the compose stack reads (beware its
quirks — see the prod-profile drift postmortem, PR #135):

    STRIPE_SECRET_KEY=rk_live_…
    STRIPE_WEBHOOK_SECRET=whsec_…

Then `docker compose up -d` (backend re-reads env on restart).

## 4. Verify

1. Run the prod smoke test (`prod-smoke-test` skill).
2. Make one real small purchase (starter pack, $1.99) with a real card;
   confirm credits arrive and the order shows COMPLETED.
3. Refund it from the Dashboard (Payments → the charge → Refund) — until the
   in-app refund flow ships, Dashboard refunds do NOT claw back credits.
4. Check Dashboard → Webhooks → the endpoint shows the delivered
   `checkout.session.completed` with HTTP 200.

## Sandbox vs prod recap

| Env   | Key                              | Webhook secret                  |
| ----- | -------------------------------- | ------------------------------- |
| local | `STRIPE_SANDBOX_SECRET` (zshrc)  | stripe-cli `listen` whsec       |
| CI    | repo secret `STRIPE_SANDBOX_SECRET` | stripe-cli `listen` whsec    |
| prod  | `rk_live_…` in `.env.prod`       | Dashboard endpoint `whsec_…`    |
```

- [ ] **Step 2: Commit**

```bash
git add docs/payments-prod-keys.md
git commit -m "docs: production Stripe key runbook"
```

---

### Task 10: End-to-end verification + PR

- [ ] **Step 1: Full local backend suite with the flag ON**

```bash
cd backend && STRIPE_SANDBOX_TESTS_ENABLED=true \
  STRIPE_SANDBOX_SECRET="$(zsh -c 'source ~/.zshrc >/dev/null 2>&1; printf %s "$STRIPE_SANDBOX_SECRET"')" \
  ./gradlew build
```

Expected: BUILD SUCCESSFUL, sandbox tests included (check the test report lists `StripeSandboxIntegrationTest` as executed, not skipped).

- [ ] **Step 2: Frontend checks**

Run: `cd frontend && npm run format:check && npm run lint && npx vue-tsc -b --noEmit`
Expected: clean.

- [ ] **Step 3: Local payment E2E green** (already run in Task 7; re-run if anything changed since)

- [ ] **Step 4: Update the knowledge graph**

Run: `graphify update .`

- [ ] **Step 5: Push and open the PR**

```bash
git push -u origin claude/jolly-allen-2rl6a1
gh pr create --title "Stripe sandbox integration tests + CI wiring" --body "$(cat <<'EOF'
## Summary
- Real Stripe **sandbox** integration tests for the Phase C Checkout flow, gated by the boolean `STRIPE_SANDBOX_TESTS_ENABLED` feature flag (skip cleanly when unset; key comes from shell env locally, from the `STRIPE_SANDBOX_SECRET` repo secret in CI)
- New coverage: real session creation (amount/metadata/URLs verified via `Session.retrieve`), real `checkout.session.expired` event round-trip through `handleEvent`, and the accepted-signature webhook path (previously only the 400 path was tested)
- Full-browser payment E2E (`frontend/e2e/payment/`): dev-login → buy sheet → real checkout.stripe.com → 4242 card → stripe-cli-forwarded webhook → wallet credited. Runs nightly + `workflow_dispatch` via `payment-e2e.yml`, NOT in the PR-blocking shards
- e2e profile now seeds the product catalog (Flyway is off there, so the buy sheet was empty)
- `docs/payments-prod-keys.md`: go-live runbook (restricted live key + webhook endpoint + `.env.prod`)

## Test plan
- [ ] Backend CI job runs (not skips) `StripeSandboxIntegrationTest`
- [ ] `payment-e2e` workflow green on this PR (triggered by its paths filter)
- [ ] Local: `STRIPE_SANDBOX_TESTS_ENABLED=true ./gradlew build` and `npm run test:e2e:payment` both green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 6: Watch CI — backend job must run (not skip) the sandbox tests; payment-e2e workflow must trigger via its `pull_request` paths filter and pass**

```bash
gh pr checks --watch
gh run list --workflow=payment-e2e.yml --limit 3
```

Expected: all green. If the backend job skips the sandbox tests, the secret/env wiring in Task 4 is wrong. If payment-e2e fails, download `payment-e2e-artifacts` and read the two logs before changing anything.

- [ ] **Step 7: Report back with the prod-key runbook** (Task 9 doc) — per the user's request, the "how to create the prod api key" instructions are delivered once local + CI are both green.
