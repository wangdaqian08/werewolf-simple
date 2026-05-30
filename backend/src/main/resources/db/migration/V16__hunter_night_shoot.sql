-- Hunter night-death shoot: a hunter killed by wolves at night may fire one
-- shot during the day-reveal flow (a hunter killed by witch poison may not).
--
-- 1. Add HUNTER_SHOOT_NIGHT_DEATH to the games_sub_phase_check constraint.
--    Mirrors V8 — drop + recreate listing the COMPLETE current set plus the new
--    value (dropping any existing value would break in-flight games).
ALTER TABLE games DROP CONSTRAINT games_sub_phase_check;

ALTER TABLE games
    ADD CONSTRAINT games_sub_phase_check
    CHECK (sub_phase IN ('RESULT_HIDDEN', 'RESULT_REVEALED', 'HUNTER_SHOOT_NIGHT_DEATH',
                         'VOTING', 'RE_VOTING', 'VOTE_RESULT', 'HUNTER_SHOOT', 'BADGE_HANDOVER'));

-- 2. Loop-safety marker: set true once the night-death hunter has shot OR passed
--    so the day-reveal advancer never re-enters HUNTER_SHOOT_NIGHT_DEATH (matters
--    in the hunter-is-sheriff badge→shoot→badge cascade).
ALTER TABLE night_phases
    ADD COLUMN hunter_night_shoot_resolved BOOLEAN NOT NULL DEFAULT FALSE;
