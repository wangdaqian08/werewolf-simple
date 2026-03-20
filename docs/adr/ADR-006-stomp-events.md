---
id: ADR-006
title: STOMP WebSocket Event Design
status: accepted
---

## Context

The game requires real-time push from server to clients for phase changes, vote results, and night kills. Some events
are public (all players); some are private (one player's role/night result).

## Decision

Use Spring's STOMP broker with three topic types:

| Topic                  | Audience         | Used for                                           |
|------------------------|------------------|----------------------------------------------------|
| `/topic/room/{roomId}` | All room members | `ROOM_UPDATE`, `GAME_STARTED`                      |
| `/topic/game/{gameId}` | All game members | Phase changes, vote tally, eliminations, game over |
| `/user/queue/private`  | One player       | Role assignment, seer result, witch attack info    |

All messages are `DomainEvent` subclasses serialized with `@JsonTypeInfo(Id.NAME)` — the `type` field in JSON matches
the simple class name (e.g. `"PhaseChanged"`, `"PlayerEliminated"`).

### Key DomainEvent types

| Event                  | Fields                                              | Channel    |
|------------------------|-----------------------------------------------------|------------|
| `PhaseChanged`         | `gameId, phase: GamePhase, subPhase: String?`       | game topic |
| `NightSubPhaseChanged` | `gameId, subPhase: NightSubPhase`                   | game topic |
| `RoleAssigned`         | `gameId, userId, role: PlayerRole`                  | private    |
| `PlayerEliminated`     | `gameId, userId, role: PlayerRole`                  | game topic |
| `VoteTally`            | `gameId, eliminatedUserId?, tally: Map<String,Int>` | game topic |
| `SeerResult`           | `gameId, checkedUserId, isWerewolf`                 | private    |
| `GameOver`             | `gameId, winner: WinnerSide`                        | game topic |
| `HunterShot`           | `gameId, hunterUserId, targetUserId`                | game topic |
| `BadgeHandover`        | `gameId, fromUserId, toUserId?`                     | game topic |
| `SheriffElected`       | `gameId, sheriffUserId?`                            | game topic |
| `RoleConfirmed`        | `gameId, userId`                                    | game topic |
| `NightResult`          | `gameId, kills: List<String>`                       | game topic |

### Private channel usage

The seer result (`SeerResult`) is sent privately immediately after `SEER_CHECK` succeeds, before the night advances.
This means the seer receives their result in the private channel, not the game topic — other players see only the phase
advancement.

## Consequences

- Frontend must subscribe to all three topics after joining a game
- `DomainEvent` subclass name is the wire type discriminator — renaming a class is a breaking change for connected
  clients
- `PhaseChanged.subPhase` is `String?` because it carries values from different sub-phase enums depending on the phase (
  see ADR-004)
