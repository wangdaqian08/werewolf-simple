-- Perk settlement correctness: record when a perk actually took effect and
-- when it was settled (CONSUMED/REFUNDED at game end). See PerkSettlementService.
ALTER TABLE perk_activations ADD COLUMN triggered_at TIMESTAMP;
ALTER TABLE perk_activations ADD COLUMN settled_at TIMESTAMP;

-- Account page queries (last-50 by user)
CREATE INDEX idx_pa_user_created ON perk_activations (user_id, created_at DESC);
CREATE INDEX idx_po_user_created ON payment_orders (user_id, created_at DESC);

-- Policy change: an ineffective perk (never triggered, otherwise saved, or
-- holder dealt werewolf) is now auto-refunded at game end (was: no refund).
UPDATE perks
SET description = '若狼人在第一夜袭击你且你不是狼人，袭击失败（与守卫/女巫救人无法区分）。若道具未生效（首夜未被袭击、被其他方式救下，或你被分配为狼人），游戏结束后自动退还积分。'
WHERE perk_code = 'NIGHT1_IMMUNITY';
