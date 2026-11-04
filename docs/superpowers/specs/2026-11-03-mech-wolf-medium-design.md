# 机械狼 (Mechanical Wolf) and 通灵师 (Medium): design

Date: 2026-11-03. Branch: `feat/mech-wolf-medium`.

## Why

The game gets the board built around these two roles: a wolf that works alone and copies a skill, and a good-side checker that sees exact roles. The rules were picked from three sources and confirmed by the owner, rule by rule. They fit what the code already has, and they leave out the variants that would need a new night-resolution model.

Sources:
1. A text the owner pasted.
2. https://werewolf.mentiss.ai/zh-hans/play/roles
3. https://finance.ifeng.com/c/8cbUHPjRQ7A

## Goals

- Two new roles a host can add to a room: `MECHANICAL_WOLF` and `MEDIUM`.
- The rules below, enforced on the server. As everywhere else, the frontend only shows state and sends actions.
- Nobody can tell from the shared screen or the audio whether these roles are alive, or what they did.

## Non-goals

- Mechanical-wolf variants left out on purpose:
  - a 追刀 or 双刀 for learning a wolf (sources 2 and 3);
  - poison reflecting back on the witch (source 2).
- No change to how the seer works.
- No new win condition.

## Anti-goals (a failure even if it works)

- **A tell in the night flow.** The night's order, sub-phase changes or timing differ depending on whether the mechanical wolf or medium is alive, whether it learned, or what it learned.
- **Leaks between the wolves and the mechanical wolf.** The wolves see the mechanical wolf, or it sees them, through their teammate lists, their pick broadcasts, the review page or the state API.
- **Regressions.** Any existing board plays differently.

## The rules (confirmed)

### 通灵师 / MEDIUM (good side, counts as 神 / god)

- **M1.** Each night the medium checks one living player other than themself and sees that player's exact role.
- **M2.** Seer and medium cannot be in the same room; the host picks one of the two.
- **M3.** Results of checking the mechanical wolf:
  - If it has not learned yet: `MECHANICAL_WOLF`.
  - If it has learned, including earlier the same night: the learned role.
- **M4.** The seer is unchanged: it sees the mechanical wolf as a wolf, learned or not.

### 机械狼 / MECHANICAL_WOLF (wolf side)

- **W1. Kept apart from the pack.**
  - The other wolf-side roles (`WEREWOLF`, `WHITE_WOLF_KING`, `BLACK_WOLF_KING`) never see the mechanical wolf as a teammate, and it never sees them.
  - While any pack wolf is alive it gets no `WolfSelectionChanged` broadcast. Once it is the lone killer (W6) it is the only one who gets it.
  - Wolves may pick it as their kill target.
- **W2.** It cannot self-destruct (自爆).
- **W3.** It counts as a wolf in every win check, in both CLASSIC and HARD_MODE. Villagers win only once it is dead too.
- **W4. Its own night turn** (see the night order below).
  - Until it has learned, it may learn one living player other than itself on any night, or skip that night.
  - Learning happens once per game. When it learns, it is told that player's true role.
  - The learned role is the target's role at that moment, and it is fixed for the rest of the game.
- **W5. What it gets for each learned role.** "From the next night" means it cannot use the skill on the night it learned.

  | Learned role | Skill |
  |---|---|
  Targets: every skill below picks a living player. Only the protection may target the mechanical wolf itself; the checks and the poison may not.

  | `MEDIUM` | From the next night, check one living player (not self) each night and see the exact role, with the same M3 rules. |
  | `SEER` | From the next night, check one living player (not self) each night and see wolf or good. |
  | `WITCH` | From the next night, one poison for the whole game and no antidote. It uses the same rules as the witch's poison: the guard does not block it, the antidote cannot undo it, and a player it kills cannot shoot. |
  | `GUARD` | From the next night, one protection for the whole game. It may protect itself. The protected player cannot die at night that night: not to the wolf kill, not to the witch's poison. It does not cancel out with the antidote (no 奶穿) and has no effect on the witch. |
  | `HUNTER`, `BLACK_WOLF_KING` | Shoots on death from the moment it learns, the same night included. It uses the existing private shot flow, and cannot shoot if poisoned. |
  | `VILLAGER`, `IDIOT`, `WEREWOLF`, `WHITE_WOLF_KING` | No skill. An idiot's vote immunity is not copied. |

- **W6. Lone kill.**
  - Once no other wolf-side player is alive, the mechanical wolf makes the kill in the normal wolf turn (`WEREWOLF_PICK`).
  - The kill follows the normal wolf-kill rules: the witch sees it and can save it, the guard can block it, 同守同救 applies, and 空刀 follows the room setting.
  - While any other wolf is alive, it cannot kill.
- **W7. Room setup.** The mechanical wolf takes one of the room's wolf seats, and at least one plain `WEREWOLF` must remain. This extends the king rule: `wolfCount ≥ (number of special wolves) + 1`, where the special wolves are the kings plus the mechanical wolf. It may be paired with the seer or the medium; neither is required.

### Night order (confirmed)

狼人 → 机械狼 → 女巫 → 预言家 or 通灵师 → 守卫

- The mechanical wolf's turn is after the wolves ("先刀后验": when it is alone, it kills first, then uses its skill) and before the witch.
- The witch sees only the wolf kill, never the mechanical wolf's poison.
- A role that is not in the room has no turn. A role that is in the room but dead still walks its sub-phases with the dead-role delay, as today.

## Decided parts of the how

### Server

- **Roles.**
  - Add `PlayerRole.MECHANICAL_WOLF(isWolf = true)` and `PlayerRole.MEDIUM`. `MEDIUM` is a god by the existing `isGod` rule.
  - `isWolf` keeps its meaning: wolf side and win checks. Every place that uses `isWolf` to mean "the pack" must leave out the mechanical wolf. That covers:
    - who wakes and acts in `WEREWOLF_PICK` (`WerewolfHandler`, `GameActionDispatcher.nightTurn`);
    - the `WOLF_SELECT` broadcast;
    - the teammate lists (`GameService` night state, the role card's `wolfTeammates`, `PlayerReviewBuilder`, `GameReviewBuilder`);
    - self-destruct (`SelfDestructService`);
    - `GameStateLogger`.
  - Add one helper on `PlayerRole` for "the pack" so the rule lives in one place. The lone-kill rule (W6) is: the actors are the living pack, or, if the pack is empty, the living mechanical wolf.
- **Seer.** It needs no change: `isWolf` is true for the mechanical wolf, so the seer always sees it as a wolf, learned or not (M4).
- **Room.**
  - New `rooms.has_mechanical_wolf` and `rooms.has_medium` columns, following V26.
  - `RoomService` validation adds M2 and W7.
  - `buildRoleList` adds the mechanical wolf as a wolf seat and the medium as a god.
- **Night sub-phases.** New: `MECH_WOLF_PICK`, `MECH_WOLF_RESULT`, `MEDIUM_PICK`, `MEDIUM_RESULT`. Each fits the `VARCHAR(20)` column.
  - The mechanical wolf always walks both of its sub-phases, whatever it does that night (learn, check, poison, protect or nothing). Everyone receives the sub-phase broadcasts, so a sequence that changed with its choice would be a tell.
  - The result screen shows the learned role or the check result. Otherwise it shows a plain "done" / "no action tonight".
- **Actions.**
  - Mechanical wolf: `MECH_LEARN`, `MECH_CHECK`, `MECH_POISON`, `MECH_PROTECT`, `MECH_SKIP` (in PICK), `MECH_CONFIRM` (in RESULT).
  - Medium: `MEDIUM_CHECK` (PICK), `MEDIUM_CONFIRM` (RESULT).
  - The handler accepts an action only when that action is legal right now. For example, `MECH_POISON` is accepted only if it learned the witch, did so on an earlier night, and has not used the poison yet.
  - Each new turn claims `NightTurnLedger` the way the existing night turns do.
- **Handlers.**
  - New `MechanicalWolfHandler` and `MediumHandler`.
  - Handler `@Order` sets the night order, so renumber it to give: Werewolf, MechanicalWolf, Witch, Seer/Medium, Guard. Keep Villager, Hunter and Idiot after them.
  - `NightOrchestrator.nightSequence` and `nightRoleLoop` add the two roles when the room has them.
  - The `WEREWOLF` turn's "alive" check stays "any living wolf-side player", which W6 needs.
- **Storage.** Add a Flyway migration V30. Never edit an earlier one.
  - `game_players.learned_role` (nullable): the learned role. `night_phases.mech_learn_target_user_id` records which night it learned.
  - `night_phases` per-night columns:
    - `mech_check_target_user_id`
    - `mech_check_result_role`
    - `mech_poison_target_user_id`
    - `mech_guard_target_user_id`
    - `medium_checked_user_id`
    - `medium_result_role`
  - Re-create the role CHECK constraints with the full role list (the V26 pattern). Re-create the `night_phases.sub_phase` CHECK (V1) with the new sub-phases.
- **Night resolution** (`NightOrchestrator.computeKills`, the one source of truth):
  - The wolf kill lands as today (`wolfKillLands` plus the night-1 perk), unless the target is `mech_guard_target_user_id`.
  - The witch's poison and the mechanical wolf's poison each kill their target, unless that target is the mechanically-guarded player.
  - "Poisoned" covers both poisons wherever it disables a shot (`DayRevealAdvancer.nightShooterId`).
- **Shooting.** "Shoots on death" becomes a per-player question. True for `HUNTER`, for `BLACK_WOLF_KING`, and for a mechanical wolf whose learned role is one of those two. Every current `role.shootsOnDeath` call site moves to it: `VotingPipeline` at three sites and `DayRevealAdvancer`.
- **State API** (`GameService`). These are private, sent only to the role's own player:
  - Mechanical wolf: whether it has learned, the learned role, which skill it can use tonight (if any), whether its poison or protection is used, and this night's result.
  - Medium: this night's result plus a check history, the same shape as the seer's but carrying a role instead of a wolf flag.
  - Results are also pushed privately after commit, the way `SEER_CHECK` is today.
- **Audio.** Add `MechanicalWolfAudioConfig` and `MediumAudioConfig`, using the open-eyes and close-eyes file names below. Add both roles to the default per-role delays (`RoomService.buildGameConfig`).

### Frontend

- `types`, `ROLE_DEFINITIONS`, `RoleRevealCard` (the mechanical wolf shows no teammates and has its rule text), and the role maps in `NightPhase` and `GameReview`.
- i18n `zh` (Traditional) and `en`, keys sorted.
- Room setup: the two toggles, with seer and medium mutually exclusive in the UI. The server still enforces M2.
- Night screens:
  - The mechanical wolf turn: learn, then check, poison, protect, or no action; plus its result screen.
  - The medium turn: pick and result. The result shows a role name.
  - While the pack is alive, the mechanical wolf sees the normal "eyes closed" screen during `WEREWOLF_PICK`. It sees the wolf kill UI only when it is alone (W6).
  - Any `isWolf`-based check in the frontend gets the same pack-versus-mechanical-wolf review. Examples: `wolfVariant`, `isWolfTarget`.
- The game review and role history show the mechanical wolf's learning and its skill use, and the medium's checks.

### Tests (minimum)

- Unit:
  - `computeKills` with the mechanical guard and the mechanical poison;
  - the shooting predicate for a learned hunter;
  - every W5 row in the handler;
  - M3 results;
  - W7 and M2 validation;
  - the pack-versus-mechanical-wolf filter.
- Integration:
  - a full night with both roles, where the sub-phase broadcast sequence is identical whether the mechanical wolf learns, skips or is dead;
  - a lone mechanical-wolf kill;
  - a state API check that wolves get no mechanical-wolf teammate and the reverse.
- One real-backend E2E spec: the mechanical wolf learns on night 1, uses the skill on night 2, and the medium sees the learned role. Add it to the CI shard matrix in `.github/workflows/ci.yml`.

## Audio files to generate (owner)

Generate on https://ttsmaker.com/ with voice **1506-AI 阿乐**, then loudness-match (see `docs/adr/audio.md` § Generating narration files). Put them in `backend/src/main/resources/static/audio/`:
- [ ] `mech_wolf_open_eyes.mp3`: 机械狼请睁眼
- [ ] `mech_wolf_close_eyes.mp3`: 机械狼请闭眼
- [ ] `medium_open_eyes.mp3`: 通灵师请睁眼
- [ ] `medium_close_eyes.mp3`: 通灵师请闭眼

Owner's call (2026-11-03): until the real recordings exist, this branch ships **placeholder files with exactly these names** (short silent mp3s), so nothing ever 404s. The owner replaces them in place later; no code change is needed then.

## Calls made during the design review (author's calls, not owner-confirmed)

- **Self-destruct "last wolf" check** (`SelfDestructService`): count only the pack. If the mechanical wolf counted, whether a 白狼王's take goes through would show that the mechanical wolf is alive.
- **Order of the dawn death list:** sort by seat. Three death sources (wolf kill, witch poison, mechanical poison) must not be readable from the order.
- **Real-backend role-leak scanner** (`frontend/e2e/real/helpers/role-leak.ts`):
  - add both roles to `PLAYER_ROLES`;
  - exempt a player's own private result: the medium's check, the mechanical wolf's learned role and its check.
- **Old rooms are out of scope** (owner's call, 2026-11-03): no open room predates the new roles, so there is no fallback work for stored configs without them.

## Builder's calls (left open on purpose)

- Exact component and composable layout on the frontend.
- The shape of private payloads beyond the fields listed above.
- Whether the mechanical wolf's and the medium's result screens reuse the seer's result component.
