-- 戰績分 per seat, written at settlement. Until now the score was the sum of a player's
-- GAME_REWARD ledger rows; task rewards will pay score without coins, so it gets its own column.
ALTER TABLE game_players ADD COLUMN IF NOT EXISTS score_earned INT NOT NULL DEFAULT 0;

-- Backfill: every settled seat earned exactly what its GAME_REWARD row paid.
UPDATE game_players gp
SET score_earned = COALESCE((
    SELECT SUM(t.amount) FROM credit_transactions t
    WHERE t.type = 'GAME_REWARD' AND t.game_id = gp.game_id AND t.user_id = gp.user_id
), 0)
WHERE gp.score_earned = 0;
