-- Admin credit grants (AdminService) are journaled as ADMIN_GRANT.
ALTER TABLE credit_transactions DROP CONSTRAINT credit_transactions_type_check;

ALTER TABLE credit_transactions
    ADD CONSTRAINT credit_transactions_type_check
    CHECK (type IN ('PURCHASE', 'GAME_REWARD', 'PERK_SPEND', 'REFUND', 'ADMIN_GRANT'));
