-- Update games.phase CHECK constraint to match current GamePhase enum values.
-- Includes legacy values ('DAY', 'VOTING') so existing rows from older games remain valid.

ALTER TABLE games DROP CONSTRAINT IF EXISTS games_phase_check;

ALTER TABLE games
    ADD CONSTRAINT games_phase_check
    CHECK (phase IN (
        'ROLE_REVEAL', 'SHERIFF_ELECTION',
        'WAITING', 'NIGHT', 'DAY_PENDING',
        'DAY_DISCUSSION', 'DAY_VOTING',
        'GAME_OVER'
--         ,
--         'DAY', 'VOTING'  -- legacy values kept for backward compatibility
    ));
