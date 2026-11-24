# Going live: Stripe payments

Payments ship switched off (`PAYMENTS_ENABLED=false`). This runbook turns them on in
two stages: a test-mode rehearsal on the real host, then live keys. Start only when the
nightly **Payment E2E (Stripe sandbox)** workflow is green.

## How payments behave

- A paid Checkout Session becomes credits through one idempotent path, called by the
  webhook, by the return page (`/pay/result`), and by a reconcile job every 10 minutes.
  A missed webhook therefore costs minutes, never credits.
- Players refund a purchase themselves from 我的賬戶 → 充值記錄 (申請退款) within 14 days if
  they have spent no credits since, at most once every 30 days. The app then refunds the
  whole charge through Stripe, and the account cannot enter games until Stripe confirms it.
  You can still refund in the Dashboard (our faults, players past the limit), **full refunds
  only**. A full refund or a
  chargeback (inquiries included) takes the purchase's credits back; the balance may go
  negative, and a negative balance blocks spending. A won dispute gives the credits back.
- A **partial refund** changes no credits. It flags the order for manual handling
  (`[payment-alert]` in the log), and a flagged order never changes credits on its own
  again: fix the balance by hand if needed.
- Prices are in **AUD** (packs A$4.99 and A$9.99). Before paying, the player must tick
  Stripe's "I agree to the Terms of Service and the Refund Policy" box; Stripe stores that
  consent on the session, which is the evidence in a chargeback.
- The policy pages are `/terms.html`, `/refunds.html` and `/privacy.html`. Before live
  keys, have an Australian lawyer review them (a fixed-fee review is enough).

## 0. Public business details

Dashboard → Settings → Business → Public details (in the rehearsal sandbox and again in
live mode):

- Business name **Blue Vertex**, support email `wangdaqian08@gmail.com`, website
  `https://private.youplay123.online`.
- Terms of service URL `https://private.youplay123.online/terms.html`, privacy policy URL
  `https://private.youplay123.online/privacy.html`.
- **Statement descriptor:** short and recognisable, e.g. `BLUEVERTEX GAME`, so players
  recognise the charge on their bank statement (fewer "I don't recognise this" chargebacks).
- **Payment methods** (Settings → Payment methods): with AUD, Stripe's dynamic payment
  methods also offer Klarna and Zip. The chosen first set is cards, Apple Pay and Google
  Pay: switch Klarna and Zip off unless you want them.

## 1. Restricted key

Dashboard → Developers → API keys → "Create restricted key" (once in test mode for
stage 1, once in live mode for stage 2):

| Permission        | Level |
| ----------------- | ----- |
| Checkout Sessions | Write |
| Customers         | Write |
| PaymentIntents    | Read  |
| Charges           | Read  |
| Refunds           | Write |
| Disputes          | Read  |
| All others        | None  |

- Refunds: Write is needed because the app creates the player's self-service refund
  (always the full charge, reason "requested by customer").
- PaymentIntents: Read is needed because the backend expands the PaymentIntent when it
  reads a session or a charge.
- Invoicing is not used: receipts are Stripe's charge receipts.

Name it `werewolf-backend-prod` and copy the `rk_…` value; Stripe shows it once.

## 2. Webhook endpoint

Dashboard → Developers → Webhooks → "Add endpoint" (again once per mode):

- URL: `https://private.youplay123.online/api/payment/webhook`
- API version: **`2026-05-27.dahlia`**, the version stripe-java 33 is built for. The
  backend re-reads every object from the API, so a mismatch does not lose events, but pin
  it anyway so the payloads match the SDK.
- Events (11). Each one only tells the backend to re-read the session or charge:
  - `checkout.session.completed`, `checkout.session.expired`,
    `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`
  - `charge.refunded`, `charge.refund.updated`, `refund.updated`, `refund.failed`
  - `charge.dispute.created`, `charge.dispute.updated`, `charge.dispute.closed`
- Copy the signing secret (`whsec_…`).

## 3. Cloudflare must let Stripe through

Stripe's servers are not browsers. For the path `/api/payment/webhook`:

- **Access:** no Access policy may cover it (add a Bypass rule if the host is behind Access).
- **Bot Fight Mode / WAF:** no challenge or block; add a skip rule for the path if needed.

Check from any machine:

    curl -s -w ' %{http_code}\n' -X POST https://private.youplay123.online/api/payment/webhook \
      -H 'Stripe-Signature: t=1,v1=bogus' -d '{}'

| Reply                                   | Meaning                        |
| --------------------------------------- | ------------------------------ |
| `{"error":"payments disabled"} 503`     | Reached the backend; still off |
| `{"error":"invalid signature"} 400`     | Reached the backend; on        |
| An HTML page, 403, or a challenge       | Cloudflare blocked it          |

After step 4, also use the endpoint's **Send test event** in the Dashboard: its response
must be JSON from the backend, not an HTML page.

## 4. Configure the backend

Add to the backend's env file (`.env.prod` in this repo's compose; see `.env.prod.example`):

    PAYMENTS_ENABLED=true
    FRONTEND_BASE_URL=https://private.youplay123.online
    STRIPE_SECRET_KEY=rk_test_…      # stage 1; rk_live_… in stage 2
    STRIPE_WEBHOOK_SECRET=whsec_…    # the secret of the endpoint in the same mode

With `PAYMENTS_ENABLED=true`, a blank key, webhook secret or `FRONTEND_BASE_URL` stops
the backend at startup. Recreate the backend
(`docker compose --env-file .env.prod up -d --force-recreate backend`), then check
`https://private.youplay123.online/api/health`.

## 5. Stage 1: test-mode rehearsal on the real host

Use a **new, empty sandbox** for the rehearsal, not the one CI uses: the reconcile job
sweeps the last 30 days of Stripe sessions and charges, and every CI order it finds raises
a `has no order` / `carries orderNo` alert against the production database.

With that sandbox's details, key and endpoint from steps 0–4, on a signed-in (non-guest) account:

1. Buy the A$4.99 pack with card `4242 4242 4242 4242` (any future date, any CVC), ticking
   the Terms box. Credits arrive, the order reads 已完成 in 我的賬戶 → 充值記錄, 收據 opens
   Stripe's receipt, and the Dashboard payment shows the Terms accepted.
2. Dashboard → Payments → that payment → Refund → the **full** amount. Within seconds the
   order reads 已退款, the credits are gone, and the ledger shows 充值退回.
3. Buy again with `4000 0000 0000 0259` (Stripe's dispute card). The order reads
   拒付處理中 and the ledger shows 拒付扣回.
4. Buy once more, then press 申請退款 on that order and confirm. The order reads 已退款, the
   credits are gone, and you can create a room again; the Dashboard shows the refund with
   reason "Requested by customer" and the order number in its metadata.
5. Dashboard → Webhooks → the endpoint: every delivery answered 200.
5. The alert grep below shows nothing for these orders.

## 6. Stage 2: live keys

1. Repeat steps 0, 1, 2 and 4 in **live** mode, and recreate the backend.
2. Buy the A$4.99 pack with a real card; confirm credits, the receipt and the Terms consent.
3. Refund it in full from the Dashboard; confirm 已退款 and the credits are taken back.

Switching from test to live keys is self-healing: test-mode customer ids are replaced and
test-mode orders expire on their own. No SQL is needed.

## Self-service refunds

- Each one logs `[payment] self-service refund order=… user=… refund=… outcome=…`, and the
  Stripe refund carries reason `requested_by_customer` with metadata `orderNo` and `userId`.
- The account is locked (cannot create or join a room) from the request until Stripe reports
  the refund succeeded or failed; a card refund usually settles at once.
- A refund that later fails gives the credits back, unlocks the account and logs
  `[payment-alert] self-service refund … failed`; the player can try again.
- Use a Dashboard refund instead for problems on our side and for players past the
  once-per-30-days limit (they are told to email). Dashboard refunds never lock an account.

## Deciding an emailed refund request

The Refund Policy promises a full refund within 14 days of a purchase if the player has
not spent credits since (a role-pick charge returned in full does not count). The rule is
judged at the moment the player asked, not when you handle the email. Put the order number
and the time the email arrived (in UTC) on the first two lines and run it read-only, as with
any production query:

```sql
\set order_no 'PUT-THE-ORDER-NUMBER-HERE'
\set asked_at 'WHEN-THE-PLAYER-ASKED'
-- asked_at: when the request email arrived, in UTC, e.g. 2026-11-20 13:05
BEGIN READ ONLY;
WITH o AS (
    SELECT order_no, user_id, status, amount_cents, currency, credits, completed_at,
           CAST(:'asked_at' AS timestamp) AS asked_at
    FROM payment_orders
    WHERE order_no = :'order_no'
),
spends AS (  -- spending between the purchase and the request; a role-pick charge returned in full does not count
    SELECT t.id
    FROM credit_transactions t
    JOIN o ON t.user_id = o.user_id
    WHERE t.type = 'PERK_SPEND'
      AND t.created_at > o.completed_at
      AND t.created_at <= o.asked_at
      AND NOT EXISTS (SELECT 1 FROM credit_transactions r
                      WHERE r.type = 'REFUND' AND r.user_id = t.user_id
                        AND r.perk_activation_id = t.perk_activation_id
                        AND r.created_at <= o.asked_at)
)
SELECT o.order_no, o.status, o.amount_cents, o.currency, o.credits, o.completed_at, o.asked_at,
       round(extract(epoch FROM (o.asked_at - o.completed_at)) / 86400, 1) AS days_to_request,
       (SELECT count(*) FROM spends) AS spends_before_request,
       CASE WHEN o.status = 'COMPLETED'
             AND o.asked_at - o.completed_at <= interval '14 days'
             AND (SELECT count(*) FROM spends) = 0
            THEN 'REFUNDABLE' ELSE 'NOT REFUNDABLE' END AS decision
FROM o;
ROLLBACK;
```

`REFUNDABLE` → refund the full charge in the Dashboard. `NOT REFUNDABLE` is only the
change-of-mind rule: if something went wrong on our side (paid but not credited, wrong
amount, credits that do not work), refund or fix it anyway (Australian Consumer Law).

## Alerts

Payment problems log one tag, and Stripe emails you when the endpoint keeps failing.

    docker compose --env-file .env.prod logs --since 24h backend | grep '\[payment-alert\]'

| Alert text                                   | What to do                                                          |
| -------------------------------------------- | ------------------------------------------------------------------- |
| `paid session … has no order`                | A payment with no order row: find it in the Dashboard by `orderNo`  |
| `completed session … carries orderNo …`      | Same, found by the reconcile job's 30-day Stripe sweep              |
| `charge … of order … has no order`           | A refund or dispute on a charge with no order row: same             |
| `paid session … could not be credited`       | Crediting threw; the stack trace follows. The job retries           |
| `order … flagged for review: …`              | Partial refund or a paid expired order: settle it by hand           |
| `order … needs review and its money state …` | A flagged order changed again in Stripe: re-check it by hand        |
| `reconcile credited session …`               | The job credited a payment the webhook missed: check deliveries     |
| `reconcile fixed charge …`                   | The job applied a refund or dispute the webhook missed: same        |
| `webhook event … failed again`               | An event keeps failing; Stripe retries it: read the error           |
| `self-service refund … of order … failed`    | A player's refund failed at Stripe; credits are back. Check Stripe  |

Flagged orders keep `needs_review = true` and a `review_reason` in `payment_orders`. There
is no admin page: use this grep, the database and the Stripe Dashboard (search by the
`orderNo` / `userId` metadata).

## Keys by environment

| Env   | Key                                 | Webhook secret               |
| ----- | ----------------------------------- | ---------------------------- |
| local | `STRIPE_SANDBOX_SECRET` (zshrc)     | stripe-cli `listen` whsec    |
| CI    | repo secret `STRIPE_SANDBOX_SECRET` | stripe-cli `listen` whsec    |
| prod  | `rk_…` in the backend's env file    | Dashboard endpoint `whsec_…` |
