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

The shot reuses the hunter's actions `HUNTER_SHOOT` / `HUNTER_PASS` (historical names). They mean
"the on-death shot" for every `PlayerRole.shootsOnDeath` role (HUNTER, BLACK_WOLF_KING). The 黑狼王
is never treated as a hunter. The shot is **private** (see [The private shot](#the-private-shot)).

| Death | Shot? | Opened by |
|---|---|---|
| Voted out | ✅ while the day sits on `VOTE_RESULT` / `BADGE_HANDOVER` | `VotingPipeline.collectEliminationEvents` |
| Knifed by the wolves at night (自刀) | ✅ from the host's reveal, while the day sits on `RESULT_REVEALED` / `BADGE_HANDOVER` | `GamePhasePipeline.revealNightResult` via `DayRevealAdvancer.nightShooterId` |
| Shot by the hunter | ✅ (chain, see below) | `VotingPipeline.handleHunterShoot` |
| Poisoned by the witch (even if also knifed) | ❌ | `DayRevealAdvancer.nightShooterId` |
| Self-destructs (自爆), including during the sheriff campaign | ❌ (and no take — only 白狼王 takes) | `SelfDestructService`; `nightShooterId` |
| Its death already decides the game | ❌ the game ends first | win check runs before the shot |

## The private shot

Settled with the owner on 2026-10-22: nobody but the shooter may learn that a dead player holds a gun.

- `games.pending_shooter_user_id` (V26) names the dead shooter whose shot is open now. It is set at
  the night reveal, at a vote-out, or by a chained shot. A shot or a pass clears it.
- **There is no shot sub-phase.** The day stays on its ordinary post-death sub-phase
  (`RESULT_REVEALED`, `VOTE_RESULT` or `BADGE_HANDOVER`), so every other player sees the same screen
  whether or not someone can shoot.
- **The host is not held back.** Starting the vote (`DAY_ADVANCE`) or the night (`VOTING_CONTINUE`,
  `initNight`) clears the pending shooter: an unused shot is lost. The shooter's banner says so
  ("主持人进入下一阶段后失效").
- **A pass broadcasts nothing.** The shooter's own browser re-reads its state; no other browser
  receives an event, so a pass is indistinguishable from "nobody could shoot".
- The state sends `canShoot` (`dayPhase` / `votingPhase`), true only on the shooter's own state.
  Nothing else in the payload names the shooter.
- A wolf may self-destruct while a shot is pending (rejecting it would tell the wolf a shot exists).
- A shot itself is public: "X号 开枪带走 Y号" in the 游戏记录, a `HunterShot` event. Only the
  hunter and the 黑狼王 can shoot, so a shot narrows the shooter down to one of the two.

## Roles never reach other browsers

Until game over, no payload pairs another player's id with a role (verified by the role-leak scan in
the E2E suite, `frontend/e2e/real/helpers/role-leak.ts`):

- `PlayerEliminated` carries no `role`.
- `votingPhase` has no `eliminatedRole`.
- The `VOTE_RESULT` action-log row (`GET /api/game/{id}/events`, readable mid-game) has no
  `eliminatedRole`. The post-game review reads roles from `game_players`.

## The hunter ↔ 黑狼王 chain

A shooter killed by a shot fires next, in both directions: the hunter shoots the 黑狼王 → the king
shoots; the king shoots the hunter → the hunter shoots. A room has at most one hunter and one 黑狼王,
so a chain is at most two shots.

- The chained shooter becomes `games.pending_shooter_user_id`: its own private shot.
- A shot shooter dies at once, even as sheriff. Its shot and its badge handover are then both open;
  its own screen shows the shot first. A non-shooter sheriff who is shot keeps the 国标 deferred kill
  (alive until the badge is handed over).
- A dead sheriff hands over the badge (`BADGE_HANDOVER`); the day then pauses on `VOTE_RESULT`
  (vote-out) or `RESULT_REVEALED` (night death) for the host.
- `elimination_history.hunter_shot_*` keeps the day's latest shot (the deferred-kill marker).
  Every shot is its own `HUNTER_SHOT` action-log row, so the 游戏记录 and the game review show both.

## Anonymity

In-game text never says which role fires: "你可以开枪" on the shooter's own screen, nothing on anyone
else's, "🔫 开枪" in the 游戏记录. The post-game review names the shooter's role.

## HARD_MODE

`HardModeCounterplay.hasHunterWithBullet` is "a living HUNTER": a hunter only fires once dead, so a
living one always holds the bullet. The old `hunterHasShot` flag (any `hunter_shot_user_id` row)
was removed: a 黑狼王 shot would have wrongly spent the hunter's bullet.

## Verification

| Layer | Coverage |
|---|---|
| Backend unit | `PlayerRoleTest`, `WinConditionCheckerTest` (counterplay), `RoomServiceTest` (one king, ≥ 2 wolves, wolf seat), `GameServiceStartTest`, `DayRevealAdvancerTest` (the shot never changes the sub-phase; knifed king, poison, 自爆/taken in the campaign), `VotingPipelineTest` (private shot on vote-out, a pass broadcasts nothing, identity, chain, sheriff, deciding shot, night chain), `GameService{Day,Voting}PhaseTest` (`canShoot` only for the shooter, no `eliminatedRole`), `JsonWireFormatTest` (`PlayerEliminated` without role), `ActionLogServiceTest` (no `eliminatedRole`) |
| Backend integration | `BlackWolfKingShootIntegrationTest` (vote-out, night, poison, 自爆, both vote-out chains, both night chains, sheriff, HARD_MODE), `HunterNightDeathShootIntegrationTest` / `HunterVotingShootIntegrationTest` (only the shooter's state says `canShoot`; the host moving on ends the shot; shot or pass during the badge handover), `FlywayMigrationsIntegrationTest` V26 (Postgres only) |
| Frontend unit | `roleDefinitions`, `roleRevealCard`, `dayPhase` / `votingPhase` (role card, shooter-only shot screens, the host is not held back, chain shooter, shot before the badge), `CreateRoomViewLimits`, `roleComposition`, `actionLogDrawer` (two shots), `gameReview` |
| Real-backend E2E | `frontend/e2e/real/black-wolf-king-scenarios.spec.ts` (CI shard 3), one browser per seat, every action a DOM click: create-room king rules; knifed king → hunter → villager chain, then vote → wolves win → review; voted-out king shoots the seer, night 2 still plays the dead seer's cues; voted-out hunter shoots the king, the king's deciding shot → review; knifed hunter shoots the king, the king reloads and fires back; poisoned king and 自爆 king get no shot; the host starts the vote before the king acts (shot lost, a late shot is refused); a voted-out hunter passes and no other browser receives anything. Every game checks that only the shooter's browser shows the shot, the role-loop audio, and the role-leak scan (every STOMP frame and `/api/game` JSON on each non-wolf browser). Set `BWK_SHOTS_DIR` to keep the screenshots. |
