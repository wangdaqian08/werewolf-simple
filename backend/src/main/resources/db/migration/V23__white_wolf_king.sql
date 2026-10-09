-- White Wolf King (白狼王): a wolf-camp role that may take one player with it
-- when it self-destructs.

-- 1. Room toggle. The king takes one of the room's wolf_count seats.
ALTER TABLE rooms ADD COLUMN has_white_wolf_king BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. The player the king took, shown in the day banner next to
--    self_destruct_user_id (V18). Reset to NULL at night-init.
ALTER TABLE games ADD COLUMN self_destruct_taken_user_id VARCHAR(128);

-- 3. 'WHITE_WOLF_KING' is 15 characters: widen the VARCHAR(10) role columns and
--    re-create their CHECKs listing the COMPLETE current role set (V8 pattern).
ALTER TABLE game_players DROP CONSTRAINT game_players_role_check;
ALTER TABLE game_players ALTER COLUMN role TYPE VARCHAR(20);
ALTER TABLE game_players
    ADD CONSTRAINT game_players_role_check
        CHECK (role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING'));

ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_eliminated_role_check;
ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_hunter_shot_role_check;
ALTER TABLE elimination_history ALTER COLUMN eliminated_role TYPE VARCHAR(20);
ALTER TABLE elimination_history ALTER COLUMN hunter_shot_role TYPE VARCHAR(20);
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_eliminated_role_check
        CHECK (eliminated_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING'));
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_hunter_shot_role_check
        CHECK (hunter_shot_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING'));
