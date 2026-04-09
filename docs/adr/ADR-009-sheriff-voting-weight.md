# ADR-009: Sheriff Voting Weight Implementation

## Status
Accepted

## Context
The werewolf game frontend displays "Sheriff's vote counts as 1.5× during eliminations" to players, but the backend voting system did not implement this weighted voting logic. This created a critical inconsistency where player expectations did not match actual game behavior.

### Problem Statement
- **Frontend Display**: SheriffElection.vue shows "Sheriff's vote counts as 1.5× during eliminations"
- **Backend Reality**: All votes counted as 1.0, regardless of sheriff status
- **Impact**: Players might make strategic decisions based on incorrect assumptions about voting power
- **User Question**: When sheriff votes for player A (1.5 votes) and one regular player votes for player B (1.0 vote), who gets eliminated?

### Current Implementation
The voting system in `VotingPipeline.kt` used simple counting:
```kotlin
val tally: Map<String, Int> = votes
    .mapNotNull { it.targetUserId }
    .groupingBy { it }
    .eachCount()
```

Each vote counted as exactly 1.0, with no weight system.

## Decision
Implement sheriff voting weight using a **floating-point calculation approach** that maintains backward compatibility while adding the required weight functionality.

### Technical Approach
1. **Internal Calculation**: Use `Double` for weighted vote calculations (1.5 for sheriff, 1.0 for others)
2. **Data Type Change**: Update `DomainEvent.VoteTally` from `Map<String, Int>` to `Map<String, Double>`
3. **Dedicated Calculator**: Create `TallyCalculator` utility class for reusable weight logic
4. **Floating-Point Tolerance**: Use epsilon-based comparison (0.001) to handle precision issues
5. **Frontend Compatibility**: Leverage existing `number` type in TypeScript for display

### Implementation Details

#### Core Components

**1. TallyCalculator Utility Class**
```kotlin
object TallyCalculator {
    fun calculateWeightedTally(
        votes: List<Vote>,
        sheriffUserId: String?
    ): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        for (vote in votes) {
            val targetId = vote.targetUserId ?: continue
            val weight = if (vote.voterUserId == sheriffUserId) 1.5 else 1.0
            result[targetId] = (result[targetId] ?: 0.0) + weight
        }
        return result
    }

    fun findTopCandidate(tally: Map<String, Double>): String? {
        if (tally.isEmpty()) return null
        val maxVotes = tally.values.maxOrNull() ?: return null
        if (maxVotes <= 0) return null

        val epsilon = 0.001
        val topCandidates = tally.filterValues { abs(it - maxVotes) < epsilon }.keys
        return if (topCandidates.size == 1) topCandidates.first() else null
    }
}
```

**2. Modified VotingPipeline Logic**
```kotlin
val votes = voteRepository.findByGameIdAndVoteContextAndDayNumber(...)
val tally: Map<String, Double> = TallyCalculator.calculateWeightedTally(
    votes,
    context.game.sheriffUserId
)
val eliminated = TallyCalculator.findTopCandidate(tally)
```

**3. Updated Event Structure**
```kotlin
data class VoteTally(
    val gameId: Int,
    val eliminatedUserId: String?,
    val tally: Map<String, Double>  // Changed from Int to Double
)
```

#### Key Design Decisions

1. **Why Double instead of Integer Scaling?**
   - Preserves exact fractional weights (1.5 vs 1.0)
   - More intuitive for players seeing 1.5 votes
   - Avoids confusion from displaying 3 vs 2 when it's actually 1.5 vs 1.0

2. **Why Epsilon-Based Comparison?**
   - Floating-point arithmetic can introduce tiny precision errors
   - Direct equality comparison can fail for values like 2.0000001 vs 2.0
   - Epsilon of 0.001 provides robust tie detection

3. **Why Separate Calculator Class?**
   - Reusable across different voting contexts
   - Easier to test in isolation
   - Clear separation of concerns

4. **Why Not Modify Vote Storage?**
   - Vote records remain as simple integers
   - Only calculation logic changes
   - Maintains backward compatibility with existing data

### Test Coverage

**Unit Tests (TallyCalculatorTest.kt)**: 15 test cases
- Basic weight calculation
- Sheriff weight application
- Abstention handling
- Empty vote scenarios
- Edge cases (multiple sheriff flags, floating-point precision)

**Integration Tests (VotingPipelineSheriffWeightTest.kt)**: 10 test cases
- Sheriff 1.5x vs regular 1.0x vote
- Sheriff tie breaker scenarios
- Sheriff abstention
- Sheriff absence
- Re-voting phase weight application
- Elimination triggering
- Tie handling
- Dead player voting rejection
- Edge case handling

### Example Scenarios

**Scenario 1: Sheriff Weight Advantage**
- Sheriff votes for Player A: 1.5 votes
- Regular player votes for Player B: 1.0 vote
- **Result**: Player A eliminated (1.5 > 1.0)

**Scenario 2: Sheriff vs Multiple Regular Votes**
- Sheriff votes for Player A: 1.5 votes
- Two regular players vote for Player B: 2.0 votes
- **Result**: Player B eliminated (2.0 > 1.5)

**Scenario 3: Sheriff Abstains**
- Sheriff abstains: 0 votes
- Two regular players vote for Player A: 2.0 votes
- **Result**: Player A eliminated (2.0 > 0)

**Scenario 4: Tie Detection**
- Sheriff votes for Player A: 1.5 votes
- One regular player votes for Player A: 1.0 vote
- One regular player votes for Player B: 2.0 votes
- **Result**: Player B eliminated (2.5 vs 2.0 - no tie)

### Migration Strategy

1. **Data Type Change**: Updated `VoteTally` event from `Map<String, Int>` to `Map<String, Double>`
2. **Backend Adaptation**: Modified `VotingPipeline.kt` and `GameService.kt` to use weighted calculation
3. **Test Updates**: Updated existing tests to expect `Double` values instead of `Int`
4. **Frontend Compatibility**: Leverages existing `number` type support (no changes needed)

### Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| Floating-point precision errors | Epsilon-based comparison (0.001 tolerance) |
| Frontend display issues | TypeScript `number` type already supports decimals |
| Performance impact | Minimal - voting frequency is low, calculation is simple |
| Database compatibility | Vote storage unchanged, only calculation logic modified |
| Existing game data | No schema changes required |

## Consequences

### Positive
- **Fixes Critical Bug**: Resolves frontend-backend inconsistency
- **Player Trust**: Game behavior now matches displayed information
- **Strategic Depth**: Sheriff role gains actual voting power as intended
- **Test Coverage**: Comprehensive test suite prevents regressions
- **Clean Architecture**: Dedicated calculator class improves code organization

### Negative
- **Data Type Change**: `VoteTally` event structure changed from `Int` to `Double`
- **Test Updates**: Required updates to existing test expectations
- **Complexity**: Added floating-point arithmetic and precision handling

### Neutral
- **Vote Storage**: No changes to how votes are stored in database
- **API Impact**: Event structure change may affect external consumers
- **Performance**: Negligible impact due to infrequent voting operations

## Alternatives Considered

### Alternative 1: Integer Scaling (2x weight)
- **Approach**: Sheriff vote counts as 2 instead of 1.5
- **Pros**: No floating-point arithmetic, simpler implementation
- **Cons**: Doesn't match frontend display (1.5x), player confusion
- **Decision**: Rejected - frontend explicitly shows 1.5x

### Alternative 2: Separate Sheriff Vote Field
- **Approach**: Add `sheriffVoteCount` field alongside regular votes
- **Pros**: Maintains integer types, clear separation
- **Cons**: More complex data structure, harder to compare totals
- **Decision**: Rejected - overcomplicates the data model

### Alternative 3: Frontend-Only Calculation
- **Approach**: Calculate weights only on frontend display
- **Pros**: No backend changes needed
- **Cons**: Game logic on frontend is insecure, inconsistent
- **Decision**: Rejected - violates security principles

## Implementation Status

- ✅ TallyCalculator utility class created
- ✅ Unit tests for TallyCalculator (15 test cases)
- ✅ Integration tests for sheriff weight (10 test cases)
- ✅ DomainEvent.VoteTally updated to `Map<String, Double>`
- ✅ VotingPipeline.kt integrated with TallyCalculator
- ✅ GameService.kt updated for frontend data
- ✅ Existing tests updated and passing
- ✅ All backend tests passing

## Future Considerations

1. **Performance Monitoring**: Monitor if floating-point calculations cause any performance issues
2. **Precision Tuning**: Adjust epsilon value if precision issues arise in production
3. **Additional Weight Scenarios**: Consider if other roles need special weights in future
4. **API Documentation**: Update API documentation to reflect `Double` vote counts
5. **Player Education**: Ensure players understand the sheriff's 1.5x voting power

## References
- [ADR-005: Generic Votes Table](ADR-005-generic-votes-table.md)
- [ADR-006: STOMP Events](ADR-006-stomp-events.md)
- Frontend: `SheriffElection.vue:363`
- Backend: `VotingPipeline.kt:96-106`
- Test: `VotingPipelineSheriffWeightTest.kt`