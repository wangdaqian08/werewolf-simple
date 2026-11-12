-- 单局任务: one private task dealt per seat at game start, judged at settlement.
-- task_code NULL = no task (games under six seats, and every game before this migration).
-- task_done NULL = not judged yet (game running or cancelled).
ALTER TABLE game_players ADD COLUMN IF NOT EXISTS task_code VARCHAR(30);
ALTER TABLE game_players ADD COLUMN IF NOT EXISTS task_done BOOLEAN;
-- task_score = the 戰績分 the task paid (a per-night task pays once per night); NULL until judged.
ALTER TABLE game_players ADD COLUMN IF NOT EXISTS task_score INT;
