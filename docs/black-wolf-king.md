# Black Wolf King (黑狼王)

A wolf-camp role (`PlayerRole.BLACK_WOLF_KING`, `isWolf = true`). It wakes and kills with the wolves,
the seer sees it as a wolf, and it counts as a wolf for win checks. When it dies (not poisoned, not
self-destructed) it shoots one player, like the hunter.

The host enables it per room (`rooms.has_black_wolf_king`). It takes one of the room's `wolfCount`
seats, not a god seat. Rules settled with the owner on 2026-10-21.

## Room rules

| Rule | Where |
|---|---|
| One wolf king per room: 白狼王 and 黑狼王 are mutually exclusive | `RoomService.validateRoleComposition`; the create-room toggles disable the other king |
| Needs `wolfCount ≥ 2` (same gate as 白狼王) | `RoomService.validateRoleComposition` |
| Offered at 9+ players (`KING_MIN_PLAYERS`); switched off when the board drops below 9 | `CreateRoomView.vue` |

Suggested board: the 12-player 狼王守卫 (3 wolves + 黑狼王 · seer, witch, hunter, guard · 4 villagers).

## When it shoots

The shot reuses the hunter's flow: `HUNTER_SHOOT` / `HUNTER_PASS` and the sub-phases `HUNTER_SHOOT`
and `HUNTER_SHOOT_NIGHT_DEATH` keep their historical names. They now mean "the on-death shot" for
every `PlayerRole.shootsOnDeath` role (HUNTER, BLACK_WOLF_KING). The 黑狼王 is never treated as a hunter.

| Death | Shot? | Where |
|---|---|---|
| Voted out | ✅ | `DAY_VOTING / HUNTER_SHOOT` |
| Knifed by the wolves at night (自刀) | ✅ at the host's reveal | `DAY_DISCUSSION / HUNTER_SHOOT_NIGHT_DEATH` |
| Shot by the hunter | ✅ (chain, see below) | same sub-phase as the hunter's shot |
| Poisoned by the witch (even if also knifed) | ❌ | `DayRevealAdvancer.pendingShooterId` |
| Self-destructs (自爆), including during the sheriff campaign | ❌ (and no take — only 白狼王 takes) | `SelfDestructService`; `pendingShooterId` |
| Its death already decides the game | ❌ the game ends first | win check runs before the shot |

## The hunter ↔ 黑狼王 chain

A shooter killed by a shot fires next, in both directions: the hunter shoots the 黑狼王 → the king
shoots; the king shoots the hunter → the hunter shoots. A room has at most one hunter and one 黑狼王,
so a chain is at most two shots.

- The chained shooter is stored in `games.chain_shooter_user_id` (V26) while its shot is pending.
  Its shot or pass clears it.
- A shot shooter dies at once, even as sheriff: its shot comes before its badge. A non-shooter
  sheriff who is shot keeps the 国标 deferred kill (alive until the badge is handed over).
- When every shot is resolved, a dead sheriff hands over the badge (`BADGE_HANDOVER`), then the day
  pauses on `VOTE_RESULT` (vote-out) or `RESULT_REVEALED` (night death).
- The state exposes who may fire now as `votingPhase.hunterUserId` / `dayPhase.hunterUserId`
  (the voted-out or wolf-killed shooter, else the chained one).
- `elimination_history.hunter_shot_*` keeps the day's latest shot (the deferred-kill marker).
  Every shot is its own `HUNTER_SHOT` action-log row, so the 游戏记录 and the game review show both.

## Anonymity

In-game text never says which role fires: "你可以开枪" for the shooter, "有玩家正在开枪 / 等待玩家开枪"
for everyone else, "🔫 开枪" in the 游戏记录. The vote history still shows the voted-out player's role
(existing behaviour). The post-game review names the shooter's role.

## HARD_MODE

`HardModeCounterplay.hasHunterWithBullet` is "a living HUNTER": a hunter only fires once dead, so a
living one always holds the bullet. The old `hunterHasShot` flag (any `hunter_shot_user_id` row)
was removed: a 黑狼王 shot would have wrongly spent the hunter's bullet.

## Verification

| Layer | Coverage |
|---|---|
| Backend unit | `PlayerRoleTest`, `WinConditionCheckerTest` (counterplay), `RoomServiceTest` (one king, ≥ 2 wolves, wolf seat), `GameServiceStartTest`, `DayRevealAdvancerTest` (knifed king, poison, chain, 自爆/taken in the campaign), `VotingPipelineTest` (vote-out, identity, chain, sheriff, deciding shot, night chain), `GameServiceVotingPhaseTest` (`hunterUserId`) |
| Backend integration | `BlackWolfKingShootIntegrationTest` (vote-out, night, poison, 自爆, both vote-out chains, both night chains, sheriff, HARD_MODE), `FlywayMigrationsIntegrationTest` V26 (Postgres only) |
| Frontend unit | `roleDefinitions`, `roleRevealCard`, `dayPhase` / `votingPhase` (role card, role-neutral shot screens, chain shooter), `CreateRoomViewLimits`, `roleComposition`, `actionLogDrawer` (two shots), `gameReview` |
| Real-backend E2E | `frontend/e2e/real/black-wolf-king-scenarios.spec.ts` (CI shard 3), one browser per seat, every action a DOM click: create-room king rules; knifed king → hunter → villager chain, then vote → wolves win → review; voted-out king shoots the seer, night 2 still plays the dead seer's cues; voted-out hunter shoots the king, the king's deciding shot → review; knifed hunter shoots the king, the king reloads and fires back; poisoned king and 自爆 king get no shot. Each game checks the role-loop audio (backend log + a villager's STOMP receipts) and that no non-wolf sees the shooter's role. Set `BWK_SHOTS_DIR` to keep the screenshots. |
