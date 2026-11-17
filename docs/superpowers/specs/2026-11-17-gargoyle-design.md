# 石像鬼 (Gargoyle): design

Date: 2026-11-17. Branch: `feat/gargoyle`.

## Why

New roles come in published board pairs (one special wolf + one special god). The game already has
守墓人 (gravekeeper), but its board partner 石像鬼 is missing. Adding 石像鬼 completes the 12-player board
**石像鬼守墓人**: 预言家、女巫、猎人、守墓人、4 平民 / 3 狼人 + 石像鬼.

The rules were settled with the owner in a grilling session on 2026-11-17 (Q1–Q19). Sources:

1. [维基学院: 石像鬼](https://zh.wikiversity.org/wiki/%E7%8B%BC%E4%BA%BA%E6%AE%BA/%E9%81%8A%E6%88%B2%E8%A7%92%E8%89%B2/%E7%9F%B3%E5%83%8F%E9%AC%BC)
2. [游民岛: 石像鬼技能](http://www.youmindao.com/langrensha/322.html) and [12人守墓人石像鬼板子规则](https://youmindao.com/langrensha/323.html)
3. [TapTap 官方论坛: 石像鬼如何玩才有趣](https://www.taptap.cn/moment/134329902433305120)
4. [维基学院: 板子](https://zh.wikiversity.org/zh-hans/%E7%8B%BC%E4%BA%BA%E6%AE%BA/%E6%9D%BF%E5%AD%90)

## Goals

- A new role `GARGOYLE` a host can add to a room.
- The rules below, enforced on the server. The frontend only shows state and sends actions.
- Nobody can tell from the shared screen, the audio or the timing whether the gargoyle is alive or what it did.

## Non-goals

- A one-tap "石像鬼守墓人" board preset in room setup.
- A gargoyle-specific 单局任务. It gets the generic wolf tasks (and, like the mechanical wolf, not
  `WOLF_FIRST_NIGHT_GOD`, because it never knifes on night 1 while the pack lives).
- Moving the gravekeeper's place in the night order.

## Anti-goals (a failure even if it works)

- **A tell in the night flow.** Order, sub-phase changes or timing that differ by whether the gargoyle is
  alive, checked, skipped or is the lone killer.
- **Leaks between the pack and the gargoyle.** Teammate lists, pick broadcasts, the review page or the
  state API showing one to the other.
- **Regressions.** Any existing board plays differently.

## The rules (confirmed)

| # | Rule | Source |
|---|---|---|
| G1 | Wolf camp (`isWolf = true`), **not** a pack wolf (`isPackWolf = false`, like the mechanical wolf). Takes one of the room's wolf seats and counts as a special wolf: 9+ players and `wolfCount ≥ specials + 1` (specials = 白狼王, 黑狼王, 机械狼, 石像鬼). | Q1, sources 1–2 |
| G2 | Mutually unknown with the pack. No pack wolf sees the gargoyle as a teammate and it sees none. While a pack wolf lives it gets no `WolfSelectionChanged`. Wolves may pick it as their kill target. | Q2, all sources |
| G3 | Each night, night 1 included, it may check one **living** player other than itself and is told that player's **exact role** (`player.role`). | Q3, all sources |
| G4 | **No repeat check for the whole game.** A player it has checked on any earlier night cannot be checked again. Sources write "不能重复验人" with no 连续; rules meaning consecutive nights say so (乌鸦, 守卫). The role intro states this. | Q3, sources 2–3 |
| G5 | It may skip the check on any night (needed: late in the game every living player may already be checked). | Q4 |
| G6 | It cannot self-destruct (自爆). | Q5, sources 1–3 |
| G7 | The seer and the gravekeeper see it as a wolf. The medium sees `GARGOYLE`. | Q5, Q8, source 2 |
| G8 | **Lone kill.** Once no other wolf-camp player is alive (kings included), it makes the kill in the normal wolf turn (`WEREWOLF_PICK`), with the normal rules: the witch sees and can save it, the guard blocks it, 同守同救, 空刀 per room setting, it may knife itself. Reuses the mechanical wolf's W6 (`RoleRules.wolfKillers`). | Q6, sources 1–2 |
| G9 | **Night order:** the gargoyle's turn is the **first** of the night, before the wolves ("先验后刀"). Final order (handler `@Order`): 石像鬼 → 狼人 → 机械狼 → 女巫 → 预言家 → 通灵师 → 守墓人 → 守卫. | Q7, Q12, source 2 |
| G10 | Exclusion: only `GARGOYLE ↔ MECHANICAL_WOLF` (both are lone wolves; no source defines what a mechanical wolf learns from a gargoyle). Same three enforcement points as the other pairs: `BLOCKED_WITH`, `RoomService.validateRoleComposition`, `GameService` start check. Everything else may share a board (a knight's duel on it kills it, no skill). | Q8 |
| G11 | A dead gargoyle's turn still plays: narration and the dead-role delay as for any dead role (ADR-010). | Q9 |
| G12 | It counts as a wolf in every win check (CLASSIC and HARD_MODE). | Q10 |
| G13 | Checking a pack wolf shows its exact role (e.g. 狼人, 白狼王). It is **not** added to the gargoyle's teammates and that wolf is not told. | Q14, source 1 |
| G14 | The already-checked list is in the gargoyle's private state; the UI greys those players out; the server rejects them anyway. | Q15 |
| G15 | The public 游戏记录 never shows its checks. The post-game review shows each night "N號（石像鬼）查驗 M號：〈身份〉", like the medium. | Q17 |

### Role intro text

- zh: 狼人陣營。每晚可查驗一名玩家的具體身份；不能查自己，整局不能重複查驗同一人。與狼隊互不相認，不能自爆；預言家、守墓人查驗為狼人。其他狼人全部出局後才獲得狼刀，當晚先驗後刀。
- en: Wolf camp. Each night, may check one player's exact role; not itself, and never the same player twice in a game. It and the pack do not know each other; it cannot self-destruct; the seer and the gravekeeper see it as a wolf. Gets the wolf kill only once every other wolf is out, and checks before it kills that night.

## Wire contract

| Item | Value |
|---|---|
| Role | `PlayerRole.GARGOYLE` / `'GARGOYLE'` |
| Room flag | `rooms.has_gargoyle` / `Room.hasGargoyle`; create/update via the room's `roles` list |
| Sub-phases | `GARGOYLE_PICK`, `GARGOYLE_RESULT` |
| Actions | `GARGOYLE_CHECK` (target required), `GARGOYLE_SKIP` (both in `GARGOYLE_PICK`, both move to `GARGOYLE_RESULT`), `GARGOYLE_CONFIRM` (in `GARGOYLE_RESULT`, ends the turn) |
| Night columns | `night_phases.gargoyle_checked_user_id`, `night_phases.gargoyle_result_role` |
| Private event | `DomainEvent.GargoyleResult(gameId, targetUserId, role)` with `@JsonTypeName("GargoyleResult")`, sent after commit |
| Private state (gargoyle only, every night sub-phase) | `nightPhase.gargoyle = { checkedPlayerIds: string[], result: null \| { kind: 'CHECK', targetPlayerId, targetNickname, targetSeatIndex, role } \| { kind: 'SKIP' }, history: [{ round, nickname, seatIndex, role }] }`. `checkedPlayerIds` = every earlier night's check plus tonight's. |
| Audio | `GargoyleAudioConfig`: `gargoyle_open_eyes.mp3` / `gargoyle_close_eyes.mp3`. Commit 0-byte placeholders; the owner replaces them (the client skips a cue whose `play()` fails). Dead-role delay = `SEER_DEFAULT`, like the mechanical wolf. |
| Migration | `V37__gargoyle.sql`: room flag, two night columns, role CHECKs re-created with the complete role set (V33 pattern), sub-phase CHECK with the two new sub-phases. |

## Touchpoints

Templates: medium (`MediumHandler`, `MediumTurn.vue`, commit `994b665`) for pick + result; mechanical wolf
for pack separation and lone kill; gravekeeper (commit `4d2230b`) for a single-role PR's full file list.

Backend
- `model/Enums.kt`: `GARGOYLE(isWolf = true)`, `isPackWolf` excludes it, sub-phases, actions.
- `model/Room.kt`, `model/NightPhase.kt`, `V37__gargoyle.sql`, `FlywayMigrationsIntegrationTest`.
- `game/role/GargoyleHandler.kt` (`@Order(0)`), `game/role/RoleRules.kt` (`wolfKillers` lone killer = any non-pack wolf).
- `game/action/GameActionDispatcher.kt` (dispatch + `TURN_SUB_PHASE`), `game/night/NightOrchestrator.kt` (`activeNightRoles` first).
- `game/RoleDealer.kt`, `service/RoomService.kt` (create mapping, role list, specials, exclusion, timing), `service/GameService.kt` (start check, private state).
- `audio/impl/GargoyleAudioConfig.kt`, `audio/RoleAudioConfiguration.kt`, `service/AudioService.kt`, `model/RoleDelayConfig.kt`.
- `service/GameReviewBuilder.kt`, `service/PlayerReviewBuilder.kt`, `dto/*ReviewDtos.kt`, `service/RoleHistoryBuilder.kt`, `service/GameStateLogger.kt`.
- `scripts/act.sh`, `scripts/roles.sh`.

Frontend
- `types/index.ts`, `utils/roleDefinitions.ts` (`BLOCKED_WITH`), i18n zh/en (name, desc, rules, turn texts).
- `components/GargoyleTurn.vue`, `NightPhase.vue`, `views/GameView.vue`.
- Narration mapping (`services/audioService.ts`, `composables/useAudioService.ts`, `utils/nightNarration.ts`).
- `RoleRevealCard.vue`, `RoleComposition.vue`, `RoleHistory.vue`, `RoleCardSheet.vue`, `GameReview.vue`, `PlayerReview.vue`, mocks, `DemoView.vue`.

E2E and CI
- `frontend/e2e/real/helpers/` (audio manifest, multi-browser, role-leak, shell-runner), `frontend/e2e/real/gargoyle-flow.spec.ts`, its CI shard in `.github/workflows/ci.yml`.

Docs
- This spec; `docs/new-role.md` status table (also mark 骑士 and 守墓人 done); `docs/narration-scripts.md`; `docs/adr/audio.md` night order if listed there.

## Acceptance (all must pass)

| Layer | Rule coverage |
|---|---|
| Backend unit | G3 exact role; G3 no self, dead target rejected; G4 a target from an earlier night rejected (night 3 vs night 1); G5 skip; dead actor rejected; G8 cannot kill while a pack wolf lives, can once alone; G6 self-destruct rejected; G7 seer/gravekeeper see wolf, medium sees GARGOYLE; G10 mech-wolf pair rejected (room + start); G1 special-wolf count; G13 teammates unchanged after checking a wolf; G2 not in pack teammate lists; tasks exclude `WOLF_FIRST_NIGHT_GOD`. |
| Backend integration | G9 full night order with the gargoyle first; G8 lone kill with witch save and guard block; G11 dead gargoyle's turn plays with the same delay; G12 win check; G7 medium sees GARGOYLE; knight duel on it kills it; G15 review contents. |
| Frontend unit | Turn UI: checked players disabled, skip, result shown, confirm; role definitions and exclusion; i18n texts; review rendering. |
| Real-backend E2E `gargoyle-flow.spec.ts` | From the gargoyle's own browser: night 1 check → result → night 2 the earlier target is disabled → after the pack is out it makes the kill → the review shows its checks. Runs with the 0-byte placeholders; the night does not stall. |
| Gates | Full backend + frontend unit suites, lint, e2e-waits lint, `vue-tsc -b`, mock-UI E2E, CI green. |
