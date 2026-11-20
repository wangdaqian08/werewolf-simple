# Stripe credit top-up (充值): design

- Date: 2026-11-19. Status: **approved by the owner 2026-11-20 (R1–R8, detail review Q1–Q11).**
- Compliance (terms, refund-policy text, tax, invoices as legal documents) is a **separate PR**.
- Builds on what is already on main (Phase C hosted Checkout, PR #137 sandbox tests, #138 order history,
  #172 async payments). Prior decisions: memory `stripe-payment-decisions` (2026-06-11); this doc replaces
  its refund and invoice parts where they differ (see "Changed from 2026-06-11").

## Why

A player taps 充值, pays, and sees their 金币 go up. The code for this mostly exists, but it has never run in
production and only partly in the sandbox. A code review (2026-11-19) found it can take money without giving
credits and can charge a player twice, and it ignores refunds and chargebacks. Real money makes every one of
those unacceptable.

## Owner principles (2026-11-19)

1. **Use Stripe's native features; do not reinvent.** Hosted Checkout, Stripe idempotency keys, session
   expiry, Stripe Customers, metadata, Stripe receipts, Stripe webhooks and the Stripe API as the source of
   truth. No custom card forms, no home-made retry queues where Stripe already retries.
2. **The app has fallbacks.** No single missed webhook, crash or misconfiguration may leave a paid order
   uncredited.
3. **Records are traceable.** Every top-up, credit, refund and chargeback can be followed from the player's
   screen to our database to the Stripe Dashboard and back.

## Goals

- Tap 充值 → pay on Stripe → credits arrive, normally within seconds of returning to the app.
- **Never charged twice by accident** for one intended purchase.
- **Never charged without the credits**, even if the webhook is lost.
- **Never credited twice** for one payment.
- A full refund or a chargeback takes the purchase's credits back; winning a chargeback gives them back.
- Players can see every top-up with its status and a Stripe receipt; the owner can trace any of them.

## Non-goals

- Compliance work (separate PR).
- Partial refunds (not supported — see R3).
- An in-app refund request flow (refunds are done in the Stripe Dashboard).
- New currencies or localized prices (USD stays, as now).
- Stripe Invoicing (`invoice_creation`), subscriptions, embedded Checkout or Payment Element.

## Anti-goals (a failure even if it works)

- A payment that leaves no trace in our database or in the logs.
- A failure that is only visible because a player complains.
- Card data touching our servers (hosted Checkout keeps it with Stripe).

## Owner decisions (2026-11-19)

| # | Decision |
|---|---|
| R1 | **Refunds happen in the Stripe Dashboard only.** The app reacts to Stripe's refund events. |
| R2 | **A full refund takes back the purchase's full credits**, whatever the player has spent. The balance **may go negative**. |
| R3 | **Full refunds only.** A partial refund (possible by mistake in the Dashboard) is **not** applied automatically: it is recorded, alerted, and the order is flagged for manual handling (my call). |
| R4 | **A chargeback (dispute) takes back the full credits as soon as it is opened.** If the dispute is **won**, the credits are given back; if lost, they stay taken. |
| R5 | A negative balance blocks spending (the existing debit already requires `balance >= amount`) until wins or a top-up bring it back above the price. |
| R7 | **Combinations follow one rule (2026-11-20):** the purchase's credits are present exactly when we keep the money — **a dispute in any state takes precedence**. Credits are taken back while any dispute is open (an *inquiry* included, even before Stripe withdraws funds), after a lost dispute, and after a full refund. They are present again when the dispute is won or the inquiry closes without becoming a chargeback **and** the charge is not fully refunded; a failed refund (money not returned) also restores them. Once any partial refund happens, the order stays with manual handling. |
| R8 | **No admin view for flagged orders (2026-11-20).** Orders needing manual handling are flagged in the database and alerted in the logs only; the owner handles them in the Stripe Dashboard / database. No admin list, no admin debit. |
| R6 | Agreed recommendations: idempotency keys, reuse/expire open sessions, 30-minute session expiry, three-layer fulfillment, Stripe receipts instead of invoices, a Stripe Customer per user, an event log, startup config checks, runbook and test fixes (all detailed below). |

## Owner decisions — detail review (2026-11-20)

| # | Decision |
|---|---|
| Q1 | First release: **cards, Apple Pay, Google Pay** (Dashboard toggles; all confirm immediately). Link stays at Stripe's default. Alipay / WeChat Pay later, only if players need them. |
| Q2 | Production host is **`https://private.youplay123.online`** (home PC behind the Cloudflare Tunnel `werewolf-home`): `FRONTEND_BASE_URL` and the webhook endpoint `…/api/payment/webhook` use it. |
| Q3 | Payments ship **switched off**; the owner turns them on after the runbook passes. |
| Q4 | Alerts = the payment log tag + Stripe's own "webhook endpoint failing" emails. No new notification channel. The runbook says how to grep the tag. |
| Q5 | Session expiry 30 min; reconciliation every 10 min for orders still `CREATED` after 10 min; the return page asks Stripe at most once per 5 s per order; after 30 s the return page says 「正在確認付款，你可以先離開；金幣到賬後會顯示在餘額和充值記錄裡」. All values in `application.yml`. |
| Q6 | Player-facing statuses: 處理中 / 已完成 / 已過期 (未扣款) / 失敗 (未扣款) / 已退款 / 拒付處理中 / 已拒付. A negative balance is shown as is (e.g. −120) with 「餘額不足，暫時無法使用金幣」. Reversal ledger rows read 充值退回 / 拒付扣回 and carry the order number. |
| Q7 | Four PRs, in order: **(1) backend core** (no double charge, three-layer fulfillment, event log, DB backstops, fail-fast config; this doc ships with it) → **(2) refunds and chargebacks** (R7, negative balance) → **(3) frontend** (paged records, receipts, statuses, return-page fallback, negative balance) → **(4) tests and runbook** (fix nightly payment E2E, refund/dispute E2E, runbook, `.env.prod.example`). Each PR ships on its own. |
| Q8 | The runbook makes sure Cloudflare (Access, Bot Fight Mode, WAF) does not block `/api/payment/webhook`, and verifies with the Dashboard's "send test webhook" (a 400 bad-signature reply proves it reached the backend). |
| Q9 | The Stripe-side sweep looks back **30 days** (configurable) and the reconciliation also **runs once at startup**, so payments made just before the home PC went offline are credited as soon as it is back, even after Stripe's ~3 days of retries. |
| Q10 | Going live is two stages: a **test-mode rehearsal on the real private host** (test card 4242: top-up → credits → Dashboard refund → take-back; the dispute test card: take-back), then live keys and one real $1.99 starter-pack purchase. |
| Q11 | Credit packs and prices unchanged (starter 100 / $1.99, value 300+30 / $4.99, big 700+100 / $9.99, USD). |

## The design

### 1. Starting a purchase — no double charge

1. **Order first, Stripe second.** Save the `payment_orders` row and commit, *then* call Stripe. Today
   `Session.create` runs inside the DB transaction (`PaymentService.kt:120`), so Stripe can hold a session our
   DB never saved.
2. **Stripe idempotency key** on `Session.create` = a value derived from our `orderNo`. A network retry
   returns the same session instead of a second one.
3. **One purchase in flight per user.**
   - If the user already has a `CREATED` order **for the same product** whose Stripe session is still `open`
     (checked with `Session.retrieve`), return that session's URL; create nothing new.
   - Otherwise, before creating the new session, **expire** the user's other `open` sessions with Stripe's
     `expire` API. A session that turns out to be already paid is left alone (it will be fulfilled by §2).
4. **Short sessions:** `expires_at` = 30 minutes (Stripe's minimum), so old tabs and links stop working soon.
5. **Stripe Customer per user:** create on first purchase, store `stripe_customer_id`, pass `customer` on the
   session. (No email prefill: the users table stores no email — my call, after the builder check.) Put `orderNo` and `userId` in the metadata of the session
   **and** of the PaymentIntent (`payment_intent_data.metadata`), so refund and dispute events, which carry
   the charge / PaymentIntent and not the session, map back to the order.
6. Frontend: the 充值 buttons stay disabled while the request is in flight (as today); a reused session is
   simply opened.

### 2. Fulfillment — one idempotent function, three callers

`fulfill(sessionId)` is the **only** place that turns a paid session into credits:

- It **always reads the session from the Stripe API** (`Session.retrieve`, with the PaymentIntent and its
  latest charge expanded) and trusts that, not the webhook payload. This is Stripe's recommended pattern; it
  also makes us immune to webhook API-version mismatches (today a mismatch makes the payload deserialize as
  empty and fulfillment silently does nothing).
- Paid (`payment_status` = `paid`) → in **one DB transaction**: conditional update `CREATED → COMPLETED`, store
  PaymentIntent id, charge id and the charge's **`receipt_url`**, and credit the wallet (`PURCHASE`, linked to
  the order). If the update matches 0 rows the order is already done → no-op.
- Find the order by session id, then by `client_reference_id`, then by metadata `orderNo`. **Not found → fail
  loudly** (alert, and for the webhook a non-2xx so Stripe retries) — never "log and return 200" as today
  (`PaymentService.kt:178–182`).
- A paid session whose order is `EXPIRED`/`FAILED` → alert + flag for manual handling (should be impossible).
- **DB backstop:** a unique index so one order can have at most one `PURCHASE` ledger row.

The three callers:

1. **Webhook** (`checkout.session.completed`, `checkout.session.async_payment_succeeded`): the event is a
   trigger; take the session id and call `fulfill`. `async_payment_failed` / `expired` likewise re-read the
   session and mark `FAILED` / `EXPIRED`.
2. **The return page** (`GET /api/payment/order/{orderNo}`): if the order is still `CREATED`, call `fulfill`
   (or mark `EXPIRED`) before answering. Throttle per order so the page's 1.5 s polling cannot flood Stripe
   (an in-memory throttle is enough: production runs one backend — my call).
3. **A reconciliation job** (scheduled; interval and age thresholds in `application.yml`):
   - every `CREATED` order older than a few minutes → `fulfill` / mark `EXPIRED` from Stripe's state;
   - every `COMPLETED` order missing its charge id / receipt → backfill;
   - a Stripe-side sweep: list recently completed Checkout Sessions (`Session.list`) and fulfill or alert on any
     without a `COMPLETED` order — this covers "order row lost". Only sessions carrying our `orderNo` metadata
     are considered, so anything else on the Stripe account is ignored (my call).

### 3. Refunds and chargebacks — webhook-driven (R1–R4, R7)

- Events: `charge.refunded`, `charge.refund.updated`, `refund.updated`, `refund.failed` (a refund that later
  fails; Stripe sends `charge.refund.updated` only for some payment methods), `charge.dispute.created`,
  `charge.dispute.updated`, `charge.dispute.closed`. Each is only a trigger: re-read the charge (with its refunds
  and disputes) from the API. Map to the order by PaymentIntent id, then by PaymentIntent metadata.
- **Reconcile, don't replay.** For the order, work out from Stripe's current state whether its credits *should*
  be present (R7), compare with whether they *are* present (a per-order field, updated in the same transaction as
  the ledger entry, order row locked), and write at most one corrective ledger entry:
  - should be absent, are present → take back the order's full credits (balance may go negative);
  - should be present, are absent → give them back;
  - equal → nothing.
  So any order of events, duplicates and replays end in the same, correct balance.
- Order status mirrors the money state: `REFUNDED`, `DISPUTED` (any open dispute, inquiry included),
  `DISPUTE_LOST`, back to `COMPLETED` after a won dispute / closed inquiry.
- **Partial refund** → no automatic credit change, order flagged `NEEDS_REVIEW` permanently, alert (R3, R8).
- New ledger types (names are the builder's): "purchase reversed" (negative) and "purchase reinstated"
  (positive), each linked to the order. The existing `REFUND` type stays for perks.
- Wallet: drop the `balance >= 0` CHECK (new migration); add an unconditional debit used only by reversals;
  normal spending keeps its `balance >= amount` guard.

### 4. Event log and traceability

- Every webhook delivery is written to the event log (extend `payment_events`): Stripe event id, type, object
  id, linked order, received time, processed time, outcome, error. The row **survives a processing failure**
  (written in its own transaction), and dedupe skips only events already processed successfully — so a failed
  event is retried by Stripe and its failures stay visible.
- Order row keeps the Stripe ids (session, PaymentIntent, charge, customer), receipt URL, and timestamps for
  completed / refunded / disputed.
- Every ledger row of a purchase, reversal or reinstatement links to its order.
- **Alerts:** one fixed log tag for payment problems (paid-but-no-order, paid non-`CREATED` order, partial refund,
  repeated event failures, reconciliation fixes). Flagged orders are stored with the flag and reason; there is **no
  admin view** (R8) — the owner finds them through the log tag, the database and the Stripe Dashboard.
- Player view: 充值記錄 with paging, status, credits, amount, date and the **Stripe receipt link**; orders still
  processing re-check themselves via §2.2. A flagged order shows the player its money state (e.g. 已完成 /
  已退款), never an internal "needs manual handling" label (my call). The balance and ledger show negative values honestly.
- Stripe side: Customer per user, metadata `orderNo` / `userId` on session and PaymentIntent → searchable in the
  Dashboard.

### 5. Configuration and going live

- **Fail fast:** an explicit payments switch in `application.yml`, **off by default** so an environment without keys
  still starts (my call); when on, a missing secret key, webhook secret or
  frontend base URL stops startup (today a blank webhook secret makes every webhook fail with 400, silently).
  New tunables (session expiry, reconciliation interval/ages, return-page throttle) live in `application.yml`
  with no defaults in Kotlin (project rule).
- **Runbook** (`docs/payments-prod-keys.md`): pin the webhook endpoint's API version to the SDK's
  (`stripe-java` 33 → `2026-05-27.dahlia`); add the refund and dispute events; restricted-key permissions for
  everything the code now calls (Checkout Sessions, Customers, PaymentIntents/Charges read, Disputes read);
  the host `https://private.youplay123.online` for `FRONTEND_BASE_URL` and the webhook URL (Q2); the Cloudflare check (Q8); the two-stage go-live (Q10); how to find the alert tag in the logs (Q4); add the missing names to `.env.prod.example`;
  replace "Dashboard refunds do not take back credits" with the new behaviour; full refunds only.
- Payment methods stay Stripe's dynamic payment methods, toggled in the Dashboard (no `payment_method_types`
  in code); async methods are covered by the async events.

### 6. Tests

- **Unit / integration (H2 locally, Postgres in CI):** fulfill from each caller; duplicate and concurrent deliveries;
  order not found → non-2xx and alert; Stripe error during create; reuse and expiry of open sessions; refund full /
  partial / failed; dispute opened (inquiry and chargeback) / won / lost / inquiry closed; the R7 combinations
  (dispute won then refunded, refunded then dispute won, partial then rest) in every event order and with replays; negative balance blocks spending; startup
  validation; the event log keeps failed attempts.
- **Sandbox (behind `STRIPE_SANDBOX_TESTS_ENABLED`):** real API round trips — create, reuse, expire, retrieve;
  refund and dispute through Stripe's test helpers (e.g. the dispute test card).
- **Browser E2E (`frontend/e2e/payment/`):** fix the nightly failure (Stripe's hosted page now renders the card form
  inside an iframe, the Payment Element, so the old top-level selectors find nothing); forward the new events in `run-payment-e2e.sh`; cover pay →
  credits, return-page fallback with the webhook forwarder off, and a refund.
- Frontend unit tests for the return page and the order list (none today).

## Changed from 2026-06-11

- Refunds: Dashboard-only (R1) instead of an in-app request + admin approval flow; no admin view (R8).
- Receipts: Stripe's charge `receipt_url` (free) instead of `invoice_creation` (paid Stripe Invoicing).
- Kept: hosted Checkout, dynamic payment methods, in-app order history, a Stripe Customer per user.

## Left to the builder

Exact names (statuses, ledger types, columns, endpoints), migration layout (next is `V39`; widen
`payment_orders.status`, today `VARCHAR(10)`, for the new statuses), the throttle and job
intervals, how the Stripe calls are wrapped for testing, and PR split — as long as every
goal, decision and anti-goal above holds and each PR is shippable on its own.
