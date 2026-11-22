-- Packs are priced in AUD; the 1.99 starter pack is retired (kept for old orders).
UPDATE products SET currency = 'aud';
UPDATE products SET active = FALSE WHERE product_key = 'starter';
