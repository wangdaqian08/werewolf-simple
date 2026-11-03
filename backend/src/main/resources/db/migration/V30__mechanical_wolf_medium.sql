-- Mechanical wolf (机械狼) and medium (通灵师): two new roles, their room toggles,
-- the mechanical wolf's learned role, and the night sub-phases/columns they use.

-- 1. Room toggles. The mechanical wolf takes one of the room's wolf_count seats.
ALTER TABLE rooms ADD COLUMN has_mechanical_wolf BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rooms ADD COLUMN has_medium BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. The role the mechanical wolf learned (NULL until it learns).
ALTER TABLE game_players ADD COLUMN learned_role VARCHAR(20);

-- 3. Per-night mechanical wolf and medium state.
ALTER TABLE night_phases ADD COLUMN mech_learn_target_user_id VARCHAR(128);
ALTER TABLE night_phases ADD COLUMN mech_check_target_user_id VARCHAR(128);
ALTER TABLE night_phases ADD COLUMN mech_check_result_role VARCHAR(20);
ALTER TABLE night_phases ADD COLUMN mech_poison_target_user_id VARCHAR(128);
ALTER TABLE night_phases ADD COLUMN mech_guard_target_user_id VARCHAR(128);
ALTER TABLE night_phases ADD COLUMN medium_checked_user_id VARCHAR(128);
ALTER TABLE night_phases ADD COLUMN medium_result_role VARCHAR(20);

-- 4. Re-create the role CHECKs listing the COMPLETE current role set (V26 pattern).
ALTER TABLE game_players DROP CONSTRAINT game_players_role_check;
ALTER TABLE game_players
    ADD CONSTRAINT game_players_role_check
        CHECK (role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM'));

ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_eliminated_role_check;
ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_hunter_shot_role_check;
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_eliminated_role_check
        CHECK (eliminated_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM'));
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_hunter_shot_role_check
        CHECK (hunter_shot_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM'));

-- 5. Night sub-phase CHECK (V1 inline, auto-named) with the four new sub-phases.
ALTER TABLE night_phases DROP CONSTRAINT night_phases_sub_phase_check;
ALTER TABLE night_phases
    ADD CONSTRAINT night_phases_sub_phase_check
        CHECK (sub_phase IN ('WAITING', 'WEREWOLF_PICK', 'MECH_WOLF_PICK', 'MECH_WOLF_RESULT', 'SEER_PICK', 'SEER_RESULT', 'MEDIUM_PICK', 'MEDIUM_RESULT', 'WITCH_ACT', 'GUARD_PICK', 'COMPLETE'));
