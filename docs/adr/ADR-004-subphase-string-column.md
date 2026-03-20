---
id: ADR-004
title: Game.subPhase Stored as String (Union Type)
status: accepted
---

## Context

The `games` table tracks a sub-phase within the current phase. Two phases use sub-phases:

- `DAY` → `DaySubPhase` (`RESULT_HIDDEN`, `RESULT_REVEALED`)
- `VOTING` → `VotingSubPhase` (`VOTING`, `VOTE_RESULT`, `HUNTER_SHOOT`, `BADGE_HANDOVER`)

`NIGHT` and `SHERIFF_ELECTION` own their sub-phase in separate tables (`night_phases.sub_phase`,
`sheriff_elections.sub_phase`) — those already use proper enums.

## Decision

`Game.subPhase` is stored as `VARCHAR(25)` (`String?` in Kotlin). When the phase is `DAY`, it holds a `DaySubPhase`
name; when `VOTING`, a `VotingSubPhase` name; otherwise `null`.

Comparisons in the code use `.name`:

```kotlin
context.game.subPhase != DaySubPhase.RESULT_HIDDEN.name
context.game.subPhase = VotingSubPhase.VOTING.name
```

## Alternatives considered

- **Two separate nullable columns** (`daySubPhase`, `votingSubPhase`) — cleaner typing but adds columns that are always
  null except one
- **Sealed class / common interface** — would require a JPA `AttributeConverter` and adds complexity for two simple
  enums
- **Separate `@Enumerated` column per phase** — same as above

## Consequences

- The sub-phase column is a string union — a typo would be a silent bug. This is mitigated by always going through
  `.name` on the source enum (never bare string literals)
- `DomainEvent.PhaseChanged.subPhase` is also `String?` for the same reason — it carries whichever sub-phase type is
  relevant for the current phase
- If a third phase ever needs sub-phases, no schema change is needed
