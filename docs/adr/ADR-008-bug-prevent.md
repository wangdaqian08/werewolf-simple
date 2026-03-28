# ADR-008 — Bug Prevention Patterns

Patterns distilled from bugs found in the sheriff election feature.
Apply these rules when implementing any new role or game phase.

---

## Bugs Found

| Bug | Root Cause |
|-----|------------|
| Reveal Result hidden when host quits | `v-if="allVoted"` — host with `canVote=false` can never flip it |
| Self-vote allowed | No `actor === target` check on frontend or backend |
| No UI feedback after Give Up Vote | Action sent to backend but no locked-state followed |
| `allVoted` never true when all players quit | `eligibleVoterCount === 0` not treated as trivially complete |
| Abstain didn't clear selection highlight | `myVote ?? selectedId` leaks `selectedId` when `myVote` is `undefined` |
| Quit candidates appeared in result tally | `buildResult()` iterated all candidates without filtering by `RUNNING` |

---

## Prevention Rules

### 1. Write edge-case tests before writing the component

For every new role or action, enumerate these actors before touching code:

- Dead player tries to act
- Player acts on themselves
- Player acts twice in the same phase
- Host is also the actor — what can/can't they see?
- Everyone eligible has quit or abstained — does the flow get stuck?

### 2. Server state vs local UI state — never conflate them

- **Row click** → local `selectedId` ref, instant feedback, no server call
- **Confirm action** → server call → STOMP returns locked state (`myVote`, `hasActed`, etc.)

Define the locked state in types first. The UI only renders action buttons when `!lockedState`.

### 3. Use `null` for "explicitly no value", not `undefined`

`undefined` means "field not present". `null` means "player deliberately chose nothing" (e.g., abstain).

Abstain stores `null` in DB but arrives as `undefined` on the frontend — so `myVote ?? selectedId` leaks `selectedId`. Guard with `election.abstained` explicitly instead of relying on `??`.

### 4. Host controls: always visible, never hidden

Never put a host action button behind `v-if`. A host who quit the campaign has `canVote=false` — they can never flip `allVoted`, so the button disappears permanently.

**Rule:** host-only buttons are always visible, controlled by `:disabled`. Only hide them when the sub-phase makes the action entirely irrelevant.

### 5. Filter by status, not by presence

A `QUIT` candidate is still in `election.candidates`. Always filter before iterating:

```kotlin
candidates.filter { it.status == CandidateStatus.RUNNING }
```

Add a `// NOTE: RUNNING only` comment at every iteration site so it isn't silently dropped in future edits.

### 6. `allVoted` must handle the zero-eligible case

If all candidates quit during speech, `eligibleVoterCount` is `0`. Plain `submittedVoteCount >= eligibleVoterCount` is always true for 0 but conceptually wrong — the game would skip voting silently.

```ts
const allVoted = eligibleVoterCount === 0 || submittedVoteCount >= eligibleVoterCount
```

### 7. Backend validation checklist for every new action handler

Before marking an action handler done, answer each question:

- Can a dead player trigger this? → guard `actor.alive`
- Can the actor target themselves? → guard `actor.userId != target.userId`
- Is the sub-phase guard correct? → check `game.subPhase` / `nightPhase.subPhase`
- Does `allActed` correctly handle zero eligible actors?
