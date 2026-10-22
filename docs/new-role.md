# New Roles Roadmap (14–18 players)

> Roadmap, not a task-by-task plan. Before you start a role, settle its open rule questions with the
> owner, then write a separate implementation plan for that role (superpowers:writing-plans).

**Goal:** Support 14–18-player games by adding four roles: 白狼王, 骑士, 狼王 (黑狼王) and 守墓人.

**Origin:** Agreed in a Claude session on 2026-10-08/09. Saved here on 2026-10-21 so it lives in the repo
and not only in a chat transcript.

## Status

| Item | Status |
 |---|---|
| Step 0: camp concept (`PlayerRole.isWolf` / `isGod`), replace `role == WEREWOLF` checks | ✅ Done (`Enums.kt`, PR #149) |
| 白狼王 `WHITE_WOLF_KING` | ✅ Done (PR #149) |
| `MAX_PLAYERS` | ⚠️ 15 now (`CreateRoomView.vue`). Target 16, max 18 |
| 骑士 | ❌ Not started |
| 狼王 (黑狼王) `BLACK_WOLF_KING` | ✅ Done (rules: `docs/black-wolf-king.md`) |
| 守墓人 | ❌ Not started |

## Roles

| # | Role | Camp | Ability | Reuses |
 |---|---|---|---|---|
| 1 | 白狼王 ✅ | Wolf | During the day, self-destructs and can take one player with them | `WOLF_SELF_DESTRUCT` |
| 2 | 骑士 | Good (god) | During the day, flips their card and duels one player. Target is a wolf: the wolf dies and night starts at once. Target is good: the knight dies | Day-interrupt flow (`IDIOT_REVEAL`, self-destruct) |
| 3 | 狼王 (黑狼王) ✅ | Wolf | When voted out, knifed at night or shot by the hunter, shoots one player. Cannot shoot if poisoned or after 自爆 | Private on-death shot (`games.pending_shooter_user_id`) in `VotingPipeline.handleHunterShoot` + `DayRevealAdvancer` (not `HunterHandler`, which is a shell) |
| 4 | 守墓人 | Good (god) | Each night, learns whether the player voted out the previous day was good or a wolf | New result-only night step (no target pick) |

白狼王 rules chosen at the time (see `docs/wolf-self-destruct.md`): the take is optional, a taken hunter
cannot shoot, and a taken sheriff can still pass the badge. Needs `wolfCount ≥ 2` and at least 9 players
(`KING_MIN_PLAYERS`).

**Deferred:**
- 隐狼: changes seer results and who joins the night kill.
- 狼美人: needs chained deaths.

**Not recommended:**
- 丘比特: lovers form a third camp, so every win condition changes.
- 魔术师, 摄梦人, 恶灵骑士: many rule edge cases with the witch, guard and seer.

## Recommended boards

Gods are what limit the player count, because plain wolves and villagers can repeat. Keep wolves / gods /
villagers at about 1/3 each. Every board below stays inside the existing `floor(players/3)` wolf rule.

| Players | Wolves | Gods | Villagers |
 |---|---|---|---|
| 14 | 4: 3 wolves + 白狼王 | 5: seer, witch, hunter, guard, knight | 5 |
| 15 | 5: 4 wolves + 白狼王 | 5: seer, witch, hunter, guard, knight | 5 |
| 16 | 5: 3 wolves + 白狼王 + 狼王 | 6: seer, witch, hunter, guard, idiot, knight | 5 |
| 18 | 6: 4 wolves + 白狼王 + 狼王 | 6: seer, witch, hunter, guard, knight, 守墓人 | 6 |

- **18 is the comfortable maximum:** 6-6-6, with both wolf kings in play.
- **21 is possible but not recommended:** it uses all 7 gods against only 2 special wolves, so good is
  too strong. A full speaking round also gets too long on mobile.

## Open rule questions (settle before implementing)

Rule details vary between platforms. Do not pick these silently.

**骑士**
- When can the knight duel: only during day speeches, or also during the sheriff speeches and voting?
- If the wolf dies, does night start with no vote that day? The sheriff badge: what happens if the duelled
  wolf or the knight holds it?
- If the duelled wolf is 狼王, can it shoot? If it is 白狼王, can it still take someone?
- One duel per game, and only while alive?

**狼王** — ✅ settled 2026-10-21, see `docs/black-wolf-king.md`
- Shot on: vote-out, wolf night kill, hunter shot (chain both ways). No shot: poison, 自爆.
  白狼王 cannot coexist (one king per room). A knight duel is still open — decide it with the knight.
- It can self-destruct like a normal wolf (no take), and then it cannot shoot.
- The shot is anonymous in-game ("X号 开枪"); the post-game review names the role.

**守墓人**
- No one voted out (tie, idiot revealed, wolf self-destruct): what does the step show?
- Is the result camp only (good / wolf), and is 白狼王/狼王 shown as "wolf"?
- Where does the step go in the night order? It needs open/close narration audio even when the
  gravekeeper is dead (dead-role audio rule, ADR-010).

## Per-role touchpoints (from PR #149, 白狼王)

Backend
- `model/Enums.kt`: `PlayerRole` entry (set `isWolf` for wolf roles); new `ActionType` / `NightSubPhase` if needed
- `model/Room.kt` `has<Role>` flag + Flyway `V27__<role>.sql` (V26 is the latest now) + `FlywayMigrationsIntegrationTest`
- `service/RoomService.kt`: create mapping, room role list, `validateRoleComposition` rules (one wolf king per room)
- `service/GameService.kt`: role list used for dealing
- `game/role/<Role>Handler.kt` + `game/action/GameActionDispatcher.kt`
- `game/night/NightOrchestrator.kt` (active roles in `nightSequence` and `nightRoleLoop`, night order) for night roles
- `game/phase/GamePhasePipeline.kt`, `game/phase/WinConditionChecker.kt`
- `audio/impl/<Role>AudioConfig.kt` + `audio/RoleAudioConfiguration.kt` + narration files
- `service/ActionLogService.kt`, `model/EliminationHistory.kt`. Check that game review (PR #155) renders the role
- Camp-dependent services: `PerkService`, `RewardSettlementService`

Frontend
- `types/index.ts` role union, `utils/roleDefinitions.ts`
- `views/CreateRoomView.vue`: toggle, minimum-player gate and hint (see `KING_MIN_PLAYERS`)
- `RoleRevealCard.vue`, `RoleComposition.vue`, `ActionMenu.vue`, `DayPhase.vue` / `NightPhase.vue` /
  `VotingPhase.vue`, `ActionLogDrawer.vue`, `ResultView.vue`, `views/dev/RoleRevealDevView.vue`

Tests and docs
- TDD: handler unit tests, service tests, an integration flow test, and a real e2e
  `frontend/e2e/real/<role>-flow.spec.ts`
- A rules doc (like `docs/wolf-self-destruct.md`) and a `docs/scenarios/` entry

## Raising the player limit

- Raise `MAX_PLAYERS` 15 → 16 (18 at most) after the knight ships, because the 15/16 boards need it.
- **Not verified:** whether 16–18 seats fit on the 417×614 portrait screen. Check this first.
- At 16+ players, the sheriff election and day speeches get much longer. Review the default timers.
