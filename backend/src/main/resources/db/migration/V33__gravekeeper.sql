-- Gravekeeper (守墓人): a god that learns each night whether yesterday's vote-out was good or wolf.

-- 1. Room toggle.
ALTER TABLE rooms ADD COLUMN has_gravekeeper BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Per-night result, stored at night-init: GOOD / WOLF / NONE, and whom the vote put out.
--    Both NULL when the game has no living gravekeeper.
ALTER TABLE night_phases ADD COLUMN gravekeeper_result VARCHAR(10);
ALTER TABLE night_phases ADD COLUMN gravekeeper_exiled_user_id VARCHAR(128);

-- 3. Re-create the role CHECKs listing the COMPLETE current role set (V30 pattern).
ALTER TABLE game_players DROP CONSTRAINT game_players_role_check;
ALTER TABLE game_players
    ADD CONSTRAINT game_players_role_check
        CHECK (role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT', 'GRAVEKEEPER'));

ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_eliminated_role_check;
ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_hunter_shot_role_check;
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_eliminated_role_check
        CHECK (eliminated_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT', 'GRAVEKEEPER'));
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_hunter_shot_role_check
        CHECK (hunter_shot_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT', 'GRAVEKEEPER'));

-- 4. Night sub-phase CHECK with the gravekeeper's sub-phase (V30 pattern).
ALTER TABLE night_phases DROP CONSTRAINT night_phases_sub_phase_check;
ALTER TABLE night_phases
    ADD CONSTRAINT night_phases_sub_phase_check
        CHECK (sub_phase IN ('WAITING', 'WEREWOLF_PICK', 'MECH_WOLF_PICK', 'MECH_WOLF_RESULT', 'SEER_PICK', 'SEER_RESULT', 'MEDIUM_PICK', 'MEDIUM_RESULT', 'GRAVEKEEPER_RESULT', 'WITCH_ACT', 'GUARD_PICK', 'COMPLETE'));
