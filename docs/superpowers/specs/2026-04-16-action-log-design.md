# Action Log Feature Design

**Date:** 2026-04-16  
**Branch:** history  
**Status:** Approved

## Overview

Allow players to view a historical log of public game events during the day phase. A floating "📋 记录" button opens a bottom-sheet drawer showing per-round summaries: night deaths (no cause attribution), vote tallies, hunter shots, and idiot reveals.

---

## Architecture & Data Flow

```
Game events fire (backend)         game_events table
  Night ends → night deaths ──────→ NIGHT_DEATH row(s)
  Vote result ─────────────────────→ VOTE_RESULT row
  Hunter shoots ───────────────────→ HUNTER_SHOT row
  Idiot revealed ──────────────────→ IDIOT_REVEAL row

Player taps "查看记录" button
  DayPhase.vue ──→ GET /api/game/{gameId}/events
                   returns ordered list of ActionLogEntryDto
  ActionLogDrawer.vue renders grouped timeline
```

The existing `game_events` table is used as-is (no migration). The `message` column stores a JSON payload per event type. `event_type` tells the frontend how to parse it. `target_user_id` stores the primary target for each event.

---

## Backend

### Event Types & Write Points

| Event type | Written when | Written by | JSON payload in `message` |
|---|---|---|---|
| `NIGHT_DEATH` | NIGHT→DAY_DISCUSSION transition | `GamePhasePipeline` | `{dayNumber, userId, nickname, seatIndex}` — one row per dead player, **no cause** |
| `VOTE_RESULT` | After tally finalised | `VotingPipeline` | `{dayNumber, tally:[{userId,nickname,seatIndex,votes,voters:[{userId,nickname,seatIndex}]}], eliminatedUserId?, eliminatedNickname?, eliminatedSeatIndex?, eliminatedRole?}` |
| `HUNTER_SHOT` | After hunter shot resolves | `HunterHandler` | `{dayNumber, hunterUserId, hunterNickname, hunterSeatIndex, targetUserId, targetNickname, targetSeatIndex}` |
| `IDIOT_REVEAL` | After idiot survives vote | `IdiotHandler` | `{dayNumber, userId, nickname, seatIndex}` |

**Privacy rule:** `NIGHT_DEATH` never includes the kill cause (wolf attack vs witch poison). Players only learn that someone died, not why.

### New Endpoint

```
GET /api/game/{gameId}/events
Authorization: Bearer <JWT>
```

Response — array of `ActionLogEntryDto`, ordered by `created_at ASC`:
```json
[
  {
    "id": 1,
    "eventType": "NIGHT_DEATH",
    "message": "{\"dayNumber\":1,\"userId\":\"abc\",\"nickname\":\"Alice\",\"seatIndex\":3}",
    "targetUserId": "abc",
    "createdAt": "2026-04-16T10:00:00"
  }
]
```

### Files to Modify (Backend)

- `GamePhasePipeline.kt` — inject `GameEventRepository` + `UserRepository`; write `NIGHT_DEATH` rows on NIGHT→DAY transition
- `VotingPipeline.kt` — inject `GameEventRepository` + `UserRepository`; write `VOTE_RESULT` row after tally
- `HunterHandler.kt` — inject `GameEventRepository` + `UserRepository`; write `HUNTER_SHOT` row after shot
- `IdiotHandler.kt` — inject `GameEventRepository` + `UserRepository`; write `IDIOT_REVEAL` row after reveal
- `GameController.kt` — add `GET /{gameId}/events` endpoint
- `GameDtos.kt` — add `ActionLogEntryDto`

### TDD Approach

Each write point gets a unit test verifying the correct `GameEvent` rows are saved with the right `event_type`, `message` JSON, and `target_user_id`. The endpoint gets an integration test.

---

## Frontend

### New Files

- `frontend/src/components/ActionLogDrawer.vue` — slide-up full-height drawer, groups events by round
- Type `ActionLogEntry` added to `frontend/src/types/index.ts`
- `gameService.getActionLog(gameId)` added to `frontend/src/services/gameService.ts`

### Modified Files

- `frontend/src/components/DayPhase.vue` — floating "📋 记录" button + wire up drawer open/close

### ActionLogDrawer Display

Events grouped and rendered as a timeline:

```
第1夜
  • 3号·Alice 出局
  • 7号·Bob 出局

第1天 · 投票结果
  3号·Alice  3票  [投票: 5号·Bob, 6号·Carol, 4号·Dave]
  5号·Bob    1票  [投票: 3号·Alice]
  ▶ 淘汰: 3号·Alice (村民)
  ▶ Hunter 5号·Bob 开枪击中 7号·Carol  ← if hunter shot
  ▶ 2号·Dave 揭示身份：白痴            ← if idiot reveal
```

- No cause shown for night deaths (wolf/witch attribution hidden)
- Vote tallies always show voters (seat + nickname)
- Drawer is lazy-fetched: REST call fires only when opened
- Drawer covers the full screen with a close button; styled in Ink & Paper theme

### Floating Button Placement

Positioned bottom-right in `DayPhase.vue`, visible during both `RESULT_HIDDEN` and `RESULT_REVEALED` subphases. Uses `btn-outline` style to stay unobtrusive.

---

## Constraints

- No new DB migration required (table already exists)
- Night kill cause is never exposed via this endpoint
- The endpoint is available to any authenticated player in the game (no role-based filtering)
- Drawer is accessible during DAY phase only (not wired into NightPhase or VotingPhase)
