# Payment compliance PR A (pages, consent, AUD) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the legal pages, the in-app price/links text, Stripe's Terms consent on Checkout and AUD-only packs, so the site meets Stripe's review needs. Payments stay switched off.

**Architecture:** Three static HTML pages in `frontend/public/` (served by nginx before the SPA fallback). The backend passes two URLs built from `app.payment.frontend-base-url` into the Checkout Session (`consent_collection` + `custom_text`). Migration V42 moves every pack to AUD and deactivates `starter`. One shared money formatter for the buy sheet and the order history.

**Tech Stack:** Vue 3 + TS + vitest; Spring Boot 4 / Kotlin, stripe-java 33, Flyway; Playwright (payment E2E on the Stripe sandbox).

**Spec:** `docs/superpowers/specs/2026-11-22-payment-compliance-design.md` (§1–§6, §8 except the self-refund items; §7 self-service refund is PR B).

## Global Constraints

- English only for the policy pages and the purchase text (D5). App UI strings go in both `en.ts` and `zh.ts` (the i18n parity test enforces both).
- Business name **Blue Vertex**; contact **wangdaqian08@gmail.com** (D6).
- Currency **AUD**; packs `value` (A$4.99, 300+30) and `big` (A$9.99, 700+100); `starter` deactivated, not deleted (D7). Text: `Prices in AUD.` — no GST wording.
- Pages must never say "no refunds"; they say statutory guarantees are not affected; the 14-day / not-spent rule limits change-of-mind refunds only (D1–D3). Suspension only for fraud, chargebacks, breach; term changes announced in advance (D4).
- Policy pages render fully without JavaScript; links always use the `.html` path.
- Never edit a merged migration (V1–V41); add V42.
- Tunables in `application.yml` with no Kotlin default; no `!!` in `src/main`.
- E2E: no fixed sleeps (`eslint.e2e-waits.config.js`); `getByTestId` for our own DOM; Stripe's DOM uses Stripe's names (documented exception).
- Commit trailer: the attribution line the session provides. Never touch the production DB; Stripe calls only with test-mode keys.

## Review Focus

1. A product row whose currency is not AUD (a wrong DB row) → the buy sheet shows the code (`4.99 USD`), never a bare `$` (Task 4 test `formats a non-AUD amount with its code`).
2. Old USD test orders in 充值記錄 keep showing USD after the switch (Task 4 test on AccountView with a `usd` order).
3. A stale client still posts `productKey=starter` → 400 product not found, no order, no Stripe call (Task 1 test).
4. Stripe's AUD page offers card / Klarna / Zip with the card form collapsed; a USD page shows the card form directly → the E2E handles both (Task 5 helper clicks the card option only when it exists).
5. Policy page text drifts into "no refunds" or drops the consumer-guarantee sentence → the page test fails (Task 3 test).

---

### Task 1: V42 — AUD packs, starter retired

**Files:**
- Create: `backend/src/main/resources/db/migration/V42__aud_prices.sql`
- Modify: `backend/src/main/kotlin/com/werewolf/config/E2eProductSeeder.kt`
- Modify tests that seed or buy `starter`: `PaymentCheckoutIntegrationTest.kt`, `PaymentChargeIntegrationTest.kt`, `PaymentFulfillmentIntegrationTest.kt`, `PaymentReconciliationIntegrationTest.kt`, `PaymentWebhookIntegrationTest.kt` (all in `backend/src/test/kotlin/com/werewolf/integration/`)
- Test: `FlywayMigrationsIntegrationTest.kt`, `PaymentCheckoutIntegrationTest.kt`

**Interfaces:**
- Produces: DB state — every `products.currency = 'aud'`; `starter.active = false`. E2E profile seeds only `value` and `big` (aud).

- [ ] **Step 1: Write the failing Flyway test** (same style as the V41 test)

```kotlin
@Test
fun `V42 prices every pack in AUD and retires the starter pack`() {
    flyway().migrate()
    sql {
        assertThat(queryStrings("SELECT DISTINCT currency FROM products")).containsExactly("aud")
        assertThat(queryBool("SELECT active FROM products WHERE product_key = 'starter'")).isFalse()
        assertThat(queryStrings("SELECT product_key FROM products WHERE active ORDER BY sort_order")).containsExactly("value", "big")
    }
}
```
(Use whatever query helpers the file already has; add a tiny one if missing.)

- [ ] **Step 2: Write the failing stale-client test** in `PaymentCheckoutIntegrationTest`: deactivate a product (`active = false`), call `POST /api/payment/checkout` with its key → 400, no order row, `gateway.createCalls` empty.

- [ ] **Step 3: Run both, expect FAIL** (V42 missing; the stale-client test may already pass — keep it as a guard)

Run: `cd backend && SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:<throwaway port>/... ./gradlew test --tests '*FlywayMigrationsIntegrationTest*' --tests '*PaymentCheckoutIntegrationTest*'` against a throwaway Postgres 16 container you start yourself (the Flyway tests only run on Postgres).

- [ ] **Step 4: Write V42** — exactly:

```sql
-- Packs are priced in AUD; the 1.99 starter pack is retired (kept for old orders).
UPDATE products SET currency = 'aud';
UPDATE products SET active = FALSE WHERE product_key = 'starter';
```

- [ ] **Step 5: E2eProductSeeder** seeds only `value` (超值包, 300, bonus 30, 499) and `big` (豪华包, 700, bonus 100, 999), both `currency = "aud"`.

- [ ] **Step 6: Point the integration tests away from `starter`.** Each test that does `findByProductKeyAndActiveTrue("starter") ?: save(Product("starter", …))` or `product("starter", …)` uses the key `"test-pack"` instead (same credits/price), so its amount assertions stay as they are and no `save` collides with V42's inactive `starter` row on Postgres.

- [ ] **Step 7: Run** the five payment integration classes + Flyway on Postgres and the full backend suite on H2 (`./gradlew test`). Expected: all pass.

- [ ] **Step 8: Commit** `feat(payment): V42 prices packs in AUD and retires the starter pack`

---

### Task 2: Checkout asks for Terms consent

**Files:**
- Modify: `backend/src/main/kotlin/com/werewolf/service/StripeGateway.kt` (`CheckoutRequest`)
- Modify: `backend/src/main/kotlin/com/werewolf/service/PaymentService.kt:118-128`
- Modify: `backend/src/main/kotlin/com/werewolf/service/StripeGatewayImpl.kt`
- Test: `PaymentCheckoutIntegrationTest.kt`, `StripeSandboxIntegrationTest.kt`

**Interfaces:**
- Produces: `CheckoutRequest.termsUrl: String`, `CheckoutRequest.refundPolicyUrl: String` = `"${props.frontendBaseUrl}/terms.html"` / `"${props.frontendBaseUrl}/refunds.html"`.

- [ ] **Step 1: Failing test** — extend `first checkout commits one order…` (or a new test):

```kotlin
assertThat(req.termsUrl).isEqualTo("${props.frontendBaseUrl}/terms.html")
assertThat(req.refundPolicyUrl).isEqualTo("${props.frontendBaseUrl}/refunds.html")
```

- [ ] **Step 2: Failing sandbox assertion** in `createCheckout creates a real sandbox session…`:

```kotlin
assertThat(session.consentCollection?.termsOfService).isEqualTo("required")
assertThat(session.customText?.termsOfServiceAcceptance?.message)
    .contains("${props.frontendBaseUrl}/terms.html").contains("${props.frontendBaseUrl}/refunds.html")
```

- [ ] **Step 3: Run, expect FAIL** (compile error on the new fields).

- [ ] **Step 4: Implement.** Add the two fields; `PaymentService` fills them; `StripeGatewayImpl` adds

  - `.setConsentCollection(ConsentCollection.builder().setTermsOfService(TermsOfService.REQUIRED).build())`
  - `.setCustomText(CustomText.builder().setTermsOfServiceAcceptance(TermsOfServiceAcceptance.builder().setMessage("I agree to the [Terms of Service](${req.termsUrl}) and the [Refund Policy](${req.refundPolicyUrl}).").build()).build())`

  Both are required together: verified 2026-11-22, Stripe refuses `consent_collection` alone unless the account has a Terms URL in its public details ("You cannot collect consent to your terms of service unless a URL is set in the Stripe Dashboard"), and accepts it with the `custom_text` message.

- [ ] **Step 5: Run** `PaymentCheckoutIntegrationTest` (H2) and the sandbox test: `STRIPE_SANDBOX_TESTS_ENABLED=true ./gradlew test --tests '*StripeSandboxIntegrationTest*'` (test-mode key from `STRIPE_SANDBOX_SECRET`). Expected: pass, 7/7 sandbox.

- [ ] **Step 6: Commit** `feat(payment): Checkout requires agreeing to the Terms and Refund Policy`

---

### Task 3: The three policy pages

**Files:**
- Create: `frontend/public/terms.html`, `frontend/public/refunds.html`, `frontend/public/privacy.html`
- Test: `frontend/src/__tests__/policyPages.test.ts`

**Interfaces:**
- Produces: URLs `/terms.html`, `/refunds.html`, `/privacy.html` (Task 2 builds the first two, Task 4 links all three).

- [ ] **Step 1: Failing test** — reads each file with `node:fs` from `public/`:

```ts
for (const page of ['terms', 'refunds', 'privacy']) {
  it(`${page}.html names the business, the contact and the other pages`, () => {
    const html = read(page)
    expect(html).toContain('Blue Vertex')
    expect(html).toContain('mailto:wangdaqian08@gmail.com')
    expect(html).toMatch(/Last updated/i)
    for (const other of ['terms', 'refunds', 'privacy'].filter((p) => p !== page))
      expect(html).toContain(`href="/${other}.html"`)
    expect(html).not.toMatch(/<script/i) // readable without JavaScript
    expect(html).not.toMatch(/\bno refunds?\b/i) // unlawful wording in Australia
  })
}
it('the refund policy states the change-of-mind rule and the consumer guarantees', () => {
  const html = read('refunds')
  expect(html).toMatch(/change of mind/i)
  expect(html).toMatch(/14 days/)
  expect(html).toMatch(/Australian Consumer Law/)
})
it('the terms cover credits, role-pick draws, minors and AUD', () => {
  const html = read('terms')
  for (const s of [/no cash value/i, /random/i, /under 18/i, /AUD/, /Australian Consumer Law/]) expect(html).toMatch(s)
})
```

- [ ] **Step 2: Run, expect FAIL** (`npx vitest run src/__tests__/policyPages.test.ts`).

- [ ] **Step 3: Write the pages** per spec §1 and D1–D4, D7, D9 (the refund page includes the D2 example with the returned-card note: "a role-pick charge that is returned in full does not count as spending"; for now the "how to ask" section says email with the order number — PR B replaces it with the button). Ink & Paper tokens and fonts in an inline `<style>`, readable at 360 px, a back link to `/`. Retention wording per the spec's builder's calls. Governing law: "the laws of Australia".

- [ ] **Step 4: Run, expect PASS.** Also `npx prettier --check public/*.html`.

- [ ] **Step 5: Commit** `feat(legal): Terms, Refund Policy and Privacy Policy pages`

---

### Task 4: Prices in AUD and links in the app

**Files:**
- Create: `frontend/src/utils/money.ts`, `frontend/src/__tests__/money.test.ts`
- Modify: `frontend/src/views/AccountView.vue` (replace the local `fmtMoney` and its currency sets with the util)
- Modify: `frontend/src/views/LobbyView.vue` (buy sheet price, AUD line, lobby footer)
- Modify: `frontend/src/i18n/locales/en.ts`, `zh.ts`
- Modify: `frontend/src/mocks/data.ts` (mock products and orders → `aud`; drop a starter product if one exists)
- Test: `LobbyViewCredits.test.ts` or `LobbyView.test.ts`, `AccountView.test.ts:421-430` (update)

**Interfaces:**
- Produces: `formatMoney(amountCents: number, currency: string): string` — `aud` → `A$4.99`; zero-decimal and three-decimal currencies keep today's rules (moved from AccountView); any other → `"<amount> <CODE>"` (e.g. `4.99 USD`).
- testids: `buy-terms-line`, `buy-terms-link`, `buy-refunds-link`, `lobby-legal`, `legal-terms`, `legal-privacy`, `legal-refunds`, `legal-contact`.

- [ ] **Step 1: Failing tests**

```ts
// money.test.ts
expect(formatMoney(499, 'aud')).toBe('A$4.99')
expect(formatMoney(499, 'AUD')).toBe('A$4.99')
expect(formatMoney(499, 'usd')).toBe('4.99 USD')     // formats a non-AUD amount with its code
expect(formatMoney(500, 'jpy')).toBe('500 JPY')      // zero-decimal kept
// lobby (buy sheet open, products from the mock: value 499 aud, big 999 aud)
expect(wrapper.get('[data-testid="buy-value"]').text()).toContain('A$4.99')
expect(wrapper.get('[data-testid="buy-terms-line"]').text()).toContain('Prices in AUD.')
expect(wrapper.get('[data-testid="buy-terms-link"]').attributes('href')).toBe('/terms.html')
expect(wrapper.get('[data-testid="buy-refunds-link"]').attributes('href')).toBe('/refunds.html')
// lobby footer, logged out AND logged in
expect(wrapper.get('[data-testid="legal-terms"]').attributes('href')).toBe('/terms.html')
expect(wrapper.get('[data-testid="legal-privacy"]').attributes('href')).toBe('/privacy.html')
expect(wrapper.get('[data-testid="legal-refunds"]').attributes('href')).toBe('/refunds.html')
expect(wrapper.get('[data-testid="legal-contact"]').attributes('href')).toBe('mailto:wangdaqian08@gmail.com')
// AccountView: a usd order shows "4.99 USD", an aud order "A$4.99" (replaces the old "$4.99, no USD" test)
```

- [ ] **Step 2: Run, expect FAIL.**

- [ ] **Step 3: Implement.** Buy sheet line text (en): `Prices in AUD. Purchases are subject to our {terms} and {refunds}.` with link labels `Terms` / `Refund Policy` (use i18n slots or two keys). D5 keeps purchase text English: `zh.ts` carries the same English strings for this line. Footer: `Terms · Privacy · Refunds · Contact` with `target="_blank" rel="noopener"` on the page links. Keep the footer small and muted (Ink & Paper `--muted`).

- [ ] **Step 4: Run** `npx vitest run`, `npx vue-tsc -b`, `npx eslint .`, `npx prettier --check src`. Expected: all green (the i18n parity test included). Then the mocked account E2E: `npx playwright test e2e/account.spec.ts` (port 5174 free first).

- [ ] **Step 5: Commit** `feat(payment): AUD prices, Terms/Refund links on the buy sheet, legal footer in the lobby`

---

### Task 5: Payment E2E follows AUD and consent

**Files:**
- Modify: `frontend/e2e/payment/payment-flow.spec.ts`

**Interfaces:**
- Consumes: testid `buy-value` (Task 4), Checkout consent (Task 2), seeded `value` pack (Task 1).

- [ ] **Step 1: Update the spec**
  - `buyStarter` → `buyPack(page, request, card)` clicking `buy-value`; expectations `+330` / balance `330`; refund and dispute tests expect balance `0` as before.
  - In `checkoutForm`, wait for **either** `input[name="number"]` **or** `[role="button"][data-value="card"]` to be visible in some frame; when the card option exists, click it, then wait for `input[name="number"]`. (AUD shows card / Klarna / Zip collapsed — observed 2026-11-22; USD shows the card form directly.)
  - Tick the Terms box: click the `label` that contains `input[name="termsOfServiceConsent"]` at `position: { x: 5, y: 5 }` (a plain `.check()` does not toggle Stripe's styled box — observed). Do it before submit.
  - After the return page, assert through `stripeApi('GET', 'checkout/sessions/<id>')`: `consent.terms_of_service === 'accepted'` and `currency === 'aud'`.
- [ ] **Step 2: Lint** `npx eslint -c eslint.e2e-waits.config.js e2e/payment && npx eslint e2e/payment && npx tsc --noEmit -p tsconfig.e2e.json`.
- [ ] **Step 3: Run** `npm run test:e2e:payment` (needs stripe-cli + `STRIPE_SANDBOX_SECRET`, ports 8080/5174 free). Expected: phase 1 1/1, phase 2 3/3.
- [ ] **Step 4: Commit** `test(payment): E2E buys the AUD value pack and accepts the Terms`

---

### Task 6: Runbook and spec follow-ups

**Files:**
- Modify: `docs/payments-prod-keys.md`

- [ ] **Step 1: Edit the runbook** (spec §6, PR A parts):
  - New step 0 "Public business details" (both modes): name Blue Vertex, support email, website `https://private.youplay123.online`, Terms URL `…/terms.html`, Privacy URL `…/privacy.html`; statement descriptor short and recognisable (e.g. `BLUEVERTEX GAME`).
  - Payment methods note: with AUD, Stripe's dynamic payment methods also offer Klarna and Zip; keep or switch them off in Dashboard → Settings → Payment methods (the owner chose cards, Apple Pay and Google Pay first).
  - Rehearsal / live steps: the value pack A$4.99 instead of the starter pack; tick the Terms box; the Dashboard payment shows the Terms accepted.
  - Refund decision query (read-only, `BEGIN READ ONLY; … ROLLBACK;`) for emailed requests: input `order_no`; output amount, credits, `completed_at`, days since, and "refundable" when ≤ 14 days and no `PERK_SPEND` after `completed_at` without a `REFUND` row of the same `perk_activation_id`. Test it against the throwaway Postgres with a seeded example (spec D2 example: refundable before the −25, not after; a −25/+25 pair stays refundable).
  - "Before live keys: have an Australian lawyer review the three pages (fixed-fee review)."
- [ ] **Step 2: Commit** `docs(payment): runbook — public details, AUD pack, Terms consent, refund decision query`

---

## After all tasks

Full checks: backend `./gradlew test` (H2) + payment/Flyway classes on a throwaway Postgres; frontend `npx vitest run`, `npx vue-tsc -b`, `npx eslint .`, the no-sleeps lint; payment E2E on the sandbox. Then the final whole-branch review, push, and a PR (payments remain off).
