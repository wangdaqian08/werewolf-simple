-- #157 (private hunter / 黑狼王 shot) edited V26 in place after it had shipped
-- (#156 was published as :main and :latest): chain_shooter_user_id became
-- pending_shooter_user_id inside the same migration. A database that ran the
-- shipped V26 then fails at startup: Flyway reports a checksum mismatch, and
-- even after `flyway repair` Hibernate validation fails on the missing column.
-- V26 is restored to its shipped text; this migration carries the rename and
-- the data move #157 left out. (An applied migration is never edited again.)
-- A database that ran the EDITED V26 already has the new column: the rename
-- is skipped there, and FlywayV26RepairConfig realigns that V26 checksum.

-- 1. The rename #157 made by editing V26, where it has not happened yet.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'games'
                  AND column_name = 'chain_shooter_user_id') THEN
        ALTER TABLE games RENAME COLUMN chain_shooter_user_id TO pending_shooter_user_id;
    END IF;
END $$;

-- 2. #157 removed the HUNTER_SHOOT and HUNTER_SHOOT_NIGHT_DEATH sub-phases: a
--    pending shot now lives only in pending_shooter_user_id while the day sits
--    on its normal post-death sub-phase. A game saved in a removed sub-phase
--    would reject every action (shoot, pass, host continue) for good. Move it
--    to the state the new code would have produced:
--      shooter   = the chained shooter if one was set, else the voted-out
--                  player (HUNTER_SHOOT) / the wolves' night victim
--                  (HUNTER_SHOOT_NIGHT_DEATH): exactly who the old code let shoot;
--      sub-phase = BADGE_HANDOVER while a dead sitting sheriff still owes the
--                  badge (sheriff_user_id only moves to the heir at handover),
--                  else VOTE_RESULT / RESULT_REVEALED.
UPDATE games g
SET pending_shooter_user_id = COALESCE(
        g.pending_shooter_user_id,
        (SELECT e.eliminated_user_id FROM elimination_history e
          WHERE e.game_id = g.game_id AND e.day_number = g.day_number)),
    sub_phase = CASE
        WHEN EXISTS (SELECT 1 FROM game_players p
                      WHERE p.game_id = g.game_id AND p.user_id = g.sheriff_user_id AND NOT p.is_alive)
        THEN 'BADGE_HANDOVER' ELSE 'VOTE_RESULT' END
WHERE g.sub_phase = 'HUNTER_SHOOT';

UPDATE games g
SET pending_shooter_user_id = COALESCE(
        g.pending_shooter_user_id,
        (SELECT n.wolf_target_user_id FROM night_phases n
          WHERE n.game_id = g.game_id AND n.day_number = g.day_number)),
    sub_phase = CASE
        WHEN EXISTS (SELECT 1 FROM game_players p
                      WHERE p.game_id = g.game_id AND p.user_id = g.sheriff_user_id AND NOT p.is_alive)
        THEN 'BADGE_HANDOVER' ELSE 'RESULT_REVEALED' END
WHERE g.sub_phase = 'HUNTER_SHOOT_NIGHT_DEATH';

-- 3. Re-create the sub-phase CHECK listing the COMPLETE current set (V8/V16
--    pattern), now without the two removed values: no row can hold them again.
ALTER TABLE games DROP CONSTRAINT games_sub_phase_check;
ALTER TABLE games
    ADD CONSTRAINT games_sub_phase_check
    CHECK (sub_phase IN ('RESULT_HIDDEN', 'RESULT_REVEALED',
                         'VOTING', 'RE_VOTING', 'VOTE_RESULT', 'BADGE_HANDOVER'));
