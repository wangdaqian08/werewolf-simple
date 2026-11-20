-- A dispute takeback gets its own ledger type, so the player sees 拒付扣回 rather than 充值退回.
ALTER TABLE credit_transactions DROP CONSTRAINT credit_transactions_type_check;
ALTER TABLE credit_transactions
    ADD CONSTRAINT credit_transactions_type_check
    CHECK (type IN ('PURCHASE', 'GAME_REWARD', 'PERK_SPEND', 'REFUND', 'ADMIN_GRANT',
                    'PURCHASE_REVERSAL', 'PURCHASE_REINSTATED', 'DISPUTE_REVERSAL'));
