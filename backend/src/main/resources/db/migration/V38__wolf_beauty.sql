-- Wolf beauty (狼美人): a pack wolf that charms one player each night; the charmed dies with her (殉情).

-- 1. Room toggle. The wolf beauty takes one of the room's wolf_count seats.
ALTER TABLE rooms ADD COLUMN has_wolf_beauty BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. Per-night charm target (NULL = skipped, dead or no wolf beauty).
ALTER TABLE night_phases ADD COLUMN wolf_beauty_charm_user_id VARCHAR(128);

-- 3. The day 殉情 victim, for the banner; cleared at night-init.
ALTER TABLE games ADD COLUMN charm_death_user_id VARCHAR(128);

-- 4. Re-create the role CHECKs listing the COMPLETE current role set (V33 pattern).
ALTER TABLE game_players DROP CONSTRAINT game_players_role_check;
ALTER TABLE game_players
    ADD CONSTRAINT game_players_role_check
        CHECK (role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT', 'GRAVEKEEPER', 'GARGOYLE', 'WOLF_BEAUTY'));

ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_eliminated_role_check;
ALTER TABLE elimination_history DROP CONSTRAINT elimination_history_hunter_shot_role_check;
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_eliminated_role_check
        CHECK (eliminated_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT', 'GRAVEKEEPER', 'GARGOYLE', 'WOLF_BEAUTY'));
ALTER TABLE elimination_history
    ADD CONSTRAINT elimination_history_hunter_shot_role_check
        CHECK (hunter_shot_role IN ('WEREWOLF', 'VILLAGER', 'SEER', 'WITCH', 'HUNTER', 'GUARD', 'IDIOT', 'WHITE_WOLF_KING', 'BLACK_WOLF_KING', 'MECHANICAL_WOLF', 'MEDIUM', 'KNIGHT', 'GRAVEKEEPER', 'GARGOYLE', 'WOLF_BEAUTY'));

-- 5. Night sub-phase CHECK with WOLF_BEAUTY_PICK (V33 pattern).
ALTER TABLE night_phases DROP CONSTRAINT night_phases_sub_phase_check;
ALTER TABLE night_phases
    ADD CONSTRAINT night_phases_sub_phase_check
        CHECK (sub_phase IN ('WAITING', 'GARGOYLE_PICK', 'GARGOYLE_RESULT', 'WEREWOLF_PICK', 'WOLF_BEAUTY_PICK', 'MECH_WOLF_PICK', 'MECH_WOLF_RESULT', 'SEER_PICK', 'SEER_RESULT', 'MEDIUM_PICK', 'MEDIUM_RESULT', 'GRAVEKEEPER_RESULT', 'WITCH_ACT', 'GUARD_PICK', 'COMPLETE'));
