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

| Env   | Key                                 | Webhook secret               |
| ----- | ----------------------------------- | ---------------------------- |
| local | `STRIPE_SANDBOX_SECRET` (zshrc)     | stripe-cli `listen` whsec    |
| CI    | repo secret `STRIPE_SANDBOX_SECRET` | stripe-cli `listen` whsec    |
| prod  | `rk_live_…` in `.env.prod`          | Dashboard endpoint `whsec_…` |
