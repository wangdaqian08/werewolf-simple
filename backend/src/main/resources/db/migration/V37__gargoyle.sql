-- Gargoyle (石像鬼): a lone wolf that checks one player's exact role each night, before the wolves.

-- 1. Room toggle. The gargoyle takes one of the room's wolf_count seats.
ALTER TABLE rooms ADD COLUMN has_gargoyle BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Per-night check: whom it checked and the exact role it saw. Both NULL = skipped, dead or no gargoyle.
ALTER TABLE night_phases ADD COLUMN gargoyle_checked_user_id VARCHAR(128);
ALTER TABLE night_phases ADD COLUMN gargoyle_result_role VARCHAR(20);

-- 3. Re-create the role CHECKs listing the COMPLETE current role set (V33 pattern).
ALTER TABLE game_players DROP CONSTRAINT game_players_role_check;
ALTER TABLE game_players
    ADD CONSTRAINT game_players_role_check
        CHECK (role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT', 'GRAVEKEEPER', 'GARGOYLE'));

ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_eliminated_role_check;
ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_hunter_shot_role_check;
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_eliminated_role_check
        CHECK (eliminated_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT', 'GRAVEKEEPER', 'GARGOYLE'));
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_hunter_shot_role_check
        CHECK (hunter_shot_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT', 'GRAVEKEEPER', 'GARGOYLE'));

-- 4. Night sub-phase CHECK with the gargoyle's two sub-phases (V33 pattern).
ALTER TABLE night_phases DROP CONSTRAINT night_phases_sub_phase_check;
ALTER TABLE night_phases
    ADD CONSTRAINT night_phases_sub_phase_check
        CHECK (sub_phase IN ('WAITING', 'GARGOYLE_PICK', 'GARGOYLE_RESULT', 'WEREWOLF_PICK', 'MECH_WOLF_PICK', 'MECH_WOLF_RESULT', 'SEER_PICK', 'SEER_RESULT', 'MEDIUM_PICK', 'MEDIUM_RESULT', 'GRAVEKEEPER_RESULT', 'WITCH_ACT', 'GUARD_PICK', 'COMPLETE'));
