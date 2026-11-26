-- At most one open self refund per player: two taps on two orders cannot both pass the
-- 30-day limit while the first is still waiting for Stripe. A mark leaves this index when
-- the refund settles (settled_at set) or is dropped (requested_at cleared).
CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_orders_one_open_self_refund
    ON payment_orders (user_id)
    WHERE self_refund_requested_at IS NOT NULL AND self_refund_settled_at IS NULL;
