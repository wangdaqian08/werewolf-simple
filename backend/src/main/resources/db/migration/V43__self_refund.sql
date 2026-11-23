-- Self-service refunds: when the player asked, Stripe's refund id, and when Stripe confirmed it.
ALTER TABLE payment_orders
    ADD COLUMN IF NOT EXISTS self_refund_requested_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS self_refund_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS self_refund_settled_at TIMESTAMP;
