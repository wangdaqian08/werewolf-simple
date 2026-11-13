-- 点将卡 price tiers (roadmap item 3, decided 2026-11-14 from production data):
-- 村民 10, 神 25, 狼人 30. Rules are unchanged: charged only when the card takes effect,
-- refunded when it misses, the room never starts, or the game is cancelled.
-- Plain UPDATEs by key: safe to run again.
UPDATE perks SET price_credits = 10 WHERE perk_code = 'ROLE_PICK_VILLAGER';
UPDATE perks SET price_credits = 25 WHERE perk_code = 'ROLE_PICK_GOD';
UPDATE perks SET price_credits = 30 WHERE perk_code = 'ROLE_PICK_WOLF';
