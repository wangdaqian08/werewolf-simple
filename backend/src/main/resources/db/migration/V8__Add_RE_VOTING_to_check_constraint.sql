-- Add RE_VOTING to the games_sub_phase_check constraint
-- This was missing from the original constraint but is used in the VotingSubPhase enum

-- First, drop the existing constraint
ALTER TABLE games DROP CONSTRAINT games_sub_phase_check;

-- Then recreate it with RE_VOTING included
ALTER TABLE games
    ADD CONSTRAINT games_sub_phase_check
    CHECK (sub_phase IN ('RESULT_HIDDEN', 'RESULT_REVEALED',
                         'VOTING', 'RE_VOTING', 'VOTE_RESULT', 'HUNTER_SHOOT', 'BADGE_HANDOVER'));