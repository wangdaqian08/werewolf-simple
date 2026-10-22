-- Black Wolf King (黑狼王): a wolf-camp role that shoots one player when it dies
-- (not poisoned, not self-destructed), sharing the hunter's HUNTER_SHOOT flow.

-- 1. Room toggle. The king takes one of the room's wolf_count seats.
ALTER TABLE rooms ADD COLUMN has_black_wolf_king BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. A shooter (hunter or black wolf king) killed by another shot fires next.
--    Set while that chained shot is pending, NULL otherwise.
ALTER TABLE games ADD COLUMN chain_shooter_user_id VARCHAR(128);

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
