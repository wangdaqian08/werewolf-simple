-- Add sub_phase column to games for DAY and VOTING sub-phase tracking
ALTER TABLE games
    ADD COLUMN sub_phase VARCHAR(25)
        CHECK (sub_phase IN ('RESULT_HIDDEN', 'RESULT_REVEALED',
                             'VOTING', 'VOTE_RESULT', 'HUNTER_SHOOT', 'BADGE_HANDOVER'));
