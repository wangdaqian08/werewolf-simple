# Stripe Payment Integration Tests + CI — Design

**Date:** 2026-06-11
**Branch:** `claude/jolly-allen-2rl6a1`
**Status:** approved design, pending implementation

## Goal

The Phase C Stripe Checkout integration (hosted page + webhook fulfillment) currently has
only offline tests: `PaymentWebhookIntegrationTest` replays hand-written event JSON on H2.
Nothing exercises the real Stripe API, so API-version drift, request-shape mistakes in
`createCheckout`, and the accepted-signature webhook path are untested.

Add two layers of integration tests against the **Stripe sandbox**, wire them into CI
securely, and (once both are green locally and in CI) document how to create production
keys. Verified outcome for each part is listed in its section.

## Decisions already pinned (from Q&A)

1. Test depth: **both** backend sandbox tests (PR gate) and full browser E2E (separate job).
2. Browser E2E trigger: **nightly cron + `workflow_dispatch`** — never blocks PRs.
3. Gating: **boolean feature-flag env var**, NOT value-matching on the secret
   (user correction 2026-06-11).
4. Local key sourcing: read `STRIPE_SANDBOX_SECRET` from the shell env (`~/.zshrc`).
   CI key sourcing: GitHub Actions repository secrets (encrypted, log-masked,
   unavailable to fork PRs).
5. `STRIPE_SANDBOX_PUBLIC_API` (publishable key) is unused — hosted Checkout never
   needs it on the frontend.

## Part 1 — Backend sandbox tests (PR-blocking)

New class `backend/src/test/kotlin/com/werewolf/integration/StripeSandboxIntegrationTest.kt`.

### Gating

```kotlin
@EnabledIfEnvironmentVariable(named = "STRIPE_SANDBOX_TESTS_ENABLED", matches = "true")
```

- Plain boolean flag. Machines without the flag (other contributors, fork PRs) skip
  the class cleanly; `./gradlew build` stays green everywhere.
- The flag is also declared as a visible config property in `application.yml`
  (default `false`) and dev config:
  `app.payment.sandbox-tests-enabled: ${STRIPE_SANDBOX_TESTS_ENABLED:false}`
  so the switch is documented in config, not buried in a test annotation.
- Key mapping for the test context only: the test class sets the Spring property
  `app.payment.stripe-secret-key` from the `STRIPE_SANDBOX_SECRET` env var (via
  `@SpringBootTest(properties = [...])` placeholder resolution), test profile H2,
  same as the existing integration tests.
- **Safety guard inside the test** (assertion, not gating): first check fails loudly
  if the configured key is not a test-mode key, so the flag can never be pointed at a
  live key silently.

### Tests

1. **Real checkout session** — `paymentService.createCheckout(userId, productKey)`
   against the sandbox; then `Session.retrieve(order.stripeSessionId)` and assert:
   `url` present, `amountTotal`, `currency`, `metadata.orderNo`/`userId`,
   `clientReferenceId == orderNo`, success/cancel URLs contain the configured
   frontend base URL; order row persists the session id, status CREATED.
2. **Real event round-trip** — `Session.expire(sessionId)` via API, then poll Stripe's
   Events API for the `checkout.session.expired` event referencing that session, and
   feed the *real* event through `paymentService.handleEvent()` → order flips EXPIRED,
   wallet untouched. Catches API-version / JSON-shape drift that hand-written event
   JSON cannot.
3. **Guest + unknown-product rejection** stay covered by the existing offline test.

Also extend the existing offline `PaymentWebhookIntegrationTest` (no sandbox needed):

4. **Signed webhook accepted** — compute a valid `Stripe-Signature` header
   (HMAC-SHA256, `t=<ts>,v1=<sig>` over `"<ts>.<payload>"`) with a known test
   `whsec`, POST to `/api/payment/webhook` → 200 and order fulfilled. Today only the
   bad-signature 400 path is tested.

### CI wiring

In `ci.yml` backend job, on the `Build & test` step add:

```yaml
env:
  STRIPE_SANDBOX_TESTS_ENABLED: 'true'
  STRIPE_SANDBOX_SECRET: ${{ secrets.STRIPE_SANDBOX_SECRET }}
```

Repository secret `STRIPE_SANDBOX_SECRET` set once via `gh secret set` (value sourced
from `~/.zshrc`, never committed). Fork PRs receive no secrets → flag absent → skip.

**Verified outcome:** `STRIPE_SANDBOX_TESTS_ENABLED=true ./gradlew test` green locally
(with zshrc sourced); backend CI job green on this PR with the secret set; a run
*without* the flag shows the class as skipped, not failed.

## Part 2 — Full browser E2E (nightly + manual)

### Layout

- Spec: `frontend/e2e/payment/payment-flow.spec.ts` — its own directory, deliberately
  **outside** `e2e/real/`, so the existing 6-shard `e2e-integration` matrix and
  `playwright.real.config.ts` never pick it up.
- Config: `frontend/playwright.payment.config.ts` (modelled on
  `playwright.real.config.ts`: workers 1, backend+Vite webServers, backend log tee).
- npm script: `test:e2e:payment`.

### Backend boot for this config

`SPRING_PROFILES_ACTIVE=e2e,dev` plus env:

- `STRIPE_SECRET_KEY=$STRIPE_SANDBOX_SECRET`
- `STRIPE_WEBHOOK_SECRET=<from stripe-cli, see below>`
- `FRONTEND_BASE_URL=http://localhost:5174` — so Stripe's success/cancel redirect
  returns to the local app instead of prod.

The `dev` profile activates `DevAuthController` (`@Profile("dev & !prod")`) so the spec
can mint a non-guest `dev:` user — guests are blocked from purchasing.

### Webhook delivery

`stripe listen --forward-to localhost:8080/api/payment/webhook` (stripe-cli) forwards
real sandbox webhooks to the local backend. The signing secret comes from
`stripe listen --print-secret` (stable per account+device) and is exported as
`STRIPE_WEBHOOK_SECRET` before the backend boots. Local prerequisite:
`brew install stripe/stripe-cli/stripe` + `stripe login` (or
`STRIPE_API_KEY=$STRIPE_SANDBOX_SECRET`).

### Spec flow

1. Dev-login a `dev:` user (POST `/api/auth/dev`, store JWT the same way the app does).
2. Lobby → credit shop → click a product (testids on our side per
   `feedback_use_testid_not_text`).
3. Redirect to real `checkout.stripe.com`; fill test card `4242 4242 4242 4242`,
   future expiry, any CVC, email, name. **Documented exception to the testid-only
   rule:** Stripe's hosted page is third-party DOM; use Stripe's stable input names
   (`cardNumber`, `cardExpiry`, `cardCvc`, `billingName`, `email`).
4. Stripe redirects to `/pay/result?status=success&orderNo=…`; `PayResultView` polls
   `GET /api/payment/order/{orderNo}` until the webhook fulfills.
5. Assert `pay-credits` testid shows the purchased credits and the wallet balance API
   reflects the credit. Backend-log error scan per `feedback_e2e_six_design_principles`.

The spec consults the `write-real-e2e-test` skill at implementation time (CI-vs-local
pitfalls), acknowledging that the external checkout.stripe.com dependency is the
accepted risk that motivated the nightly (non-PR-blocking) trigger.

### Workflow `payment-e2e.yml`

- `on: schedule` (daily cron) + `workflow_dispatch`.
- Steps: checkout, Java 17, Node 24, npm ci, Playwright chromium (cached), install
  stripe-cli (apt repo or pinned release binary), start `stripe listen` in background,
  export `STRIPE_WEBHOOK_SECRET`, run `npx playwright test --config=playwright.payment.config.ts`.
- Env: `STRIPE_SANDBOX_SECRET` from repo secrets, `STRIPE_SANDBOX_TESTS_ENABLED` not
  required here (Playwright config is only ever invoked explicitly).
- Artifacts: Playwright HTML report + backend log on failure.

**Verified outcome:** one green local run (`npm run test:e2e:payment`) and one green
manual `workflow_dispatch` run on this branch before declaring done.

## Part 3 — Production key handoff (instructions only, after 1+2 green)

No code changes — `application.yml` already reads `STRIPE_SECRET_KEY` /
`STRIPE_WEBHOOK_SECRET`. Deliverable is a written runbook:

1. Stripe Dashboard → live mode → create a **restricted key** (`rk_live_…`) with only:
   Checkout Sessions (write), Customers (write), Products/Prices (read; only if needed),
   plus Refunds (write) when the refund flow ships. No secret key (`sk_live_`) on the VM.
2. Dashboard → Developers → Webhooks → add endpoint
   `https://www.youplay123.online/api/payment/webhook` with events
   `checkout.session.completed`, `checkout.session.expired` → copy the live `whsec_…`.
3. Set both values in `.env.prod` on the GCP VM (the untracked env file the compose
   stack reads — see `project_prod_profile_dev_prod_drift` memory for the file's
   quirks), `docker compose up -d`, then verify with the prod-smoke-test skill plus a
   real small purchase + Dashboard refund.

## Out of scope

- Refund flow, invoice_creation, order-history page (pinned separately in
  `project_stripe_payment_decisions` memory — implemented after this testing
  foundation is in place).
- Publishable-key plumbing (unused by hosted Checkout).
- Re-balancing the e2e-integration shard map (payment E2E lives outside it).
