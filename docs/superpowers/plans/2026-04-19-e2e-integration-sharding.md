# E2E Integration — CI Sharding + Nickname Namespacing

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut E2E integration wall-clock (~7 min → ~3 min) and make parallel runs safe by (1) sharding the CI job and (2) namespacing hard-coded nicknames that collide when the same backend sees concurrent sessions.

**Architecture:** Two independent patches. Patch 1 (CI sharding) is zero-risk — each matrix shard runs on its own GitHub runner VM, its own fresh Spring Boot, its own H2. Patch 2 (nickname-namespacing) removes the last shared-state collision so a single shard can safely flip `fullyParallel: true` in a future follow-up.

**Tech Stack:** GitHub Actions matrix strategy, Playwright `--shard=M/N` with blob reporter, `playwright merge-reports`, bash `join-room.sh --prefix`, Vue `localStorage`, Spring dev-auth `/api/auth/dev` (nickname → deterministic `userId: dev:${lowercase(nickname)}`).

---

## Patch 1 — CI Sharding (apply now)

**Files:**
- Modify: `.github/workflows/ci.yml` — replace the single `e2e-integration` job with a matrix-sharded version + add a `merge-reports-integration` job that mirrors `merge-reports` for the UI suite.

### Task 1: Shard the `e2e-integration` job

**Replace the block at `ci.yml:184-246`** with the following. The key changes:
- `strategy.matrix.shard: [1, 2, 3]` + `fail-fast: false`
- Name includes shard index so failures are attributed
- `--shard=${{ matrix.shard }}/3` flag on the Playwright command
- `blob` reporter in CI (already configured in `playwright.real.config.ts:20-22`)
- Upload `blob-report/` per shard for the merge job
- Keep the per-shard HTML upload on failure for direct artifact inspection

```yaml
  # ── E2E Integration (real backend) ──────────────────────────────────────────
  e2e-integration:
    name: E2E · Integration (${{ matrix.shard }}/3)
    runs-on: ubuntu-latest
    needs: backend
    strategy:
      fail-fast: false
      matrix:
        shard: [1, 2, 3]

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: 17
          distribution: temurin

      - name: Cache Gradle packages
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('backend/**/*.gradle.kts', 'backend/gradle/wrapper/gradle-wrapper.properties') }}
          restore-keys: gradle-${{ runner.os }}-

      - uses: actions/setup-node@v4
        with:
          node-version: 24
          cache: npm
          cache-dependency-path: frontend/package-lock.json

      - name: Install dependencies
        run: npm ci
        working-directory: frontend

      - name: Cache Playwright browsers
        id: playwright-cache
        uses: actions/cache@v4
        with:
          path: ~/.cache/ms-playwright
          key: playwright-${{ runner.os }}-${{ hashFiles('frontend/package-lock.json') }}
          restore-keys: |
            playwright-${{ runner.os }}-

      - name: Install Playwright browsers
        if: steps.playwright-cache.outputs.cache-hit != 'true'
        run: npx playwright install --with-deps chromium
        working-directory: frontend

      - name: Install Playwright OS dependencies (cache hit)
        if: steps.playwright-cache.outputs.cache-hit == 'true'
        run: npx playwright install-deps chromium
        working-directory: frontend

      - name: E2E integration tests (shard ${{ matrix.shard }}/3)
        run: npm run test:e2e:integration -- --shard=${{ matrix.shard }}/3
        working-directory: frontend

      - name: Upload blob report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: blob-report-integration-${{ matrix.shard }}
          path: frontend/blob-report/
          retention-days: 1

      - name: Upload HTML report on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: playwright-report-integration-${{ matrix.shard }}
          path: frontend/playwright-report/
          retention-days: 7

  merge-reports-integration:
    name: Merge Integration E2E Reports
    if: always()
    needs: e2e-integration
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-node@v4
        with:
          node-version: 24
          cache: npm
          cache-dependency-path: frontend/package-lock.json

      - name: Install dependencies
        run: npm ci

      - name: Download blob reports
        uses: actions/download-artifact@v4
        with:
          pattern: blob-report-integration-*
          path: frontend/blob-report
          merge-multiple: true

      - name: Merge reports
        run: npx playwright merge-reports --reporter html ./blob-report

      - name: Upload merged HTML report on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: playwright-report-integration-merged
          path: frontend/playwright-report/
          retention-days: 7
```

- [ ] **Step 1: Apply the yml edit**

Replace lines 184–246 of `.github/workflows/ci.yml` with the block above. Verify the file still parses with:

```bash
yq eval '.jobs | keys' .github/workflows/ci.yml
```

Expected output: `[backend, lint-test, e2e-ui, merge-reports, e2e-integration, merge-reports-integration]`.

- [ ] **Step 2: Push and observe one run**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: shard e2e-integration 3-ways to cut wall-clock and isolate backend state"
git push
```

Watch the Actions tab. Expected: three `E2E · Integration (N/3)` jobs run in parallel, each with its own fresh Spring Boot (port 8080 on a dedicated runner VM). Total wall clock should drop from ~7 min to ~3–4 min.

If any shard fails with a test that passed in the serial run, the failure is order-dependent — mitigate by moving the flaky file to a different shard (Playwright shards by filename hash) or by fixing the test's setup/teardown.

---

## Patch 2 — Nickname namespacing (scoped, apply after Patch 1 lands)

**Context:** Each spec's host browser calls `.fill('Host')` on the login form. Spring's `/api/auth/dev` maps nicknames deterministically: `Host` → `userId: dev:host`. Within a single shard all tests share one backend, so if we ever flip `fullyParallel: true` to run files concurrently, two hosts will fight over `dev:host`'s seat assignments. Bots are already safe — `join-room.sh:177` already randomizes `NICK="${PREFIX}${i}-${RUN_ID}"`.

**What we change:** make the host nickname unique per Playwright worker. A `workerIndex` from `testInfo` is enough because Playwright guarantees at most one test per worker at a time and all specs use the `setupGame` helper (so there's one host per worker).

**Files:**
- Modify: `frontend/e2e/real/helpers/multi-browser.ts` — accept an optional `hostNick`, default to `` `Host-w${workerIndex}-${rand}` ``. Replace the two literal `'Host'` occurrences.
- Modify: `frontend/e2e/real/flow-12p-sheriff.spec.ts`, `frontend/e2e/real/idiot-flow.spec.ts`, `frontend/e2e/real/werewolf-win.spec.ts`, `frontend/e2e/real/revote-flow.spec.ts` — they look up the host bot by `b.nick === 'Host'`; change to a passed-in token or to checking `b.userId === ctx.hostUserId`.
- Modify: `frontend/e2e/real/login.spec.ts` — the two Alice/Host/Guest tests currently use fixed names; switch to `randomName(testInfo)` helper.
- Add: `frontend/e2e/real/helpers/nicknames.ts` — export `hostNickname(testInfo)` and `uniqueNick(base, testInfo)`.

### Task 3: Add the nickname helper

**Files:**
- Create: `frontend/e2e/real/helpers/nicknames.ts`

- [ ] **Step 1: Write the helper**

```typescript
// frontend/e2e/real/helpers/nicknames.ts
import type { TestInfo } from '@playwright/test'

/**
 * Build a unique nickname scoped to this Playwright worker + test.
 *
 * Spring's /api/auth/dev maps nickname → userId: dev:${lowercase(nickname)}.
 * Two parallel tests both using 'Host' collide on dev:host. Suffixing with
 * workerIndex + a short random token keeps each test's user space isolated.
 */
export function uniqueNick(base: string, testInfo: TestInfo): string {
  const rand = Math.random().toString(36).slice(2, 6)
  return `${base}-w${testInfo.workerIndex}-${rand}`
}

/** Shortcut for the host browser nickname. */
export function hostNickname(testInfo: TestInfo): string {
  return uniqueNick('Host', testInfo)
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/e2e/real/helpers/nicknames.ts
git commit -m "test(e2e): add worker-scoped nickname helper"
```

### Task 4: Thread the nickname through `setupGame`

**Files:**
- Modify: `frontend/e2e/real/helpers/multi-browser.ts`

Currently `multi-browser.ts:93` calls `.fill('Host')` and line 176 writes `stateData.hostNick = 'Host'`. We plumb a `hostNick` string through the helper's return value so specs can look the host up without hard-coding.

- [ ] **Step 1: Add `hostNick` to `setupGame` signature**

```typescript
// multi-browser.ts — at the top of setupGame(...)
import { hostNickname } from './nicknames'
// inside setupGame, right after `const contexts: BrowserContext[] = []`
const hostNick = opts.hostNick ?? hostNickname(opts.testInfo)
```

- [ ] **Step 2: Replace literal `'Host'`**

Change line 93: `.fill('Host')` → `.fill(hostNick)`.
Change line 176: `stateData.hostNick = 'Host'` → `stateData.hostNick = hostNick`.
Change line 269 `const hostBot = bots.find((b) => b.nick === 'Host')` → `const hostBot = bots.find((b) => b.nick === hostNick)`.

- [ ] **Step 3: Expose `hostNick` in the return**

```typescript
return { hostPage, gameId, roomCode, pages, botsByRole, hostRole, hostNick, /* existing fields */ }
```

- [ ] **Step 4: Pass `testInfo` from each spec**

Each caller of `setupGame(...)` passes `testInfo` (already available via Playwright's test fixture). Update `flow-12p-sheriff.spec.ts`, `idiot-flow.spec.ts`, `werewolf-win.spec.ts`, `revote-flow.spec.ts`, `game-flow.spec.ts`:

```typescript
// Before
const ctx = await setupGame(browser, { totalPlayers: 12, hasSheriff: true })
// After
const ctx = await setupGame(browser, { totalPlayers: 12, hasSheriff: true, testInfo })
```

- [ ] **Step 5: Update host-bot lookups in specs**

Replace every `b.nick === 'Host'` in `idiot-flow.spec.ts`, `werewolf-win.spec.ts`, `revote-flow.spec.ts` with `b.nick === ctx.hostNick` (or `b.userId === ctx.hostUserId` if that's exposed).

- [ ] **Step 6: Commit**

```bash
git add frontend/e2e/real/
git commit -m "test(e2e): namespace host nickname per worker for parallel-safe sessions"
```

### Task 5: Fix `login.spec.ts` fixed names

**Files:**
- Modify: `frontend/e2e/real/login.spec.ts`

Replace every `.fill('Alice')`, `.fill('Host')`, `.fill('Guest')` with `.fill(uniqueNick('Alice', testInfo))` etc. and assert on the stored value rather than the literal string:

```typescript
// Before
await page.getByPlaceholder('Enter your nickname').fill('Alice')
// ...
expect(nickname).toBe('Alice')

// After
const alice = uniqueNick('Alice', testInfo)
await page.getByPlaceholder('Enter your nickname').fill(alice)
// ...
expect(nickname).toBe(alice)
```

- [ ] **Step 1: Apply the edits**
- [ ] **Step 2: Run `npm run test:e2e:integration -- login.spec.ts` to confirm still passes**
- [ ] **Step 3: Commit**

### Task 6: Flip `fullyParallel: true` (optional, last)

**Files:**
- Modify: `frontend/playwright.real.config.ts`

Only after Tasks 3–5 land and a full CI run is green. Flipping this lets Playwright run tests within one shard in parallel. Watch the first CI run closely; if any spec regresses on the new config, revert this one-line change — nicknames stay, and we still keep the 3-way shard win.

```diff
-  fullyParallel: false, // tests share one backend instance; avoid parallel state collisions
+  fullyParallel: true,  // backend state isolated per nickname; safe to parallelize files
```

- [ ] **Step 1: Apply the edit**
- [ ] **Step 2: Push + observe one CI run**
- [ ] **Step 3: If green, commit as-is. If red, revert this file and keep Tasks 3–5.**

---

## Risk

Patch 1 is zero-risk — each shard is a separate GitHub runner VM so state cannot cross. Worst case: one shard gets an uneven distribution of slow tests and takes longer than the others, but Playwright's file-hash sharding usually balances out for 31 tests / 3 shards.

Patch 2's risk is concentrated in Task 4 — renaming every host-lookup touchpoint. Miss one and that spec will silently fail to find the host bot. The escape hatch is to keep `fullyParallel: false` (Task 6 not applied) — then the nickname change is cosmetic but safe, and the code is ready for a later flip.
