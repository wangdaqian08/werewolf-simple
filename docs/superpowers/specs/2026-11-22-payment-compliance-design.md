# Payment compliance (Stripe top-up, part 5): design

## Why

The Stripe credit top-up (PRs #208–#211) is built but switched off. Before real money flows, the
owner wants two things (2026-11-21):

1. **Pass Stripe's account review and not get the account closed.** Stripe expects a business that
   sells online to show its name, a customer-service contact, terms, a privacy policy and a refund
   policy, and expects the price and currency to be clear before payment. A high chargeback rate is
   also a reason Stripe closes accounts.
2. **Have written terms to rely on when there is a refund request, a chargeback or a complaint.**
   In a chargeback, Stripe's strongest evidence is a refund policy the player saw and accepted
   before paying.

The merchant is an Australian company (the Stripe account is BLUEVERTEX PTY LTD), so the
Australian Consumer Law (ACL) applies.

## Owner decisions (2026-11-21 / 22)

| # | Decision |
|---|----------|
| D1 | **Refund policy:** within **14 days** of a purchase, a player can get a **full refund** of that purchase if the account has **not spent any credits since that purchase**. Otherwise no change-of-mind refund. |
| D2 | **"Not spent since"** is decided from the records: in `credit_transactions`, the player has no spending row (a negative `amount` other than a reversal, i.e. `PERK_SPEND`) created after the order's `completed_at`. Game rewards (positive rows) do not count as spending. A `PERK_SPEND` whose credits were later returned in full (a `REFUND` row with the same `perk_activation_id`, e.g. a role-pick draw that missed or a cancelled game) does not count either. Example: 11/01 top-up +100 (order A) → 11/03 game reward +20 (A still refundable) → 11/05 role-pick card −25 (A no longer refundable). |
| D3 | **ACL:** the 14-day rule only limits **change-of-mind** refunds. The pages must never say "no refunds" (unlawful in Australia), must say statutory consumer guarantees are not affected, and our own faults (paid but not credited, credits that do not work, a wrong charge) are fixed regardless of the 14 days or spending. |
| D4 | **Unfair contract terms:** account suspension is limited to fraud, chargebacks and breach of the terms; changes to the terms are announced in advance, not "at any time without notice". |
| D5 | **Language:** English only for the policy pages and the purchase text. |
| D6 | **Business name** shown: **Blue Vertex**. **Customer-service email:** `wangdaqian08@gmail.com` (the owner asked for this address on the pages). |
| D7 | **Currency: AUD.** Keep the 4.99 and 9.99 packs (`value`, `big`) at the same numbers in AUD; **remove the 1.99 `starter` pack**. Not GST-registered, so the text says "Prices in AUD" with no GST wording. |
| D8 | **Approach 1** (below): static English pages + Stripe's own consent checkbox on Checkout. |
| D9 | Also agreed: credits are virtual, no cash value, not transferable; role-pick cards are a random draw and cost nothing when the draw misses; players under 18 need a parent's or guardian's consent to buy; no account-deletion feature (deletion requests by email). |
| D10 | **Self-service refund button** (2026-11-22, refined in a grilling round), §8: an eligible order shows 申请退款; confirming refunds it **automatically** through Stripe (no owner review). Eligible = completed, ≤ 14 × 24 h since `completed_at`, no spending since (D2), the player in **no unfinished room** (waiting or playing), and **no self-service refund on the account in the last 30 days**. |
| D11 | **Lock:** from the confirmed request until the refund settles, the account cannot create or join a room or start a game ("退款處理中"); it can still sign in and view its account. A succeeded refund unlocks at once; a pending one stays locked until Stripe reports success or failure (or the reconcile job sees it); a Stripe error at create never locks. |
| D12 | **Ineligible orders** within 14 days show the reason in grey (已消費 / 請先離開房間 / 30 天內已退過一次，請發郵件聯繫 + email); orders older than 14 days show nothing. A **confirm dialog** states the amount refunded, the credits removed, the lock, and 5–10 business days to reach the original payment method. |
| D13 | **Dashboard refunds stay** for our faults (D3) and players past the limit; they do not lock. Partial Dashboard refunds stay flagged as today. **No admin list** of self-service refunds: the log line, the Stripe refund (metadata `orderNo`, `userId`, reason `requested_by_customer`) and the order's 已退款 status are the record. The restricted key gains **Refunds: Write**. |

## Goals

- The site shows the business name, contact email, Terms, Privacy Policy and Refund Policy, readable
  without JavaScript.
- Before paying, the player sees the price in AUD and links to the Terms and Refund Policy, and must
  tick Stripe's "I agree to the Terms of Service" box on the Checkout page.
- Each paid session carries Stripe's record of that consent (usable as chargeback evidence).
- A player can get a change-of-mind refund in the app without waiting for the owner, and cannot keep
  playing on the credits while it is processed.

## Non-goals

- No owner review step, no admin list of refunds, no partial refunds from the app (the app always
  refunds the full charge).
- No account deletion feature, no cookie banner (the app sets no tracking or advertising cookies —
  verify while writing the privacy page; if something does, list it in the policy instead).
- No Chinese translation of the policy pages (D5). The rest of the app keeps its current i18n.
- No GST handling, no Stripe Tax, no invoices.
- No change to how refunds, disputes or credits work in code.

## Anti-goals (a failure even if it works)

- Any wording that reads as "no refunds" or that limits the statutory guarantees.
- Terms that let us suspend accounts, remove credits or change terms at will.
- Policy pages that render empty without JavaScript.
- The price shown in the app differing from what Stripe charges (currency or amount).

## Approaches considered

1. **Static English pages + Stripe's consent checkbox (picked).** Plain HTML pages served by the
   frontend's nginx; links in the lobby and the buy sheet; `consent_collection.terms_of_service =
   required` and a `custom_text` line on the Checkout Session. Consent is stored by Stripe on each
   session. Least code, uses native Stripe, no review risk from a JS-only page.
2. **Our own checkbox, stored in our DB.** A checkbox in the buy sheet and a terms version + time on
   each order (a migration). Evidence would sit in our DB and have to be exported by hand in a
   dispute; duplicates what Stripe does. Rejected.
3. **Pages + "by buying you agree" text, no checkbox.** Least work, but browse-wrap consent is much
   weaker evidence in a chargeback — the case the owner wants covered. Rejected.

**Refund requests** (D10, picked after the pages approach):

- **In-app button, automatic refund (picked).** The rule is mechanical (14 days, no spend, not in a
  room, once per 30 days), so a review adds delay without changing outcomes; app-made refunds are
  always full, so partial refunds cannot happen from this path. Costs Refunds: Write on the key.
- **In-app button, owner approves in the admin page.** Same lock, but the player waits hours or days
  locked out; needs a request list, approve/reject and an unlock action. Rejected.
- **Email, owner locks the account by hand.** Least code, but between the email and the lock the
  player can keep playing and spending, which defeats the lock. Rejected.

## The design

### 1. Policy pages (`frontend/public/`)

Three static files, copied as-is into `dist/` by Vite and served by nginx (`try_files $uri` serves
them before the SPA fallback). Links always use the `.html` path (`/terms` without `.html` falls back
to the SPA).

- `terms.html` — Terms of Service: who we are (Blue Vertex, Australia, contact email); the service;
  accounts (guest accounts cannot buy; Google sign-in); credits (virtual, no cash value, not
  transferable or exchangeable, earned by playing or bought; balance may go negative after a refund
  or chargeback and then blocks spending); purchases (prices in AUD, paid through Stripe; under 18
  needs a parent's or guardian's consent); role-pick cards (a random draw; credits are taken only
  when the draw succeeds and returned when it misses or the game is cancelled); refunds (summary +
  link to the Refund Policy); chargebacks (a chargeback takes back that purchase's credits); account
  suspension (fraud, chargebacks, breach — D4); changes to the terms (announced in the app before they
  apply — D4); the ACL statement (D3); governing law (the state the company is registered in —
  left to the builder to phrase as "the laws of Australia" if the state is unknown); contact.
- `refunds.html` — Refund Policy: D1, D2 (with the example), D3 (our faults are fixed regardless),
  how to ask (the 申请退款 button in 我的賬戶 → 充值記錄; the one-per-30-days limit; the lock while
  it is processed; past the limit or for our faults, email with the order number), how a refund is made (full
  refund to the original payment method through Stripe; that purchase's credits are removed, the
  balance may go negative), chargebacks, and the statutory-rights sentence.
- `privacy.html` — Privacy Policy: what is stored (account id from Google/WeChat or the guest
  nickname, nickname, avatar URL, game records and statistics, credit ledger, top-up orders, the
  Stripe customer id, created / last-seen times); card details are handled by Stripe and never reach
  us; why (running the game, payments, preventing fraud); who receives data (Stripe for payments,
  Google/WeChat for sign-in, Cloudflare as the network provider); retention; access and deletion
  requests by email; contact.

All three: Ink & Paper look (the CLAUDE.md colour tokens and fonts, inline `<style>`), readable at
phone width, a "Last updated" date, a link back to the game and to the other two pages, and the
footer line `Blue Vertex · wangdaqian08@gmail.com`. The exact legal wording is the builder's, within
D1–D9 and the anti-goals.

### 2. In-app links and price text (`frontend/`)

- **Lobby:** a small footer row on the lobby page, `Terms · Privacy · Refunds`, each opening the static
  page (plain `<a href>`; a new tab is fine), plus `Contact: wangdaqian08@gmail.com` as a `mailto:`.
  Visible whether or not the player is signed in.
- **Buy sheet:** prices show as `A$4.99` / `A$9.99`, built from the product's `currency` field the API
  already returns (an AUD price shows `A$`; any other currency shows its code, e.g. `USD 4.99`, so a
  wrong DB row is visible instead of silently showing `$`). Below the packs one line: `Prices in AUD.
  Purchases are subject to our Terms and Refund Policy.` with both links.
- English text for these lines lives in the i18n files; the zh locale gets the same English strings
  for the policy names (D5 keeps the policies English; the surrounding label may be translated — the
  builder's call).

### 3. Checkout consent (`backend/`)

In `StripeGatewayImpl.createCheckoutSession`, add:

- `consent_collection.terms_of_service = REQUIRED`;
- `custom_text.terms_of_service_acceptance.message`: `I agree to the [Terms of Service](<base>/terms.html)
  and the [Refund Policy](<base>/refunds.html).` where `<base>` is `app.payment.frontend-base-url`
  (already required when payments are on).

Verified 2026-11-22: the CI sandbox accepts both parameters on session create. Stripe may require a
Terms URL in the account's public details before `consent_collection` works in live mode — the
runbook step below covers it.

### 4. Products: AUD, no starter pack (migration `V42`)

`V42__aud_prices.sql`, idempotent:

```sql
UPDATE products SET currency = 'aud';
UPDATE products SET active = FALSE WHERE product_key = 'starter';
```

The starter row is deactivated, not deleted, so old (test/dev) orders that reference it stay valid.
The self-refund columns (§7) may go in this migration or the next one (builder's call).
`E2eProductSeeder` drops `starter` and seeds `value` and `big` in `aud`. V40/V41 are merged and must
not be edited. The `products.currency` column default stays `'usd'` (unused by inserts); changing it
is optional and the builder's call.

Test impact to handle: several backend integration tests look up `starter` with
`findByProductKeyAndActiveTrue` and otherwise `save` a new `starter` row. On Postgres (CI) the
deactivated row from V42 makes that `save` hit the `product_key` unique index. Point those tests at
`value` (A$4.99, 330 credits) or another key that V42 leaves active, and update the amounts they assert
(199 → 499 etc.).

### 5. Payment E2E (`frontend/e2e/payment/`)

- Buy `value` instead of `starter`: `buy-value`, `+330`, balance `330`; refund/dispute expectations
  follow.
- Tick Stripe's Terms checkbox before submitting (find its stable selector on the real page, the
  same way the card fields were found — it may sit outside the Payment Element iframe).
- After paying, assert from the Stripe API that the session has `consent.terms_of_service ==
  "accepted"` and `currency == "aud"`.

### 6. Runbook (`docs/payments-prod-keys.md`)

- New step before the restricted key: **Dashboard → Settings → Public details**: business name Blue
  Vertex, support email, website `https://private.youplay123.online`, Terms URL `…/terms.html`, Privacy
  URL `…/privacy.html`; **statement descriptor** short and recognisable (e.g. `BLUEVERTEX GAME`) so
  players recognise the charge on their bank statement — fewer "I don't recognise this" chargebacks.
  Do it in the rehearsal sandbox and in live mode.
- The restricted key table gains **Refunds: Write** (the app now creates refunds).
- How self-service refunds look (log line, Stripe refund metadata) and when to use a Dashboard refund
  instead (our faults, players past the 30-day limit).
- **Refund decision query** (read-only, `BEGIN READ ONLY … ROLLBACK`) for emailed requests: given an
  `order_no`, returns the order's amount, credits, `completed_at`, days since, and whether the user has
  an unreturned `PERK_SPEND` after `completed_at` (D2).
- Recommendation: have an Australian lawyer review the three pages (a fixed-fee review) before live
  keys.
- Update the rehearsal/live purchase steps from the starter pack ($1.99) to the value pack (A$4.99),
  and add "tick the Terms box" and "the session shows the Terms accepted" checks.

### 7. Self-service refund (D10–D13)

**Backend** (server-authoritative; the frontend only shows what the API says):

- Each order in `GET /api/payment/orders` (and `GET /api/payment/order/{orderNo}`) carries
  `refund: { eligible: Boolean, reason: String? }` while it is within 14 × 24 h of `completed_at`;
  `reason` is one of `SPENT`, `IN_ROOM`, `LIMIT` (absent when eligible; the field is absent after 14
  days or for non-`COMPLETED` / flagged orders). The 14 days, 30 days and limit of 1 are tunables in
  `application.yml` under `app.payment` (project rule: no Kotlin defaults).
- `POST /api/payment/order/{orderNo}/refund` (owner of the order only): re-checks eligibility under a
  lock on the order (409 with the reason code when not eligible), records the request and locks the
  account, calls Stripe `Refund.create` for the order's PaymentIntent (full amount; idempotency key
  derived from the order; metadata `orderNo`, `userId`; reason `requested_by_customer`), then runs the
  existing charge reconcile synchronously (`PaymentChargeService.reconcile(chargeId, "self-service")`)
  so a succeeded refund removes the credits and unlocks in the same request. Response: the order's new
  status (`REFUNDED`, or still `COMPLETED` with `refundPending: true`). A Stripe error before the refund
  exists → no lock, 502/503 with a retryable code.
- **Lock state:** stored on the order (e.g. `self_refund_requested_at`, `self_refund_settled_at`); the
  account is locked while any of its orders has a requested-but-unsettled self refund. Settling
  happens in the reconcile path: refund succeeded → order `REFUNDED`, credits reversed, settled;
  refund failed/cancelled → settled, credits unchanged, `[payment-alert] self-service refund … failed`.
  The webhook (`refund.updated`, `refund.failed`, `charge.refunded`) and the 10-minute reconcile job
  already re-read the charge, so a lost webhook cannot leave the account locked for more than one job
  interval. The 30-day limit counts self refunds that were requested and not failed.
- **Enforcement:** create room, join room, ready and start game reject a locked account with error code
  `REFUND_PENDING` (the frontend translates it). Existing perk/role-pick spending is also unreachable
  because those happen inside rooms.
- Concurrency: double clicks / two tabs → the order lock + a conditional "requested" update + the
  Stripe idempotency key give at most one refund.
- Log: `[payment] self-service refund order=… user=… outcome=…` at INFO.

**Frontend** (`AccountView`, lobby/room entry points):

- Order row: 申请退款 button when `refund.eligible`; grey reason text for `SPENT` / `IN_ROOM` /
  `LIMIT` (the LIMIT text includes the contact email); nothing when `refund` is absent.
- Confirm dialog (D12) with the order's amount (from `fmtMoney`) and credits; confirm → POST →
  refresh orders and wallet; show 退款處理中 while pending.
- Lobby / create / join: a `REFUND_PENDING` rejection shows 退款處理中，完成前無法進入遊戲.
- zh and en strings in the i18n files (the button and hints are app UI, not policy pages).

### 8. Tests

- Backend: the session-create mapping sends `consent_collection` and the `custom_text` message with
  the configured base URL (unit test on the params, or the sandbox test asserts the returned
  session's `consent_collection`); V42 on Postgres (Flyway test): `starter` inactive, every product
  `aud`.
- Frontend unit: buy sheet shows `A$4.99`, the AUD line and both links; a non-AUD product shows its
  code; lobby footer links point at the three `.html` pages.
- A check that the three pages exist in `frontend/public/` and each contains the contact email and
  links to the other two (cheap test or script).
- Payment E2E as in §5.
- Self refund (backend integration, fake gateway): eligible → refunded, credits removed, unlocked in
  one call; each ineligible reason (spent; spent-then-returned counts as not spent; in a waiting room;
  second refund within 30 days; > 14 days → no `refund` field); pending refund keeps the lock until a
  `refund.updated` succeeded / `refund.failed` arrives; Stripe error → no lock; double submit → one
  refund; locked account cannot create/join/ready/start.
- Frontend unit: button / reason text / nothing per API state; confirm dialog content; pending text.
- Payment E2E (sandbox): buy, press 申请退款, confirm → order 已退款, balance 0, account not locked.
- Sandbox test: `Refund.create` with metadata and reason round-trips.

## Builder's calls (settled while writing; check while reading)

- **Two purchases close together:** D2 is applied per order — "spending after *this* order's
  `completed_at`". A spend after both A and B blocks both; a spend before A does not block A.
- **Live mode refuses `consent_collection`** (no Terms URL in public details): checkout fails with the
  existing "payment unavailable" error. No fallback in code: payments stay off until the runbook's
  public-details step is done, and the rehearsal catches it.
- **Old orders keep their own currency:** the order history formats from `order.currency`
  (`AccountView.fmtMoney`), so a USD test order still shows as USD. V42 touches `products` only.
- **Pack contents stay as V19 seeded them** (value 300+30 credits / 4.99, big 700+100 / 9.99); V42
  changes only the currency and the starter pack.
- **Retention wording:** account and game data kept while the account exists; payment records kept
  as long as Australian tax and accounting law requires (e.g. 5 years); deletion requests by email.
- **The host is public:** `https://private.youplay123.online` is the game host players use (Cloudflare
  Tunnel, no Access gate), so Stripe's reviewers can open the pages there.

## Left to the builder

The exact legal wording within D1–D9 and the anti-goals; page layout within the Ink & Paper style;
i18n key names; whether the lobby footer opens a new tab; the statement descriptor text suggestion;
test placement; one PR or several (each must ship on its own, and payments stay switched off).
