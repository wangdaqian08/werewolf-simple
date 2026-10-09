# Wolf self-destruction (自爆)

Standard 狼人杀 move: a werewolf publicly kills themselves during the day to end the day immediately, forcing the game directly to NIGHT and skipping the day vote.

## Phase × allowed × next-state table

| Phase | Sub-phase | Action chip | Allowed? | Next state | Host's next action |
|---|---|---|---|---|---|
| `ROLE_REVEAL` | — | hidden | ❌ | — | — |
| `NIGHT` | `WAITING` / `WEREWOLF_PICK` / `SEER_PICK` / `SEER_RESULT` / `WITCH_ACT` / `GUARD_PICK` | hidden | ❌ | — | — |
| `SHERIFF_ELECTION` | `SIGNUP` | visible | ✅ | `DAY_DISCUSSION / RESULT_HIDDEN`; `daySkipVoting=true`; election aborts | Host reveals night deaths → host sees **进入夜晚** |
| `SHERIFF_ELECTION` | `SPEECH` | visible | ✅ | `DAY_DISCUSSION / RESULT_HIDDEN`; `daySkipVoting=true`; election aborts | Same as above |
| `SHERIFF_ELECTION` | `VOTING` | visible | ✅ | `DAY_DISCUSSION / RESULT_HIDDEN`; `daySkipVoting=true`; election aborts | Same as above |
| `SHERIFF_ELECTION` | `RESULT` | visible | ✅ | `DAY_DISCUSSION / RESULT_HIDDEN`; `daySkipVoting=true` | Same as above |
| `SHERIFF_ELECTION` | `TIED` | visible | ✅ | `DAY_DISCUSSION / RESULT_HIDDEN`; `daySkipVoting=true` | Same as above |
| `DAY_DISCUSSION` | `RESULT_HIDDEN` | visible | ✅ | stays at `RESULT_HIDDEN`; `daySkipVoting=true`; pending night kills applied | Host clicks **显示结果** → `RESULT_REVEALED` → host sees **进入夜晚** |
| `DAY_DISCUSSION` | `RESULT_REVEALED` | visible | ✅ | stays at `RESULT_REVEALED`; `daySkipVoting=true` | **进入夜晚** swaps in for **开始投票** |
| `DAY_VOTING` | `VOTING` | visible | ✅ | `DAY_DISCUSSION / RESULT_REVEALED`; `daySkipVoting=true`; votes discarded | Host sees **进入夜晚** (no vote-result screen) |
| `DAY_VOTING` | `RE_VOTING` | visible | ✅ | `DAY_DISCUSSION / RESULT_REVEALED`; `daySkipVoting=true`; votes discarded | Same as above |
| `DAY_VOTING` | `VOTE_RESULT` | visible | ✅ | `DAY_DISCUSSION / RESULT_REVEALED`; `daySkipVoting=true` | Same as above |
| `GAME_OVER` | — | hidden | ❌ | — | — |

## Special cases

| Trigger | Effect |
|---|---|
| Wolf-sheriff (badge holder) self-destructs | `game.sheriffUserId = null` AND `GamePlayer.sheriff = false` (both — frontend reads the latter). `BadgeHandover(actor, null)` broadcast. Badge is destroyed permanently. |
| Last wolf self-destructs | `GameOver(winner = VILLAGER)` fires regardless of phase. |
| Non-wolf attempts | Server returns `Rejected("Only werewolves can self-destruct")`; UI hides the option for non-wolves. |
| Dead wolf attempts | Server returns `Rejected("Dead players cannot act")`. |
| Two wolves tap 自爆 simultaneously | First wolf's `@Transactional` block flips phase + alive=false; second hits the dead-player or wrong-phase rejection. |
| Host tries to start the vote after a 自爆 | `DAY_ADVANCE` is rejected while `daySkipVoting=true` ("the day ends without a vote"); only **进入夜晚** (`VOTING_CONTINUE`) is accepted. |

## White Wolf King (白狼王)

A wolf-camp role (`PlayerRole.WHITE_WOLF_KING`, `isWolf = true`): it wakes and kills with the wolves, the seer sees it as a wolf, and it counts as a wolf for win checks. The host enables it per room (`rooms.has_white_wolf_king`); it takes one of the room's `wolfCount` seats, not a god seat. It needs at least 2 wolves (`wolfCount ≥ 2`, enforced by the backend) — alone it would always be the last wolf and could never take anyone. The create-room UI only offers it for 9+ players, where the wolf minimum is already 2.

When it self-destructs it may name one player to take with it (`WOLF_SELF_DESTRUCT` + `targetUserId`). Everything else follows the table above.

| Trigger | Effect |
|---|---|
| King self-destructs with no target | Same as any wolf self-destruct. |
| King names an alive player (not itself) | That player dies too (`alive = false`, `diedDay` set); `games.self_destruct_taken_user_id` records them for the day banner (cleared at night-init). The win check counts both deaths. |
| Taken player is the hunter | Dies without a shot. |
| Taken player is the sheriff, night result already shown (or during voting) | → `DAY_DISCUSSION / BADGE_HANDOVER` via `DayRevealAdvancer`; the dead sheriff passes or destroys the badge, then the day lands on `RESULT_REVEALED` with **进入夜晚**. |
| Taken player is the sheriff, night result still hidden | Stays `RESULT_HIDDEN`; the host's reveal routes through `DayRevealAdvancer`, which hands the badge over first. |
| A plain werewolf sends a target | `Rejected("Only the White Wolf King can take a player")`. |
| King targets itself, a dead or an unknown player | Rejected; nothing changes. |
| King is the last alive wolf | House rule: the self-destruct goes ahead, the target is ignored (nobody is taken); the game ends with a villager win. |
| King is voted out, or shot by the hunter | Dead, so it can no longer self-destruct: no take. |
| King self-destructs during the vote or on the vote-result screen (last words) | Allowed — house rule kept on purpose (some rule sets forbid it). |
| Banner / 游戏记录 | Show "X号 自爆了，带走了 Y号"; the taken player's role is not revealed. |

## Verification

| Layer | Coverage |
|---|---|
| Backend unit | `SelfDestructServiceTest.kt` — 9 cases (all phases, sheriff destruction, last-wolf win, non-wolf reject, dead wolf reject, NIGHT reject). `VotingPipelineTest.kt` extended for `continueToNight` from `DAY_DISCUSSION` when `daySkipVoting=true`. |
| Backend (白狼王) | `SelfDestructServiceTest.kt` (take, win check, rejects, hunter, sheriff in each sub-phase), `SelfDestructFlowIntegrationTest.kt` (sheriff taken → badge → night; hunter taken → no shot; `rule -` / `house rule -` cases for every rule above), `GamePhasePipelineDayTest.kt` (no vote after 自爆), `FlywayMigrationsIntegrationTest.kt` (V23, Postgres only). |
| Frontend unit | `actionMenu.test.ts` (11 cases), `dayPhase.test.ts` (`daySkipVoting` host-button swap + layout regression), `votingPhase.test.ts` (right-stack layout), `sheriffElection.test.ts` (below-header layout, ActionMenu in every sub-phase), `actionLogDrawer.test.ts` (`SELF_DESTRUCT` event renders). |
| Real-backend E2E | `frontend/e2e/real/self-destruct-flow.spec.ts` — wolf drives full DOM flow from DAY_DISCUSSION/REVEALED through self-destruct, asserts `daySkipVoting` propagation, host-button swap, and 自爆 entry in the action log. Plus non-wolf empty-menu assertion. |
| Real-backend E2E (白狼王) | `frontend/e2e/real/white-wolf-king-flow.spec.ts` — the king makes the night kill from its own browser, then self-destructs and takes a villager; banner, 游戏记录 and the next night are asserted. |
| Manual (Playwright MCP) | Screenshots at every allowed sub-phase under `frontend/e2e/screenshots/feat-validation-*.png`. |
