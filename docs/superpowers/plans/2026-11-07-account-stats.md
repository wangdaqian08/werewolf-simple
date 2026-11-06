# Account Stats (我的戰績) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A "我的戰績" card at the top of the account page (totals, per-camp W/L, recent-20 勝/負 strip, win-rate percentile), plus a labelled balance in the 點將卡 panel.

**Architecture:** A read-only `UserStatsService` aggregates finished games (`games ⋈ game_players`) for the caller and ranks win rates across players with ≥5 games. It is exposed as `GET /api/user/stats`. A presentational `StatsCard.vue` renders it in `AccountView`. `RolePickPanel` gets a copy-only change.

**Tech Stack:** Kotlin / Spring Boot 4 / JPA (H2 tests, Postgres CI), Vue 3 + TS, Vitest, Playwright (screenshots only).

**Spec:** `docs/superpowers/specs/2026-11-07-account-stats-design.md`

## Global Constraints

- **Counted game:** `endedAt != null && winner != null`, and the user has a `game_players` row.
- **Win:** `role.isWolf == (winner == WinnerSide.WEREWOLF)`. Reuse `PlayerRole.isWolf`; do not keep a second role list.
- **Percentile:**
  - The population is users with ≥ 5 counted games.
  - X = floor(100 × (players in the population other than the caller with a strictly lower win rate) / (population size − 1)). It is 0 when the population size is ≤ 1.
  - It is null when the caller has < 5 games; then `gamesToRank = 5 − games`, otherwise 0.
  - Compare win rates exactly, without integer rounding: compare `w1*g2` against `w2*g1`.
- **Copy:** `zh.ts` is Traditional Chinese, `en.ts` is English. Exact zh strings:
  - `我的戰績`, `總局數`, `勝`, `負`, `勝率`
  - `打敗了 {pct}% 的玩家`, `再玩 {n} 局解鎖排名`
  - `狼人陣營`, `好人陣營`, `最近 20 局（新 → 舊）`
  - `訪客帳號跟隨暱稱，更換暱稱會看不到原來的戰績和積分`
  - `餘額 ◈{n}`, `（凍結 {n}）`
- No `!!` in `backend/src/main`. No new Spring test contexts (`@SpringBootTest @ActiveProfiles("test")` only, no `@MockBean`/`@SpyBean`). Use portable JPQL, since tests run on H2 locally and Postgres in CI.
- `data-testid` on every element a test reads. Type-check with `npx vue-tsc -b`. Run `npm run format:check` (Prettier; CI fails on it).
- Stage individual files. Commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Cancelled games mixed into the counts.** A game with `endedAt` set but `winner` null must not count as a loss. Test in Task 1.
2. **The caller ranked against themselves, or ties counted as beaten.** Test in Task 1.
3. **A player with zero games** (a fresh guest): the endpoint returns zeros, `recent=[]`, `percentile=null`, `gamesToRank=5`, and the card renders without errors. Tests in Task 1 and Task 2.
4. **Another user's data leaking**: the endpoint reads the user only from the authentication principal and takes no userId parameter. Test in Task 1.
5. **An unfinished game** (`endedAt` null) where the user is mid-game is excluded. Test in Task 1.

---

### Task 1: `UserStatsService` + `GET /api/user/stats`

**Files:**
- Create: `backend/src/main/kotlin/com/werewolf/service/UserStatsService.kt`
- Modify: `backend/src/main/kotlin/com/werewolf/repository/Repositories.kt` (one JPQL query on `GamePlayerRepository`)
- Modify: `backend/src/main/kotlin/com/werewolf/controller/UserController.kt` (add `@GetMapping("/stats")`)
- Test: `backend/src/test/kotlin/com/werewolf/integration/UserStatsIntegrationTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class CampRecord(val wins: Int, val losses: Int)
  data class UserStats(val games: Int, val wins: Int, val losses: Int, val winRate: Int /*floor %, 0 when no games*/,
                       val wolf: CampRecord, val good: CampRecord, val recent: List<String> /*"W"|"L", newest first, ≤20*/,
                       val percentile: Int?, val gamesToRank: Int)
  fun UserStatsService.statsFor(userId: String): UserStats
  ```
  `GET /api/user/stats` → `UserStats` JSON for `authentication.principal`.
- Query: `GamePlayerRepository` gets one JPQL query returning rows `(userId, role, winner, endedAt)` for counted games (join `Game` on gameId, with `g.endedAt IS NOT NULL AND g.winner IS NOT NULL`). Use it unfiltered for the population and filtered by user for the caller. Two simple queries are fine: `findFinishedResults()` and `findFinishedResultsFor(userId)`. Compute wins in Kotlin with `role.isWolf`.

- [ ] **Step 1: Write the failing tests.** Seed users, games and game_players directly through the repositories, as `GameReviewIntegrationTest` does.
  - `counts only finished games with a winner`: games with winner null and with endedAt null are both excluded (Review Focus 1, 5).
  - `wolf and good wins by camp`:
    - WHITE_WOLF_KING in a WEREWOLF win → wolf.wins.
    - MECHANICAL_WOLF in a VILLAGER win → wolf.losses.
    - SEER in a VILLAGER win → good.wins.
  - `recent is newest first and capped at 20`: 22 games with distinct endedAt → `recent.size == 20`, and the first entry is the latest game.
  - `under 5 games gives null percentile and gamesToRank`: 3 games → `percentile == null`, `gamesToRank == 2`.
  - `zero games`: everything 0, `recent == []`, `percentile == null`, `gamesToRank == 5` (Review Focus 3).
  - `percentile counts strictly lower win rates among others`. Population of caller (60%, 5 games) plus A (40%, 5), B (60%, 10), C (80%, 5), D (100%, 2 games — excluded, under 5) → beaten = {A} = 1 of 3 others → 33.
  - `population of one gives 0`.
  - `controller returns only the caller's stats`: GET with user X's token returns X's numbers. There is no userId parameter (Review Focus 4).

- [ ] **Step 2: Run, expect a compile failure.** `cd backend && ./gradlew test --tests 'com.werewolf.integration.UserStatsIntegrationTest'`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the class, then the full `./gradlew test`.** Expect PASS.
- [ ] **Step 5: Commit** `feat(backend): own-stats endpoint for the account page`

---

### Task 2: Stats card, guest note, 點將卡 balance label (frontend)

**Files:**
- Create: `frontend/src/components/StatsCard.vue`
- Modify: `frontend/src/types/index.ts` (`UserStats`), `frontend/src/services/userService.ts` (`getStats(): Promise<UserStats>` → `GET /user/stats`), `frontend/src/mocks/index.ts` + `frontend/src/mocks/data.ts` (mock stats: 37 games, 22 W / 15 L, 59%, wolf 9/5, good 13/10, recent `WWLWLLWWWLWLWWLLWLWW`, percentile 72), `frontend/src/views/AccountView.vue`, `frontend/src/components/RolePickPanel.vue`, `frontend/src/i18n/locales/{zh,en}.ts`
- Test: `frontend/src/__tests__/StatsCard.test.ts`, `frontend/src/__tests__/AccountView.test.ts` (extend), `frontend/src/__tests__/RolePickPanel.test.ts` (extend)

**Interfaces:**
- Consumes: `GET /api/user/stats` (Task 1).
- `StatsCard` props: `stats: UserStats`. Test ids:
  - `stats-card`, `stats-games`, `stats-wins`, `stats-losses`, `stats-winrate`
  - `stats-percentile` (beaten text) or `stats-unlock` (unlock text)
  - `stats-wolf`, `stats-good`
  - `stats-recent` containing cells `stats-recent-cell` with `data-result="W"|"L"`
- `AccountView`:
  - Load stats on mount alongside the wallet. Render `StatsCard` above `account-wallet`.
  - If the stats load fails, hide the card. Do not block the page.
  - Show `account-guest-note` (the guest copy) only when `userStore.isGuest`.
- `RolePickPanel`: `rp-balance` renders `餘額 ◈{n}`. The frozen suffix stays `（凍結 {n}）`. Layout is per the approved mockup (option A strip, 10 cells per row).

- [ ] **Step 1: Write the failing tests.**
  - `StatsCard`:
    - mock numbers render; 20 cells in order with the right `data-result`;
    - percentile 72 → `打敗了 72% 的玩家`;
    - `percentile null, gamesToRank 2` → `再玩 2 局解鎖排名` and no percentile element;
    - zero games → zeros and 0 cells (Review Focus 3).
  - `AccountView`:
    - the card renders above the wallet when stats load;
    - a failed load → no card, wallet still shown;
    - the guest note appears for a guest and not for a Google user.
  - `RolePickPanel`:
    - balance 80, frozen 0 → `rp-balance` text `餘額 ◈80`;
    - frozen 20 → contains `餘額 ◈60` and `（凍結 20）`. Pass balance 60 and frozenCredits 20.
- [ ] **Step 2: Run, expect failures.** `cd frontend && npx vitest run src/__tests__/StatsCard.test.ts src/__tests__/AccountView.test.ts src/__tests__/RolePickPanel.test.ts`
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the full `npx vitest run`, `npx vue-tsc -b`, `npm run lint`, `npm run format:check`.** Expect PASS / 0 errors.
- [ ] **Step 5: Commit** `feat(frontend): stats card on the account page, labelled role-pick balance`

---

### Task 3: Screenshots for the owner (no code)

- [ ] Start the mock dev server on a free port (not 5174). With Playwright at 417×614, screenshot:
  1. the account page as a Google user (stats card + wallet);
  2. the account page as a guest (guest note visible);
  3. a room with the 點將卡 panel showing `餘額 ◈…`, and with a card chosen so `（凍結 20）` shows.

  Save the files under the session scratchpad and open them for the owner. Do not commit the screenshots, and do not commit regenerated `frontend/e2e/screenshots/*`.
