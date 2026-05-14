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
| `DAY_VOTING` | `VOTING` | visible | ✅ | `DAY_VOTING / VOTE_RESULT`; tallies discarded; `daySkipVoting=true` | Host's existing **继续 / Continue** advances to night |
| `DAY_VOTING` | `RE_VOTING` | visible | ✅ | `DAY_VOTING / VOTE_RESULT`; tallies discarded | Same as above |
| `DAY_VOTING` | `VOTE_RESULT` | visible | ✅ | stays at `VOTE_RESULT` | Same as above |
| `GAME_OVER` | — | hidden | ❌ | — | — |

## Special cases

| Trigger | Effect |
|---|---|
| Wolf-sheriff (badge holder) self-destructs | `game.sheriffUserId = null` AND `GamePlayer.sheriff = false` (both — frontend reads the latter). `BadgeHandover(actor, null)` broadcast. Badge is destroyed permanently. |
| Last wolf self-destructs | `GameOver(winner = VILLAGER)` fires regardless of phase. |
| Non-wolf attempts | Server returns `Rejected("Only werewolves can self-destruct")`; UI hides the option for non-wolves. |
| Dead wolf attempts | Server returns `Rejected("Dead players cannot act")`. |
| Two wolves tap 自爆 simultaneously | First wolf's `@Transactional` block flips phase + alive=false; second hits the dead-player or wrong-phase rejection. |

## Verification

| Layer | Coverage |
|---|---|
| Backend unit | `SelfDestructServiceTest.kt` — 9 cases (all phases, sheriff destruction, last-wolf win, non-wolf reject, dead wolf reject, NIGHT reject). `VotingPipelineTest.kt` extended for `continueToNight` from `DAY_DISCUSSION` when `daySkipVoting=true`. |
| Frontend unit | `actionMenu.test.ts` (11 cases), `dayPhase.test.ts` (`daySkipVoting` host-button swap + layout regression), `votingPhase.test.ts` (right-stack layout), `sheriffElection.test.ts` (below-header layout, ActionMenu in every sub-phase), `actionLogDrawer.test.ts` (`SELF_DESTRUCT` event renders). |
| Real-backend E2E | `frontend/e2e/real/self-destruct-flow.spec.ts` — wolf drives full DOM flow from DAY_DISCUSSION/REVEALED through self-destruct, asserts `daySkipVoting` propagation, host-button swap, and 自爆 entry in the action log. Plus non-wolf empty-menu assertion. |
| Manual (Playwright MCP) | Screenshots at every allowed sub-phase under `frontend/e2e/screenshots/feat-validation-*.png`. |
