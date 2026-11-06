INSERT INTO perks (perk_code, name, description, price_credits) VALUES
 ('ROLE_PICK_WOLF', '点将卡·狼人', '发牌时优先进入狼人阵营；想当狼的人多于名额时随机抽签。未如愿或游戏取消，积分在游戏结束时退回。', 20),
 ('ROLE_PICK_GOD', '点将卡·神', '发牌时优先成为神职；想当神的人多于名额时随机抽签。未如愿或游戏取消，积分在游戏结束时退回。', 20),
 ('ROLE_PICK_VILLAGER', '点将卡·村民', '发牌时优先成为村民；想当村民的人多于名额时随机抽签。未如愿或游戏取消，积分在游戏结束时退回。', 20);

-- Role-pick cards are many per room (one per player), not one per room.
DROP INDEX ux_one_active_perk_per_room;
CREATE UNIQUE INDEX ux_one_active_perk_per_room ON perk_activations (room_id, perk_code)
    WHERE status = 'ACTIVE' AND perk_code NOT LIKE 'ROLE_PICK_%';
CREATE UNIQUE INDEX ux_one_role_pick_per_player ON perk_activations (room_id, user_id)
    WHERE status = 'ACTIVE' AND perk_code LIKE 'ROLE_PICK_%';

ALTER TABLE room_players ADD COLUMN role_pick VARCHAR(10)
    CHECK (role_pick IN ('NONE', 'WOLF', 'GOD', 'VILLAGER'));
