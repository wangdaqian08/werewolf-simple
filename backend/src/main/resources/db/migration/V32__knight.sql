-- Knight (骑士): a god with a one-shot daytime duel. No night turn, so no sub-phase change.

-- 1. Room toggle.
ALTER TABLE rooms ADD COLUMN has_knight BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. The duel (one per game): its target and the day it was fired. Both NULL until it is used.
ALTER TABLE games ADD COLUMN knight_duel_target_user_id VARCHAR(128);
ALTER TABLE games ADD COLUMN knight_duel_day INT;

-- 3. Re-create the role CHECKs listing the COMPLETE current role set (V30 pattern).
ALTER TABLE game_players DROP CONSTRAINT game_players_role_check;
ALTER TABLE game_players
    ADD CONSTRAINT game_players_role_check
        CHECK (role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT'));

ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_eliminated_role_check;
ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_hunter_shot_role_check;
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_eliminated_role_check
        CHECK (eliminated_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT'));
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_hunter_shot_role_check
        CHECK (hunter_shot_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT'));
