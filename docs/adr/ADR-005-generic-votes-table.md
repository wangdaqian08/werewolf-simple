---
id: ADR-005
title: Generic Votes Table for Sheriff Election and Elimination
status: accepted
---

## Context

Two voting rounds occur in a game: the sheriff election (once per game) and elimination voting (once per day). Both have
the same structure: a voter picks a target (or abstains).

## Decision

One `votes` table covers both contexts, distinguished by a `vote_context` enum column:

```sql
votes
( game_id, vote_context ENUM ('SHERIFF_ELECTION','ELIMINATION'),
    day_number, -- 0 for sheriff election, 1+ for elimination rounds
    voter_user_id, target_user_id,
    UNIQUE (game_id, vote_context, day_number, voter_user_id))
```

Query pattern:

```kotlin
voteRepository.findByGameIdAndVoteContextAndDayNumber(gameId, VoteContext.ELIMINATION, dayNumber)
```

## Alternatives considered

- **Separate `sheriff_votes` and `elimination_votes` tables** — identical schema duplicated; join queries become more
  complex if cross-vote analysis is ever needed

## Consequences

- Tally logic is the same for both contexts — shared helper method
- `day_number = 0` is the convention for the sheriff election round; elimination rounds start at 1
- Abstain is represented as `target_user_id = NULL`
