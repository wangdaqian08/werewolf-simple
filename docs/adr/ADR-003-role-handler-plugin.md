---
id: ADR-003
title: Role Handler Plugin Pattern
status: accepted
---

## Context

Each special role (Werewolf, Seer, Witch, Hunter, Guard) has different night actions and different accepted action
types. We needed a way to add or modify role behaviour without touching the central dispatcher.

## Decision

Define a `RoleHandler` interface and implement one `@Component` per role. Spring auto-discovers and injects them as
`List<RoleHandler>` into `GameActionDispatcher` and `NightOrchestrator`.

```kotlin
interface RoleHandler {
    val role: PlayerRole
    fun acceptedActions(phase: GamePhase, subPhase: String?): Set<ActionType>
    fun handle(action: GameActionRequest, context: GameContext): GameActionResult
    fun nightSubPhases(): List<NightSubPhase>   // empty = no night action
    fun onDayEnter(context: GameContext): List<DomainEvent>
    fun onEliminationPending(context: GameContext, targetId: String): EliminationModifier?
}
```

`NightOrchestrator.nightSequence()` builds the ordered night sub-phase list by filtering handlers whose role is active
in the room config (`hasSeer`, `hasWitch`, etc.) and flattening their `nightSubPhases()`.

**Exception**: Hunter and Badge actions are handled directly by `VotingPipeline` because they require DB access to
`EliminationHistory` and don't fit the night-only handler model. `HunterHandler` implements the interface for
`acceptedActions()` registration only; its `handle()` returns a rejection.

## Consequences

- Adding a new role = add one `@Component` class, no changes to dispatcher or orchestrator
- Room config flags (`hasSeer`, `hasWitch`, `hasHunter`, `hasGuard`) control which handlers participate at runtime
- Night order is determined by the order handlers appear in Spring's injected list — currently ordered by class name;
  can be made explicit with `@Order` if needed
