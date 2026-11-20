-- Refunds and chargebacks: new order statuses, reversal ledger types, and a wallet
-- balance that may go negative when credits were already spent before the reversal.

ALTER TABLE payment_orders DROP CONSTRAINT payment_orders_status_check;
ALTER TABLE payment_orders
    ADD CONSTRAINT payment_orders_status_check
    CHECK (status IN ('CREATED', 'COMPLETED', 'EXPIRED', 'FAILED', 'REFUNDED', 'DISPUTED', 'DISPUTE_LOST'));

ALTER TABLE payment_orders
    ADD COLUMN credits_reversed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE credit_transactions DROP CONSTRAINT credit_transactions_type_check;
ALTER TABLE credit_transactions
    ADD CONSTRAINT credit_transactions_type_check
    CHECK (type IN ('PURCHASE', 'GAME_REWARD', 'PERK_SPEND', 'REFUND', 'ADMIN_GRANT',
                    'PURCHASE_REVERSAL', 'PURCHASE_REINSTATED'));

-- V19 declared this CHECK inline, so Postgres named it wallets_balance_check.
ALTER TABLE wallets DROP CONSTRAINT wallets_balance_check;
