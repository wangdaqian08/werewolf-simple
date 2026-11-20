# Stripe top-up — PR 2: refunds and chargebacks — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A purchase's credits follow the money. A full refund or a chargeback (inquiries included) takes them back, and the balance may go negative. A won dispute, an inquiry closed without a chargeback, or a failed refund gives them back. A partial refund goes to manual handling. This holds for any order of events, duplicates and lost webhooks.

**Architecture:**
- **Reconcile, don't replay.** A new `PaymentChargeService.reconcileCharge(chargeId, source)` re-reads the charge from Stripe: its refunds, its dispute, and the PaymentIntent metadata.
  - It works out the money state and whether the credits **should** be present.
  - It compares that with a per-order `credits_reversed` flag and writes at most one corrective ledger entry.
  - The writes go through conditional updates in `PaymentOrderWriter`, the same race-safe pattern as `markCompletedIfCreated`.
- Callers:
  - the refund and dispute webhooks;
  - a new reconciliation step that lists recent refunds and disputes from Stripe (the fallback for lost webhooks), and re-checks open disputes.
- PR 1's flow is reused unchanged: the webhook is only a trigger, the event log keeps every attempt, there are no Stripe calls inside transactions, and `[payment-alert]` is reserved for anomalies.

**Tech Stack:** Kotlin, Spring Boot 4, JPA, Flyway (H2 locally, Postgres in CI), stripe-java 33.0.0 (API `2026-05-27.dahlia`), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-11-19-stripe-topup-design.md`
- §3, and owner decisions R1–R5, R7, R8 (Q6 labels are for PR 3).
- PR 1 is merged (#208, `35360c6`). Frontend labels and copy are PR 3; the payment E2E and the runbook are PR 4.

## Global Constraints

- **Migration** `V40__stripe_refunds_disputes.sql`, H2- and Postgres-compatible. Never edit V19/V28/V39. To re-create a CHECK, drop it by name (`payment_orders_status_check`, `credit_transactions_type_check`, `wallets_balance_check`) and add it back, as V28 does.
- **No `!!`** in `backend/src/main`.
- **Tunables** in `application.yml` under `app.payment`, with no Kotlin defaults. **One shared payment test context:** use `@PaymentIntegrationTest` and `FakeStripeGateway`. Test the switched-off behaviour only in unit tests.
- **Money rules (verbatim from the spec):**
  - **R2:** a full refund takes back the purchase's full credits (`payment_orders.credits`). The balance **may go negative**.
  - **R3:** a partial refund triggers no automatic change. It is recorded, alerted, and the order is flagged for manual handling.
  - **R4:** a dispute takes the credits back **as soon as it is opened**, inquiries included. A **won** dispute gives them back; a lost one keeps them taken.
  - **R5:** a negative balance blocks spending. The existing `tryDebit` (`balance >= amount`) must stay as it is.
  - **R7:** credits are present exactly when we keep the money, and a dispute in any state takes precedence.
    - **Absent** while any dispute is open (`warning_needs_response`, `warning_under_review`, `needs_response`, `under_review`), after `lost`, or after a full refund.
    - **Present** again on `won` or `warning_closed`, but only if the charge is not fully refunded.
    - A failed refund does not count as refunded.
    - Once any partial refund has happened, the order stays with manual handling, permanently.
  - **R8:** no admin view. Flagged orders are only stored and alerted.
- **Ledger types** (new): `PURCHASE_REVERSAL` (negative) and `PURCHASE_REINSTATED` (positive), each linked to the order. `note` is the order number plus the reason (`refund` / `dispute` / `dispute won` / `inquiry closed` / `refund failed`). The existing `REFUND` type is unchanged (perks).
- **Order status mirrors the money state, in precedence order:** dispute lost → `DISPUTE_LOST`; dispute open → `DISPUTED`; fully refunded → `REFUNDED`; otherwise `COMPLETED`.
- **Commits** end with the attribution line the session provides.

## Review Focus

1. **Event order and replays.** Dispute won → full refund; full refund → dispute won; dispute created → closed won → closed won again (replayed): each ends with the correct balance and at most the right number of ledger rows. Pinned in Task 3 (matrix test).
2. **Partial, then the rest refunded** → never auto-applied: the order stays `needs_review`, no reversal, alerted once. Pinned in Task 3.
3. **Refund webhook lost** → the reconciliation step finds the refund and reverses the credits. Pinned in Task 5.
4. **A refund event arrives before our order is `COMPLETED`** (fulfillment delayed) → non-2xx, so Stripe retries; no credits move; it is applied after fulfillment. Pinned in Task 4.
5. **Negative balance** → spending is refused, and a later game reward or top-up brings the balance back. Pinned in Task 1.

---

### Task 1: Migration V40, enums, unconditional debit

**Files:**
- Create: `backend/src/main/resources/db/migration/V40__stripe_refunds_disputes.sql`:
  - `payment_orders.status` CHECK re-created with `'REFUNDED','DISPUTED','DISPUTE_LOST'` added;
  - `payment_orders.credits_reversed BOOLEAN NOT NULL DEFAULT FALSE`;
  - `credit_transactions.type` CHECK re-created with `'PURCHASE_REVERSAL','PURCHASE_REINSTATED'` added;
  - `ALTER TABLE wallets DROP CONSTRAINT wallets_balance_check` (R2). Check the real constraint name on Postgres first; V19 declares it inline.
- Modify: `model/Enums.kt` (`PaymentOrderStatus` += `REFUNDED, DISPUTED, DISPUTE_LOST`; `CreditTxType` += `PURCHASE_REVERSAL, PURCHASE_REINSTATED`), and `model/Economy.kt` (`PaymentOrder.creditsReversed`; remove any JPA/Bean-validation `balance >= 0` on `Wallet` if present).
- Modify: `service/WalletService.kt`. Add `fun forceDebit(userId: String, amount: Int, type: CreditTxType, paymentOrderId: Int, note: String): Int`: an unconditional `UPDATE … balance = balance - :amount` plus a ledger row, returning the new balance, which may be negative. Use it only for `PURCHASE_REVERSAL`; normal `debit` keeps its guard.
- Tests:
  - `integration/FlywayMigrationsIntegrationTest.kt` (Postgres-only pattern): the new statuses and types are accepted, and a wallet can hold −50.
  - `integration/WalletServiceIntegrationTest.kt`:
    - `forceDebit` to −50 → balance −50, with a ledger row;
    - **(Review Focus 5)** then `debit(10)` returns null and nothing is written;
    - then `credit(60)` → balance 10, and `debit(10)` succeeds.

**Interfaces:**
- Produces: the enum values, `PaymentOrder.creditsReversed`, and `WalletService.forceDebit(...)`.

- [ ] Write the failing tests → run them (`cd backend && ./gradlew test --tests '*WalletServiceIntegrationTest' --tests '*FlywayMigrationsIntegrationTest'`) → implement → run them, then the full suite → commit `feat(payment): V40 — refund/dispute statuses, reversal ledger types, balance may go negative`.

### Task 2: Gateway — read a charge's money state; list recent refunds and disputes

**Files:**
- Modify: `service/StripeGateway.kt`:
  ```kotlin
  data class RefundInfo(val refundId: String, val amount: Long, val status: String?)   // "succeeded"|"pending"|"failed"|"canceled"|"requires_action"
  data class ChargeSnapshot(
      val chargeId: String,
      val paymentIntentId: String?,
      val orderNo: String?,          // payment_intent.metadata["orderNo"]
      val amount: Long,
      val refunds: List<RefundInfo>,
      val disputeStatus: String?,    // null = never disputed
  )
  // added to StripeGateway:
  fun retrieveCharge(chargeId: String): ChargeSnapshot                      // expand refunds, dispute, payment_intent
  fun listRecentChargeIdsWithRefundsOrDisputes(createdAfterEpochSec: Long): Sequence<String> // Refund.list + Dispute.list, distinct charge ids
  ```
- Modify: `service/StripeGatewayImpl.kt` (stripe-java mapping; `resource_missing` → the existing `StripeResourceMissingException` with a new `CHARGE` kind).
- Modify: `test/.../support/FakeStripeGateway.kt`. Keep charges by id, with helpers to add, refund (full, partial, failed), open a dispute, and set its status. Also list support.
- Modify: `integration/StripeSandboxIntegrationTest.kt`, env-gated, **test mode only**:
  - create a PaymentIntent confirmed with `pm_card_visa`, refund it fully, then `retrieveCharge` shows one succeeded refund equal to the amount;
  - a PaymentIntent confirmed with `pm_card_createDispute` → `retrieveCharge` shows a non-null `disputeStatus` (poll with a bounded wait; skip the assertion with a clear message if Stripe has not created it within the bound);
  - `listRecentChargeIdsWithRefundsOrDisputes` contains both charge ids.

**Interfaces:**
- Produces: `ChargeSnapshot`, `RefundInfo`, `StripeGateway.retrieveCharge`, `StripeGateway.listRecentChargeIdsWithRefundsOrDisputes`, and the fake's helpers.

- [ ] Write the failing tests (fake behaviour unit tests and the sandbox test) → run them → implement → run them, plus the sandbox test if `STRIPE_SANDBOX_TESTS_ENABLED=true` and the key is `sk_test_`/`rk_test_` → commit `feat(payment): gateway reads a charge's refunds and dispute; lists recent refunds and disputes`.

### Task 3: The money rule and the reconcile-don't-replay writer

**Files:**
- Create: `service/ChargeMoneyState.kt`, a pure function and the heart of R7:
  ```kotlin
  enum class DisputePhase { NONE, OPEN, WON, LOST, INQUIRY_CLOSED }
  data class MoneyState(
      val refundedAmount: Long,        // sum of refunds with status succeeded|pending (failed/canceled excluded)
      val fullyRefunded: Boolean,      // refundedAmount >= amount
      val partialRefundSeen: Boolean,  // any non-failed/non-canceled refund with amount < charge amount, or refundedAmount in (0, amount)
      val dispute: DisputePhase,
  ) {
      val creditsShouldBePresent: Boolean   // !(dispute in OPEN, LOST) && !fullyRefunded
      val orderStatus: PaymentOrderStatus   // LOST→DISPUTE_LOST, OPEN→DISPUTED, fullyRefunded→REFUNDED, else COMPLETED
  }
  fun moneyState(c: ChargeSnapshot): MoneyState
  ```
  Dispute mapping: `warning_needs_response | warning_under_review | needs_response | under_review` → `OPEN`; `won` → `WON`; `lost` → `LOST`; `warning_closed` → `INQUIRY_CLOSED`; `null` → `NONE`. An unknown status → `OPEN`, the conservative choice, with a WARN.
- Modify: `service/PaymentOrderWriter.kt`. Add `@Transactional fun applyMoneyState(orderId: Int, state: MoneyState, reason: String): ChargeOutcome`. It takes the order row lock (`SELECT … FOR UPDATE` via a `@Lock(PESSIMISTIC_WRITE)` finder, or conditional updates) and:
  - order not in `COMPLETED/REFUNDED/DISPUTED/DISPUTE_LOST` → returns `NOT_SETTLED` and changes nothing;
  - `state.partialRefundSeen` → `flag(order, "partial refund")` (it already does not re-alert), returns `NEEDS_REVIEW`, and makes no credit change;
  - should be absent, `creditsReversed == false` → conditional update to `true` + `walletService.forceDebit(order.userId, order.credits, PURCHASE_REVERSAL, orderId, "$orderNo $reason")` → `REVERSED`;
  - should be present, `creditsReversed == true` → conditional update to `false` + `walletService.credit(…, PURCHASE_REINSTATED, paymentOrderId = orderId, note)` → `REINSTATED`;
  - otherwise → `UNCHANGED`;
  - in every case, set `status = state.orderStatus` and `updatedAt`.
- Create: `service/PaymentChargeService.kt` (not transactional; reuse PR 1's `checkNoTransaction` guard):
  ```kotlin
  enum class ChargeOutcome { REVERSED, REINSTATED, UNCHANGED, NEEDS_REVIEW, NOT_SETTLED, NOT_OURS }
  class OrderNotFoundForChargeException(chargeId: String) : RuntimeException(...)
  fun reconcileCharge(chargeId: String, source: String): ChargeOutcome
  ```
  - It calls `gateway.retrieveCharge`, finds the order by `stripe_charge_id` → `stripe_payment_intent_id` → `orderNo` metadata, then calls `writer.applyMoneyState`.
  - No order and no `orderNo` → `NOT_OURS` (WARN).
  - Our `orderNo` but no order → `[payment-alert]` + throw.
  - `NOT_SETTLED` → throw `IllegalStateException`, so the webhook retries (Review Focus 4).
  - Every non-`UNCHANGED` outcome is logged with the order number, the money state and the source.
  - `REVERSED` / `REINSTATED` are logged at INFO. `NEEDS_REVIEW` alerts once, through `flag`.
- Tests:
  - Create `unit/service/ChargeMoneyStateTest.kt`: a table test covering every dispute status × {no refund, full, partial, partial + rest, failed refund, pending refund}, with the expected `creditsShouldBePresent`, `orderStatus` and `partialRefundSeen`.
  - Create `integration/PaymentChargeIntegrationTest.kt`, fake gateway, starting from a `COMPLETED` order of 330 credits:
    - full refund → balance −330 + start, status `REFUNDED`, one `PURCHASE_REVERSAL`; the replay is `UNCHANGED`;
    - dispute `warning_needs_response` → reversed, `DISPUTED`; → `warning_closed` → reinstated, `COMPLETED`;
    - dispute `needs_response` → reversed; → `won` → reinstated; → `won` replayed → `UNCHANGED`;
    - dispute `lost` → reversed, `DISPUTE_LOST`;
    - **(Review Focus 1)** dispute won, then full refund → net reversed once, status `REFUNDED`. Full refund, then dispute opened and won → stays reversed (fully refunded wins), status `REFUNDED`;
    - refund pending → reversed; refund failed → reinstated;
    - **(Review Focus 2)** partial refund → `NEEDS_REVIEW`, no ledger row, one alert; then the rest refunded → still `NEEDS_REVIEW`, no ledger row, no second alert;
    - order still `CREATED` → throws, nothing changes;
    - unknown charge without `orderNo` → `NOT_OURS`; with our `orderNo` → throws + alert;
    - two threads `reconcileCharge` the same full refund at once → exactly one `PURCHASE_REVERSAL`.

- [ ] Write the failing tests → run them → implement → run them, then the full suite → commit `feat(payment): credits follow the money — reconcile a charge's refunds and dispute (R2–R4, R7)`.

### Task 4: Webhooks for refunds and disputes

**Files:**
- Modify: `service/PaymentService.kt` `handleEvent`. Add dispatch; the charge id comes from the **raw JSON**:
  - `charge.refunded` → object is a Charge → `id`;
  - `charge.refund.updated` → object is a Refund → `charge`;
  - `charge.dispute.created | charge.dispute.updated | charge.dispute.closed` → object is a Dispute → `charge`.

  Then `chargeService.reconcileCharge(chargeId, "webhook")`; the event log's `payment_order_id` = that order. Add a raw-JSON field helper next to `rawObjectId` in `PaymentEventLog.kt`: `rawObjectField(event, "charge")`.
- Test: Modify `integration/PaymentWebhookIntegrationTest.kt`:
  - each of the 5 event types moves credits as expected (fake state set first), and the row is `PROCESSED`;
  - **(Review Focus 4)** `charge.refunded` for an order still `CREATED` → 5xx, row `FAILED`, no ledger change; after fulfillment, the redelivery → reversed, row `PROCESSED`, `attempts == 2`;
  - a dispute event for a foreign charge → 200, `PROCESSED`, no alert.

- [ ] Write the failing tests → run them → implement → run them, then the full suite → commit `feat(payment): refund and dispute webhooks reconcile the charge`.

### Task 5: Reconciliation fallback for refunds and disputes + the PR 1 residual

**Files:**
- Modify: `service/PaymentReconciliationJob.kt`:
  - **Residual from PR 1:** run `sweepStripe` **before** `settleStaleOrders`. A session paid in its last minutes is then credited before an order without a session could be failed.
  - New step `reconcileRefundsAndDisputes`:
    - for every charge id from `gateway.listRecentChargeIdsWithRefundsOrDisputes(now − sweepLookbackDays)`, plus every order currently `DISPUTED` (its charge id) → `chargeService.reconcileCharge(id, "reconcile")`;
    - errors are handled per item;
    - "our orderNo but no order" is alerted once per process (the same seen-set approach as the session sweep);
    - `NOT_SETTLED` is skipped silently (the order is credited first by the earlier steps; the next run applies the refund).
  - `ReconcileReport` += `reversed: Int, reinstated: Int`.
- Test: Modify `integration/PaymentReconciliationIntegrationTest.kt`:
  - **(Review Focus 3)** a `COMPLETED` order whose fake charge was fully refunded (no webhook) → after `reconcile()` it is reversed, `report.reversed == 1`; a second run → 0;
  - a `DISPUTED` order whose dispute became `won` → reinstated;
  - **step-order test:** a session-less `CREATED` order aged 42 min whose orderNo is on a completed session in the fake sweep → credited (not `FAILED`/`FLAGGED`);
  - a foreign refunded charge → ignored, no alert.

- [ ] Write the failing tests → run them → implement → run them, then the full suite → commit `feat(payment): reconciliation catches lost refund/dispute webhooks; sweep before settling stale orders`.

### Task 6: Docs

**Files:**
- Modify: `docs/database-design.md` (payment section): the new statuses, `credits_reversed`, the new ledger types, and that a negative balance is allowed.
- Modify: `docs/payments-prod-keys.md`. Only extend the short top note:
  - the webhook endpoint must also send `charge.refunded`, `charge.refund.updated`, `charge.dispute.created`, `charge.dispute.updated` and `charge.dispute.closed`;
  - refunds are done in the Dashboard as **full refunds only**;
  - the restricted key needs read access to Charges, Refunds and Disputes.

  The full rewrite stays in PR 4.

- [ ] Edit → run `./gradlew test` once → commit `docs(payment): refunds and chargebacks follow the money`.
