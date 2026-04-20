---
name: verify-enum-values
description: Use before writing or changing any ActionType, NightSubPhase, VotingSubPhase, ElectionSubPhase, DaySubPhase, GamePhase, Role, VoteContext, WinConditionMode, CandidateStatus, RoomStatus, or ReadyStatus string literal in code, tests, docs, or shell scripts. Cheap to run, prevents a whole class of silent bugs.
---

# Verify Enum Values Against `Enums.kt`

Enum names in this codebase have drifted between code, scripts, and docs more than once. Example: scripts and docs referenced `HUNTER_SKIP`, but the backend only ever defined `HUNTER_PASS`. The script accepted the alias and sent the literal string, which the backend silently rejected — a latent bug that lived in docs and scripts for months.

**Rule:** any string literal that represents an enum value must be proved against `backend/src/main/kotlin/com/werewolf/model/Enums.kt` before use.

## Source of truth

`backend/src/main/kotlin/com/werewolf/model/Enums.kt` is authoritative. Enums defined there:

- `RoomStatus`, `ReadyStatus`
- `GamePhase`, `NightSubPhase`, `DaySubPhase`, `VotingSubPhase`, `ElectionSubPhase`
- `PlayerRole`, `CandidateStatus`, `VoteContext`, `WinnerSide`, `WinConditionMode`
- `ActionType`

If you need an audio filename, look at `backend/src/main/resources/static/audio/` and the handlers in `backend/src/main/kotlin/com/werewolf/game/role/*Handler.kt`, not docs.

## How to verify

Run one of these before writing the literal:

```
grep -n '^\s*[A-Z_]*\b' backend/src/main/kotlin/com/werewolf/model/Enums.kt
```

Or, for a specific enum:

```
grep -n 'enum class ActionType' -A 40 backend/src/main/kotlin/com/werewolf/model/Enums.kt
```

Or, cheapest — ask the Grep tool:

```
Grep pattern="^\s*(HUNTER_|WOLF_|SEER_|WITCH_|GUARD_|SHERIFF_|IDIOT_|BADGE_|VOTING_|CONFIRM_|START_|REVEAL_|DAY_)"
      path="backend/src/main/kotlin/com/werewolf/model/Enums.kt"
      output_mode="content"
```

If the literal you intend to write is not in the file, **do not write it.** Either add it to `Enums.kt` (with a matching handler branch and, if it changes the DB enum, a Flyway migration — see V8 and V10 for the pattern) or use the correct existing value.

## Cross-file parity checklist

When you add or rename a value, these must all move together:

| Layer                   | Where                                                                         |
|-------------------------|-------------------------------------------------------------------------------|
| Kotlin enum             | `backend/src/main/kotlin/com/werewolf/model/Enums.kt`                          |
| Dispatcher branch       | `backend/src/main/kotlin/com/werewolf/game/action/GameActionDispatcher.kt`     |
| Role handler            | `backend/src/main/kotlin/com/werewolf/game/role/<Role>Handler.kt`              |
| DB CHECK constraint     | Latest Flyway migration under `backend/src/main/resources/db/migration/`       |
| Frontend action types   | `frontend/src/types/` and `frontend/src/mocks/index.ts`                        |
| CLI wrapper             | `scripts/act.sh` (case statement + usage + examples)                           |
| Docs                    | `README.md`, `docs/adr/ADR-002-*`, `docs/scenarios/*`, `docs/real-backend-testing.md` |

If you rename a value, prefer keeping the old name as a legacy alias in the CLI wrapper that rewrites to the new value — this is how we handle `HUNTER_SKIP → HUNTER_PASS` today (`scripts/act.sh`).

## Doc parity

Docs that claim an enum value exists should cite `Enums.kt` so the next reader doesn't have to verify from scratch. Example:

```markdown
Action routing: see `backend/src/main/kotlin/com/werewolf/model/Enums.kt` (source of truth).
```

## When to skip

This skill is cheap. There is no situation where skipping it saves meaningful time, because the failure modes it prevents (silent backend rejection, broken docs, test flake after rename) cost hours.
