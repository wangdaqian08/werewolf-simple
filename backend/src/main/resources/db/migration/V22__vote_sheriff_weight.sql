-- Freeze the sheriff's 1.5x weight onto the vote at cast time, so a badge
-- handover after the tally (voted-out sheriff passes the badge) cannot
-- re-weight votes already cast.
ALTER TABLE votes
    ADD COLUMN sheriff_vote BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill in-flight rows with the previous behaviour (weight by current sheriff).
UPDATE votes v
SET sheriff_vote = TRUE
FROM games g
WHERE v.game_id = g.game_id
  AND v.vote_context = 'ELIMINATION'
  AND v.voter_user_id = g.sheriff_user_id;
