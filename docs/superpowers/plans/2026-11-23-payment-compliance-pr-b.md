# Payment compliance PR B (self-service refund) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A player can refund an eligible top-up from 充值記錄 with one confirmed tap. The app refunds the full charge through Stripe, takes the credits back, and blocks entering games while the refund is in progress.

**Ruling (my call; easy to change):** the lock is checked on create and join only, not on ready and start as spec §7 lists. A locked player cannot be in a room, because the request needs "in no room" and the lock blocks entering one. So the ready and start checks would never fire.

**Architecture:** Eligibility is computed by the backend and sent with each order. A new `SelfRefundService` locks the order row, marks the request (which locks the account), creates a Stripe refund with an idempotency key, then runs the existing charge reconcile, so credits come back in the same request. The lock is only checked where a player enters a room: create and join. The refund rule already rules out being in a room, and ready/start happen inside one.

**Tech Stack:** Spring Boot 4 / Kotlin, stripe-java 33, Flyway; Vue 3 + TS + vitest; Playwright payment E2E on the Stripe sandbox.

**Spec:** `docs/superpowers/specs/2026-11-22-payment-compliance-design.md` — D1, D2, D10–D13 and §7 (plus the §8 test list for the refund).

**Branch:** `feat/self-service-refund`, from `feat/payment-compliance` (PR #212). Rebase onto main once #212 merges.

## Global Constraints

- **Eligible** (all must hold; D10):
  - the order is `COMPLETED` and not `needsReview`;
  - `now − completed_at ≤ self-refund-days` (14 × 24 h);
  - no unreturned `PERK_SPEND` after `completed_at` (D2: a `PERK_SPEND` with a `REFUND` row of the same `perk_activation_id` does not count);
  - the player is in no unfinished room (`RoomRepository.findActiveRoomsForUser` empty);
  - fewer than `self-refund-max-per-window` (1) self refunds requested on the account in the last `self-refund-window-days` (30).
- Tunables in `application.yml` under `app.payment` with a one-line comment each, no Kotlin defaults: `self-refund-days: 14`, `self-refund-window-days: 30`, `self-refund-max-per-window: 1`.
- **Lock** (D11): the account is locked while one of its orders has `self_refund_requested_at` set and `self_refund_settled_at` null.
  - The charge reconcile path (webhook, job, or the request itself) settles it from the refund's status at Stripe.
  - `succeeded` → set `self_refund_settled_at`, which unlocks.
  - `pending` → stay locked. The credits are already reversed, because `moneyState` counts pending as returned.
  - `failed` or `canceled` → clear `self_refund_requested_at` and `self_refund_id` (unlock), and log `[payment-alert] self-service refund <refundId> of order <orderNo> failed`. The existing reinstate path gives the credits back, and the order can then be refunded again.
- Stripe refund: full amount on the order's PaymentIntent, `reason = requested_by_customer`, metadata `orderNo` and `userId`, idempotency key `self-refund-<orderNo>`.
- A Stripe error before the refund exists leaves no lock (the request mark is cleared) and answers `503 {code: "REFUND_UNAVAILABLE"}`.
- Lock rejection on create/join: `409 {error: "<English>", code: "REFUND_PENDING"}`. UI text 退款處理中，完成前無法進入遊戲.
- Ineligible reasons sent to the UI: `SPENT`, `IN_ROOM`, `LIMIT`, plus `PENDING` while this order's own refund is requested and not settled (the UI shows 退款處理中). After 14 days, or for non-`COMPLETED` or flagged orders, the order carries no `refund` field.
- Dashboard refunds keep working unchanged and never lock (D13). No admin list.
- Project rules: no `!!` in `src/main`; never edit V1–V42 (add V43); E2E has no fixed sleeps and uses testids for our DOM.

## Review Focus

1. Double tap or two tabs → exactly one Stripe refund and one credit reversal (Task 4 test `two concurrent requests refund once`).
2. A player who joins a waiting room in another tab after the list loaded → `POST` answers 409 `IN_ROOM`; the stale button cannot bypass the rule (Task 4 test `a stale button cannot refund while the player is in a room`).
3. A Stripe outage at refund time → no lock, a clear retryable error, and a second tap works (Task 4 test `a Stripe error leaves no lock and a retry refunds`).
4. A refund that later fails → the account unlocks, the credits come back, an alert is logged, and the order is eligible again (Task 4 test `a failed self refund unlocks, gives credits back and alerts`).
5. Someone else's order, or an unknown order number → 404, nothing changes (Task 4 test `another player's order is 404`).

---

### Task 1: Schema, settings, order fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V43__self_refund.sql`
- Modify: `backend/src/main/kotlin/com/werewolf/model/Economy.kt` (`PaymentOrder`), `config/StripeProperties.kt` (`PaymentProperties`), `backend/src/main/resources/application.yml`, the test fixtures that construct `PaymentProperties` (grep `PaymentProperties(`)
- Test: `FlywayMigrationsIntegrationTest.kt`

**Interfaces:**
- Produces:
  - `PaymentOrder.selfRefundRequestedAt: LocalDateTime?`, `PaymentOrder.selfRefundId: String?` and `PaymentOrder.selfRefundSettledAt: LocalDateTime?`;
  - `PaymentProperties.selfRefundDays: Long`, `selfRefundWindowDays: Long`, `selfRefundMaxPerWindow: Int`;
  - `PaymentOrderRepository.existsByUserIdAndSelfRefundRequestedAtIsNotNullAndSelfRefundSettledAtIsNull(userId: String): Boolean`;
  - `PaymentOrderRepository.countByUserIdAndSelfRefundRequestedAtAfter(userId: String, after: LocalDateTime): Long`.

- [ ] **Step 1: Failing Flyway test** `V43 adds the self-refund columns`: after migrate, insert a `payment_orders` row with `self_refund_requested_at`, `self_refund_id` and `self_refund_settled_at` set and read all three back.
- [ ] **Step 2: Run on a throwaway Postgres 16 → FAIL** (column missing).
- [ ] **Step 3:** V43 = `ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS self_refund_requested_at TIMESTAMP, ADD COLUMN IF NOT EXISTS self_refund_id VARCHAR(255), ADD COLUMN IF NOT EXISTS self_refund_settled_at TIMESTAMP;`. Add the entity fields, the repository methods, the three properties and the yml keys (with comments).
- [ ] **Step 4: Run** the Flyway test (Postgres) and `./gradlew test` (H2) → PASS.
- [ ] **Step 5: Commit** `feat(payment): V43 self-refund columns and settings`

---

### Task 2: Gateway can create a refund

**Files:**
- Modify: `service/StripeGateway.kt`, `service/StripeGatewayImpl.kt`, `backend/src/test/kotlin/com/werewolf/support/FakeStripeGateway.kt`
- Test: `StripeSandboxIntegrationTest.kt`

**Interfaces:**
- Produces:
  - `StripeGateway.createRefund(paymentIntentId: String, orderNo: String, userId: String, idempotencyKey: String): RefundInfo`;
  - fake: `failCreateRefund: Boolean`, `createRefundCalls: Int`, `refundStatusOnCreate: String = "succeeded"`.
  - The fake finds the charge by its PaymentIntent and appends a full-amount refund, so `retrieveCharge` shows it. Repeating the same idempotency key returns the same refund.

- [ ] **Step 1: Failing sandbox test** `createRefund refunds the whole charge once, with the reason and metadata`:
  - pay a PaymentIntent with the test card, call `createRefund` twice with the same key;
  - assert the same refund id both times, `amount == charge.amount`, status `succeeded`;
  - `Refund.retrieve(id)` has `reason == "requested_by_customer"` and `metadata["orderNo"]`.
- [ ] **Step 2: Run** with `STRIPE_SANDBOX_TESTS_ENABLED=true` → FAIL (compile).
- [ ] **Step 3: Implement** with `RefundCreateParams` (`setPaymentIntent`, `setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)`, `putMetadata`) and `RequestOptions` with the idempotency key. Implement the fake.
- [ ] **Step 4: Run** the sandbox test (expect 8/8) and the H2 suite → PASS.
- [ ] **Step 5: Commit** `feat(payment): gateway creates a full refund with reason and metadata`

---

### Task 3: Each order says whether it can be refunded

**Files:**
- Create: `service/SelfRefundPolicy.kt`
- Modify: `service/PaymentService.kt` (`listOrders`, `orderStatus`)
- Test: create `integration/SelfRefundPolicyIntegrationTest.kt`

**Interfaces:**
- Produces:
  - `enum class SelfRefundBlock { SPENT, IN_ROOM, LIMIT, PENDING }`;
  - `data class SelfRefundView(val eligible: Boolean, val reason: SelfRefundBlock?)`;
  - `SelfRefundPolicy.viewsFor(userId: String, orders: List<PaymentOrder>): Map<Int, SelfRefundView>`, keyed by order id, containing only orders inside the window, using one query each for spends, active rooms and the 30-day count;
  - each order map in `listOrders` / `orderStatus` gains `"refund" to mapOf("eligible" to …, "reason" to …)` when a view exists.
- Reason precedence: `PENDING` > `SPENT` > `LIMIT` > `IN_ROOM`.

- [ ] **Step 1: Failing tests** (H2, seeded data):
  - fresh order → eligible;
  - a later `PERK_SPEND` → `SPENT`;
  - a `PERK_SPEND` with a matching `REFUND` → eligible;
  - a spend before `completed_at` → eligible;
  - the player in a WAITING room → `IN_ROOM`;
  - another order self-refunded 10 days ago → `LIMIT`; 31 days ago → eligible;
  - this order requested and not settled → `PENDING` (even after it turned `REFUNDED` from a pending refund);
  - `completed_at` 15 days ago, or status `REFUNDED` with the self refund settled, or `needsReview` → no view;
  - the window and limit read the properties (set `self-refund-days` to 1 in one test via a constructed policy).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement**; wire it into both endpoints.
- [ ] **Step 4: Run** the new class plus `PaymentControllerTest` and `PaymentFulfillmentIntegrationTest` (the response keys changed) → PASS.
- [ ] **Step 5: Commit** `feat(payment): orders carry their self-refund eligibility`

---

### Task 4: Request a refund

**Files:**
- Create: `service/SelfRefundService.kt`
- Modify:
  - `controller/PaymentController.kt` (`POST /api/payment/order/{orderNo}/refund`);
  - `service/PaymentChargeService.kt` (alert only when `source == "reconcile"`; after applying the money state, call `writer.settleSelfRefund(orderId, charge.refunds)`);
  - `service/PaymentOrderWriter.kt` (a locked `markSelfRefund(orderId)`, `clearSelfRefund(orderId)`, `storeSelfRefundId(orderId, refundId)`, and `settleSelfRefund(orderId: Int, refunds: List<RefundInfo>)`, which applies the lock rules in Global Constraints).
- Test: create `integration/SelfRefundIntegrationTest.kt`

**Interfaces:**
- Consumes: Task 1 fields and repo, Task 2 `createRefund`, Task 3 `SelfRefundPolicy`.
- Produces: `SelfRefundService.request(userId: String, orderNo: String): Map<String, Any>`, returning `{status, refundPending}`. It throws `SelfRefundNotAllowedException(reason: SelfRefundBlock)`, `OrderNotFoundException` and `StripeException`.
- Controller replies:
  - 200 with the map;
  - 404 when the order is not found or not the caller's;
  - 409 `{code: reason}` when not allowed;
  - 503 `{code: "REFUND_UNAVAILABLE"}` on a Stripe error.
- Flow:
  1. In one transaction, lock the order row, re-check eligibility with the policy (an order already `PENDING` with no `selfRefundId` may resume), and set `selfRefundRequestedAt` if unset.
  2. Outside any transaction, `createRefund`; on `StripeException`, clear the mark and rethrow.
  3. Store `selfRefundId`.
  4. `chargeService.reconcile(chargeId, "self-service")`.
  5. Log `[payment] self-service refund order=… user=… outcome=…`.

- [ ] **Step 1: Failing tests** (fake gateway):
  - `an eligible order is refunded, credits removed and the account unlocked in one call`: status `REFUNDED`, one `PURCHASE_REVERSAL`, `createRefundCalls == 1`, no `[payment-alert]`.
  - Each ineligible reason → 409 with that code, nothing changes.
  - `a stale button cannot refund while the player is in a room`.
  - `two concurrent requests refund once`: two threads, then `createRefundCalls` ≤ 2 with one refund id, and one reversal row.
  - `a Stripe error leaves no lock and a retry refunds`.
  - `a pending refund reverses the credits but keeps the account locked; when it succeeds it unlocks`: the fake creates the refund `pending`, then flips it to `succeeded` and reconcile runs.
  - `a failed self refund unlocks, gives credits back and alerts`.
  - `another player's order is 404`.
  - `a guest cannot refund` (403, as for checkout).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the new class plus every `Payment*IntegrationTest` (H2 and Postgres) → PASS.
- [ ] **Step 5: Commit** `feat(payment): self-service refund request`

---

### Task 5: A refund in progress blocks entering games

**Files:**
- Modify: `service/RoomService.kt` (`createRoom`, `joinRoom`: `throw RefundPendingException` when locked), `controller/RoomController.kt` (409 `{error, code: "REFUND_PENDING"}`)
- Test: `RoomControllerTest` or the room integration test the repo already uses for create/join

**Interfaces:**
- Consumes: `existsByUserIdAndSelfRefundRequestedAtIsNotNullAndSelfRefundSettledAtIsNull(userId)`.
- Produces: `class RefundPendingException : RuntimeException`.

- [ ] **Step 1: Failing tests:** a locked user gets 409 `REFUND_PENDING` on create and on join; after `self_refund_settled_at` is set both succeed; an unlocked user is unaffected.
- [ ] **Step 2: Run → FAIL.** **Step 3: Implement.** **Step 4: Run** the room tests and the H2 suite → PASS.
- [ ] **Step 5: Commit** `feat(room): a self refund in progress blocks creating and joining rooms`

---

### Task 6: Refund button, reasons, confirm, lock message

**Files:**
- Modify:
  - `frontend/src/types/index.ts` (`PaymentOrderSummary.refund?: { eligible: boolean; reason?: 'SPENT' | 'IN_ROOM' | 'LIMIT' | 'PENDING' }`);
  - `services/paymentService.ts` (`requestRefund(orderNo): Promise<{ status: string; refundPending: boolean }>`);
  - `views/AccountView.vue`, `views/LobbyView.vue` (join error), `views/CreateRoomView.vue` (create error);
  - `i18n/locales/{zh,en}.ts`, `mocks/`.
- Test: `AccountView.test.ts`, `LobbyView.test.ts` / `CreateRoomView.test.ts`, `paymentService.test.ts`

**Interfaces:**
- testids: `order-refund-btn`, `order-refund-reason`, `refund-confirm`, `refund-confirm-amount`, `refund-confirm-yes`, `refund-confirm-no`, `refund-error`.

- [ ] **Step 1: Failing tests:**
  - an eligible order shows the button; `SPENT` / `IN_ROOM` / `LIMIT` / `PENDING` show 已消費 / 請先離開房間 / 30 天內已退過一次，請發郵件聯繫 wangdaqian08@gmail.com / 退款處理中;
  - no `refund` field → neither shows;
  - the confirm shows the amount (`formatMoney`), the credits, the lock sentence and 5–10 個工作日;
  - yes → `requestRefund` called once, then orders and wallet reload;
  - a 503 shows 退款失敗，請稍後再試;
  - a 409 reloads the orders;
  - join and create with `code: REFUND_PENDING` show 退款處理中，完成前無法進入遊戲;
  - the service posts to `/payment/order/WW1/refund`.
- [ ] **Step 2: Run → FAIL.** **Step 3: Implement** (an inline confirm panel inside the order row; no new dialog component). **Step 4:** `npx vitest run`, `npx vue-tsc -b`, `npm run lint`, prettier, mocked `e2e/account.spec.ts` → PASS.
- [ ] **Step 5: Commit** `feat(payment): refund button, reasons and confirm on 充值記錄; lock message on join/create`

---

### Task 7: Policy page, runbook, E2E

**Files:**
- Modify: `frontend/public/refunds.html`, `frontend/src/__tests__/policyPages.test.ts`, `docs/payments-prod-keys.md`, `frontend/e2e/payment/payment-flow.spec.ts`

- [ ] **Step 1: Failing page test:** refunds.html matches `/Request refund/`, `/once every 30 days/` and `/cannot (create|join).{0,40}game/i`, and still matches the ACL sentence.
- [ ] **Step 2: Edit refunds.html.** "How to ask" becomes: the 申請退款 (Request refund) button in 我的賬戶 → 充值記錄; refunds start at once and the account cannot create or join a game until the refund goes through; at most once every 30 days; past the limit, or for problems on our side, email with the order number.
- [ ] **Step 3: Runbook:**
  - the restricted key table gets **Refunds: Write**, explaining that the app now creates refunds;
  - a "Self-service refunds" section covers the log line, the Stripe refund (reason `requested_by_customer`, metadata `orderNo`/`userId`), when to use a Dashboard refund instead, and the `self refund failed` alert row;
  - the step-5 rehearsal adds: buy, press 申請退款, confirm; the order reads 已退款, credits are gone, and you can join a game again.
- [ ] **Step 4: E2E test** `the player refunds a purchase from 充值記錄`:
  - buy (helper), open the account page, click `order-refund-btn` then `refund-confirm-yes`;
  - `order-status` reads 已退款; the balance is 0;
  - the Stripe refund for the PaymentIntent has `reason == 'requested_by_customer'`.
- [ ] **Step 5: Run** the page test, `npm run test:e2e:payment` (phase 1 1/1, phase 2 4/4), and lint → PASS.
- [ ] **Step 6: Commit** `docs+test(payment): refund policy and runbook describe the button; E2E self refund`

## After all tasks

- **Full checks:**
  - backend `./gradlew test` on H2, plus the payment and Flyway classes on a throwaway Postgres;
  - sandbox tests;
  - frontend vitest, `vue-tsc -b`, lint, the no-sleeps lint;
  - payment E2E.
- Then a final whole-branch review, push, and a PR stacked on #212 (rebase after #212 merges). Payments stay off.
