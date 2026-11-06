# Account stats (我的戰績): design

Date: 2026-11-07. Branch: `feat/account-stats`.

## Why

Players want to see how they have done, and to see their credits before deciding on a 點將卡. The account page already shows credits. This adds a stats card, and makes the balance in the 點將卡 panel read clearly as a balance.

The owner confirmed every rule below (grilling Q1–Q11, 2026-11-07). The owner also chose layout A (a strip of 勝/負 cells) from a mockup.

## Goals

- At the top of the existing account page (`/account`, opened from the lobby ☰), a "我的戰績" card. Both guest and Google users can see it.
- The 點將卡 panel shows its balance with a label.
- After the code is done, show the owner screenshots of the account page and the 點將卡 panel (owner request).

## Non-goals

- No new entry point. No avatar or nickname button in the lobby.
- No viewing another player's stats.
- No per-game links to the review page, and no per-game role or date in the recent list.
- No win-streak reward. That idea is parked for later; see the `streak-reward-idea` memory.
- No merging of guest and Google accounts.

## Anti-goals

- **Stats that disagree with the game.** A cancelled game counted, or a win credited to the wrong camp.
- **Stats that leak someone else's data.** The endpoint returns only the caller's own numbers, plus the anonymous percentile.

## Rules (confirmed)

1. **Games that count**: games with `endedAt != null` and `winner != null` that the user played (has a `game_players` row). Cancelled games (`winner == null`) are excluded. The host plays, so their games count. Games finished before launch count too.
2. **Win**: the player's camp is the winner's camp: `role.isWolf == (winner == WEREWOLF)`. Alive or dead does not matter. 白狼王, 黑狼王 and 機械狼 are wolves (`PlayerRole.isWolf`). This is the same rule `RewardBreakdown` uses.
3. **Card content**:
   - total games, wins, losses, win rate;
   - "打敗了 X% 的玩家";
   - per camp: 狼人陣營 W/L and 好人陣營 W/L;
   - the most recent 20 counted games as 勝/負 cells, newest first (left to right, wrapping to a second row). No role, no date, not clickable.
4. **Percentile ("打敗 X%")**:
   - The population is every account (guest and Google together) with at least 5 counted games.
   - X = the share of the *other* players in that population whose win rate is strictly lower than the caller's, rounded down to a whole percent.
   - If the caller has fewer than 5 games, show "再玩 N 局解鎖排名" (N = 5 − games) instead.
   - If the caller is the only player in the population, X = 0.
   - Computed on every request, with no cache.
5. **Credits**: the account page's existing wallet section stays as it is.
6. **點將卡 panel**: the balance reads "餘額 ◈80". With frozen credits it reads "餘額 ◈60（凍結 20）".
7. **Guest note**: for guests, the account page shows one line: "訪客帳號跟隨暱稱，更換暱稱會看不到原來的戰績和積分".
8. A user with no games sees zeros, an empty strip, and the unlock message.

## Shape (builder may adjust names)

- `GET /api/user/stats` (authenticated, own data only) →
  `{ games, wins, losses, winRate /*0–100 int*/, wolf: {wins, losses}, good: {wins, losses}, recent: ['W'|'L'…≤20, newest first], percentile: number|null, gamesToRank: number }`
  `percentile` is null when the caller has fewer than 5 games.
- Backend: one aggregate query over `games ⋈ game_players` (no per-game N+1). The percentile needs each user's wins and games in that population: one GROUP BY. It must use portable SQL/JPQL, since tests run on H2 locally and Postgres in CI.
- Frontend: a `StatsCard` component at the top of `AccountView` (above the wallet). zh.ts copy in Traditional Chinese, plus en.ts. A `data-testid` on every element tests read.

## Tests the builder should write

- Counting: a cancelled game is excluded; wolf and good wins are credited right (白狼王 / 機械狼 count as wolf); recent is ordered newest first and capped at 20.
- Percentile: under 5 games → null + gamesToRank; ties are not counted as beaten; a population of one → 0; the caller is never compared with themselves.
- Endpoint: it returns only the caller's numbers. No new Spring test context.
- Frontend: card renders numbers, the strip and the unlock text; the guest note shows only for guests; the 點將卡 balance label, with and without frozen credits.

## Delivery

When the code is done, screenshot the account page (stats + credits; guest and Google views) and the 點將卡 panel at 417×614 with mock data, and show them to the owner before the PR.
