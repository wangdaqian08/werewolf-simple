-- Black Wolf King (黑狼王): a wolf-camp role that shoots one player when it dies
-- (not poisoned, not self-destructed), sharing the hunter's on-death shot.

-- 1. Room toggle. The king takes one of the room's wolf_count seats.
ALTER TABLE rooms ADD COLUMN has_black_wolf_king BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. The dead shooter (hunter or black wolf king) whose private shot is open now:
--    set at the night reveal, a vote-out or a chained shot; NULL otherwise.
ALTER TABLE games ADD COLUMN pending_shooter_user_id VARCHAR(128);

-- 3. Re-create the role CHECKs listing the COMPLETE current role set (V8/V23 pattern).
--    'BLACK_WOLF_KING' is 15 characters: the VARCHAR(20) columns (V23) fit it.
ALTER TABLE game_players DROP CONSTRAINT game_players_role_check;
ALTER TABLE game_players
    ADD CONSTRAINT game_players_role_check
        CHECK (role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING'));

ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_eliminated_role_check;
ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_hunter_shot_role_check;
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_eliminated_role_check
        CHECK (eliminated_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING'));
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_hunter_shot_role_check
        CHECK (hunter_shot_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING'));
