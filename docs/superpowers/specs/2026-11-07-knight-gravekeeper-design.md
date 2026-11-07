# 骑士 (Knight) and 守墓人 (Gravekeeper): design

Date: 2026-11-07. Rules confirmed by the owner, question by question (grilling, five rounds, plus one spec review).

## Why

`docs/new-role.md` (2026-10) planned these two gods for 14–18-player boards. That roadmap is out of date: it predates
the mechanical wolf and the medium (PR #173), the role-pick card (V31) and the current night order. This spec settles
both roles against the code as it is today.

## Decision record

Rules that vary between rule sets, with the source the owner chose to follow. No official (网易) rule page was found
for any of them.

| # | Decision | Source |
|---|---|---|
| D1 | **骑士只能在非警上环节发动决斗.** The knight can never duel during the sheriff campaign, in any sub-phase. A knight killed at night cannot duel even before its death is announced. | [游民岛: 骑士被刀了可以用技能吗](https://www.youmindao.com/langrensha/216.html): "首轮有上警环节，该环节虽然法官还没有宣布骑士出局，但其实无法在该阶段发动决斗"; "骑士只能在非警上环节发动决斗". [游民岛: 骑士决斗规则](https://www.youmindao.com/langrensha/215.html): the duel happens after the campaign and before the exile vote. |
| D2 | **A duelled wolf has no death skill.** 黑狼王 does not shoot, 白狼王 does not take anyone. | [游民岛: 狼王被骑士戳了可以开枪吗](https://www.youmindao.com/langrensha/247.html); [维基学院: 骑士](https://zh.wikiversity.org/wiki/%E7%8B%BC%E4%BA%BA%E6%AE%BA/%E9%81%8A%E6%88%B2%E8%A7%92%E8%89%B2/%E9%A8%8E%E5%A3%AB): "決鬥死亡的狼王、白狼王和狼美人技能不能發動". |
| D4 | **机械狼 never shares a board with 骑士 or 守墓人** (owner, 2026-11-08; replaces the earlier Q11 "learns them as no skill"). The room setup blocks the second role while the first is on, and the server rejects the combination. The knight ships this in its own PR; the gravekeeper PR adds 守墓人 to the same rule. | No source defines it. Every 机械狼 rule page lists only the roles of its own board, 机械狼通灵师 (通灵师, 女巫, 猎人, 守卫, 平民, 狼人): [游民星空 测评](https://shouyou.gamersky.com/news/202409/1811620.shtml), [langrensha.net](https://www.langrensha.net/strategy/meachanical_wolf.html), [werewolves.games](https://werewolves.games/ji-xie-lang/). 骑士 and 守墓人 come from other boards (白狼王骑士; 石像鬼守墓人). |
| D3 | **No duel after a 自爆 the same day.** | Inferred, no explicit text found: a self-destruct ends the day at once and skips the remaining speeches ([当游网](https://www.3h3.com/news/238158.html)). The duel only exists during day speeches. |

## Balance (summary for the owner)

- **Knight: a strong god.**
  - One duel always removes someone (a wolf, or the knight) and publishes one camp fact.
  - It is strongest next to the seer or the medium: check a wolf, then duel it the next day.
  - Its built-in cost is that a successful duel skips that day's vote.
  - Add it together with a special wolf (白狼王 or 黑狼王), not on top of an otherwise plain board.
- **Gravekeeper: a weak god.**
  - It only confirms whether yesterday's exile was right, so its effect on balance is small.
  - Its information partly overlaps with the medium's, but the two do not conflict.
- **Both take a god seat.**
  - On the server, `validateRoleComposition` only checks `wolves + gods ≤ players`.
  - In the room setup UI, `RoomSetupPanel` keeps the god count within wolves ± 1 (`RoomSetupPanel.vue:230-252`).
  - Keeping about 1/3 villagers is still the host's job. This is unchanged.
- **The role-pick card's 神 option** uses `isGod`, so both roles join it with no change.

## Goals

- Two new god roles a host can add to a room: `KNIGHT` and `GRAVEKEEPER`.
- The rules below, enforced on the server. As everywhere else, the frontend only shows state and sends actions.
- Role descriptions (the role card's rules text) updated for every role whose rules these change.

## Non-goals

- Raising `MAX_PLAYERS` (15, `RoomSetupPanel.vue:201`). This is separate work: whether 16+ seats fit on a 417×614
  screen is unverified.
- Recommended-board presets.
- A sound effect for the duel. The knight has no narration.
- Mechanical-wolf skills for the new roles. They never share a board with it (D4).
- Fixing the existing host declare-out case where the current sheriff speaker is declared out. The speech does not
  auto-advance and the host must press next (`SheriffService.kt:511-526`). The knight cannot hit this case (D1), so it
  is tracked separately.

## Anti-goals (a failure even if it works)

- **A tell in the night flow.** The gravekeeper step runs differently depending on whether the gravekeeper is alive,
  or whether anyone was exiled.
- **A leak of a private shot.** A duel accepted or rejected depending on whether someone holds an unfired night shot.
- **Regressions.** Any existing board plays differently. This includes default boards: see the `ROLE_DEFINITIONS`
  order under Frontend.

## The rules (confirmed)

### 骑士 / KNIGHT (good side, god)

- **K1. When (D1).** Only in `DAY_DISCUSSION / RESULT_REVEALED`, every day: after the night result is revealed and
  before the vote starts.
  - Rejected everywhere else:
    - every `SHERIFF_ELECTION` sub-phase;
    - `RESULT_HIDDEN`;
    - `BADGE_HANDOVER`;
    - all of `DAY_VOTING`;
    - night.
  - Also rejected when `daySkipVoting` is true, that is after a 自爆 that day (D3).
- **K2. Once.** One duel per game, and only while the knight is alive.
  - The target is any living player other than the knight.
  - After the duel the knight has no skill, and its identity is public.
- **K3. Target is wolf-side** (`isWolf`: `WEREWOLF`, `WHITE_WOLF_KING`, `BLACK_WOLF_KING`, `MECHANICAL_WOLF`):
  - The target dies.
  - The day ends with no vote. This reuses self-destruct's `daySkipVoting` path: the day stays at
    `RESULT_REVEALED`, and the host can only go to night.
  - **The duelled wolf has no death skill (D2).** 黑狼王 does not shoot. A mechanical wolf that learned 猎人 or
    黑狼王 does not shoot. 白狼王 cannot self-destruct or take anyone.
- **K4. Target is good:** the knight dies, and the day continues normally. The vote still happens.
- **K5. Sheriff badge.** If the dead player (the knight or the duelled wolf) holds the badge, the normal handover runs
  (pass or destroy), routed through `DayRevealAdvancer` like a 白狼王-taken sheriff. Only 自爆 force-destroys the
  badge.
- **K6. Game end.** The win check runs right after the duel. Duelling the last wolf means villagers win. A failed duel
  that kills the last god in HARD_MODE means wolves win.
- **K7. HARD_MODE counterplay.** A living knight that has not dueled counts as counterplay in `HardModeCounterplay`,
  like a living hunter (`WinConditionChecker.kt:25-40`).
- **K8. An unfired night shot is lost.**
  - A hunter or 黑狼王 killed at night gets a private shot after the reveal, open until the vote starts.
  - A successful duel in that window sends the day to night, and the shot is dropped (night-init clears it,
    `NightOrchestrator.kt:817`). Self-destruct already behaves this way.
  - This is intended. Rejecting the duel instead would reveal that a secret shooter exists.
- **K9. What is public:**
  - A banner and a 遊戲記錄 entry, e.g. `3號 翻牌騎士，決鬥 7號：7號是狼人，出局` / `…7號是好人，騎士出局`.
  - Camp only, never which wolf. The post-game review names the roles.

### 守墓人 / GRAVEKEEPER (good side, god)

- **G1. What it learns.** Each night, the gravekeeper learns whether the player **voted out** the previous day was good
  or wolf. Camp only: 白狼王, 黑狼王 and the mechanical wolf all read as wolf.
- **G2. Only a vote-out counts.** These do not count:
  - a hunter or 黑狼王 shot;
  - a duel;
  - a 白狼王 take;
  - a host declare-out;
  - a revealed idiot (who survives).

  With no vote-out, the result is `昨日無人被放逐`. On night 1 it is `首夜無信息`.
  - Source of truth: `elimination_history` for `dayNumber = currentNight − 1`. Night N and day N share a dayNumber,
    which advances at night-init.
  - Only `VotingPipeline` writes `elimination_history`, and only for a real exile: the idiot path returns before the
    write (`VotingPipeline.kt:576-608`).
- **G3. A night step every night, night 1 included.** One sub-phase `GRAVEKEEPER_RESULT` with no pick:
  open-eyes narration → the result → `GRAVEKEEPER_CONFIRM` → close-eyes narration.
  - It runs whether the gravekeeper is alive or dead (dead-role delay, as today), and whether or not anyone was
    exiled.
  - There is no per-night special case.
- **G4. Night order:** 狼人 → 机械狼 → 女巫 → 预言家/通灵师 → **守墓人** → 守卫. Its result does not depend on that
  night's actions, so the position only groups the checkers together.

### Interactions with existing roles

- **Seer** sees both roles as good.
- **Medium** sees the exact role, `KNIGHT` / `GRAVEKEEPER`.
- **Mechanical wolf:** never on the same board as either role (D4), so it can never learn them.
- **Room setup:** both are blocked while the mechanical wolf is on, and the mechanical wolf is blocked while either is
  on (D4). Neither has a minimum player count.

## Role descriptions (rules text) to change

zh is Traditional Chinese. en mirrors it line for line; the builder translates.

**Changed roles:**

- `BLACK_WOLF_KING`: add `被騎士決鬥` to the "不能開槍" sentence.
- `WHITE_WOLF_KING`: add `被騎士決鬥` to the "…後沒有技能" sentence.
- `MECHANICAL_WOLF` (D4): add the line `機械狼和騎士不能同時出現在同一個房間。`. The gravekeeper PR changes it to
  `機械狼和騎士、守墓人不能同時出現在同一個房間。`

**Unchanged roles:**

- `HUNTER` and `IDIOT`: a duel never kills a good target.
- `SEER` and `MEDIUM`: their generic texts already cover it.

**New `KNIGHT` rules:**

```
陣營：好人陣營（神職）。
白天公布夜晚結果後、開始投票前，可以翻牌決鬥一名存活玩家（不能是自己），整局一次；警長競選期間不能決鬥。
對方是狼人（含白狼王、黑狼王）：對方出局，本日不再投票，直接進入黑夜；被決鬥的狼不能開槍或帶人。
對方是好人：你出局，當天照常進行。
當天已有狼人自爆時不能決鬥。決鬥成功會立即入夜，建議等開槍結束後再決鬥。
死者是警長時照常移交警徽。
機械狼和騎士不能同時出現在同一個房間。
```

**New `GRAVEKEEPER` rules:**

```
陣營：好人陣營（神職）。
每晚得知前一天被投票放逐的玩家是好人還是狼人（白狼王、黑狼王、機械狼都算狼人）；第一晚沒有信息。
只算投票放逐；被開槍、被決鬥、被白狼王帶走、被房主判定出局、白痴翻牌都不算，此時顯示「昨日無人被放逐」。
機械狼和守墓人不能同時出現在同一個房間。
```

Also add `desc` / `descShort` lines in the same style as the other roles. The wording is the builder's call.

## Decided parts of the how

### Server

- **Enums:**
  - `PlayerRole.KNIGHT` and `PlayerRole.GRAVEKEEPER`. Both are gods by the existing `isGod`, and `learnedSkill` is
    `NONE`.
  - `ActionType.KNIGHT_DUEL` and `ActionType.GRAVEKEEPER_CONFIRM`.
  - `NightSubPhase.GRAVEKEEPER_RESULT` (17 chars, fits `VARCHAR(20)`).
- **Flyway: one migration per PR.** Never edit V31 or earlier, or a migration already merged.
  - **V32 (knight PR):**
    - `rooms.has_knight`;
    - the duel state below;
    - re-create the role CHECK constraints with the full role list, `KNIGHT` included (pattern:
      `V30__mechanical_wolf_medium.sql:20-39`).
  - **V33 (gravekeeper PR):**
    - `rooms.has_gravekeeper`;
    - `night_phases.gravekeeper_result`;
    - re-create the role CHECKs again with `GRAVEKEEPER`;
    - re-create the `night_phases.sub_phase` CHECK with `GRAVEKEEPER_RESULT`.
- **Room and dealing:** wire both roles the way the medium is wired:
  - the create mapping and the room role list (`RoomService.kt:73, 242, 388`);
  - dealing (`game/RoleDealer.kt:10-24`).

  Note: there is no `buildRoleList` function; only a comment in `GameService.kt:52` names it.
- **Duel state:** `games.knight_duel_target_user_id` and `games.knight_duel_day`.
  - They persist for the whole game, and they are how K2 ("once") is enforced.
  - The banner shows when `knight_duel_day == dayNumber`.
  - The outcome is derived from the target's role.
  - These are separate from the `self_destruct_*` fields.
- **`KnightDuelService`**, dispatched like `SelfDestructService` (`GameActionDispatcher`):
  - It validates K1 and K2, applies K3 and K4, and runs the win check (K6).
  - It checks the K1 window **before** checking whether the actor is alive. During the campaign a knight killed last
    night is still alive in the DB, and it must get the same rejection as any other knight (test 3).
  - In `RESULT_REVEALED`, last night's deaths are already applied, so there is no pending-kill logic.
  - Success: reuse self-destruct's `daySkipVoting` path.
  - Badge handover: through `DayRevealAdvancer`.
  - Concurrency: actions are serialized by `loadForUpdate` (`GameActionDispatcher.kt:38`). If a 自爆 and a duel race,
    the second one sees `daySkipVoting` (or a dead actor or target) and is rejected.
  - The duel path never opens a pending shot (D2). Do not route it through the vote-out shot logic in
    `VotingPipeline`.
  - New `DomainEvent.KnightDuel` with `@JsonTypeName("KnightDuel")`.
- **HARD_MODE (K7):** add `hasKnightWithDuel` to `HardModeCounterplay`. It is true when a living knight exists and
  `knight_duel_target_user_id` is null.
- **`GravekeeperHandler`:**
  - `@Order(6)`; Guard moves from `@Order(6)` to `@Order(7)`. Update any test that asserts the order.
  - One sub-phase. It accepts `GRAVEKEEPER_CONFIRM` only in that sub-phase.
  - It claims `NightTurnLedger` like the other turns.
  - `NightOrchestrator.activeNightRoles` adds it when `room.hasGravekeeper`, every night.
- **Gravekeeper result delivery:**
  - When `GRAVEKEEPER_RESULT` opens and the gravekeeper is alive, compute the result (`GOOD` / `WOLF` / `NONE`) and
    store it in `night_phases.gravekeeper_result`.
  - A dead gravekeeper gets nothing new.
  - The gravekeeper's private state (`GameService`) serves tonight's result and the history by night. Review and role
    history are built from NightPhase rows (`RoleHistoryBuilder.kt:15-27`, `PlayerReviewBuilder.kt:185-198`).
  - There is no action-triggered push. The seer's push answers its own check; the gravekeeper picks nothing.
- **Other backend places that list roles or sub-phases:**
  - `AudioService.kt:31-42` (`mapSubPhaseToRole`);
  - `GameActionDispatcher.kt:136-152` (`TURN_SUB_PHASE`) and the dispatch case;
  - `model/RoleDelayConfig.kt:70-99` (default per-role delay and the GameConfig default map);
  - `RoleHistoryBuilder.kt:54-60`;
  - a new `MyNightAction` variant with `@JsonTypeName` (`dto/PlayerReviewDtos.kt:92-120`);
  - `ActionLogService.recordKnightDuel` (遊戲記錄);
  - `GameReviewBuilder`;
  - `GameStateLogger`.
- **Audio:**
  - `GravekeeperAudioConfig` with `gravekeeper_open_eyes.mp3` and `gravekeeper_close_eyes.mp3`;
  - add the role to `RoleAudioConfiguration`;
  - the knight has no audio config.

### Frontend

- **Role definitions:**
  - the `types` role union;
  - `ROLE_DEFINITIONS`, appended **last**. `fitRolesToBoard` turns gods on from the front of the list
    (`RoomSetupPanel.vue:274-287`), so appending keeps existing default boards unchanged. The emoji is the builder's
    call.
- **Role maps:**
  - `RoleRevealCard` rules;
  - `RoleComposition.vue:44` (`GOD_IDS`);
  - `PlayerReview.vue:166`;
  - the maps in `NightPhase`, `GameReview` and `ActionLogDrawer`;
  - `GameView.vue:591-608` (`NIGHT_TURN_ACTIONS`);
  - `useAudioService.ts:14-24`;
  - `DemoView.vue:565`.
- `RoomSetupPanel.vue`: two toggles.
- **`ActionMenu` duel chip** (`決鬥`):
  - Shown only to a living knight, only in `DAY_DISCUSSION / RESULT_REVEALED`, only when `daySkipVoting` is false,
    and only while the duel is unused.
  - The knight's private state carries `knightDuelUsed: boolean`. The server still decides.
  - Flow: pick a living target → confirm dialog (irreversible) → send `KNIGHT_DUEL`.
- **Duel banner** on the day screen (K9). After a successful duel the host sees only `進入夜晚`.
- **`NightPhase`:** a gravekeeper result screen (`好人` / `狼人` / `昨日無人被放逐` / `首夜無信息`) plus a confirm
  button.
- **i18n:** zh (Traditional) and en, keys sorted. Every new element a test reads has a `data-testid`.

### E2E helpers and scripts

- Add the new roles to:
  - `setupGame` `allOptionalRoles` (`e2e/real/helpers/multi-browser.ts:279-289`);
  - `shell-runner.ts:56` (the type) and `:303` (the regex);
  - `role-leak.ts:21-27`.
- `scripts/act.sh`: add `KNIGHT_DUEL` and `GRAVEKEEPER_CONFIRM`.
- `scripts/roles.sh:161`: update the hints.

### Audio TODO (owner)

Generate the files on ttsmaker.com with the voice "1506-AI 阿乐", then run loudnorm. Put them in
`backend/src/main/resources/static/audio/`:

- `gravekeeper_open_eyes.mp3` — 守墓人请睁眼
- `gravekeeper_close_eyes.mp3` — 守墓人请闭眼

The scripts are already in `docs/narration-scripts.md`.

## Tests

### Test focus: no duel during the sheriff campaign (owner priority, D1)

Every row needs a backend test. Rows marked E2E also get a real-backend E2E test
(`frontend/e2e/real/knight-flow.spec.ts`). It follows the `write-real-e2e-test` skill: getByTestId only, no sleeps,
no retries, no skips.

**Unhappy paths**

| # | Case | Expected |
|---|---|---|
| 1 | `KNIGHT_DUEL` in each election sub-phase: `SIGNUP`, `SPEECH`, `VOTING`, `RESULT`, `TIED` | Rejected, state unchanged (backend) |
| 2 | Knight in the election (as candidate and as non-candidate) | No `決鬥` chip: `getByTestId(...)` count 0 in `SIGNUP` and `SPEECH` (**E2E**) |
| 3 | Knight killed on night 1 sends `KNIGHT_DUEL` during `SPEECH` (death not yet revealed) | Rejected, and the rejection is the same as for a living knight in `SPEECH`, so the response does not reveal the death |
| 4 | Same knight after the reveal | Rejected: dead |
| 5 | `KNIGHT_DUEL` in `RESULT_HIDDEN`, `BADGE_HANDOVER`, `DAY_VOTING` (all sub-phases), night | Rejected |
| 6 | Duel after a 自爆 the same day (`daySkipVoting`) | Rejected |
| 7 | A wolf self-destructs and the knight duels concurrently | Exactly one applies; the second is rejected; state consistent (backend only) |
| 8 | Second duel, a dead knight, or a non-knight sends `KNIGHT_DUEL` | Rejected |
| 9 | Target is self, a dead player, or unknown | Rejected |

**Happy paths**

| # | Case | Expected |
|---|---|---|
| 10 | After the election ends and the night result is revealed, the knight duels a wolf | Wolf dead; `daySkipVoting=true`; only `進入夜晚`; `DAY_ADVANCE` rejected; banner + 遊戲記錄 (**E2E**) |
| 11 | Same window, the knight duels a good player | Knight dead and public; the vote still happens (**E2E**) |
| 12 | Duelled 黑狼王, or a mech wolf that learned 猎人 | No pending shot opens |
| 13 | Duelled 白狼王 | Takes nobody |
| 14 | Duel kills the last wolf | Game over, villagers win |
| 15 | HARD_MODE: a failed duel kills the last god | Wolves win |
| 16 | HARD_MODE: wolves ≥ humans after a vote, a living knight with an unused duel | No wolf win (K7); after the duel is used, the normal check applies |
| 17 | The knight (or the duelled wolf) is the sheriff | Badge handover first, then back to the day |
| 18 | A hunter killed at night has an unfired shot; the knight duels a wolf | Duel accepted; the day goes to night; the shot is dropped (K8) |

### Other tests (minimum)

- **Gravekeeper:**
  - wolf exiled → `WOLF`, also for each of the three special wolves;
  - good exiled → `GOOD`;
  - each G2 case → `NONE`;
  - night 1 → `NONE`, shown as `首夜無信息`;
  - the step runs on night 1 and when the gravekeeper is dead;
  - the sub-phase sequence is identical whether or not anyone was exiled;
  - a dead gravekeeper gets no stored result.
- **Room:**
  - the toggles persist;
  - both roles are dealt as gods;
  - default boards are unchanged;
  - V32 and V33 are covered in `FlywayMigrationsIntegrationTest`.
- **Seer, medium and mechanical-wolf interactions:** one test each.
- **Frontend unit tests:**
  - ActionMenu chip visibility per phase, sub-phase, `daySkipVoting` and duel-used state;
  - the confirm dialog;
  - the banner;
  - the gravekeeper result screen, all four texts;
  - the role card texts;
  - the RoomSetupPanel toggles.
- **Constraints:**
  - No new Spring test contexts.
  - Portable SQL: tests run on H2 locally and Postgres in CI.
  - Type-check with `vue-tsc -b`.

## Delivery

One spec, two PRs: first the knight (V32), then the gravekeeper (V33).

## Left to the builder

- Exact column and DTO names beyond those above.
- `desc` / `descShort` wording.
- The en texts.
- The emoji.
- How the components are split.
