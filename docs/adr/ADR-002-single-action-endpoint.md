---
id: ADR-002
title: Single Game Action Endpoint with ActionType Enum
status: accepted
---

## Context

The game has ~20 distinct player actions spread across multiple phases (role reveal, sheriff election, day, voting,
night). We needed to decide whether to expose a REST endpoint per action or one unified endpoint.

## Decision

Use a **single endpoint** `POST /api/game/action` with a typed `actionType` field, dispatched by `GameActionDispatcher`.

```
POST /api/game/action
{ gameId, actionType: ActionType, targetUserId?, payload? }
```

`ActionType` is a Kotlin enum (`model/Enums.kt`) covering all 21 action types:

| Group            | Actions                                                                                                       |
|------------------|---------------------------------------------------------------------------------------------------------------|
| Role reveal      | `CONFIRM_ROLE`                                                                                                |
| Day              | `REVEAL_NIGHT_RESULT`, `DAY_ADVANCE`                                                                          |
| Voting           | `SUBMIT_VOTE`, `VOTING_REVEAL_TALLY`, `VOTING_CONTINUE`                                                       |
| Post-elimination | `HUNTER_SHOOT`, `HUNTER_SKIP`, `BADGE_PASS`, `BADGE_DESTROY`                                                  |
| Night: wolf      | `WOLF_KILL`                                                                                                   |
| Night: seer      | `SEER_CHECK`, `SEER_CONFIRM`                                                                                  |
| Night: witch     | `WITCH_ACT`                                                                                                   |
| Night: guard     | `GUARD_PROTECT`, `GUARD_SKIP`                                                                                 |
| Sheriff election | `SHERIFF_CAMPAIGN`, `SHERIFF_QUIT`, `SHERIFF_START_SPEECH`, `SHERIFF_ADVANCE_SPEECH`, `SHERIFF_REVEAL_RESULT` |

`GameActionDispatcher` routes each `ActionType` to the appropriate pipeline or role handler. Jackson deserializes the
string from JSON to the enum automatically.

## Alternatives considered

- **One endpoint per action** — clean REST semantics but 20+ endpoints, each requiring its own auth + context loading
- **WebSocket messages for actions** — natural fit but complicates request/response error handling; REST gives
  synchronous rejection

## Consequences

- All game logic is server-authoritative — the frontend sends an action, receives success or a rejection reason
- Invalid `actionType` strings from the client are rejected at deserialization (Jackson throws before reaching the
  dispatcher)
- Adding a new action requires: adding an `ActionType` value + a `when` branch in the dispatcher
