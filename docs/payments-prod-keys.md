# Going live: Stripe production keys

> **Note:** payments are now off unless `PAYMENTS_ENABLED=true`. When it is true, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` and `FRONTEND_BASE_URL` are all required or the app refuses to start. The webhook endpoint must also send `charge.refunded`, `charge.refund.updated`, `refund.updated`, `refund.failed`, `charge.dispute.created`, `charge.dispute.updated` and `charge.dispute.closed`. Refunds are made in the Dashboard as **full refunds only**. The restricted key needs read access to Charges, Refunds and Disputes. This runbook gets a full rewrite in PR 4. Switching from test to live keys is self-healing: stale test-mode customer ids are replaced and test-mode orders expire on their own; no manual SQL is needed.

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
| Charges           | Read  |
| Refunds           | Read  |
| PaymentIntents    | Read  |
| Disputes          | Read  |
| All others        | None  |

The app never makes refunds (they are done in the Dashboard), so no Refunds: Write.
If your Dashboard has no separate Refunds row, Charges: Read covers it.
PaymentIntents: Read is needed because the backend expands the PaymentIntent when it
reads a session or a charge. Invoicing is not used: receipts are Stripe's charge receipts.
Name it `werewolf-backend-prod`. Copy the `rk_live_…` value once — Stripe
won't show it again.

## 2. Create the live webhook endpoint

Dashboard (live mode) → Developers → Webhooks → "Add endpoint":

- URL: `https://www.youplay123.online/api/payment/webhook`
- Events: `checkout.session.completed`, `checkout.session.expired`,
  `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`
  (the last two credit or fail a delayed payment such as ACH once it clears),
  `charge.refunded`, `charge.refund.updated`, `refund.updated`, `refund.failed`
  (a refund, or a refund that later fails), `charge.dispute.created`,
  `charge.dispute.updated`, `charge.dispute.closed`
  (a refund or chargeback takes the credits back; a won dispute gives them back)
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
3. Refund it from the Dashboard (Payments → the charge → Refund → the **full**
   amount). Within seconds the order shows REFUNDED and its credits are taken
   back (the balance may go negative). Never refund part of a charge: a partial
   refund changes no credits and flags the order for manual handling
   (`[payment-alert]` in the backend log).
4. Check Dashboard → Webhooks → the endpoint shows the delivered
   `checkout.session.completed` and `charge.refunded`, both with HTTP 200.

## Sandbox vs prod recap

| Env   | Key                                 | Webhook secret               |
| ----- | ----------------------------------- | ---------------------------- |
| local | `STRIPE_SANDBOX_SECRET` (zshrc)     | stripe-cli `listen` whsec    |
| CI    | repo secret `STRIPE_SANDBOX_SECRET` | stripe-cli `listen` whsec    |
| prod  | `rk_live_…` in `.env.prod`          | Dashboard endpoint `whsec_…` |
