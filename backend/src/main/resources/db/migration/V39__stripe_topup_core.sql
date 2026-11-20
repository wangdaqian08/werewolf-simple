-- Stripe top-up core: Stripe customer per user, order/event audit columns,
-- and a DB backstop so one order can get at most one PURCHASE ledger row.

ALTER TABLE users
    ADD COLUMN stripe_customer_id VARCHAR(255);
CREATE UNIQUE INDEX ux_users_stripe_customer ON users (stripe_customer_id);

-- Widen status (its CHECK keeps the same four values; the next PR extends it).
ALTER TABLE payment_orders
    ALTER COLUMN status TYPE VARCHAR(20);
ALTER TABLE payment_orders
    ADD COLUMN stripe_charge_id    VARCHAR(255),
    ADD COLUMN receipt_url         VARCHAR(1000),
    ADD COLUMN session_expires_at  TIMESTAMP,
    ADD COLUMN needs_review        BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN review_reason       VARCHAR(255),
    ADD COLUMN updated_at          TIMESTAMP;

-- Existing rows get PROCESSED through the column default.
ALTER TABLE payment_events
    ADD COLUMN object_id         VARCHAR(255),
    ADD COLUMN payment_order_id  INT,
    ADD COLUMN status            VARCHAR(20) NOT NULL DEFAULT 'PROCESSED',
    ADD COLUMN attempts          INT         NOT NULL DEFAULT 1,
    ADD COLUMN last_error        VARCHAR(1000),
    ADD COLUMN processed_at      TIMESTAMP;
ALTER TABLE payment_events
    ADD CONSTRAINT payment_events_status_check CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED'));

-- Purchase idempotency backstop: one PURCHASE row per order
CREATE UNIQUE INDEX ux_ct_purchase_order ON credit_transactions (payment_order_id)
    WHERE type = 'PURCHASE';
