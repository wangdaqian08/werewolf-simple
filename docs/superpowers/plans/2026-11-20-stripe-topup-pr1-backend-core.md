# Stripe top-up — PR 1: backend core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A paid Checkout Session is always credited exactly once, even if the webhook is lost. A player cannot create two payable sessions for one intended purchase.

**Architecture:**
- All Stripe calls go through one Spring bean, `StripeGateway`. It returns plain snapshots, so tests can fake it.
- `PaymentFulfillmentService` is the only path from "Stripe says paid" to credits. It always re-reads the session from Stripe. Three callers use it: the webhook, the return-page status check, and a scheduled reconciliation job.
- Database writes live in a separate `PaymentOrderWriter` bean. That keeps `@Transactional` boundaries real (no self-invocation), and keeps Stripe network calls outside DB transactions.

**Tech Stack:** Kotlin, Spring Boot 4, JPA, Flyway (H2 locally, Postgres in CI), stripe-java 33.0.0 (API `2026-05-27.dahlia`), JUnit 5 + MockK.

**Spec:** `docs/superpowers/specs/2026-11-19-stripe-topup-design.md`
- Sections §1, §2, §4 (backend part) and §5 (config part).
- Owner decisions R1–R8 and Q1–Q11.
- Refunds/chargebacks (§3), frontend, payment E2E and runbook are **later PRs**. Do not build them here.

## Global Constraints

- **Never edit a merged migration.** This PR adds `V39__stripe_topup_core.sql`. Keep it H2- and Postgres-compatible, and follow the partial unique index pattern of `ux_ct_game_reward` in V19.
- **No `!!`** in `backend/src/main`; use `?: error("…")`.
- **Tunables:**
  - They live in `application.yml` under the existing `app.payment` block. New `PaymentProperties` fields have **no Kotlin defaults** (project rule).
  - The test profile and the `e2e` profile override only what they need.
- **Values:**
  - session expiry 30 min (Stripe minimum);
  - reconciliation every 10 min;
  - reconcile orders `CREATED` for ≥ 10 min;
  - Stripe sweep look-back 30 days;
  - return-page Stripe check at most once per 5 s per order.
- **Payments switch:** `app.payment.enabled`, env `PAYMENTS_ENABLED`, default **false** (Q3).
  - When true, a blank `stripe-secret-key`, `stripe-webhook-secret` or `frontend-base-url` must **fail startup**.
  - When false, checkout returns 503, the webhook returns 503 (so Stripe keeps retrying), and reconciliation does not run.
- **Alert tag:** every payment anomaly is logged at ERROR with the literal prefix `[payment-alert]` (Q4).
- **Metadata:** `orderNo` and `userId` go on the Checkout Session **and** on `payment_intent_data.metadata`.
- **Stripe Customer:** one per user, stored as `users.stripe_customer_id`. No email prefill (users have no email column).
- **No new Spring test contexts beyond one shared payment test configuration.** Every distinct `@SpringBootTest` context holds a Hikari pool, and CI Postgres has 100 connections (memory `ci-pg-connection-budget`). Fake the gateway with **one** shared `@TestConfiguration`, reused by every payment integration test.
- **Commits:** each ends with the attribution line the session provides.

## Review Focus

1. **Webhook and return page fulfill the same order at the same moment** → exactly one `PURCHASE` ledger row, and the balance goes up once. Pinned in Task 3: a concurrent-fulfill integration test plus the unique index.
2. **`Session.create` throws after the order was committed** → the player gets 503, and the order does not stay 處理中 forever: reconciliation marks it `FAILED`. Pinned in Task 6.
3. **A webhook event sent at a different Stripe API version** (empty deserializer) → still fulfilled, because the handler reads only `data.object.id` from the raw JSON and re-reads the session. Pinned in Task 4.
4. **Stripe created the session but saving `stripe_session_id` failed** → the webhook still finds the order by `client_reference_id` / metadata `orderNo`. Pinned in Task 3.
5. **Payments switched off while Stripe still delivers** → the webhook answers 503 (Stripe retries for days) instead of dropping the event with a 200. Pinned in Task 4.

---

### Task 1: Payments switch, new tunables, fail-fast startup check

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/config/StripeProperties.kt`. Add fields to `PaymentProperties`, all without defaults:
  - `enabled: Boolean`
  - `sessionExpiryMinutes: Long`
  - `reconcileIntervalMs: Long`
  - `reconcileMinAgeMinutes: Long`
  - `sweepLookbackDays: Long`
  - `statusCheckThrottleSeconds: Long`
- Modify: `backend/src/main/resources/application.yml`, `app.payment` block:
  - `enabled: ${PAYMENTS_ENABLED:false}`;
  - the five values above with one-line comments;
  - change `frontend-base-url` to `${FRONTEND_BASE_URL:}`, because the host is now the private one (Q2) and the startup check enforces it when enabled.
- Modify: `backend/src/test/resources/application-test.yml` and the `e2e` profile yml (find which profile files set `app.payment`). Set the values tests need, with `enabled: true` where payment tests run.
- Create: `backend/src/main/kotlin/com/werewolf/config/PaymentConfigValidator.kt`. A `@Component` that checks at startup (`@PostConstruct`) and throws `IllegalStateException` naming the missing key.
- Test: `backend/src/test/kotlin/com/werewolf/unit/config/PaymentConfigValidatorTest.kt`

**Interfaces:**
- Produces: the `PaymentProperties` fields above, used by Tasks 3–6.

- [ ] **Step 1: Write the failing tests.** Construct `PaymentProperties` directly:
  - `enabled=false` with blank keys → no exception;
  - `enabled=true` with a blank `stripeSecretKey` → throws, and the message contains `stripe-secret-key`;
  - same for `stripe-webhook-secret` and `frontend-base-url`;
  - all set → no exception.
- [ ] **Step 2: Run them and see them fail.** `cd backend && ./gradlew test --tests '*PaymentConfigValidatorTest'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the test, then the full `./gradlew test` → PASS.** Existing payment tests must still boot.
- [ ] **Step 5: Commit.** `feat(payment): payments switch and fail-fast startup check`

### Task 2: Migration V39, entities, DB backstop

**Files:**
- Create: `backend/src/main/resources/db/migration/V39__stripe_topup_core.sql`:
  - `users.stripe_customer_id VARCHAR(255)` (unique).
  - `payment_orders`:
    - widen `status` to `VARCHAR(20)`, keeping the CHECK `('CREATED','COMPLETED','EXPIRED','FAILED')` (PR 2 extends it);
    - add `stripe_charge_id VARCHAR(255)`, `receipt_url VARCHAR(1000)`, `session_expires_at TIMESTAMP`, `needs_review BOOLEAN NOT NULL DEFAULT FALSE`, `review_reason VARCHAR(255)`, `updated_at TIMESTAMP`.
  - `payment_events`:
    - add `object_id VARCHAR(255)`, `payment_order_id INT`, `status VARCHAR(20) NOT NULL DEFAULT 'PROCESSED'` with CHECK `('RECEIVED','PROCESSED','FAILED')`, `attempts INT NOT NULL DEFAULT 1`, `last_error VARCHAR(1000)`, `processed_at TIMESTAMP`;
    - existing rows become `PROCESSED`.
  - `CREATE UNIQUE INDEX ux_ct_purchase_order ON credit_transactions (payment_order_id) WHERE type = 'PURCHASE';`
- Modify: `model/Economy.kt` (`PaymentOrder`, `PaymentEvent` fields), `model/User.kt` (`stripeCustomerId`), `model/Enums.kt` (`PaymentEventStatus { RECEIVED, PROCESSED, FAILED }`).
- Modify: `repository/Repositories.kt`:
  - `PaymentOrderRepository`:
    - `findFirstByUserIdAndProductIdAndStatusAndSessionExpiresAtAfterOrderByCreatedAtDesc(userId: String, productId: Int, status: PaymentOrderStatus, now: LocalDateTime): Optional<PaymentOrder>`
    - `findByUserIdAndStatusAndStripeSessionIdIsNotNull(userId: String, status: PaymentOrderStatus): List<PaymentOrder>`
    - `findByStatusAndCreatedAtBefore(status: PaymentOrderStatus, cutoff: LocalDateTime): List<PaymentOrder>`
    - `findByStatusAndReceiptUrlIsNull(status: PaymentOrderStatus): List<PaymentOrder>`
    - a conditional `markStatusIfCreated(orderId: Int, newStatus: PaymentOrderStatus): Int`, next to the existing `markCompletedIfCreated`.
  - `PaymentEventRepository`: keep `tryInsert`; add `findById` use (inherited).
- Test:
  - Modify: `integration/FlywayMigrationsIntegrationTest.kt`
  - Create: `integration/PaymentLedgerBackstopIntegrationTest.kt`

**Interfaces:**
- Produces: the entity fields and repository methods named above.

- [ ] **Step 1: Write the failing tests.**
  - Flyway: the new columns exist, and a `payment_events` row inserted without `status` reads back `PROCESSED`.
  - Backstop:
    - inserting two `credit_transactions` rows with `type='PURCHASE'` and the same `payment_order_id` → the second insert throws `DataIntegrityViolationException`;
    - two `GAME_REWARD` rows with null `payment_order_id` → fine.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then the full suite → PASS.**
- [ ] **Step 5: Commit.** `feat(payment): V39 — order/event audit columns, Stripe customer id, one PURCHASE per order`

### Task 3: StripeGateway and the single fulfillment path

**Files:**
- Create: `backend/src/main/kotlin/com/werewolf/service/StripeGateway.kt`:
  ```kotlin
  data class CheckoutSnapshot(
      val sessionId: String,
      val status: String?,          // "open" | "complete" | "expired"
      val paymentStatus: String?,   // "paid" | "unpaid" | "no_payment_required"
      val orderNo: String?,         // metadata["orderNo"]
      val clientReferenceId: String?,
      val paymentIntentId: String?,
      val chargeId: String?,        // payment_intent.latest_charge.id
      val receiptUrl: String?,      // payment_intent.latest_charge.receipt_url
      val url: String?,             // hosted page URL while open
      val expiresAtEpochSec: Long?,
  )
  data class CheckoutRequest(
      val orderNo: String, val userId: String, val customerId: String,
      val productName: String, val currency: String, val unitAmountCents: Long,
      val successUrl: String, val cancelUrl: String, val expiresAtEpochSec: Long,
  )
  interface StripeGateway {
      fun createCheckoutSession(req: CheckoutRequest, idempotencyKey: String): CheckoutSnapshot
      fun retrieveSession(sessionId: String): CheckoutSnapshot   // expands payment_intent.latest_charge
      fun expireSession(sessionId: String): CheckoutSnapshot     // returns current state if no longer open
      fun listCompletedSessions(createdAfterEpochSec: Long): Sequence<CheckoutSnapshot> // auto-paging
      fun createCustomer(userId: String): String                 // metadata userId; returns customer id
  }
  ```
- Create: `service/StripeGatewayImpl.kt`. A `@Component` wrapping the stripe-java static API.
  - It sets `RequestOptions.setIdempotencyKey` on create.
  - `metadata` and `payment_intent_data.metadata` both carry `orderNo` and `userId`.
  - `customer` is set, plus `expires_at` and `client_reference_id`.
  - No logic beyond mapping. It is exercised by the sandbox test in Task 5.
- Create: `service/PaymentOrderWriter.kt` (`@Component`, every public method `@Transactional`):
  - `applyPaid(snapshot: CheckoutSnapshot): FulfillOutcome`
    - finds the order by session id → `clientReferenceId` → `orderNo`;
    - conditional `CREATED→COMPLETED`;
    - stores `stripeSessionId` (if missing), PaymentIntent, charge, receipt, `completedAt`, `updatedAt`;
    - `walletService.credit(order.userId, order.credits, PURCHASE, paymentOrderId, note = orderNo)`.
  - `findOrder(snapshot: CheckoutSnapshot): PaymentOrder?` (the same session id → `clientReferenceId` → `orderNo` lookup `applyPaid` uses)
  - `applyTerminal(orderId: Int, newStatus: PaymentOrderStatus): Boolean` (`EXPIRED`/`FAILED` via `markStatusIfCreated`; `false` if the order was not `CREATED`).
  - `flagForReview(orderId: Int, reason: String)`
- Create: `service/PaymentFulfillmentService.kt`:
  ```kotlin
  enum class FulfillOutcome { CREDITED, ALREADY_DONE, NOT_PAID, EXPIRED, FAILED, FLAGGED }
  class OrderNotFoundForSessionException(sessionId: String) : RuntimeException(...)
  fun fulfill(sessionId: String, source: String): FulfillOutcome
  fun syncFailed(sessionId: String, source: String): Boolean   // re-read; if Stripe says the payment failed, CREATED → FAILED
  ```
  - It calls `gateway.retrieveSession` **outside** any transaction.
  - `paymentStatus` in (`paid`, `no_payment_required`) → `writer.applyPaid`.
  - `status == "expired"` → `writer.findOrder` then `writer.applyTerminal(id, EXPIRED)`.
  - otherwise → `NOT_PAID`.
  - Order not found → log `[payment-alert] paid session {} has no order (source={})` and throw `OrderNotFoundForSessionException`.
  - Paid but the order is `EXPIRED`/`FAILED` → `flagForReview` + `[payment-alert]`, returns `FLAGGED`.
  - `source` ∈ `webhook | status-check | reconcile`, logged on every outcome.
- Test:
  - Create: `test/kotlin/com/werewolf/support/FakeStripeGateway.kt`. An in-memory map of snapshots: create/expire/list behave like Stripe; call counters.
  - Create: `test/kotlin/com/werewolf/support/PaymentTestConfig.kt`. The one shared `@TestConfiguration` with `@Primary` `FakeStripeGateway`.
  - Create: `integration/PaymentFulfillmentIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 1 properties, Task 2 repositories, `WalletService.credit(userId, amount, type, paymentOrderId, note)`.
- Produces: `StripeGateway`, `CheckoutSnapshot`, `CheckoutRequest`, `PaymentFulfillmentService.fulfill(sessionId, source): FulfillOutcome`, `syncFailed(sessionId, source): Boolean`, `OrderNotFoundForSessionException`, `PaymentOrderWriter`.

- [ ] **Step 1: Write the failing tests** (`PaymentFulfillmentIntegrationTest`, fake gateway):
  - paid → `CREDITED`, order `COMPLETED` with charge id and receipt URL, wallet +credits, exactly one `PURCHASE` row;
  - second call → `ALREADY_DONE`, the wallet is unchanged;
  - unpaid → `NOT_PAID`, nothing changes;
  - expired → `EXPIRED`, order `EXPIRED`;
  - **(Review Focus 4)** order saved without `stripe_session_id`, snapshot carries `clientReferenceId` → `CREDITED`, and the session id is now stored;
  - unknown session → throws `OrderNotFoundForSessionException`, no ledger row;
  - paid snapshot for an `EXPIRED` order → `FLAGGED`, `needs_review=true`, no credit;
  - **(Review Focus 1)** two threads call `fulfill` for the same session at once → exactly one `PURCHASE` row, the balance rose by `credits` once, and one call returns `ALREADY_DONE` (or one hits the unique index and is treated as `ALREADY_DONE`, never a 2nd credit).
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then the full suite → PASS.**
- [ ] **Step 5: Commit.** `feat(payment): StripeGateway and one idempotent fulfillment path`

### Task 4: Webhook as a trigger + event log

**Files:**
- Modify: `service/PaymentService.kt` `handleEvent(event: Event)`. It no longer has its own `@Transactional`; it delegates. Then:
  1. `eventLog.begin(event)`, a `REQUIRES_NEW` method on a new `PaymentEventLog` component (`service/PaymentEventLog.kt`):
     - inserts or loads the `payment_events` row (`RECEIVED`, `object_id` = `data.object.id` from the **raw JSON**, i.e. `event.dataObjectDeserializer.rawJson`);
     - returns `false` if the row is already `PROCESSED`, which means skip;
     - otherwise increments `attempts` on a re-delivery.
  2. Dispatch on `event.type`:
     - `checkout.session.completed`, `checkout.session.async_payment_succeeded` → `fulfillment.fulfill(objectId, "webhook")`;
     - `checkout.session.async_payment_failed` → `fulfillment.syncFailed(objectId, "webhook")`;
     - `checkout.session.expired` → `fulfill` (which marks it `EXPIRED` from Stripe's state);
     - anything else → no-op, recorded as processed.
  3. Success → `eventLog.markProcessed(eventId, orderId)` (`REQUIRES_NEW`). Exception → `eventLog.markFailed(eventId, message)` (`REQUIRES_NEW`), then **rethrow**, so the controller returns 5xx and Stripe retries.
  - Remove `extractSession` and the old private `fulfill`.
- Modify: `controller/PaymentController.kt` webhook: `!props.enabled` → 503 before verifying (**Review Focus 5**). The bad-signature 400 is unchanged.
- Test: Modify `integration/PaymentWebhookIntegrationTest.kt`. Use `PaymentTestConfig`, and keep the signed-HTTP test.

**Interfaces:**
- Consumes: `PaymentFulfillmentService`, `PaymentOrderWriter`, Task 2 event columns.
- Produces: `PaymentEventLog.begin(event): Boolean`, `markProcessed(eventId: String, orderId: Int?)`, `markFailed(eventId: String, error: String)`.

- [ ] **Step 1: Write the failing tests:**
  - completed event (fake says paid) → credited once; replaying the same event id → skipped, the event row is `PROCESSED`, `attempts == 1`;
  - order not found → `handleEvent` throws, the row is `FAILED` with `last_error`; after the order is created and the event re-delivered → credited, row `PROCESSED`, `attempts == 2`;
  - **(Review Focus 3)** event JSON with `"api_version": "2020-08-27"` → still credited (the session id comes from the raw JSON);
  - expired event → order `EXPIRED`;
  - async failed → order `FAILED`;
  - **(Review Focus 5)** `enabled=false` → the HTTP webhook returns 503 and no event row exists. Toggle via a property override in the **same** shared context if possible; otherwise test the controller with a unit test. Do not add a new Spring context.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then the full suite → PASS.**
- [ ] **Step 5: Commit.** `feat(payment): webhook is only a trigger; event log keeps every attempt`

### Task 5: Checkout without double charges

**Files:**
- Modify: `service/PaymentService.kt` `createCheckout(userId, productKey): String`. It is no longer `@Transactional`; writes go through `PaymentOrderWriter`. Steps:
  1. Existing guards: guest → 403. `!props.enabled` or blank key → `PaymentsUnavailableException`. Unknown product → 400.
  2. **Reuse:** find this user's latest `CREATED` order for this product with `session_expires_at > now`; `gateway.retrieveSession`; if `status == "open"` → return its `url`, creating nothing.
  3. **Expire others:** for every other `CREATED` order of the user with a session id → `gateway.expireSession`; if the result is `complete`, call `fulfillment.fulfill(id, "checkout")`; if `expired`, `writer.applyTerminal(orderId, EXPIRED)`.
  4. `customerId = users.stripe_customer_id ?: gateway.createCustomer(userId)` (save it).
  5. `writer.createOrder(...)` commits the order (`CREATED`).
  6. `gateway.createCheckoutSession(req, idempotencyKey = "checkout-$orderNo")`, with `expiresAt = now + sessionExpiryMinutes`.
  7. `writer.attachSession(orderId, sessionId, expiresAt)`.
  8. Return the URL.
  - A `StripeException` / gateway failure → log `[payment]` (not an alert), throw `PaymentsUnavailableException`, so the controller returns 503. The committed order stays `CREATED` without a session; Task 6 marks it `FAILED`.
- Modify: `controller/PaymentController.kt` so it maps `PaymentsUnavailableException` to 503 for gateway failures too (today a `StripeException` becomes a raw 500).
- Modify: `integration/StripeSandboxIntegrationTest.kt` (runs only with `STRIPE_SANDBOX_TESTS_ENABLED=true`). Route it through `StripeGatewayImpl`:
  - create with an idempotency key twice → same session id;
  - retrieve;
  - expire → `expired`;
  - `listCompletedSessions(now-1h)` runs without error;
  - `createCustomer` returns `cus_…`.
- Test: Create `integration/PaymentCheckoutIntegrationTest.kt` (shared fake gateway).

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: `PaymentOrderWriter.createOrder(userId: String, product: Product): PaymentOrder` and `attachSession(orderId: Int, sessionId: String, expiresAt: LocalDateTime)`.

- [ ] **Step 1: Write the failing tests:**
  - first checkout → one order, one fake session, `expires_at` = now + 30 min (± a few seconds), the request carries `customerId` and the idempotency key `checkout-<orderNo>`;
  - same product again while the session is open → the **same URL**, still one order, `createCheckoutSession` called once;
  - different product → the old session is expired via the gateway, its order is `EXPIRED`, and a new order and session exist;
  - the old session turns out paid at expiry time → that order is `COMPLETED` and credited, and a new session is still created;
  - the second purchase reuses the stored customer id (`createCustomer` called once across both);
  - gateway create throws → `PaymentsUnavailableException`, the HTTP response is 503, and the order exists as `CREATED` with no session;
  - guest → 403.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then the full suite → PASS.** If the env flag is set locally, also run the sandbox test.
- [ ] **Step 5: Commit.** `feat(payment): checkout reuses or expires open sessions, idempotency key, Stripe customer, 30-min expiry`

### Task 6: Return-page check and the reconciliation job

**Files:**
- Modify: `service/PaymentService.kt` `orderStatus(userId, orderNo)`. It is no longer `readOnly`: if the order is `CREATED` and has a session id, and the last Stripe check for this order was ≥ `statusCheckThrottleSeconds` ago (an in-memory `ConcurrentHashMap<String, Instant>`), call `fulfillment.fulfill(sessionId, "status-check")`. Swallow and log gateway errors here (the page just keeps polling). The response shape is unchanged.
- Create: `service/PaymentReconciliationJob.kt` (`@Component`):
  - `@Scheduled(fixedDelayString = "\${app.payment.reconcile-interval-ms}")` and `@EventListener(ApplicationReadyEvent::class)` both call `reconcile()` (Q9: once at startup). Both no-op when `!props.enabled`.
  - `fun reconcile(): ReconcileReport`, with `data class ReconcileReport(credited: Int, expired: Int, failed: Int, backfilled: Int, alerts: Int)`. Steps:
    1. `CREATED` orders older than `reconcileMinAgeMinutes`:
       - with a session → `fulfill(…, "reconcile")`;
       - without a session → `writer.applyTerminal(orderId, FAILED)`.
    2. `COMPLETED` orders with no `receipt_url` → re-read and store the charge id / receipt.
    3. `listCompletedSessions(now − sweepLookbackDays)`, keeping only snapshots with an `orderNo`: if the order is missing → `[payment-alert]`; if it is not `COMPLETED` → `fulfill`.
  - Every action is logged with the order number. Each item is handled independently, so one failure never stops the run.
- Test:
  - Create: `integration/PaymentReconciliationIntegrationTest.kt` (calls `reconcile()` directly).
  - Modify: an existing `PaymentService` status test, or add to `PaymentFulfillmentIntegrationTest`.

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: `PaymentReconciliationJob.reconcile(): ReconcileReport`.

- [ ] **Step 1: Write the failing tests:**
  - status check on a `CREATED` order whose fake session is paid → the response says `COMPLETED`, and it is credited;
  - two status checks within 5 s → `retrieveSession` called once;
  - reconcile: a `CREATED` order 11 min old, paid → credited, `report.credited == 1`;
  - reconcile: an expired one → `EXPIRED`;
  - **(Review Focus 2)** reconcile: a `CREATED` order 11 min old with no session → `FAILED`;
  - reconcile: a 9-min-old order → untouched;
  - reconcile: a `COMPLETED` order without a receipt → receipt filled;
  - reconcile sweep: a completed fake session whose order is `CREATED` and only 1 min old → credited by the sweep;
  - a completed fake session with an unknown `orderNo` → `report.alerts == 1` and no crash;
  - `enabled=false` → `reconcile()` does nothing (unit-level, no new context).
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them, then the full `./gradlew test` → PASS.**
- [ ] **Step 5: Commit.** `feat(payment): return-page Stripe check and reconciliation job (startup + every 10 min, 30-day sweep)`

### Task 7: Docs for this PR

**Files:**
- Modify: `docs/database-design.md`. Replace the "Payment — Placeholder" section with the real tables (`payment_orders`, `payment_events` as event log, `products`, the `PURCHASE` backstop index, `users.stripe_customer_id`), short.
- Modify: `docs/payments-prod-keys.md`. Only a short note at the top: `PAYMENTS_ENABLED=true` is now required to turn payments on, and the full rewrite comes in PR 4. Do not restructure it here.
- Include the spec `docs/superpowers/specs/2026-11-19-stripe-topup-design.md` and this plan; both are already committed on the branch.

- [ ] **Step 1: Edit the two docs.**
- [ ] **Step 2: Run `./gradlew test` once more → PASS.**
- [ ] **Step 3: Commit.** `docs(payment): real payment tables and the PAYMENTS_ENABLED switch`
